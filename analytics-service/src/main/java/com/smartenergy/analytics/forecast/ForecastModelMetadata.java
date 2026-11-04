package com.smartenergy.analytics.forecast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Metadonnees du modele exporte par {@code ml/train_forecast.py} ({@code models/energy.json}).
 *
 * <p>Le JSON est INDISPENSABLE a l'inférence Java : il porte la normalisation (min/max calcules
 * sur le SEUL jeu d'entrainement, en Python) et les metriques MAE/RMSE du test affichees dans
 * {@code ForecastResponse.metrics}. Java n'a jamais recalculé ces statistiques (pas de fuite,
 * pas de double maintenance).
 *
 * <p>Structure produite par {@code export_onnx} :
 * <pre>{@code
 * { "sensor": "SENSOR-001", "window": 24, "horizon": 24,
 *   "normalization": {"min": 2.73, "max": 4.98},
 *   "metrics": {"regression-lineaire": {"mae": ..., "rmse": ...}, "persistance": {...}, "saisonnier-naif": {...}},
 *   "beatsBaselines": true, "trainedAt": "..." } }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastModelMetadata(
        String sensor,
        int window,
        int horizon,
        Normalization normalization,
        Metrics metrics,
        @JsonProperty("beatsBaselines") boolean beatsBaselines,
        Instant trainedAt) {

    public record Normalization(double min, double max) {}

    /** Les trois modeles evalues en Python : la regression lineaire + les deux baselines a battre. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metrics(
            @JsonProperty("regression-lineaire") ModelScore dense,
            @JsonProperty("persistance") ModelScore persistence,
            @JsonProperty("saisonnier-naif") ModelScore seasonalNaive) {

        /** Metriques de la reponse API : le modele retenu (+ repli sur les baselines). */
        public Map<String, Double> responseMetrics() {
            ModelScore chosen = dense != null ? dense : seasonalNaive;
            if (chosen == null) {
                return Map.of();
            }
            return Map.of("mae", chosen.mae(), "rmse", chosen.rmse());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelScore(@JsonProperty(defaultValue = "NaN") double mae,
                             @JsonProperty(defaultValue = "NaN") double rmse) {}

    /** Champs inconnus ignores : un energy.json plus recent (nouveaux champs) reste lisible. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Lecture de {@code models/energy.json} (Jackson, champs inconnus ignores : versionnage tolerant). */
    public static ForecastModelMetadata load(Path onnxPath) {
        String name = onnxPath.getFileName().toString().replaceAll("\\.onnx$", "") + ".json";
        Path json = onnxPath.resolveSibling(name);
        if (!Files.exists(json)) {
            throw new ForecastModelException(
                    "Metadonnees introuvables a " + json + " (export ml/train_forecast.py absent ?)");
        }
        try {
            return MAPPER.readValue(json.toFile(), ForecastModelMetadata.class);
        } catch (IOException e) {
            throw new ForecastModelException("Lecture impossible de " + json + " : " + e.getMessage());
        }
    }

    public double min() {
        return normalization == null ? Double.NaN : normalization.min();
    }

    public double max() {
        return normalization == null ? Double.NaN : normalization.max();
    }

    /** Erreur de chargement du modele : le module reste « inactif » (501) plutot que 500. */
    public static class ForecastModelException extends RuntimeException {
        public ForecastModelException(String message) {
            super(message);
        }
    }
}
