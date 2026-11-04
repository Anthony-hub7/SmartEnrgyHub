package com.smartenergy.analytics.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartenergy.analytics.config.AnalyticsProperties;
import com.smartenergy.contracts.analytics.ForecastResponse;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@code ForecastService} : validation des parametres, feature flag (501), remplissage de la
 * reponse (points horaires + metriques MAE/RMSE). Le prédicteur est un MOCK : ces tests ne
 * chargent AUCUN modele ONNX (l'inférence DJL est couverte par l'E2E et le test d'integration).
 */
class ForecastServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String MODEL_PATH = "models/energy.onnx";

    private MongoTemplate mongo;
    private final AtomicInteger creations = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        creations.set(0);
    }

    private AnalyticsProperties props(boolean enabled) {
        return new AnalyticsProperties("http://localhost:8084", 10.0, 2, 5,
                new AnalyticsProperties.Zscore(3.0, 30),
                new AnalyticsProperties.Forecast(enabled, MODEL_PATH));
    }

    /** Prédicteur factice : renvoie 24 sorties constantes (metriques de demonstrations). */
    private ForecastService service(boolean enabled) {
        ForecastService.Predictions fake = path -> {
            creations.incrementAndGet();
            return new FakePredictor();
        };
        return new ForecastService(props(enabled), mongo,
                Clock.fixed(NOW, ZoneOffset.UTC), fake);
    }

    @Test
    void horizon_invalide_rejet400() {
        ApiException min = assertThrows(ApiException.class, () -> service(true).forecast("S", 0));
        assertEquals(ErrorCode.INVALID_PARAMETER, min.code());
        ApiException max = assertThrows(ApiException.class, () -> service(true).forecast("S", 25));
        assertEquals(ErrorCode.INVALID_PARAMETER, max.code());
    }

    @Test
    void flag_desactive_repond501_sans_charger_le_modele() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service(false).forecast("SENSOR-001", 6));
        assertEquals(ErrorCode.FORECAST_DISABLED, ex.code());
        assertEquals(0, creations.get(), "aucun chargement quand le flag est off");
    }

    @Test
    void reponse_contient_points_horaires_et_metriques() {
        // aucune mesure en base : la derniere heure connue est NOW-1h -> fenetre vide sur
        // 24 h mais on fournit des donnees via un mock plus permissif (voir FakePredictor)
        when(mongo.find(any(Query.class), any())).thenReturn(java.util.List.of());

        ForecastResponse resp = service(true).forecast("SENSOR-001", 6);

        assertEquals("SENSOR-001", resp.sensorId());
        assertEquals(6, resp.horizonHours());
        assertNotNull(resp.points());
        assertEquals(6, resp.points().size(), "6 points = 1 par heure predite");
        assertEquals(NOW, resp.generatedAt());

        // premier point : l'heure suivant la derniere observee (NOW), puis +1 h each
        assertEquals(NOW.plus(Duration.ofHours(1)), resp.points().get(0).hour());
        assertEquals(NOW.plus(Duration.ofHours(6)), resp.points().get(5).hour());

        // metriques MAE / RMSE du modele (formules 5 et 6, mesurees en Python sur le test)
        assertNotNull(resp.metrics());
        assertEquals(0.056, resp.metrics().get("mae"), 1e-6);
        assertEquals(0.077, resp.metrics().get("rmse"), 1e-6);

        // puissances denormalisees et bornées >= 0 (fake : sortie normalisee 0.5 -> milieu du span)
        resp.points().forEach(p -> assertFalse(Double.isNaN(p.powerKw())));
    }

    @Test
    void modele_charge_une_seule_fois_sur_plusieurs_requetes() {
        when(mongo.find(any(Query.class), any())).thenReturn(java.util.List.of());
        ForecastService svc = service(true);

        svc.forecast("SENSOR-001", 3);
        svc.forecast("SENSOR-001", 12);

        assertEquals(1, creations.get(), "chargement paresseux unique (verrou double controle)");
    }

    /** Prédicteur factice : 24 sorties normalisees a 0.5, metriques de demonstrations. */
    private static final class FakePredictor implements ForecastPredictor {
        private final ForecastModelMetadata meta = new ForecastModelMetadata(
                "SENSOR-001", 24, 24,
                new ForecastModelMetadata.Normalization(2.0, 4.0),
                new ForecastModelMetadata.Metrics(
                        new ForecastModelMetadata.ModelScore(0.056, 0.077),
                        new ForecastModelMetadata.ModelScore(0.81, 0.996),
                        new ForecastModelMetadata.ModelScore(0.071, 0.098)),
                true, Instant.parse("2026-11-01T00:00:00Z"));

        @Override
        public ForecastModelMetadata metadata() {
            return meta;
        }

        @Override
        public float[] predict(float[] input) {
            float[] out = new float[24];
            java.util.Arrays.fill(out, 0.5f);
            return out;
        }

        @Override
        public void close() {}
    }
}
