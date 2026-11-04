package com.smartenergy.analytics.forecast;

import com.smartenergy.analytics.config.AnalyticsProperties;
import com.smartenergy.analytics.model.HourlyAggregateDocument;
import com.smartenergy.contracts.analytics.ForecastPoint;
import com.smartenergy.contracts.analytics.ForecastResponse;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevision (extension, chapitre 18 / decision D-18). Feature flag
 * {@code smartenergy.analytics.forecast.enabled} : desactive (valeur par defaut), la route
 * repond 501 FORECAST_DISABLED (CDC : "Previsions si le module IA est actif").
 *
 * <p>Quand le flag est actif, la demarche CDC 17.3 est respectee de bout en bout :
 * <ol>
 *   <li><b>Preparation</b> — serie horaire de {@code hourly_aggregates} (niveau A), fenetre des
 *       {@value ForecastWindow#WINDOW} dernieres heures, trous comblés par interpolation lineaire
 *       (rejet au-dela de {@value ForecastWindow#MAX_MISSING_GAP} h consecutives), normalisation
 *       min-max <b>du train</b> lue dans {@code models/energy_<sensor>.json} (jamais recalculee ici) ;</li>
 *   <li><b>Séparation</b> / <b>entrainement</b> / <b>metriques</b> — faits en Python
 *       ({@code ml/train_forecast.py}) ; MAE (formule 5) et RMSE (formule 6) sur le test sont
 *       remplies dans {@code metrics} ;</li>
 *   <li><b>Export</b> — un modele par sensor {@code models/energy_<sensor>.onnx} +
 *       {@code energy_<sensor>.json} (fallback sur {@code models/energy.onnx});</li>
 *   <li><b>Intégration</b> — cette classe : lecture Mongo, meme transformations, inférence DJL
 *       ({@link OnnxForecastPredictor}, snippet CDC 18), reponse horaire.</li>
 * </ol>
 *
 * <p>Un predicteur est charge par sensor (cache {@link #predictors}) et libere avec le
 * contexte Spring. Un fichier absent/illisible = module inactif (501), jamais une erreur 500.
 */
@Component
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    static final int MIN_HORIZON_HOURS = 1;
    static final int MAX_HORIZON_HOURS = 24;

    private final AnalyticsProperties props;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final Predictions predictions;

    /** Cache des predicturs par sensor (une instance chargee a la demande par sensor). */
    private final Map<String, ForecastPredictor> predictors = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ForecastService(AnalyticsProperties props, MongoTemplate mongo, Clock clock) {
        this(props, mongo, clock, OnnxForecastPredictor::new);
    }

    /** Variante testable : injection d'un fabricant de predicteur (mock sans ONNX). */
    ForecastService(AnalyticsProperties props, MongoTemplate mongo, Clock clock,
                    Predictions predictions) {
        this.props = props;
        this.mongo = mongo;
        this.clock = clock;
        this.predictions = predictions;
    }

    /** Fabricant paresseux : {@code path -> predicteur charge} (DJL ou mock). */
    @FunctionalInterface
    interface Predictions {
        ForecastPredictor create(String modelPath);
    }

    /**
     * @throws ApiException(INVALID_PARAMETER) si horizonHours hors [1, 24]
     * @throws ApiException(FORECAST_DISABLED) si le module IA est inactif (501)
     * @throws ApiException(NO_DATA) si l'historique horaire est insuffisant
     */
    public ForecastResponse forecast(String sensorId, int horizonHours) {
        if (horizonHours < MIN_HORIZON_HOURS || horizonHours > MAX_HORIZON_HOURS) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER,
                    "horizonHours doit etre compris entre " + MIN_HORIZON_HOURS
                            + " et " + MAX_HORIZON_HOURS);
        }
        if (!props.forecast().enabled()) {
            throw ApiException.of(ErrorCode.FORECAST_DISABLED,
                    "Module de prevision inactif : smartenergy.analytics.forecast.enabled=false");
        }
        // Resout le chemin du modele ONNX pour ce sensor (modele par sensor, fallback global).
        String modelPath = resolveModelPath(sensorId);
        ForecastPredictor p;
        try {
            p = predictors.computeIfAbsent(sensorId, sid -> predictions.create(modelPath));
        } catch (ForecastModelMetadata.ForecastModelException e) {
            log.warn("Module de prevision inactif pour {} : {}", sensorId, e.getMessage());
            throw ApiException.of(ErrorCode.FORECAST_DISABLED,
                    "Module de prevision actif mais modele indisponible pour " + sensorId + " : "
                            + e.getMessage());
        }
        ForecastModelMetadata meta = p.metadata();

        // 1. serie horaire : les `window` dernieres heures du capteur (la plus recente en dernier)
        TreeMap<Instant, Double> hourly = lastHours(sensorId, meta.window());
        if (hourly.isEmpty()) {
            throw ApiException.of(ErrorCode.NO_DATA,
                    "Aucune agregat horaire pour " + sensorId + " sur les " + meta.window()
                            + " dernieres heures : historique insuffisant pour la prevision");
        }
        Instant lastHour = hourly.lastKey();

        // 2. fenetre d'entree (interpolation des trous) -> normalisation min-max du TRAIN
        double[] window = ForecastWindow.build(hourly, lastHour, meta.window(), ForecastWindow.MAX_MISSING_GAP);
        float[] input = ForecastWindow.normalize(window, meta.min(), meta.max());

        // 3. inférence : `horizon` points futurs, denormalises en kW
        float[] raw = p.predict(input);
        if (raw.length < horizonHours) {
            throw ApiException.of(ErrorCode.NO_DATA,
                    "Le modele fournit " + raw.length + " h de prevision, demande : " + horizonHours + " h");
        }
        double[] kw = ForecastWindow.denormalize(raw, meta.min(), meta.max());

        List<ForecastPoint> points = new ArrayList<>(horizonHours);
        for (int i = 0; i < horizonHours; i++) {
            Instant hour = lastHour.plus(i + 1L, ChronoUnit.HOURS);
            // garde-fou : une puissance predite ne peut pas etre negative (bruit du reseau)
            points.add(new ForecastPoint(hour, Math.max(0.0, round3(kw[i]))));
        }
        return new ForecastResponse(sensorId, Paths.get(modelPath).getFileName().toString(), clock.instant(), horizonHours,
                points, meta.metrics() != null ? meta.metrics().responseMetrics() : Map.of());
    }

    /** Libere tous les predicturs charges (appele par Spring a l'arret si le flag etait actif). */
    @PreDestroy
    public synchronized void close() {
        for (ForecastPredictor p : predictors.values()) {
            try {
                p.close();
            } catch (Exception e) {   // fermeture au mieux ; pas de crash a l'arret
                log.warn("Fermeture du predicteur de prevision echouee : {}", e.getMessage());
            }
        }
        predictors.clear();
    }

    /** Resout le chemin du modele ONNX pour un sensor : modele par sensor si present, sinon le modele global. */
    private String resolveModelPath(String sensorId) {
        Path sensorModel = Paths.get("models/energy_" + sensorId + ".onnx");
        if (Files.exists(sensorModel)) {
            return sensorModel.toString();
        }
        return props.forecast().modelPath();   // fallback sur models/energy.onnx
    }

    // ------------------------------------------------------------------ prive

    /** Les {@code hours} dernieres heures pleines du capteur (heure -> moyenne kW), trie par heure. */
    private TreeMap<Instant, Double> lastHours(String sensorId, int hours) {
        Instant until = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant from = until.minus(hours - 1L, ChronoUnit.HOURS);
        List<HourlyAggregateDocument> docs = mongo.find(
                new Query(Criteria.where("sensorId").is(sensorId)
                        .and("hour").gte(from).lte(until))
                        .with(Sort.by(Sort.Direction.ASC, "hour")),
                HourlyAggregateDocument.class);
        TreeMap<Instant, Double> hourly = new TreeMap<>();
        for (HourlyAggregateDocument d : docs) {
            if (d.count() > 0) {
                hourly.put(d.hour(), d.sumPowerKw() / d.count());   // moyenne = sum / count (D-13)
            }
        }
        return hourly;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
