package com.smartenergy.analytics.forecast;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Construction de la fenetre d'entree pour l'inférence — reproduction en Java du preparateur
 * de donnees de {@code ml/train_forecast.py} (CDC 17.3, etape 1) pour garantir la COHERENCE
 * Python <-> Java (meme grille horaire, meme interpolation, meme normalisation).
 *
 * <ul>
 *   <li><b>Grille horaire</b> : les {@code window} heures {lastHour - window + 1h, ..., lastHour},
 *       chaque heure sans agregat vaut {@code NaN} (trou).</li>
 *   <li><b>Valeurs manquantes</b> : interpolation lineaire entre les heures voisines
 *       (equivalent pandas {@code interpolate(limit_area="inside")}) ; en bord de fenetre
 *       (aucun voisin des deux cotes), propagation de la valeur la plus proche.</li>
 *   <li><b>Rejet</b> : plus de {@code maxMissingGap} heures consecutives manquantes dans la
 *       fenetre -> {@code NO_DATA} (meme regle que l'entrainement).</li>
 * </ul>
 *
 * <p>Methode pure (aucune dependance Spring/Mongo) : testee unitairement dans
 * {@code ForecastWindowTest}.
 */
public final class ForecastWindow {

    /** Fenetre d'entree : 24 dernieres heures (CDC 17.4 : a documenter dans le rapport). */
    public static final int WINDOW = 24;
    /** Trou de plus de 6 h consecutives -> fenetre rejetee (choix fige dans docs/forecast-niveau-c.md). */
    public static final int MAX_MISSING_GAP = 6;

    private ForecastWindow() {}

    /**
     * @param hourlyAvg moyenne horaire kW par heure (heure pleine UTC -> moyenne), trous ABSENTS
     *                   de la map ; @see com.smartenergy.analytics.model.HourlyAggregateDocument
     * @param lastHour  derniere heure observee (heure pleine, extremite droite de la fenetre)
     * @return les {@code window} valeurs kW de la fenetre (interpolees)
     * @throws ApiException(NO_DATA) si plus de {@code maxMissingGap} heures consecutives manquantes
     */
    public static double[] build(Map<Instant, Double> hourlyAvg, Instant lastHour,
                                 int window, int maxMissingGap) {
        Instant start = lastHour.minus(Duration.ofHours(window - 1L));
        double[] values = new double[window];
        boolean[] missing = new boolean[window];
        for (int i = 0; i < window; i++) {
            Instant hour = start.plus(Duration.ofHours(i));
            Double v = hourlyAvg.get(hour);
            if (v == null || v.isNaN()) {
                values[i] = Double.NaN;
                missing[i] = true;
            } else {
                values[i] = v;
            }
        }

        // regle du rejet : plus de maxMissingGap heures CONSECUTIVES manquantes
        int longest = 0;
        int current = 0;
        for (boolean m : missing) {
            current = m ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        if (longest > maxMissingGap) {
            throw ApiException.of(ErrorCode.NO_DATA,
                    "Fenetre de prevision rejetee : plus de " + maxMissingGap
                            + " heures consecutives manquantes avant " + lastHour);
        }

        interpolateLinear(values, missing);
        fillBorders(values);   // bords de fenetre sans voisin : propagation de la valeur la plus proche
        if (Double.isNaN(values[0])) {
            throw ApiException.of(ErrorCode.NO_DATA,
                    "Aucune donnee dans la fenetre de prevision se terminant a " + lastHour);
        }
        return values;
    }

    /** Interpolation lineaire des trous internes (toutes les heures manquantes sont entourees de valeurs). */
    static void interpolateLinear(double[] values, boolean[] missing) {
        int n = values.length;
        for (int i = 0; i < n; i++) {
            if (!missing[i]) {
                continue;
            }
            int prev = i - 1;
            while (prev >= 0 && missing[prev]) {
                prev--;
            }
            int next = i + 1;
            while (next < n && missing[next]) {
                next++;
            }
            if (prev >= 0 && next < n) {                 // trou interne : lineaire
                double t = (i - prev) / (double) (next - prev);
                values[i] = values[prev] + t * (values[next] - values[prev]);
            }
        }
    }

    /** Bords de fenetre sans voisin des deux cotes : propagation avant/arriere (comme ffill/bfill). */
    static void fillBorders(double[] values) {
        int n = values.length;
        double firstValid = Double.NaN;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                firstValid = v;
                break;
            }
        }
        if (Double.isNaN(firstValid)) {
            return;   // fenetre entierement vide : signale par l'appelant
        }
        int i = 0;
        while (i < n && Double.isNaN(values[i])) {
            values[i++] = firstValid;                    // bord gauche
        }
        double lastValid = Double.NaN;
        for (int j = n - 1; j >= i; j--) {               // bord droit : derniere valeur connue
            if (!Double.isNaN(values[j])) {
                lastValid = values[j];
                break;
            }
        }
        if (Double.isNaN(lastValid)) {
            lastValid = firstValid;
        }
        for (int j = n - 1; j >= i && Double.isNaN(values[j]); j--) {
            values[j] = lastValid;
        }
    }

    /** Normalisation min-max : (x - min) / (max - min) avec les stats du SEUL jeu d'entrainement. */
    public static float[] normalize(double[] values, double min, double max) {
        double span = (max - min) != 0 ? (max - min) : 1.0;
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) ((values[i] - min) / span);
        }
        return out;
    }

    /** Denormalisation : x * (max - min) + min (inverse exacte de {@link #normalize}). */
    public static double[] denormalize(float[] values, double min, double max) {
        double span = (max - min) != 0 ? (max - min) : 1.0;
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] * span + min;
        }
        return out;
    }
}
