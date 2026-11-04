package com.smartenergy.analytics.forecast;

/**
 * Prédiction d'un modele de prevision : {@code window} valeurs normalisees en entree,
 * {@code horizon} valeurs normalisees en sortie (meme convention que le module Python).
 *
 * <p>Abstraite pour que {@code ForecastService} se teste sans chargeur ONNX (mock) et que
 * l'implémentation DJL soit isolee dans {@link OnnxForecastPredictor}.
 */
public interface ForecastPredictor extends AutoCloseable {

    /** Metadonnees du modele charge (normalisation, fenetre, metriques). */
    ForecastModelMetadata metadata();

    /**
     * @param input fenetre normalisee de taille {@code metadata().window()}
     * @return {@code metadata().horizon()} valeurs normalisees (les prochaines heures)
     */
    float[] predict(float[] input);

    /** Libere le modele charge. */
    @Override
    void close();
}
