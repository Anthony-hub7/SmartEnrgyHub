package com.smartenergy.analytics;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartenergy.analytics.model.AnomalyDocument;
import com.smartenergy.analytics.model.DailyAggregateDocument;
import com.smartenergy.analytics.model.HourlyAggregateDocument;
import com.smartenergy.analytics.model.SensorStateDocument;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.Severity;
import com.smartenergy.contracts.measurement.RedisKeys;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests d'integration de l'API de consultation analytics (etape 8, CDC section 5.4) :
 * summary / daily-summary (+ alias guide D-17) / anomalies / exceedances / forecast.
 *
 * Cible : la stack docker-compose de l'APPLICATION — smartenergy-mongodb (analytics_db via
 * analytics_app) et smartenergy-redis, PAS de conteneur dedie au test. Les collections sont
 * purgees avant/apres chaque test : le consumer analytics (etape 9) n'ecrit pas encore, et
 * ces tests posent eux-memes leurs agregats.
 *
 * Conception : summary/daily-summary sans agregat -> 404 NO_DATA (le flux arrivera a l'etape 9) ;
 * anomalies/exceedances -> 200 avec liste vide ; forecast -> 501 FORECAST_DISABLED (flag off).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsQueryIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void purgerLesCollections() {
        mongo.remove(new Query(), HourlyAggregateDocument.class);
        mongo.remove(new Query(), DailyAggregateDocument.class);
        mongo.remove(new Query(), AnomalyDocument.class);
        mongo.remove(new Query(), SensorStateDocument.class);
    }

    // ------------------------------------------------------------------ summary

    @Test
    void summary_sansAgregat_repond404NoData() throws Exception {
        mockMvc.perform(get("/api/analytics/{id}/summary", "SENSOR-VIDE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_DATA"));
    }

    /**
     * Critere metier : moyenne PONDEREE = somme(sumPowerKw) / somme(count) = 36/8 = 4.5
     * (la moyenne des moyennes donnerait (3.0 + 5.0)/2 = 4.0 : biais detecte ici).
     * Bornes [from, to) alignees sur les heures pleines (D-13) : 10:30-11:30 -> 10:00-12:00.
     */
    @Test
    void summary_avecAgregats_moyennePonderee_etBornesHoraires() throws Exception {
        String sensor = "SENSOR-SUM";
        mongo.save(new HourlyAggregateDocument(sensor + "|2026-10-27T10", sensor,
                Instant.parse("2026-10-27T10:00:00Z"),
                2, 6.0, 2.0, 4.0, 1.0, 1, 0, Instant.parse("2026-10-27T11:05:00Z")));
        mongo.save(new HourlyAggregateDocument(sensor + "|2026-10-27T11", sensor,
                Instant.parse("2026-10-27T11:00:00Z"),
                6, 30.0, 3.0, 7.0, 5.0, 2, 1, Instant.parse("2026-10-27T12:05:00Z")));
        // seuil du capteur injecte dans le cache (evite d'appeler sensor-service dans le test)
        redis.opsForValue().set(RedisKeys.cacheSensor(sensor), "8.5|true",
                RedisKeys.CACHE_SENSOR_TTL);

        String body = mockMvc.perform(get("/api/analytics/{id}/summary", sensor)
                        .param("from", "2026-10-27T10:30:00Z")
                        .param("to", "2026-10-27T11:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(8))
                .andExpect(jsonPath("$.avgPowerKw").value(4.5))
                .andExpect(jsonPath("$.minPowerKw").value(2.0))
                .andExpect(jsonPath("$.maxPowerKw").value(7.0))
                .andExpect(jsonPath("$.energyKwh").value(6.0))
                .andExpect(jsonPath("$.thresholdKw").value(8.5))
                .andExpect(jsonPath("$.thresholdExceededCount").value(3))
                .andExpect(jsonPath("$.anomalyCount").value(1))
                .andReturn().getResponse().getContentAsString();

        // D-13 : bornes effectives alignees sur les heures pleines
        org.junit.jupiter.api.Assertions.assertEquals(
                Instant.parse("2026-10-27T10:00:00Z"),
                Instant.parse((String) JsonPath.read(body, "$.effectiveFrom")));
        org.junit.jupiter.api.Assertions.assertEquals(
                Instant.parse("2026-10-27T12:00:00Z"),
                Instant.parse((String) JsonPath.read(body, "$.effectiveTo")));
    }

    @Test
    void summary_plageInvalide_400_invalidParameterPuisRangeTooLarge() throws Exception {
        mockMvc.perform(get("/api/analytics/{id}/summary", "SENSOR-SUM")
                        .param("from", "2026-10-27T12:00:00Z")
                        .param("to", "2026-10-27T11:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mockMvc.perform(get("/api/analytics/{id}/summary", "SENSOR-SUM")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-10-15T00:00:00Z"))     // 44 jours > 31
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
    }

    // ------------------------------------------------------------------ daily-summary (D-17)

    @Test
    void dailySummary_404SansAgregat_puis200_surLesDeuxChemins() throws Exception {
        String sensor = "SENSOR-DAY";

        mockMvc.perform(get("/api/analytics/{id}/daily-summary", sensor)
                        .param("date", "2026-10-20"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_DATA"));

        mongo.save(new DailyAggregateDocument(sensor + "|2026-10-20", sensor, "2026-10-20",
                4, 20.0, 4.0, 6.0, 0.8, 0, 1, Instant.parse("2026-10-20T23:59:59Z")));

        // chemin canonique
        mockMvc.perform(get("/api/analytics/{id}/daily-summary", sensor)
                        .param("date", "2026-10-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-20"))
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.avgPowerKw").value(5.0))     // 20/4 pondere
                .andExpect(jsonPath("$.anomalyCount").value(1));

        // ALIAS du guide (D-17) : meme reponse depuis /api/sensors/{id}/daily-summary
        mockMvc.perform(get("/api/sensors/{id}/daily-summary", sensor)
                        .param("date", "2026-10-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorId").value(sensor))
                .andExpect(jsonPath("$.energyKwh").value(0.8));
    }

    // ------------------------------------------------------------------ anomalies

    @Test
    void anomalies_pageVide_puisPaginationEtFiltre() throws Exception {
        String sensor = "SENSOR-ANOM";
        String from = "2026-10-27T00:00:00Z";
        String to = "2026-10-28T00:00:00Z";

        // aucune anomalie : 200 avec page vide (une absence d'anomalie n'est pas une erreur)
        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(0));

        // 3 anomalies : T1 THRESHOLD, T2 ZSCORE, T3 THRESHOLD (timestamps croissants)
        mongo.save(anomaly("anom-1", sensor, Instant.parse("2026-10-27T10:00:00Z"), AnomalyType.THRESHOLD));
        mongo.save(anomaly("anom-2", sensor, Instant.parse("2026-10-27T11:00:00Z"), AnomalyType.ZSCORE));
        mongo.save(anomaly("anom-3", sensor, Instant.parse("2026-10-27T12:00:00Z"), AnomalyType.THRESHOLD));

        // page 0 (size 2) : tri du plus recent au plus ancien -> T3 puis T2
        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", from).param("to", to).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items[0].anomalyId").value("anom-3"))
                .andExpect(jsonPath("$.items[1].anomalyId").value("anom-2"));

        // page 1 : le reste
        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", from).param("to", to).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].anomalyId").value("anom-1"));

        // filtre par type
        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", from).param("to", to).param("type", "THRESHOLD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // type inconnu -> 400 (conversion d'enum)
        mockMvc.perform(get("/api/analytics/{id}/anomalies", sensor)
                        .param("from", from).param("to", to).param("type", "BOOM"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anomalies_paginationInvalides_400() throws Exception {
        mockMvc.perform(get("/api/analytics/{id}/anomalies", "SENSOR-ANOM").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mockMvc.perform(get("/api/analytics/{id}/anomalies", "SENSOR-ANOM").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ------------------------------------------------------------------ exceedances

    @Test
    void exceedances_etatCourant_puisPeriode_400SansThreshold() throws Exception {
        // sans donnees : 200 liste vide
        mockMvc.perform(get("/api/analytics/exceedances").param("threshold", "10.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));

        // etat courant : seul SENSOR-HAUT depasse 10 kW
        mongo.save(new SensorStateDocument("SENSOR-HAUT", Instant.parse("2026-10-27T13:59:58Z"),
                12.4, null, 0, 0, 0));
        mongo.save(new SensorStateDocument("SENSOR-BAS", Instant.parse("2026-10-27T13:59:58Z"),
                5.1, null, 0, 0, 0));
        mockMvc.perform(get("/api/analytics/exceedances").param("threshold", "10.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sensorId").value("SENSOR-HAUT"))
                .andExpect(jsonPath("$[0].thresholdKw").value(10.0))
                .andExpect(jsonPath("$[0].lastPowerKw").value(12.4));

        // avec from/to : max quotidien de la periode
        mongo.save(new DailyAggregateDocument("SENSOR-BAS|2026-10-26", "SENSOR-BAS", "2026-10-26",
                100, 400.0, 1.0, 15.0, 60.0, 5, 0, Instant.parse("2026-10-26T23:59:59Z")));
        mockMvc.perform(get("/api/analytics/exceedances")
                        .param("threshold", "10.0")
                        .param("from", "2026-10-26T00:00:00Z")
                        .param("to", "2026-10-27T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sensorId").value("SENSOR-BAS"))
                .andExpect(jsonPath("$[0].maxPowerKw").value(15.0));

        // threshold obligatoire
        mockMvc.perform(get("/api/analytics/exceedances"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ------------------------------------------------------------------ forecast (extension)

    @Test
    void forecast_repond501_forecastDisabled_puis400SiHorizonInvalide() throws Exception {
        mockMvc.perform(get("/api/analytics/{id}/forecast", "SENSOR-001"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("FORECAST_DISABLED"));

        // horizon valide mais module inactif : meme code 501
        mockMvc.perform(get("/api/analytics/{id}/forecast", "SENSOR-001")
                        .param("horizonHours", "12"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("FORECAST_DISABLED"));

        // forme de requete invalide : 400 avant tout appel au module
        mockMvc.perform(get("/api/analytics/{id}/forecast", "SENSOR-001")
                        .param("horizonHours", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ------------------------------------------------------------------ OpenAPI (critere CDC)

    @Test
    void openApi_documenteToutesLesRoutesDeLecture() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertNotNull(
                JsonPath.read(docs, "$.paths['/api/analytics/{sensorId}/summary'].get"));
        org.junit.jupiter.api.Assertions.assertNotNull(
                JsonPath.read(docs, "$.paths['/api/analytics/{sensorId}/daily-summary'].get"));
        org.junit.jupiter.api.Assertions.assertNotNull(
                JsonPath.read(docs, "$.paths['/api/sensors/{sensorId}/daily-summary'].get"),
                "alias guide D-17 documente");
        org.junit.jupiter.api.Assertions.assertNotNull(
                JsonPath.read(docs, "$.paths['/api/analytics/{sensorId}/anomalies'].get"));
        org.junit.jupiter.api.Assertions.assertNotNull(
                JsonPath.read(docs, "$.paths['/api/analytics/exceedances'].get"));
        org.junit.jupiter.api.Assertions.assertNotNull(
                JsonPath.read(docs, "$.paths['/api/analytics/{sensorId}/forecast'].get"));

        int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(status >= 200 && status < 400,
                "swagger-ui.html repond " + status);
    }

    // ------------------------------------------------------------------ utilitaires

    private static AnomalyDocument anomaly(String id, String sensor, Instant timestamp, AnomalyType type) {
        return new AnomalyDocument(id, sensor, timestamp,
                "evt-" + id, type, Severity.HIGH, 28.9, 10.0, "SENSOR", null, timestamp.plusMillis(120));
    }
}
