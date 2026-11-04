package com.smartenergy.analytics.forecast;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Inférence ONNX via <b>DJL</b> (CDC chapitre 18), pour un modele exporte par
 * {@code ml/train_forecast.py}.
 *
 * <p>Le snippet du CDC utilise {@code Criteria<float[], float[]>} + {@code ModelZoo.loadModel} :
 * on le respecte a la lettre, en ajoutant un {@link Translator} minimal car aucun traducteur
 * {@code float[] -> float[]} n'est fourni nativement par DJL. Le modele ONNX est de rang 1
 * (entree {@code [24]}, sortie {@code [24]}, export {@code 1D}), donc pas de dimension batch.
 *
 * <p>Chargement <b>ponctuel</b> (une instance par requete via {@code ForecastService}) :
 * le modele est leger (~30 ko) et ce choix evite toute question de concurrence sur le
 * {@code Predictor} (non thread-safe).
 */
public class OnnxForecastPredictor implements ForecastPredictor {

    private static final Logger log = Logger.getLogger(OnnxForecastPredictor.class.getName());

    private final ZooModel<float[], float[]> model;
    private final ForecastModelMetadata metadata;

    /** Charge {@code modelPath} (.onnx) + le {@code .json} frere (normalisation + metriques). */
    public OnnxForecastPredictor(String modelPath) {
        this.metadata = ForecastModelMetadata.load(Paths.get(modelPath));
        Criteria<float[], float[]> criteria = Criteria.builder()
                .setTypes(float[].class, float[].class)
                .optModelPath(Paths.get(modelPath))
                .optTranslator(new FloatArrayTranslator())
                .optEngine("OnnxRuntime")
                .build();
        try {
            this.model = ModelZoo.loadModel(criteria);
        } catch (Exception e) {   // DJL enveloppe toutes les causes (fichier absent, format...)
            throw new ForecastModelMetadata.ForecastModelException(
                    "Chargement du modele ONNX impossible (" + modelPath + ") : " + e.getMessage());
        }
        log.info("Modele de prevision charge : " + modelPath + " (fenetre " + metadata.window()
                + " h, horizons 1-" + metadata.horizon() + " h)");
    }

    @Override
    public ForecastModelMetadata metadata() {
        return metadata;
    }

    @Override
    public float[] predict(float[] input) {
        try (Predictor<float[], float[]> predictor = model.newPredictor()) {
            return predictor.predict(input);
        } catch (Exception e) {
            throw new ForecastModelMetadata.ForecastModelException(
                    "Inférence impossible : " + e.getMessage());
        }
    }

    @Override
    public void close() {
        model.close();
    }

    /**
     * {@code float[]} <-> tenseur ONNX de rang 1, sans batchifier (le modele attend
     * exactement {@code [window]} en entree et rend {@code [horizon]} en sortie).
     *
     * <p>Signatures DJL 0.33 : {@code processInput(TranslatorContext, I)} /
     * {@code processOutput(TranslatorContext, NDList)} (les autres methodes ont des
     * implémentations par defaut dans l'interface).
     */
    static final class FloatArrayTranslator implements Translator<float[], float[]> {

        @Override
        public NDList processInput(TranslatorContext ctx, float[] input) {
            return new NDList(ctx.getNDManager().create(input, new Shape(input.length)));
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            return list.singletonOrThrow().toFloatArray();
        }
    }
}
