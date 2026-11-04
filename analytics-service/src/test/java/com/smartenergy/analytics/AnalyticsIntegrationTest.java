package com.smartenergy.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartenergy.analytics.model.AnomalyDocument;
import com.smartenergy.analytics.model.DailyAggregateDocument;
import com.smartenergy.analytics.model.HourlyAggregateDocument;
import com.smartenergy.analytics.model.SensorStateDocument;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.Severity;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EventIds;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.contracts.measurement.MeasurementSchema;
import com.smartenergy.contracts.measurement.RedisKeys;
import com.smartenergy.contracts.measurement.StreamContract;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests d'integration des niveaux A et B d'analytics (etape 9 ; CDC 17.1, 17.2 ; scenarios 4, 8
 * et 11) : la boucle COMPLETE — publication dans le stream -> {@code AnalyticsStreamConsumer}
 * (dedup, regles, Welford) -> {@code AggregateWriter} (MongoDB) -> lectures deja testees a l'etape 8
 * (routes summary/daily-summary/anomalies).
 *
 * Cible : la stack docker-compose de l'APPLICATION (smartenergy-redis + smartenergy-mongodb via
 * analytics_app), PAS de conteneur dedie au test (convention storage-service). Le consumer tourne
 * dans le contexte de test lui-meme (SmartLifecycle) : pas besoin de service separe.
 *
 * Robustesse : chaque test utilise un capteur au suffixe ALEATOIRE (les cles dedup Redis vivent
 * 24 h : un rejeu du meme evenement serait ignore a tort au second passage). sensor-service n'est
 * PAS demarre (port 8084 ferme) : les seuils viennent du cache Redis (seede) ou du repli par
 * defaut (scenario 11).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsIntegrationTest {

    private static final DateTimeFormatter HOUR_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    @AfterEach
    void purgerLesCollections() {
        mongo.remove(new Query(), HourlyAggregateDocument.class);
        mongo.remove(new Query(), DailyAggregateDocument.class);
        mongo.remove(new Query(), AnomalyDocument.class);
        mongo.remove(new Query(), SensorStateDocument.class);
    }

    // ------------------------------------------------------------------ niveau A : agregats (CDC 17.1)

    /**
     * Formules (2) et indicateurs obligatoires : 3 mesures a 2 s d'ecart -> count, somme, min, max,
     * energie = somme(Pi x dt), depassements ; ecrits dans hourly ET daily ET sensor_state, puis
     * relus via les routes de l'etape 8 (moyenne ponderee incluse).
     */
    @Test
    void niveauA_agregatsExacts_energieSommePidT_etLectureParResume() throws Exception {
        String sensor = sensor();
        Instant t0 = Instant.parse("2026-10-29T10:00:00Z");
        publish(measurement(sensor, t0, 4.0),
                measurement(sensor, t0.plusSeconds(2), 4.5),
                measurement(sensor, t0.plusSeconds(4), 5.0));

        awaitUntil(() -> hourlyCount(sensor, t0) == 3, Duration.ofSeconds(30));

        HourlyAggregateDocument h = hourly(sensor, t0);
        assertEquals(3, h.count(), "nombre de mesures recues");
        assertEquals(13.5, h.sumPowerKw(), 1e-9);
        assertEquals(4.0, h.minPowerKw(), 1e-9);
        assertEquals(5.0, h.maxPowerKw(), 1e-9);
        assertEquals(17.0 / 3600.0, h.energyKwh(), 1e-9,
                "formule (2) : 4.0 kW x 2 s + 4.5 kW x 2 s = 17/3600 kWh");
        assertEquals(0, h.thresholdExceededCount(), "aucun depassement (seuil 10 kW par defaut)");
        assertEquals(0, h.anomalyCount());
        assertNotNull(h.updatedAt());

        DailyAggregateDocument d = daily(sensor, "2026-10-29");
        assertEquals(3, d.count());
        assertEquals(13.5, d.sumPowerKw(), 1e-9);
        assertEquals(4.0, d.minPowerKw(), 1e-9);
        assertEquals(5.0, d.maxPowerKw(), 1e-9);
        assertEquals(h.energyKwh(), d.energyKwh(), 1e-12, "meme energie au niveau jour");

        SensorStateDocument s = mongo.findById(sensor, SensorStateDocument.class);
        assertEquals(3, s.n(), "Welford a vu les 3 mesures");
        assertEquals(4.5, s.mean(), 1e-9, "(4.0 + 4.5 + 5.0) / 3");
        assertEquals(Instant.parse("2026-10-29T10:00:04Z"), s.lastMeasuredAt());
        assertEquals(5.0, s.lastPowerKw(), 1e-9);
        assertEquals(Instant.parse("2026-10-29T10:00:04Z"), s.lastEnergyBasisTs());

        // lecture (etape 8) : resume pondere et resume quotidien refletent l'ecriture
        mockMvc.perform(get("/api/analytics/{id}/summary", sensor)
                        .param("from", "2026-10-29T10:00:00Z")
                        .param("to", "2026-10-29T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.avgPowerKw").value(4.5))
                .andExpect(jsonPath("$.minPowerKw").value(4.0))
                .andExpect(jsonPath("$.maxPowerKw").value(5.0))
                .andExpect(jsonPath("$.anomalyCount").value(0));
        mockMvc.perform(get("/api/analytics/{id}/daily-summary", sensor)
                        .param("date", "2026-10-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.avgPowerKw").value(4.5));
    }

    /** Energie : premiere mesure sans base (= 0) et trou de 10 min plafonne a 10 s (2 s x 5). */
    @Test
    void energie_premiereMesureNulle_et_trouDe10minPlafonneA10s() {
        String sensor = sensor();
        Instant t0 = Instant.parse("2026-10-29T09:00:00Z");
        publish(measurement(sensor, t0, 3.0), measurement(sensor, t0.plusSeconds(600), 3.0));

        awaitUntil(() -> hourlyCount(sensor, t0) == 2, Duration.ofSeconds(30));

        HourlyAggregateDocument h = hourly(sensor, t0);
        assertEquals(3.0 * 10 / 3600.0, h.energyKwh(), 1e-9,
                "seul le pas de 600 s compte, plafonne a 10 s ; la premiere mesure ne genere rien");
        SensorStateDocument s = mongo.findById(sensor, SensorStateDocument.class);
        assertEquals(t0.plusSeconds(600), s.lastEnergyBasisTs());
        assertEquals(2, s.n());
    }

    // ------------------------------------------------------------------ niveau B : seuil (CDC 17.2, sc. 4)

    /** Scenario 4 : un pic de puissance (x3 du nominal, > seuil capteur) -> anomalie THRESHOLD listee. */
    @Test
    void scenario4_picDePuissance_anomalieThreshold_visibleViaApi() throws Exception {
        String sensor = sensor();
        seedThreshold(sensor, "10.0|true");        // seuil capteur dans le cache (pas de sensor-service)
        double metricBefore = anomaliesDetected(AnomalyType.THRESHOLD);
        Instant t0 = Instant.parse("2026-10-29T11:00:00Z");

        List<EnergyMeasurement> lot = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            lot.add(measurement(sensor, t0.plusSeconds(2L * i), 4.0));   // nominal
        }
        publish(lot.toArray(EnergyMeasurement[]::new));
        publish(measurement(sensor, t0.plusSeconds(62), 32.0));          // pic x8 du nominal

        awaitUntil(() -> anomalyCount(sensor) == 1 && hourlyCount(sensor, t0) == 32,
                Duration.ofSeconds(30));

        AnomalyDocument a = singleAnomaly(sensor);
        assertEquals(AnomalyType.THRESHOLD, a.type());
        assertEquals(Severity.HIGH, a.severity());
        assertEquals(32.0, a.powerKw(), 1e-9);
        assertEquals(10.0, a.thresholdKw(), 1e-9);
        assertEquals("SENSOR", a.thresholdSource());
        assertNull(a.zScore(), "pas de score z pour une anomalie de seuil");

        HourlyAggregateDocument h = hourly(sensor, t0);
        assertEquals(32, h.count());
        assertEquals(1, h.thresholdExceededCount(), "un seul depassement");
        assertEquals(1, h.anomalyCount());

        // l'etat Welford n'est pas pollue par le pic (31 mesures nominales seulement)
        SensorStateDocument s = mongo.findById(sensor, SensorStateDocument.class);
        assertEquals(31, s.n(), "le pic ne met pas a jour mu/sigma");
        assertEquals(4.0, s.mean(), 1e-9);

        // visible depuis l'API /anomalies (etape 8)
        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", "2026-10-29T11:00:00Z")
                        .param("to", "2026-10-29T12:00:00Z")
                        .param("type", "THRESHOLD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].type").value("THRESHOLD"))
                .andExpect(jsonPath("$.items[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.items[0].thresholdSource").value("SENSOR"))
                .andExpect(jsonPath("$.items[0].thresholdKw").value(10.0));

        // metrique du CDC : energy_anomalies_detected_total{type}
        assertEquals(1.0, anomaliesDetected(AnomalyType.THRESHOLD) - metricBefore, 1e-9,
                "energie_anomalies_detected_total{type=THRESHOLD} incremente de 1");
    }

    // ------------------------------------------------------------------ niveau B : score z (CDC 17.2)

    /** Score z (formule 4) : une valeur extreme sur une serie stable -> anomalie ZSCORE (MEDIUM). */
    @Test
    void zscore_valeurExtreme_anomalieMedium_welfordNonPollue() {
        String sensor = sensor();
        Instant t0 = Instant.parse("2026-10-29T12:00:00Z");

        List<EnergyMeasurement> serie = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            serie.add(measurement(sensor, t0.plusSeconds(2L * i), i % 2 == 0 ? 4.0 : 4.2));
        }
        serie.add(measurement(sensor, t0.plusSeconds(70), 10.0));   // extreme (moyenne ~4.1, sigma ~0.1)
        publish(serie.toArray(EnergyMeasurement[]::new));

        awaitUntil(() -> anomalyCount(sensor) == 1 && hourlyCount(sensor, t0) == 36,
                Duration.ofSeconds(30));

        AnomalyDocument a = singleAnomaly(sensor);
        assertEquals(AnomalyType.ZSCORE, a.type());
        assertEquals(Severity.MEDIUM, a.severity());
        assertNotNull(a.zScore(), "score z renseigne");
        assertTrue(a.zScore() > 3.0, " |z| = " + a.zScore() + " > 3.0 : anomalie declaree");
        assertNull(a.thresholdKw(), "pas de seuil de puissance pour une anomalie statistique");

        SensorStateDocument s = mongo.findById(sensor, SensorStateDocument.class);
        assertEquals(35, s.n(), "la mesure anormale n'entre PAS dans Welford");
        assertEquals(4.1, s.mean(), 0.01, "moyenne des 35 mesures nominales");

        HourlyAggregateDocument h = hourly(sensor, t0);
        assertEquals(0, h.thresholdExceededCount(), "10.0 n'EXCEDE PAS un seuil de 10.0 (strict)");
        assertEquals(1, h.anomalyCount());
    }

    // ------------------------------------------------------------------ scenario 8 : doublon

    /** Scenario 8 : le meme evenement republie ne change ni les compteurs, ni les anomalies. */
    @Test
    void scenario8_evenementDuplique_compteursEtAnomaliesInchanges() throws Exception {
        String sensor = sensor();
        seedThreshold(sensor, "10.0|true");
        Instant t0 = Instant.parse("2026-10-29T13:00:00Z");
        EnergyMeasurement m1 = measurement(sensor, t0, 4.0);
        EnergyMeasurement m2 = measurement(sensor, t0.plusSeconds(2), 4.0);
        EnergyMeasurement pic = measurement(sensor, t0.plusSeconds(4), 15.0);

        publish(m1, m2, pic);
        awaitUntil(() -> hourlyCount(sensor, t0) == 3 && anomalyCount(sensor) == 1,
                Duration.ofSeconds(30));

        HourlyAggregateDocument avant = hourly(sensor, t0);
        assertEquals(23.0, avant.sumPowerKw(), 1e-9, "4.0 + 4.0 + 15.0");

        // rejeux : le meme evenement (meme eventId) publie 3 fois de plus
        publish(pic, pic, pic);
        Thread.sleep(3000);   // le consumer les voit et les ignore (dedup)

        HourlyAggregateDocument apres = hourly(sensor, t0);
        assertEquals(3, apres.count(), "sc. 8 : compteur de mesures inchange");
        assertEquals(1, apres.thresholdExceededCount());
        assertEquals(1, apres.anomalyCount());
        assertEquals(avant.sumPowerKw(), apres.sumPowerKw(), 1e-12);
        assertEquals(avant.energyKwh(), apres.energyKwh(), 1e-12);
        assertEquals(avant.updatedAt(), apres.updatedAt(), "aucune ecriture pour un doublon");
        assertEquals(1, mongo.count(new Query(Criteria.where("sensorId").is(sensor)),
                AnomalyDocument.class), "une seule anomalie THRESHOLD");
        assertTrue(Boolean.TRUE.equals(redis.hasKey(RedisKeys.dedupAnalytics(pic.eventId()))),
                "cle dedup:analytics marquee apres ecriture (TTL 24 h)");

        // visible via l'API : totalElements = 1
        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", "2026-10-29T13:00:00Z")
                        .param("to", "2026-10-29T14:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------ scenario 11 : seuil par defaut

    /** Scenario 11 : sensor-service injoignable (8084 ferme) -> traitement au seuil PAR DEFAUT. */
    @Test
    void scenario11_sensorServiceArrete_anomalieAuSeuilParDefaut() throws Exception {
        String sensor = sensor();   // pas de cache : repli sur ANALYTICS_DEFAULT_MAX_POWER_KW (10.0)
        Instant t0 = Instant.parse("2026-10-29T14:00:00Z");
        publish(measurement(sensor, t0, 25.0));

        awaitUntil(() -> anomalyCount(sensor) == 1, Duration.ofSeconds(30));

        AnomalyDocument a = singleAnomaly(sensor);
        assertEquals(AnomalyType.THRESHOLD, a.type());
        assertEquals("DEFAULT", a.thresholdSource(), "scenario 11 : seuil par defaut");
        assertEquals(10.0, a.thresholdKw(), 1e-9);
        assertEquals(Severity.HIGH, a.severity());

        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", "2026-10-29T14:00:00Z")
                        .param("to", "2026-10-29T15:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].thresholdSource").value("DEFAULT"));
    }

    // ------------------------------------------------------------------ retards / rejeu

    /** Une mesure en retard (ts avant la base) : energie 0, compteurs stables, lastMeasuredAt ferme. */
    @Test
    void mesureEnRetard_energieNulle_et_etatNeRegressePas() {
        String sensor = sensor();
        Instant t0 = Instant.parse("2026-10-29T15:00:10Z");
        publish(measurement(sensor, t0, 6.0), measurement(sensor, t0.plusSeconds(2), 6.0));
        awaitUntil(() -> hourlyCount(sensor, t0) == 2, Duration.ofSeconds(30));

        // mesure arrivee apres, mais horodatee AVANT (retard / rejeu)
        publish(measurement(sensor, t0.minusSeconds(1), 9.9));
        awaitUntil(() -> hourlyCount(sensor, t0) == 3, Duration.ofSeconds(30));

        HourlyAggregateDocument h = hourly(sensor, t0);
        assertEquals(3, h.count(), "la mesure en retard compte dans les mesures recues");
        assertEquals(21.9, h.sumPowerKw(), 1e-9);
        assertEquals(6.0 * 2 / 3600.0, h.energyKwh(), 1e-9,
                "energie du seul pas 15:00:10 -> 15:00:12 ; la mesure en retard n'apporte rien");

        SensorStateDocument s = mongo.findById(sensor, SensorStateDocument.class);
        assertEquals(Instant.parse("2026-10-29T15:00:12Z"), s.lastMeasuredAt(),
                "l'etat courant ne regresse jamais");
        assertEquals(6.0, s.lastPowerKw(), 1e-9);
        assertEquals(3, s.n());
    }

    // ------------------------------------------------------------------ utilitaires

    /** Capteur unique par test : les cles dedup Redis vivent 24 h (rejeu du meme evenement = ignore). */
    private static String sensor() {
        return "SENSOR-9A-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void publish(EnergyMeasurement... measurements) {
        for (EnergyMeasurement m : measurements) {
            redis.opsForStream().add(StringRecord.of(MeasurementEventCodec.toFields(m))
                    .withStreamKey(StreamContract.STREAM));
        }
    }

    private static EnergyMeasurement measurement(String sensorId, Instant ts, double powerKw) {
        return new EnergyMeasurement(MeasurementSchema.CURRENT_VERSION,
                EventIds.forMeasurement(sensorId, ts), "trace-etape9", sensorId, ts,
                Instant.now(), powerKw, 230.0, null, null, null, null);
    }

    private void seedThreshold(String sensorId, String cached) {
        redis.opsForValue().set(RedisKeys.cacheSensor(sensorId), cached, RedisKeys.CACHE_SENSOR_TTL);
    }

    private long hourlyCount(String sensor, Instant anyTsOfHour) {
        HourlyAggregateDocument d = hourly(sensor, anyTsOfHour);
        return d == null ? -1 : d.count();
    }

    private HourlyAggregateDocument hourly(String sensor, Instant anyTsOfHour) {
        return mongo.findById(sensor + '|' + HOUR_KEY.format(anyTsOfHour),
                HourlyAggregateDocument.class);
    }

    private DailyAggregateDocument daily(String sensor, String day) {
        return mongo.findById(sensor + '|' + day, DailyAggregateDocument.class);
    }

    private long anomalyCount(String sensor) {
        return mongo.count(new Query(Criteria.where("sensorId").is(sensor)), AnomalyDocument.class);
    }

    private AnomalyDocument singleAnomaly(String sensor) {
        AnomalyDocument a = mongo.findOne(new Query(Criteria.where("sensorId").is(sensor)),
                AnomalyDocument.class);
        assertNotNull(a, "anomalie attendue pour " + sensor);
        return a;
    }

    private double anomaliesDetected(AnomalyType type) {
        return meterRegistry.counter("energy_anomalies_detected_total", "type", type.name()).count();
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition non atteinte en " + timeout);
    }
}
