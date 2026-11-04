package com.smartenergy.analytics.service;

import com.smartenergy.analytics.client.SensorClient;
import com.smartenergy.contracts.analytics.AnomalyResponse;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.DailySummaryResponse;
import com.smartenergy.contracts.analytics.ExceedanceResponse;
import com.smartenergy.contracts.analytics.SummaryResponse;
import com.smartenergy.contracts.common.PageResponse;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.analytics.model.AnomalyDocument;
import com.smartenergy.analytics.model.DailyAggregateDocument;
import com.smartenergy.analytics.model.HourlyAggregateDocument;
import com.smartenergy.analytics.model.SensorStateDocument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Lecture des indicateurs analytics (etape 8, CDC section 5.4 ; decision D-13 granularite
 * horaire). Les ECRITURES (agregats, anomalies) arrivent a l'etape 9 : en attendant, une
 * periode sans agregat repond 404 NO_DATA — la route est prete, pas encore le flux.
 *
 * <p>Regles metier :
 * <ul>
 *   <li><b>Moyenne ponderee</b> : {@code sum(sumPowerKw) / sum(count)} sur les agregats
 *       horaires — JAMAIS la moyenne des moyennes (biais si les heures ont des counts differents).</li>
 *   <li><b>[from, to)</b> aligne sur les heures pleines en lecture (D-13) : bornes effectives
 *       renvoyees dans la reponse pour que le client sache ce qui a ete mesure.</li>
 *   <li><b>Defauts</b> : to = maintenant, from = to - 24 h ; plage max 31 jours (RANGE_TOO_LARGE).</li>
 *   <li><b>Seuil</b> : seuil courant du capteur via {@link SensorClient} (cache 60 s ->
 *       sensor-service -> repli par defaut ; ne leve jamais d'exception).</li>
 *   <li>Listes potentiellement vides (anomalies, exceedances) : 200 avec liste vide.
 *       Resumés ponctuels (summary, daily-summary) : 404 NO_DATA si aucune donnee.</li>
 * </ul>
 *
 * <p>Test croise (critere du CDC) : le {@code count} de summary doit egaler le nombre de
 * mesures Cassandra de la meme plage — possible des l'etape 9 ou les deux cotes sont alimentes.
 */
@Service
public class AnalyticsQueryService {

    static final int MAX_ANOMALY_SIZE = 500;
    static final int DEFAULT_ANOMALY_SIZE = 50;
    static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);

    private final MongoTemplate mongo;
    private final SensorClient sensorClient;
    private final Clock clock;

    public AnalyticsQueryService(MongoTemplate mongo, SensorClient sensorClient, Clock clock) {
        this.mongo = mongo;
        this.sensorClient = sensorClient;
        this.clock = clock;
    }

    /** Indicateurs sur [effectiveFrom, effectiveTo) aligne sur les heures pleines (D-13). */
    public SummaryResponse summary(String sensorId, Instant from, Instant to) {
        Instant effectiveTo = to != null ? to : clock.instant();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_RANGE);
        validateRange(effectiveFrom, effectiveTo);

        // alignement horaire : from tronque, to arrondi a l'heure superieure (sauf si deja pile)
        Instant hourFrom = effectiveFrom.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Instant hourTo = effectiveTo.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        if (hourTo.isBefore(effectiveTo)) {
            hourTo = hourTo.plus(Duration.ofHours(1));
        }

        List<HourlyAggregateDocument> docs = mongo.find(
                new Query(Criteria.where("sensorId").is(sensorId)
                        .and("hour").gte(hourFrom).lt(hourTo))
                        .with(Sort.by(Sort.Direction.ASC, "hour")),
                HourlyAggregateDocument.class);
        if (docs.isEmpty()) {
            throw ApiException.of(ErrorCode.NO_DATA,
                    "Aucun agregat horaire pour le capteur " + sensorId + " sur la periode demandee");
        }

        long count = 0;
        double sumPowerKw = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double energyKwh = 0;
        long exceeded = 0;
        long anomalies = 0;
        for (HourlyAggregateDocument d : docs) {
            count += d.count();
            sumPowerKw += d.sumPowerKw();
            min = Math.min(min, d.minPowerKw());
            max = Math.max(max, d.maxPowerKw());
            energyKwh += d.energyKwh();
            exceeded += d.thresholdExceededCount();
            anomalies += d.anomalyCount();
        }
        if (count == 0) {
            throw ApiException.of(ErrorCode.NO_DATA,
                    "Aucune mesure pour le capteur " + sensorId + " sur la periode demandee");
        }
        double avg = sumPowerKw / count;   // moyenne ponderee (PAS la moyenne des moyennes)
        double thresholdKw = sensorClient.thresholdFor(sensorId).maxPowerKw();
        return new SummaryResponse(sensorId, hourFrom, hourTo, count, avg, min, max,
                energyKwh, thresholdKw, exceeded, anomalies);
    }

    /** Resume d'une journee UTC (defaut : aujourd'hui) ; alias guide /api/sensors/{id}/daily-summary. */
    public DailySummaryResponse dailySummary(String sensorId, LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(ZoneOffset.UTC);
        DailyAggregateDocument doc = mongo.findById(
                sensorId + '|' + effectiveDate, DailyAggregateDocument.class);
        if (doc == null || doc.count() == 0) {
            throw ApiException.of(ErrorCode.NO_DATA,
                    "Aucun agregat quotidien pour le capteur " + sensorId + " le " + effectiveDate);
        }
        return new DailySummaryResponse(doc.sensorId(), doc.date(), doc.count(),
                doc.sumPowerKw() / doc.count(), doc.minPowerKw(), doc.maxPowerKw(),
                doc.energyKwh(), doc.thresholdExceededCount(), doc.anomalyCount());
    }

    /** Anomalies d'un capteur sur [from, to), triees du plus recent au plus ancien, paginees. */
    public PageResponse<AnomalyResponse> anomalies(String sensorId, Instant from, Instant to,
                                                   AnomalyType type, int page, int size) {
        Instant effectiveTo = to != null ? to : clock.instant();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_RANGE);
        validateRange(effectiveFrom, effectiveTo);
        if (page < 0) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER, "page doit etre >= 0");
        }
        if (size < 1 || size > MAX_ANOMALY_SIZE) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER,
                    "size doit etre compris entre 1 et " + MAX_ANOMALY_SIZE);
        }

        Criteria criteria = Criteria.where("sensorId").is(sensorId)
                .and("timestamp").gte(effectiveFrom).lt(effectiveTo);
        if (type != null) {
            criteria = criteria.and("type").is(type);
        }
        Query base = new Query(criteria);
        long total = mongo.count(base, AnomalyDocument.class);
        List<AnomalyResponse> items = mongo.find(base
                        .with(Sort.by(Sort.Direction.DESC, "timestamp"))
                        .skip((long) page * size)
                        .limit(size),
                AnomalyDocument.class).stream().map(AnalyticsQueryService::toResponse).toList();
        return new PageResponse<>(items, page, size, total);
    }

    /**
     * Capteurs en depassement de seuil.
     * <ul>
     *   <li>sans from/to : etat courant ({@code sensor_state.lastPowerKw > threshold}) ;</li>
     *   <li>avec from/to : {@code daily_aggregates.maxPowerKw > threshold} sur la periode.</li>
     * </ul>
     * Liste vide (200) si aucun capteur ne depasse : c'est une reponse, pas une absence de donnee.
     */
    public List<ExceedanceResponse> exceedances(double threshold, Instant from, Instant to) {
        if (from == null && to == null) {
            return mongo.find(new Query(Criteria.where("lastPowerKw").gt(threshold))
                            .with(Sort.by(Sort.Direction.DESC, "lastPowerKw")),
                    SensorStateDocument.class).stream()
                    .map(s -> new ExceedanceResponse(s.sensorId(), threshold,
                            s.lastPowerKw(), s.lastMeasuredAt(), null))
                    .toList();
        }

        Instant effectiveTo = to != null ? to : clock.instant();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_RANGE);
        validateRange(effectiveFrom, effectiveTo);
        LocalDate fromDay = effectiveFrom.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDay = effectiveTo.atZone(ZoneOffset.UTC).toLocalDate();
        if (!effectiveTo.isAfter(toDay.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            toDay = toDay.minusDays(1);   // to exactement a minuit : ce jour n'a pas d'overlap
        }
        return mongo.find(new Query(Criteria.where("date").gte(fromDay.toString())
                        .lte(toDay.toString()).and("maxPowerKw").gt(threshold)),
                DailyAggregateDocument.class).stream()
                .map(d -> new ExceedanceResponse(d.sensorId(), threshold, null, null, d.maxPowerKw()))
                .toList();
    }

    // ------------------------------------------------------------------ utilitaires

    private static void validateRange(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER,
                    "La plage [from, to) doit etre non vide : from < to");
        }
        if (to.isAfter(from.plus(MAX_RANGE))) {
            throw ApiException.of(ErrorCode.RANGE_TOO_LARGE, "Plage de 31 jours maximum par requete");
        }
    }

    private static AnomalyResponse toResponse(AnomalyDocument d) {
        return new AnomalyResponse(d.id(), d.sensorId(), d.timestamp(), d.eventId(), d.type(),
                d.severity(), d.powerKw(), d.thresholdKw(), d.thresholdSource(), d.zScore(),
                d.detectedAt());
    }
}
