package com.smartenergy.analytics.rules;

import java.time.Duration;
import java.time.Instant;

/**
 * Regles de calcul PURES du niveau A (agregats, CDC 17.1) et du niveau B (anomalies, CDC 17.2) :
 * energie (formule 2), depassement de seuil (formule 3) et score z (formule 4).
 *
 * <p>Classe sans Spring ni base de donnees : de simples fonctions testables, appelees par le
 * consumer analytics (etape 9). Les etats de Welford sont persistes dans sensor_state (MongoDB).
 *
 * <p><b>Energie (formule 2, "rectangle a gauche")</b> : {@code increment = P_prev x dt}, en kWh si
 * P en kW et dt en heures. Le pas de temps est <b>plafonne</b> a
 * {@code expectedIntervalSeconds x gapFactor} (10 s par defaut) : un trou de donnees ne doit pas
 * gonfler l'energie estimee. Une mesure dont l'horodatage n'est pas STRICTEMENT posterieur au pas
 * de temps precedent (rejeu, mesure en retard) vaut 0.0 : on ne compte jamais deux fois la meme
 * tranche. La premiere mesure d'un capteur (pas de pas precedent) vaut aussi 0.0.
 *
 * <p><b>Seuil (formule 3)</b> : comparaison STRICTE {@code Pt > Pmax} (un Pt exactement egal au
 * seuil n'est pas un depassement).
 *
 * <p><b>Score z (formule 4)</b> : statistiques en ligne de Welford (n, mean, m2), ecart-type
 * d'echantillon {@code sqrt(m2 / (n-1))}. Pas de score tant que l'echantillon est inferieur a
 * {@code minSamples} (30 par defaut, decision config) ou si l'ecart-type est nul (serie constante).
 *
 * <p><b>Limiter a documenter</b> : la moyenne et l'ecart-type de Welford sont CUMULES (pas de
 * fenetre glissante) : un profil lentement croissant est suivi avec retard ; un profil
 * sinusoidal gonfle sigma (les mesures nominales paraissent moins "loin" de la moyenne) ;
 * les mesures deja anormales ne mettent PAS a jour (mean, m2) pour eviter de polluer mu et sigma.
 */
public final class AnalyticsRules {

    private AnalyticsRules() {}

    /**
     * Etat de Welford (n, moyenne, somme des carres des ecarts) : record immuable.
     * stddev() = ecart-type d'echantillon sqrt(m2 / (n - 1)), indetermine (NaN) si n &lt; 2.
     */
    public record Welford(long n, double mean, double m2) {

        public static Welford empty() {
            return new Welford(0, 0.0, 0.0);
        }

        /** @return le nouvel etat apres incorporation de x (l'etat precedent est immuable) */
        public Welford add(double x) {
            long newN = n + 1;
            double delta = x - mean;
            double newMean = mean + delta / newN;
            double newM2 = m2 + delta * (x - newMean);
            return new Welford(newN, newMean, newM2);
        }

        /** Ecart-type d'echantillon (n - 1 au denominateur) ; NaN si n &lt; 2. */
        public double stddev() {
            if (n < 2) {
                return Double.NaN;
            }
            return Math.sqrt(m2 / (n - 1));
        }
    }

    /**
     * Increment d'energie (kWh) entre le pas de temps precedent et la mesure courante (formule 2).
     *
     * @param previousTs     horodatage du pas precedent (null = premiere mesure du capteur)
     * @param previousPowerKw puissance (kW) du pas precedent
     * @param currentTs      horodatage de la mesure courante
     * @param expectedIntervalSeconds pas de temps nominal du capteur (2 s par defaut)
     * @param gapFactor      multiplicateur du plafond de pas de temps (5 par defaut -> 10 s)
     * @return l'increment en kWh ; 0.0 si pas de pas precedent ou si currentTs &lt;= previousTs
     */
    public static double energyIncrementKwh(Instant previousTs, double previousPowerKw, Instant currentTs,
                                            int expectedIntervalSeconds, int gapFactor) {
        if (previousTs == null || !currentTs.isAfter(previousTs)) {
            return 0.0;   // premiere mesure, rejeu ou mesure en retard : pas de double compte
        }
        // duree exacte (precision ms) entre les deux mesures, plafonnee
        double dtSeconds = Duration.between(previousTs, currentTs).toNanos() / 1_000_000_000.0;
        double capSeconds = (double) expectedIntervalSeconds * gapFactor;
        double effectiveSeconds = Math.min(dtSeconds, capSeconds);
        return previousPowerKw * effectiveSeconds / 3600.0;   // kWh si kW et secondes -> heures
    }

    /**
     * Depassement de seuil (formule 3) : STRICT, {@code Pt > Pmax}.
     * Un Pt exactement egal au seuil n'est PAS un depassement.
     */
    public static boolean isThresholdExceeded(double powerKw, double maxPowerKw) {
        return powerKw > maxPowerKw;
    }

    /**
     * Score z standardise (formule 4) par rapport a l'etat de Welford.
     *
     * @param minSamples nombre minimum d'echantillons pour oser un score (30 par defaut)
     * @return le score z, ou null si l'echantillon est trop petit (&lt; minSamples) ou si
     *         l'ecart-type est nul / indetermine (serie constante : pas d'ecart a observer)
     */
    public static Double zScore(Welford state, double x, int minSamples) {
        if (state.n() < minSamples) {
            return null;   // pas encore assez d'historique pour juger une mesure
        }
        double stddev = state.stddev();
        if (!isFinite(stddev) || stddev == 0.0) {
            return null;   // serie constante ou n < 2 : le score n'a pas de sens
        }
        return (x - state.mean()) / stddev;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
