# Plan — Remplacer le réseau dense par une régression linéaire simple (CDC 17.3)

## Objectif
Remplacer le modèle `DenseNet` (PyTorch MLP 3 couches) de `ml/train_forecast.py` par un modèle de **régression linéaire simple** (scikit-learn `LinearRegression`, `ŷ = W·x + b`) tout en conservant la démarche CDC 17.3 en 6 étapes, les métriques MAE/RMSE (formules 5 et 6), l'export ONNX et l'intégration Java (DJL) existante.

## Contexte (état actuel)
- `ml/train_forecast.py` : préparation, split chrono 70/15/15, normalisation min-max sur le train, baselines (persistance/saisonnier-naïf), `DenseNet` (PyTorch), entraînement Adam + early stopping, évaluation MAE/RMSE, export ONNX + `energy.json`.
- Java : `ForecastService`, `OnnxForecastPredictor` (DJL, `Criteria<float[], float[]>`), `ForecastWindow`, `ForecastModelMetadata`, `ForecastResponse` — aucun changement nécessaire si l'entrée/sortie reste `float[24] → float[24]`.
- `models/energy.onnx` + `models/energy.json` déjà produits par le pipeline Python.

## Décisions
1. **Modèle** : `sklearn.linear_model.LinearRegression` (pas de cachée, une seule couche linéaire).
2. **Export** : ONNX via `skl2onnx` (conversion `LinearRegression` → ONNX `Gemm`). Garder DJL côté Java (inchangé), contrat `float[] → float[]` préservé.
3. **Dépendances** : ajouter `scikit-learn` (déjà dans requirements.txt) et `skl2onnx` ; retirer `torch` (optionnel, garder si utilisé ailleurs — non utilisé ailleurs dans ml/).
4. **Fenêtre/horizon** : inchangés (WINDOW=24, HORIZON=24) pour cohérence avec le Java et le CDC.
5. **Métriques** : MAE et RMSE sur le test, par horizon (1, 6, 12, 24 h), comparées aux deux baselines ; critère `beatsBaselines` conservé.
6. **Rapport** : documenter fenêtre 24 h, variable `avgPowerKw`, interpolation linéaire, rejet > 6 h, normalisation min-max sur le train uniquement, MAE/RMSE, limites (pas de capture des pics brefs, saisonnalité hebdomadaire non capturée par fenêtre 24 h seule, dérive de régime).

## Tâches (ordre d'implémentation)
1. `ml/train_forecast.py` :
   - Remplacer l'import PyTorch (`torch`, `torch.nn`) par `sklearn.linear_model.LinearRegression` et `skl2onnx.common` / `skl2onnx`.
   - Supprimer `DenseNet`, `train(...)`, `_tensors(...)` (entraînement PyTorch).
   - Ajouter `train_linear_regression(tr)` → retourne le modèle sklearn ajusté sur `(tr.X, tr.Y)` aplati si nécessaire (LinearRegression accepte `Y` 2D : multi-output).
   - Adapter `evaluate(...)` : prédiction via `model.predict(data.X)` (numpy), denormalisation, MAE/RMSE globaux + par horizon, baselines inchangés.
   - Adapter `export_onnx(...)` : conversion sklearn → ONNX via `skl2onnx.convert_sklearn`, vérification de cohérence Python ↔ ONNX (échantillon 1D), écriture `energy.onnx` + `energy.json` (métadonnées identiques : min/max, fenêtre, métriques, `input`/`output`).
   - Adapter `main(...)` : supprimer `--hidden`/`--epochs` (plus pertinents), garder `--window`/`--horizon`/`--seed` ; imprimer les métriques et `beatsBaselines`.
2. `ml/requirements.txt` : ajouter `skl2onnx`, `onnx` (déjà présent), éventuellement retirer `torch`.
3. `docs/forecast-niveau-c.md` : mettre à jour la section « Modèles à comparer » (régression linéaire simple au lieu de réseau dense/LSTM) et le rapport.
4. `ml/README.md` : mettre à jour la démarche (étape 5 : régression linéaire + export ONNX via skl2onnx).
5. Exécuter le pipeline Python pour régénérer `models/energy.onnx` + `models/energy.json`.
6. Vérifier que les tests Java existants passent (`ForecastServiceTest` avec mock, `ForecastWindowTest`) — le côté Java n'est pas modifié.

## Livrables
- `ml/train_forecast.py` réécrit (régression linéaire, 6 étapes CDC 17.3 conservées).
- `models/energy.onnx` et `models/energy.json` régénérés (modèle linéaire).
- `ml/requirements.txt` mis à jour.
- `docs/forecast-niveau-c.md` et `ml/README.md` mis à jour.

## Validation
- `python ml/train_forecast.py` s'exécute sans erreur, exporte ONNX + JSON, `beatsBaselines` évalué.
- Cohérence Python ↔ ONNX vérifiée dans `export_onnx` (écart max < 1e-4).
- Tests Java `./mvnw test` dans `analytics-service` (pas de modification côté Java → régression uniquement).
- Rapport : fenêtre, variables, valeurs manquantes, normalisation, métriques, limites documentés.

## Risques / questions ouvertes
- `skl2onnx` doit être installé dans l'environnement Python (`ml/.venv`). Vérifier disponibilité avant exécution.
- Le modèle linéaire peut ne pas battre les baselines sur ce jeu de données (sinusoïdal + bruit) ; si `beatsBaselines` = false, le rapport doit l'expliquer (limite de la régression linéaire simple sur une série saisonnière) — c'est acceptable pour le CDC (explication des limites requise).
- Aucun changement côté Java n'est requis (contrat ONNX `float[] → float[]` inchangé).
