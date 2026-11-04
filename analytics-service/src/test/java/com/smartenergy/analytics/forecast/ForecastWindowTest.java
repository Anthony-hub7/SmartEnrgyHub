package com.smartenergy.analytics.forecast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Construction de la fenetre d'entree (CDC 17.3, etape 1) : grille horaire, interpolation,
 * rejet des trous trop longs, normalisation min-max du SEUL train.
 *
 * <p>Regle de fiabilite : ces operations doivent produire en Java les MEMES valeurs que
 * {@code ml/train_forecast.py} sur les memes entrees (coherence Python <-> Java).
 */
class ForecastWindowTest {

    private static final Instant H0 = Instant.parse("2026-10-01T00:00:00Z");

    private static Map<Instant, Double> serie(int hours, double... values) {
        Map<Instant, Double> m = new HashMap<>();
        for (int i = 0; i < hours; i++) {
            m.put(H0.plus(Duration.ofHours(i)), values[i]);
        }
        return m;
    }

    @Test
    void fenetre_complete_sans_interpolation() {
        double[] vals = new double[24];
        for (int i = 0; i < 24; i++) {
            vals[i] = 3.0 + i * 0.1;
        }
        Map<Instant, Double> hourly = serie(24, vals);

        double[] window = ForecastWindow.build(hourly, H0.plus(Duration.ofHours(23)), 24, 6);

        assertEquals(24, window.length);
        assertArrayEquals(vals, window, 1e-9, "aucune donnée manquante -> valeurs identiques");
    }

    @Test
    void trou_interne_interpole_lineairement() {
        Map<Instant, Double> hourly = serie(5, 1.0, 2.0, Double.NaN, 4.0, 5.0);
        // hours 0..4 avec un trou a l'heure 2
        hourly.remove(H0.plus(Duration.ofHours(2)));
        Map<Instant, Double> fenetre = new HashMap<>(hourly);

        double[] window = ForecastWindow.build(fenetre, H0.plus(Duration.ofHours(4)), 5, 6);

        assertEquals(3.0, window[2], 1e-9, "interpolation lineaire (2.0 + 4.0) / 2 = 3.0");
        assertEquals(1.0, window[0]);
        assertEquals(5.0, window[4]);
    }

    @Test
    void trou_de_6_heures_interpole_7_heures_rejetees() {
        // 10 heures : trous aux indices 3..8 (6 trous) -> accepte + interpole
        Map<Instant, Double> sixTrous = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            if (i < 3 || i > 8) {
                sixTrous.put(H0.plus(Duration.ofHours(i)), (double) i);
            }
        }
        double[] window = ForecastWindow.build(sixTrous, H0.plus(Duration.ofHours(9)), 10, 6);
        assertEquals(10, window.length, "6 trous consecutifs = borne haute autorisee : fenetre gardée");
        // interpolations de bout en bout : entre index 2 (=2.0) et index 9 (=9.0)
        assertEquals(3.0, window[3], 1e-9, "lineaire : 2 + 7*(1/7)");
        assertEquals(8.0, window[8], 1e-9);

        // 7 heures de trous consecutifs -> rejet NO_DATA
        Map<Instant, Double> septTrous = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            if (i < 2 || i > 8) {
                septTrous.put(H0.plus(Duration.ofHours(i)), (double) i);
            }
        }
        ApiException ex = assertThrows(ApiException.class,
                () -> ForecastWindow.build(septTrous, H0.plus(Duration.ofHours(9)), 10, 6));
        assertEquals(ErrorCode.NO_DATA, ex.code(), "7 trous > maxGap 6 -> NO_DATA");
    }

    @Test
    void fenetre_entierement_vide_rejetee() {
        Map<Instant, Double> vide = Map.of();
        ApiException ex = assertThrows(ApiException.class,
                () -> ForecastWindow.build(vide, H0, 24, 6));
        assertEquals(ErrorCode.NO_DATA, ex.code());
    }

    @Test
    void normalisation_et_denormalisation_symetriques() {
        double[] values = {2.5, 3.75, 5.0};
        double min = 2.5, max = 5.0;

        float[] norm = ForecastWindow.normalize(values, min, max);
        assertEquals(0.0f, norm[0], 1e-6, "min -> 0");
        assertEquals(1.0f, norm[2], 1e-6, "max -> 1");
        assertEquals(0.5f, norm[1], 1e-6, "milieu -> 0.5");

        double[] back = ForecastWindow.denormalize(norm, min, max);
        assertArrayEquals(values, back, 1e-6, "denormalisee = exacte inverse");
    }

    @Test
    void normalisation_avec_etendue_nulle_evite_division_par_zero() {
        float[] norm = ForecastWindow.normalize(new double[] {3.0, 3.0}, 3.0, 3.0);
        assertEquals(0.0f, norm[0], 1e-6, "span=0 -> (x-min)/1 = 0, pas de NaN/Infinity");
    }
}
