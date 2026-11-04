package com.smartenergy.analytics.stream;

import com.smartenergy.analytics.client.SensorClient;
import com.smartenergy.analytics.config.AnalyticsProperties;
import com.smartenergy.analytics.model.SensorStateDocument;
import com.smartenergy.analytics.rules.AnalyticsRules;
import com.smartenergy.analytics.rules.AnalyticsRules.Welford;
import com.smartenergy.analytics.service.AggregateWriter;
import com.smartenergy.analytics.service.AggregateWriter.Anomaly;
import com.smartenergy.analytics.service.AggregateWriter.Evaluated;
import com.smartenergy.analytics.service.AggregateWriter.SensorStateUpdate;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.Severity;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.RedisKeys;
import com.smartenergy.platform.stream.AbstractStreamConsumer;
import com.smartenergy.platform.stream.StreamProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consommateur Redis de l'analytics-group (etape 9 niveaux A et B ; CDC 6.5 et 7.5 ; scenarios 4,
 * 8 et 11). Traite un lot de mesures du stream partage (energy-measurements) et ecrit les agregats
 * et les anomalies dans MongoDB via {@link AggregateWriter}.
 *
 * <p>Traitement d'un lot :
 * <ol>
 *   <li><b>Deduplication</b> : ignorer les evenements deja traites (cle
 *       {@code RedisKeys.dedupAnalytics(eventId)}) ou presents deux fois dans le lot. Regle CDC 6.5 :
 *       verifier AVANT, marquer APRES les ecritures Mongo reussies (TTL {@code DEDUP_TTL}). Une panne
 *       entre les deux peut compter une mesure deux fois dans les agregats : biais negligeable, a
 *       documenter (les anomalies restent uniques grâce a leur _id deterministe).</li>
 *   <li><b>Seuil</b> : {@code SensorClient.thresholdFor(sensorId)} une fois par capteur (cache 60 s ->
 *       sensor-service -> repli par defaut ; ne leve jamais d'exception, scenario 11).</li>
 *   <li><b>Etat du capteur</b> : lecture de sensor_state (Welford n/mean/m2, lastEnergyBasisTs,
 *       derniere puissance) une fois par capteur.</li>
 *   <li><b>Ordre des timestamps</b> : les mesures sont traitees dans l'ordre chrono croissant, par
 *       capteur : l'increment d'energie et le score z dependent de l'ordre.</li>
 *   <li><b>Regles</b> : energie (formule 2, plafond des trous), depassement de seuil strict
 *       (formule 3) -> anomalie THRESHOLD (HIGH), score z (formule 4, min 30 echantillons) ->
 *       anomalie ZSCORE (MEDIUM). <b>Welford (mean, m2) n'est mis a jour que pour les mesures NON
 *       anormales</b> (mu et sigma ne doivent pas etre pollues) ; lastEnergyBasisTs et lastPowerKw
 *       avancent en revanche pour TOUTE mesure (sinon l'energie serait comptee deux fois).</li>
 *   <li><b>Ecritures</b> : {@code aggregateWriter.apply(lot, etats)} puis marquage du dedup ;
 *       la classe de base fait XACK ensuite (exception = pas d'ACK = retry).</li>
 *   <li><b>Metrique</b> : {@code energy_anomalies_detected_total{type}} (anomalies fraiches).</li>
 * </ol>
 */
@Component
public class AnalyticsStreamConsumer extends AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsStreamConsumer.class);

    private final SensorClient sensorClient;
    private final AggregateWriter writer;
    private final MongoTemplate mongo;
    private final AnalyticsProperties props;
    private final MeterRegistry registry;

    public AnalyticsStreamConsumer(StringRedisTemplate redis, StreamProperties props,
                                   SensorClient sensorClient, AggregateWriter writer,
                                   MongoTemplate mongo, AnalyticsProperties analyticsProps,
                                   MeterRegistry registry) {
        super(redis, props);
        this.sensorClient = sensorClient;
        this.writer = writer;
        this.mongo = mongo;
        this.props = analyticsProps;
        this.registry = registry;
    }

    @Override
    protected void processBatch(List<EnergyMeasurement> batch) {
        // 1. dedup : doublons du lot + cles Redis (verifier AVANT toute ecriture)
        List<EnergyMeasurement> fresh = new ArrayList<>();
        Set<UUID> inBatch = new HashSet<>();
        for (EnergyMeasurement m : batch) {
            if (!inBatch.add(m.eventId())) {
                continue;   // meme evenement deux fois dans le lot (sc. 8)
            }
            if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.dedupAnalytics(m.eventId())))) {
                continue;   // deja traite (redelivrance apres panne, sc. 8)
            }
            fresh.add(m);
        }
        if (fresh.isEmpty()) {
            return;
        }

        // 2. seuil une fois par capteur ; 3. etat du capteur depuis sensor_state
        Map<String, SensorClient.Threshold> thresholds = new LinkedHashMap<>();
        Map<String, SensorRun> runs = new LinkedHashMap<>();
        for (EnergyMeasurement m : fresh) {
            SensorClient.Threshold threshold =
                    thresholds.computeIfAbsent(m.sensorId(), sensorClient::thresholdFor);
            SensorRun run = runs.computeIfAbsent(m.sensorId(),
                    id -> SensorRun.from(mongo.findById(id, SensorStateDocument.class)));
            run.threshold = threshold;
        }

        // 4. evaluation dans l'ordre des timestamps (croissant), 5. ecritures puis dedup
        List<Evaluated> evaluated = new ArrayList<>(fresh.size());
        fresh.stream()
                .sorted(Comparator.comparing(EnergyMeasurement::timestamp))
                .forEach(m -> evaluated.add(evaluate(m, runs.get(m.sensorId()))));

        List<SensorStateUpdate> states = new ArrayList<>(runs.size());
        runs.forEach((id, run) -> states.add(run.toUpdate(id)));

        AggregateWriter.Result result = writer.apply(evaluated, states);   // echec -> pas d'ACK

        // marquer le dedup APRES les ecritures reussies (CDC 6.5)
        for (EnergyMeasurement m : fresh) {
            redis.opsForValue().set(RedisKeys.dedupAnalytics(m.eventId()), "1", RedisKeys.DEDUP_TTL);
        }

        // 6. metriques : anomalies fraichement inserees, par type
        result.anomaliesInserted().forEach((type, count) ->
                registry.counter("energy_anomalies_detected_total", "type", type.name())
                        .increment(count));
        log.debug("lot analytics traite : {} mesures, {} capteurs, anomalies {}",
                fresh.size(), runs.size(), result.anomaliesInserted());
    }

    /** Evaluate une mesure par rapport a l'etat courant de son capteur (regles pures). */
    private Evaluated evaluate(EnergyMeasurement m, SensorRun run) {
        SensorClient.Threshold threshold = run.threshold;

        // niveau A : energie (formule 2) sur le pas precedent (toutes mesures, rejeu -> 0)
        double energy = AnalyticsRules.energyIncrementKwh(
                run.lastEnergyBasisTs, run.lastPowerKw == null ? 0.0 : run.lastPowerKw,
                m.timestamp(), props.expectedIntervalSeconds(), props.energyGapFactor());

        // niveau B : depassement de seuil strict (formule 3), seulement si le seuil est actif
        boolean thresholdExceeded = threshold.active()
                && AnalyticsRules.isThresholdExceeded(m.powerKw(), threshold.maxPowerKw());

        // niveau B : score z (formule 4) par rapport a l'etat de Welford
        Double z = AnalyticsRules.zScore(run.welford, m.powerKw(), props.zscore().minSamples());
        boolean zScoreAnomaly = z != null && Math.abs(z) > props.zscore().threshold();

        List<Anomaly> anomalies = new ArrayList<>(2);
        if (thresholdExceeded) {
            anomalies.add(new Anomaly(AnomalyType.THRESHOLD, Severity.HIGH,
                    threshold.maxPowerKw(), threshold.source().name(), null));
        }
        if (zScoreAnomaly) {
            anomalies.add(new Anomaly(AnomalyType.ZSCORE, Severity.MEDIUM, null, null, z));
        }

        // Welford : seulement les mesures NON anormales (mu et sigma non pollues)
        if (anomalies.isEmpty()) {
            run.welford = run.welford.add(m.powerKw());
        }
        // avancee de la base d'energie et de l'etat courant : TOUTE mesure plus recente
        run.advance(m);
        return new Evaluated(m, energy, thresholdExceeded, anomalies);
    }

    @Override
    protected String serviceName() {
        return "analytics-service";
    }

    /**
     * Etat en memoire d'un capteur pendant le lot : Welford (n, mean, m2) + base d'energie.
     * lastEnergyBasisTs avance a chaque mesure traitee (rejeu/mesure en retard : jamais) et la
     * puissance du pas est lastPowerKw (invariants conserves par sensor_state).
     */
    private static final class SensorRun {
        private SensorClient.Threshold threshold;
        private Instant lastMeasuredAt;
        private Double lastPowerKw;
        private Instant lastEnergyBasisTs;
        private Welford welford;

        static SensorRun from(SensorStateDocument doc) {
            SensorRun run = new SensorRun();
            if (doc != null) {
                run.lastMeasuredAt = doc.lastMeasuredAt();
                run.lastPowerKw = doc.lastPowerKw();
                run.lastEnergyBasisTs = doc.lastEnergyBasisTs();
                run.welford = new Welford(doc.n(), doc.mean(), doc.m2());
            } else {
                run.welford = Welford.empty();
            }
            return run;
        }

        void advance(EnergyMeasurement m) {
            boolean newer = lastMeasuredAt == null || m.timestamp().isAfter(lastMeasuredAt);
            if (newer) {
                lastMeasuredAt = m.timestamp();     // ne redescend jamais (retards)
                lastPowerKw = m.powerKw();
            }
            if (lastEnergyBasisTs == null || m.timestamp().isAfter(lastEnergyBasisTs)) {
                lastEnergyBasisTs = m.timestamp();  // base d'energie : toutes mesures
            }
        }

        SensorStateUpdate toUpdate(String sensorId) {
            return new SensorStateUpdate(sensorId, lastMeasuredAt, lastPowerKw,
                    lastEnergyBasisTs, welford);
        }
    }
}
