# ml/ — pipeline de prévision (EXTENSION, étape 9C)

À démarrer **après** la validation du MVP. Entraînement en Python, export ONNX dans `../models/`, inférence en Java.

## Démarche (CDC 17.3 — 6 étapes, à documenter dans le rapport)

Le pipeline entraîne **un modèle de régression linéaire simple par capteur** (`sklearn.linear_model.LinearRegression`, `ŷ = W·x + b`, 600 paramètres pour fenêtre 24 / horizon 24) et exporte un jeu de fichiers par capteur :
`models/energy_<sensor>.onnx` + `models/energy_<sensor>.json`.

1. **Données** : exporter la série horaire (moyennes de `hourly_aggregates`) par capteur ; générer plusieurs jours avec le simulateur
   (`period-seconds` = 86400 pour un cycle réaliste, ou augmenter `duration`).
2. **Préparation** : valeurs manquantes (interpolation linéaire), normalisation min-max **calculée sur l'entraînement uniquement**,
   fenêtre d'entrée 24 h, horizon 1 à 24 h.
3. **Découpage chronologique** (jamais aléatoire) : entraînement / validation / test (70/15/15).
4. **Baselines** : persistance et saisonnier naïf — calculer MAE et RMSE : c'est la référence à battre.
5. **Modèle** : régression linéaire simple (`scikit-learn`), entraînée sur le jeu d'entraînement, mesurée MAE et RMSE sur le jeu de test.
6. **Export ONNX** (via `skl2onnx`, un nœud `Gemm`) + métadonnées JSON par capteur, puis test d'inférence Java (cohérence des sorties avec Python).
7. **Intégration** : le modèle est chargé par `analytics-service` via DJL (`Criteria<float[], float[]>` sur `energy_<sensor>.onnx`, fallback sur `energy.onnx`).
8. **Limites** : taille de l'historique, saisonnalité, capteurs peu actifs, dérive.

Usage :
```
python ml/train_forecast.py                           # tous les capteurs de ml/data/
python ml/train_forecast.py --sensor SENSOR-001       # un seul capteur
python ml/train_forecast.py --sensors SENSOR-001 SENSOR-002
```

`train_forecast.py` implémente les 6 étapes CDC ; `requirements.txt` liste les dépendances (scikit-learn, skl2onnx, onnxruntime, pandas, numpy, pymongo).

**Métriques (CDC §17.4)** : MAE = (1/n)·Σ|yi − ŷi| (formule 5) et RMSE = √((1/n)·Σ(yi − ŷi)²) (formule 6), calculées sur le test, globales et par horizon (1 h, 6 h, 12 h, 24 h), comparées aux deux baselines.
