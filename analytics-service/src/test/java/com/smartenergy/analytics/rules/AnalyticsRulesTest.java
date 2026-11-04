package com.smartenergy.analytics.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartenergy.analytics.rules.AnalyticsRules.Welford;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires des regles pures (etape 9, niveaux A et B, CDC 17.1-17.2).
 * Jeu de reference de l'en-tete du guide : constant 4 kW / 1 h -> 4,0 kWh ; [1..5] -> mean 3,
 * ecart-type d'echantillon 1,5811, z(10) = 4,427 ; trou de 10 min -> pas plafonne a 10 s.
 */
class AnalyticsRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");
    private static final int INTERVAL_SECONDS = 2;
    private static final int GAP_FACTOR = 5;   // plafond = 10 s (2 x 5)

    // ------------------------------------------------------------------ niveau A : energie (formule 2)

    /** Constant 4 kW, 1801 mesures a 2 s d'ecart = 1 h couverte -> E = 4.0 kWh exactement. */
    @Test
    void energie_reference_4kw_pendant_1h_egal_4kwh() {
        double total = 0.0;
        Instant previous = T0;
        for (int i = 1; i <= 1800; i++) {
            Instant current = T0.plusSeconds(2L * i);
            total += AnalyticsRules.energyIncrementKwh(previous, 4.0, current,
                    INTERVAL_SECONDS, GAP_FACTOR);
            previous = current;
        }
        // 1800 x 4 kW x (2/3600) h = 4.0 kWh
        assertEquals(4.0, total, 1e-9, "formule (2) : E = somme(Pi x dt) = 4.0 kWh sur 1 h");
    }

    /** Un trou de 10 min entre deux mesures est plafonne a 10 s (2 s x 5) : l'energie estimee reste plausible. */
    @Test
    void energie_trou_de_10min_plafonne_a_10s() {
        double e = AnalyticsRules.energyIncrementKwh(T0, 3.0, T0.plusSeconds(600),
                INTERVAL_SECONDS, GAP_FACTOR);
        assertEquals(3.0 * 10 / 3600.0, e, 1e-12, "dt plafonne a 10 s, pas 600 s");
    }

    /** Premiere mesure (pas de pas precedent) et rejeu/mesure en retard : 0.0 (jamais de double compte). */
    @Test
    void energie_premiere_mesure_et_rejeu_valent_zero() {
        assertEquals(0.0, AnalyticsRules.energyIncrementKwh(null, 4.0, T0.plusSeconds(2),
                INTERVAL_SECONDS, GAP_FACTOR), "pas de pas precedent -> 0.0");
        assertEquals(0.0, AnalyticsRules.energyIncrementKwh(T0, 4.0, T0,
                INTERVAL_SECONDS, GAP_FACTOR), "meme horodatage (rejeu) -> 0.0");
        assertEquals(0.0, AnalyticsRules.energyIncrementKwh(T0, 4.0, T0.minusSeconds(5),
                INTERVAL_SECONDS, GAP_FACTOR), "mesure en retard -> 0.0");
    }

    // ------------------------------------------------------------------ niveau B : seuil (formule 3)

    /** Depassement STRICT : Pt egal au seuil n'est pas un depassement (Pt > Pmax). */
    @Test
    void seuil_comparaison_stricte() {
        assertTrue(AnalyticsRules.isThresholdExceeded(10.01, 10.0), "10.01 > 10.0 : depassement");
        assertFalse(AnalyticsRules.isThresholdExceeded(10.0, 10.0), "egal : pas de depassement");
        assertFalse(AnalyticsRules.isThresholdExceeded(3.7, 10.0));
    }

    // ------------------------------------------------------------------ niveau B : score z (formule 4)

    /** [1,2,3,4,5] -> mean 3.0, ecart-type d'echantillon 1.5811 (verifiable a la main). */
    @Test
    void welford_echantillon_reference_mean_3_ecart_type_15811() {
        Welford state = Welford.empty();
        for (double x : new double[]{1, 2, 3, 4, 5}) {
            state = state.add(x);
        }
        assertEquals(5, state.n());
        assertEquals(3.0, state.mean(), 1e-12);
        assertEquals(1.5811388300841898, state.stddev(), 1e-9, "sqrt(10/4) = 1,5811");
    }

    /** z(10) sur [1..5] = (10 - 3)/1,5811 = 4,427 -> anomalie des que minSamples est atteint. */
    @Test
    void zscore_reference_4427_et_rejet_si_min_samples_non_atteint() {
        Welford state = Welford.empty();
        for (double x : new double[]{1, 2, 3, 4, 5}) {
            state = state.add(x);
        }
        assertEquals(4.427188724235732, AnalyticsRules.zScore(state, 10.0, 5), 1e-9,
                "(10 - 3) / 1,5811 = 4,427 -> |z| > 3.0 : anomalie ZSCORE");
        assertNull(AnalyticsRules.zScore(state, 10.0, 30),
                "minSamples=30 non atteint (5 echantillons) -> pas de score");
    }

    /** Ecart-type nul (serie constante) ou echantillon trop petit : pas de score (jamais de division par zero). */
    @Test
    void zscore_nul_si_serie_constante_ou_trop_courte() {
        Welford constant = Welford.empty();
        for (int i = 0; i < 40; i++) {
            constant = constant.add(4.0);
        }
        assertEquals(0.0, constant.stddev(), 1e-12, "serie constante : ecart-type nul");
        assertNull(AnalyticsRules.zScore(constant, 99.0, 30), "sigma = 0 -> pas de score possible");

        Welford seul = Welford.empty().add(4.0);
        assertTrue(Double.isNaN(seul.stddev()), "n < 2 : ecart-type indetermine");
        assertNull(AnalyticsRules.zScore(seul, 99.0, 30), "n < 2 < minSamples -> pas de score");
    }

    /** Welford est immuable : add() renvoie un nouvel etat sans modifier le precedent. */
    @Test
    void welford_add_est_immuable() {
        Welford state = Welford.empty().add(1.0).add(2.0);
        Welford after = state.add(1000.0);

        assertEquals(2, state.n(), "l'etat precedent est inchange");
        assertEquals(1.5, state.mean(), 1e-12);
        assertEquals(0.5, state.m2(), 1e-12, "m2 precedent intact : delta x (x - mean') = 1 x 0.5");
        assertEquals(3, after.n(), "le nouvel etat incorpore la 3eme valeur");
        assertEquals(1003.0 / 3.0, after.mean(), 1e-9, "(1 + 2 + 1000) / 3 = 334,333");
    }
}
