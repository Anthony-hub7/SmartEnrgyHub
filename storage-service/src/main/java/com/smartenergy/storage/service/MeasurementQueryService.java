package com.smartenergy.storage.service;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.contracts.measurement.MeasurementHistoryResponse;
import com.smartenergy.contracts.measurement.MeasurementItem;
import com.smartenergy.contracts.measurement.RedisKeys;
import com.smartenergy.contracts.measurement.SensorState;
import com.smartenergy.storage.entity.LatestMeasurementEntity;
import com.smartenergy.storage.entity.MeasurementByDayEntity;
import com.smartenergy.storage.repository.MeasurementByDayRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Lecture de l'historique et de la derniere mesure (etape 8, CDC section 5.4 ;
 * decisions D-08 et D-09).
 *
 * <p><b>Historique</b> : plage {@code [from, to)} (from inclus, to exclu), pagination par
 * curseur opaque ({@link HistoryCursor}). La lecture suit le modele Cassandra (D-08) :
 * <b>UNE requete par jour UTC</b> de la plage, toujours avec la cle de partition complete et des
 * bornes de clustering — jamais de IN sur une cle de partition, jamais d'ALLOW FILTERING.
 * Chaque requete porte {@code LIMIT limit + 1} : des que la limite + 1 est atteinte, on s'arrete
 * (les jours restants ne sont pas lus) et {@code nextCursor} est renvoye.
 *
 * <p>Un capteur sans donnee repond 200 avec {@code items} vide : le storage ne consulte pas
 * sensor-service (pas de couplage entre services).
 *
 * <p><b>Derniere mesure</b> : lecture O(1) de la table {@code latest_measurement_by_sensor}
 * (D-09), avec un cache-aside Redis ({@code cache:latest:{sensorId}}, TTL 30 s) pour absorber les
 * pics de lecture d'un dashboard. Une panne Redis ne bloque jamais la lecture (repli direct sur
 * Cassandra) ; l'absence totale de mesure repond 404 MEASUREMENT_NOT_FOUND.
 */
@Service
public class MeasurementQueryService {

    private static final Logger log = LoggerFactory.getLogger(MeasurementQueryService.class);

    static final int DEFAULT_LIMIT = 100;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 1000;
    /** Plage maximale d'une requete d'historique (le storage est un service temps-reel). */
    static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);

    private final MeasurementByDayRepository repository;
    private final CassandraTemplate cassandra;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public MeasurementQueryService(MeasurementByDayRepository repository, CassandraTemplate cassandra,
                                   StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.repository = repository;
        this.cassandra = cassandra;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Historique d'un capteur sur [from, to), pagine par curseur opaque.
     *
     * @param from  borne inclusive (defaut : to - 24 h)
     * @param to    borne exclusive (defaut : maintenant)
     * @param limit nombre maximum d'items (defaut 100, domaine [1, 1000])
     * @param cursor jeton opaqne de reprise (nextCursor de la page precedente)
     * @param order "desc" (defaut, plus recent d'abord) ou "asc" (chronologique)
     */
    public MeasurementHistoryResponse history(String sensorId, Instant from, Instant to,
                                              Integer limit, String cursor, String order) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_RANGE);
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER,
                    "La plage [from, to) doit etre non vide : from < to");
        }
        if (effectiveTo.isAfter(effectiveFrom.plus(MAX_RANGE))) {
            throw ApiException.of(ErrorCode.RANGE_TOO_LARGE,
                    "Plage de 7 jours maximum par requete : paginez avec nextCursor");
        }
        int effectiveLimit = limit != null ? limit : DEFAULT_LIMIT;
        if (effectiveLimit < MIN_LIMIT || effectiveLimit > MAX_LIMIT) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER,
                    "limit doit etre compris entre " + MIN_LIMIT + " et " + MAX_LIMIT);
        }
        boolean ascending = parseOrder(order);
        HistoryCursor start = cursor != null ? HistoryCursor.decode(cursor) : null;

        List<MeasurementByDayEntity> collected = new ArrayList<>(effectiveLimit + 1);
        for (LocalDate day : days(effectiveFrom, effectiveTo, ascending)) {
            Instant dayFrom = effectiveFrom.isAfter(startOfDay(day)) ? effectiveFrom : startOfDay(day);
            Instant dayEnd = startOfDay(day.plusDays(1));
            Instant dayTo = effectiveTo.isBefore(dayEnd) ? effectiveTo : dayEnd;
            if (start != null) {
                // jours deja parcourus par la page precedente : sautes
                if (ascending && day.isBefore(start.day())) {
                    continue;
                }
                if (!ascending && day.isAfter(start.day())) {
                    continue;
                }
                if (day.equals(start.day())) {
                    // reprise de pagination : exclure le dernier item deja renvoye
                    if (ascending) {
                        Instant after = start.lastMeasuredAt().plusMillis(1);   // bornes ms : >= equivaut a >
                        if (after.isAfter(dayFrom)) dayFrom = after;
                    } else {
                        if (start.lastMeasuredAt().isBefore(dayTo)) dayTo = start.lastMeasuredAt();
                    }
                }
            }
            if (!dayTo.isAfter(dayFrom)) {
                continue;   // plage vide pour ce jour (borne haute a minuit, curseur au bout...)
            }
            // UNE requete par jour, cle de partition complete, LIMIT limit+1 (D-08)
            List<MeasurementByDayEntity> rows = repository.findByDayRange(
                    sensorId, day, dayFrom, dayTo, effectiveLimit + 1);
            if (ascending) {
                // la partition est lue en measured_at DESC (clustering) : inversion pour un parcours asc
                List<MeasurementByDayEntity> reversed = new ArrayList<>(rows);
                java.util.Collections.reverse(reversed);
                collected.addAll(reversed);
            } else {
                collected.addAll(rows);
            }
            if (collected.size() > effectiveLimit) {
                break;   // assez pour la page + detection du suivant : les jours restants ne sont pas lus
            }
        }

        boolean hasMore = collected.size() > effectiveLimit;
        List<MeasurementByDayEntity> page = hasMore ? collected.subList(0, effectiveLimit) : collected;
        String nextCursor = null;
        if (hasMore) {
            MeasurementByDayEntity last = page.get(page.size() - 1);
            nextCursor = new HistoryCursor(last.getKey().getDay(), last.getKey().getMeasuredAt()).encode();
        }
        return new MeasurementHistoryResponse(sensorId, effectiveFrom, effectiveTo,
                page.stream().map(MeasurementQueryService::toItem).toList(), nextCursor);
    }

    /**
     * Derniere mesure connue d'un capteur : cache-aside Redis (30 s) puis Cassandra (D-09).
     *
     * @throws ApiException(MEASUREMENT_NOT_FOUND) si aucune mesure n'existe pour ce capteur
     */
    public MeasurementItem latest(String sensorId) {
        String cacheKey = RedisKeys.cacheLatest(sensorId);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, MeasurementItem.class);
            }
        } catch (RuntimeException | JsonProcessingException e) {
            // panne Redis OU cache corrompu : repli sur Cassandra (jamais d'erreur 500 pour un cache)
            log.warn("cache latest inutilisable pour {} ({}) : lecture Cassandra directe",
                    sensorId, e.toString());
        }
        LatestMeasurementEntity row = cassandra.selectOne(
                SimpleStatement.newInstance(
                        "SELECT * FROM latest_measurement_by_sensor WHERE sensor_id = ?", sensorId),
                LatestMeasurementEntity.class);
        if (row == null) {
            throw ApiException.of(ErrorCode.MEASUREMENT_NOT_FOUND,
                    "Aucune mesure connue pour le capteur " + sensorId);
        }
        MeasurementItem item = toItem(row);
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(item),
                    RedisKeys.CACHE_LATEST_TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("ecriture du cache latest impossible pour {} ({})", sensorId, e.toString());
        }
        return item;
    }

    // ------------------------------------------------------------------ utilitaires

    private static boolean parseOrder(String order) {
        if (order == null || order.isBlank() || "desc".equalsIgnoreCase(order)) {
            return false;
        }
        if ("asc".equalsIgnoreCase(order)) {
            return true;
        }
        throw ApiException.of(ErrorCode.INVALID_PARAMETER, "order doit valoir \"asc\" ou \"desc\"");
    }

    /** Jours UTC couverts par [from, to), dans l'ordre de parcours. */
    private static List<LocalDate> days(Instant from, Instant to, boolean ascending) {
        LocalDate first = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = to.atZone(ZoneOffset.UTC).toLocalDate();
        List<LocalDate> days = new ArrayList<>();
        if (ascending) {
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                days.add(d);
            }
        } else {
            for (LocalDate d = last; !d.isBefore(first); d = d.minusDays(1)) {
                days.add(d);
            }
        }
        return days;
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static MeasurementItem toItem(MeasurementByDayEntity e) {
        return new MeasurementItem(e.getKey().getMeasuredAt(), e.getPowerKw(), e.getVoltage(),
                e.getTemperature(), e.getCurrentA(), e.getFrequencyHz(),
                e.getState() == null ? null : SensorState.valueOf(e.getState()));
    }

    private static MeasurementItem toItem(LatestMeasurementEntity e) {
        return new MeasurementItem(e.getMeasuredAt(), e.getPowerKw(), e.getVoltage(),
                e.getTemperature(), e.getCurrentA(), e.getFrequencyHz(),
                e.getState() == null ? null : SensorState.valueOf(e.getState()));
    }
}
