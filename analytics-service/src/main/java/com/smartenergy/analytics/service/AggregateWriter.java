package com.smartenergy.analytics.service;

import com.smartenergy.analytics.model.AnomalyDocument;
import com.smartenergy.analytics.model.DailyAggregateDocument;
import com.smartenergy.analytics.model.HourlyAggregateDocument;
import com.smartenergy.analytics.model.SensorStateDocument;
import com.smartenergy.analytics.rules.AnalyticsRules.Welford;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.Severity;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Ecriture des agregats et des anomalies dans MongoDB (etape 9 niveaux A et B, CDC 6.3 et 17.1 ;
 * decisions D-13 granularite horaire et D-14 micro-batch).
 *
 * <p>Le consumer analytics evalue un LOT de mesures (regles pures {@code AnalyticsRules}) puis
 * appelle {@link #apply} : les ecritures sont regroupees EN MEMOIRE, <b>une seule ecriture Mongo
 * par document et par lot</b> (pas une par mesure).
 *
 * <p><b>Operateurs atomiques</b> : {@code $inc} (count, sumPowerKw, energyKwh,
 * thresholdExceededCount, anomalyCount), {@code $min}/{@code $max} (min/maxPowerKw ; sur un document
 * neuf ils prennent la valeur fournie, sans "0" parasite), {@code $setOnInsert} (sensorId, hour/date)
 * et {@code $set} (updatedAt). Les agregats sont ADDITIFS : la moyenne se recalcule a la lecture
 * ({@code sumPowerKw / count}, cf. AnalyticsQueryService).
 *
 * <p><b>Anomalies idempotentes</b> : {@code _id} deterministe {@code sensorId|eventId|type} (meme
 * cle que l'index unique (eventId, type)) et upsert {@code $setOnInsert} sur TOUS les champs :
 * un rejeu (redelivrance apres panne, sc. 8) ne modifie ni ne duplique rien. Le nombre d'anomalies
 * incremente dans les agregats n'est compte que lorsque l'insertion est <b>fraiche</b>
 * ({@code getUpsertedId() != null}) : les compteurs restent exacts meme en cas de rejeu.
 *
 * <p><b>Attribution de l'energie</b> (decision D-9a) : l'increment calcule sur la mesure i est
 * compte dans l'heure et le jour UTC de la mesure courante.
 */
@Component
public class AggregateWriter {

    /** Cle horaire : "2026-10-29T10" (format du CDC pour _id de hourly_aggregates). */
    private static final DateTimeFormatter HOUR_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    private final MongoTemplate mongo;
    private final Clock clock;

    public AggregateWriter(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Une mesure evaluee : l'evenement + ses resultats d'analyse (niveaux A et B). */
    public record Evaluated(EnergyMeasurement measurement,
                            double energyIncrementKwh,
                            boolean thresholdExceeded,
                            List<Anomaly> anomalies) {
        public Evaluated {
            anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        }
    }

    /** Anomalie detectee sur une mesure (THRESHOLD = HIGH, ZSCORE = MEDIUM, CDC 17.2). */
    public record Anomaly(AnomalyType type, Severity severity,
                          Double thresholdKw, String thresholdSource, Double zScore) {}

    /** Etat final d'un capteur apres traitement d'un lot (ecrit dans sensor_state, CDC 6.3). */
    public record SensorStateUpdate(String sensorId, Instant lastMeasuredAt, Double lastPowerKw,
                                    Instant lastEnergyBasisTs, Welford welford) {}

    /** Resultat d'un lot : anomalies fraichement inserees, par type (pour les metriques). */
    public record Result(Map<AnomalyType, Integer> anomaliesInserted) {}

    /**
     * Applique un lot evalue aux collections analytics_db.
     *
     * <p>Ordre des ecritures (important pour l'idempotence) : (1) upserts d'anomalies
     * {@code $setOnInsert} -> on sait quelles sont fraiches ; (2) agregats horaires ; (3) agregats
     * quotidiens (anomalyCount limite aux anomalies fraiches) ; (4) sensor_state.
     * Une exception en cours = pas d'ACK ni de marquage dedup : le lot sera retente ; les {@code $inc}
     * deja appliques peuvent alors se compter deux fois (biais negligeable et documente, CDC 6.5).
     *
     * @param batch  mesures evaluees (ordre indifferent : le regroupement est par heure/jour)
     * @param states etat final a ecrire de chaque capteur present dans le lot
     */
    public Result apply(List<Evaluated> batch, List<SensorStateUpdate> states) {
        Instant now = clock.instant();

        // (1) anomalies : upserts idempotents ; "fraiche" = upsertedId != null
        Map<AnomalyType, Integer> inserted = new EnumMap<>(AnomalyType.class);
        Map<String, Integer> freshAnomaliesPerHour = new LinkedHashMap<>();
        Map<String, Integer> freshAnomaliesPerDay = new LinkedHashMap<>();
        for (Evaluated e : batch) {
            EnergyMeasurement m = e.measurement();
            int freshHere = 0;
            for (Anomaly a : e.anomalies()) {
                if (insertAnomaly(m, a, now)) {
                    freshHere++;
                    inserted.merge(a.type(), 1, Integer::sum);
                }
            }
            if (freshHere > 0) {
                freshAnomaliesPerHour.merge(hourlyId(m.sensorId(), m.timestamp()), freshHere, Integer::sum);
                freshAnomaliesPerDay.merge(dailyId(m.sensorId(), m.day().toString()), freshHere, Integer::sum);
            }
        }

        // (2) agregats : regroupement memoire par capteur, heure et jour UTC (micro-batch D-14)
        Map<String, Agg> hourly = new LinkedHashMap<>();
        Map<String, Agg> daily = new LinkedHashMap<>();
        for (Evaluated e : batch) {
            EnergyMeasurement m = e.measurement();
            hourly.computeIfAbsent(hourlyId(m.sensorId(), m.timestamp()),
                    id -> new Agg(m.sensorId(), m.timestamp().truncatedTo(ChronoUnit.HOURS), null))
                    .add(e);
            daily.computeIfAbsent(dailyId(m.sensorId(), m.day().toString()),
                    id -> new Agg(m.sensorId(), null, m.day().toString()))
                    .add(e);
        }
        // (2bis) anomalyCount : uniquement les anomalies fraichement inserees (jamais en rejeu)
        freshAnomaliesPerHour.forEach((id, n) -> hourly.get(id).anomalyCount += n);
        freshAnomaliesPerDay.forEach((id, n) -> daily.get(id).anomalyCount += n);

        // (3) une ecriture par document agregat et par lot
        hourly.forEach((id, a) -> mongo.upsert(new Query(Criteria.where("_id").is(id)),
                a.toUpdate(now), HourlyAggregateDocument.class));
        daily.forEach((id, a) -> mongo.upsert(new Query(Criteria.where("_id").is(id)),
                a.toUpdate(now), DailyAggregateDocument.class));

        // (4) sensor_state : un upsert par capteur (etat final calcule par le consumer)
        for (SensorStateUpdate s : states) {
            Update update = new Update()
                    .set("lastMeasuredAt", s.lastMeasuredAt())
                    .set("lastPowerKw", s.lastPowerKw())
                    .set("lastEnergyBasisTs", s.lastEnergyBasisTs())
                    .set("n", s.welford().n())
                    .set("mean", s.welford().mean())
                    .set("m2", s.welford().m2())
                    .setOnInsert("sensorId", s.sensorId());
            mongo.upsert(new Query(Criteria.where("_id").is(s.sensorId())), update, SensorStateDocument.class);
        }
        return new Result(Map.copyOf(inserted));
    }

    // ------------------------------------------------------------------ internes

    /** @return true si l'anomalie vient d'etre inseree (fraiche), false si elle existait deja */
    private boolean insertAnomaly(EnergyMeasurement m, Anomaly a, Instant now) {
        String id = m.sensorId() + '|' + m.eventId() + '|' + a.type();
        Update update = new Update()
                .setOnInsert("sensorId", m.sensorId())
                .setOnInsert("timestamp", m.timestamp())
                .setOnInsert("eventId", m.eventId().toString())
                .setOnInsert("type", a.type())
                .setOnInsert("severity", a.severity())
                .setOnInsert("powerKw", m.powerKw())
                .setOnInsert("thresholdKw", a.thresholdKw())
                .setOnInsert("thresholdSource", a.thresholdSource())
                .setOnInsert("zScore", a.zScore())
                .setOnInsert("detectedAt", now);
        return mongo.upsert(new Query(Criteria.where("_id").is(id)), update, AnomalyDocument.class)
                .getUpsertedId() != null;
    }

    /** _id hourly : "sensorId|2026-10-29T10" (heure UTC de la mesure courante, D-9a). */
    static String hourlyId(String sensorId, Instant timestamp) {
        return sensorId + '|' + HOUR_KEY.format(timestamp);
    }

    /** _id daily : "sensorId|2026-10-29" (contrat deja lu par daily-summary). */
    static String dailyId(String sensorId, String day) {
        return sensorId + '|' + day;
    }

    /** Accumulateur memoire d'un agregat (horaire ou quotidien) + ses cles ($setOnInsert). */
    private static final class Agg {
        private final String sensorId;
        private final Instant hour;     // hourly, ou null pour un agregat quotidien
        private final String date;      // daily, ou null pour un agregat horaire
        private long count;
        private double sumPowerKw;
        private double minPowerKw = Double.MAX_VALUE;
        private double maxPowerKw = -Double.MAX_VALUE;
        private double energyKwh;
        private long thresholdExceededCount;
        private long anomalyCount;

        Agg(String sensorId, Instant hour, String date) {
            this.sensorId = sensorId;
            this.hour = hour;
            this.date = date;
        }

        void add(Evaluated e) {
            EnergyMeasurement m = e.measurement();
            count++;
            sumPowerKw += m.powerKw();
            minPowerKw = Math.min(minPowerKw, m.powerKw());
            maxPowerKw = Math.max(maxPowerKw, m.powerKw());
            energyKwh += e.energyIncrementKwh();
            if (e.thresholdExceeded()) {
                thresholdExceededCount++;
            }
        }

        Update toUpdate(Instant now) {
            Update update = new Update()
                    .inc("count", count)
                    .inc("sumPowerKw", sumPowerKw)
                    .inc("energyKwh", energyKwh)
                    .inc("thresholdExceededCount", thresholdExceededCount)
                    .inc("anomalyCount", anomalyCount)
                    .min("minPowerKw", minPowerKw)
                    .max("maxPowerKw", maxPowerKw)
                    .setOnInsert("sensorId", sensorId)
                    .set("updatedAt", now);
            // cles descriptives : ecrites uniquement a l'insertion du document
            return hour != null ? update.setOnInsert("hour", hour) : update.setOnInsert("date", date);
        }
    }
}
