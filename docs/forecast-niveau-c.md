# Niveau C — Prévision IA (préparation, extension 9C)

> **Statut** : préparation rédigée à l'étape 9 (niveaux A et B livrés). **Aucun modèle n'est encore
> entraîné ni intégré** : la route `GET /api/analytics/{id}/forecast` répond `501 FORECAST_DISABLED`
> (flag `smartenergy.analytics.forecast.enabled=false`, valeur par défaut). Le niveau C sera attaqué
> **après validation des niveaux A et B** (critère du CDC §17.3 : « Après validation des niveaux
> précédents… »).

Ce document fige la **démarche** exigée par le CDC §17.3 (ses 6 étapes), les choix de préparation de
données (§17.3 et la liste de §17.4/§18 pour le rapport) et les métriques d'évaluation (formules 5
et 6). Il sert de contrat avant toute écriture de code.

## 1. Données d'entrée (étape « préparation des données »)

| Point | Choix |
|---|---|
| Source | `analytics_db.hourly_aggregates` — **série horaire** déjà produite par le niveau A (D-13) |
| Variable cible | `avgPowerKw = sumPowerKw / count` (kW) par heure et par capteur |
| Variables auxiliaires possibles | heure du jour (cycle 24 h), jour de la semaine (cycle 168 h) |
| Fenêtre d'entrée | **24 dernières heures** glissantes (24 points) |
| Horizon | **1 à 24 heures** (bornes déjà validées par l'API : `horizonHours ∈ [1, 24]`) |
| Valeurs manquantes | **interpolation linéaire** entre les heures voisines ; > 6 h consécutives manquantes → fenêtre rejetée |
| Normalisation | **min-max calculée sur l'ensemble d'entraînement uniquement** (jamais sur la validation/test, sinon fuite d'information) |

## 2. Découpage (étape « séparation entraînement / validation / test »)

- Découpage **chronologique** (jamais aléatoire) : ex. 70 % entraînement (le plus ancien),
  15 % validation, 15 % test (le plus récent).
- Justification : une série temporelle a une dépendance au passé ; un mélange aléatoire ferait
  entraîner le modèle sur le futur du test (fuite).

## 3. Modèles à comparer (étape « entraînement »)

1. **Baseline d'abord** (référence à battre) :
   - *persistance* : `ŷ(t+h) = y(t)` ;
   - *saisonnier naïf* : `ŷ(t+h) = y(t+h−24)` (même heure la veille).
2. Puis, si la baseline est battue significativement : **régression linéaire simple** (scikit-learn
   `LinearRegression`, `ŷ = W·x + b`, une seule couche linéaire sans caché) exportée en ONNX via
   `skl2onnx` (CDC §17.3). Un modèle est entraîné **par capteur** dans `ml/train_forecast.py` et
   exporté sous `models/energy_<sensor>.onnx` + `models/energy_<sensor>.json` (fallback sur le
   modèle global `models/energy.onnx`). Les réseaux denses et LSTM ne sont plus retenus pour ce
   MVP (trop complexes par rapport au gain).

## 4. Évaluation (étape « mesure de l'erreur », formules 5 et 6)

- **MAE** = (1/n)·Σ|yi − ŷi|  (formule 5) — en kW, interprétable directement.
- **RMSE** = √((1/n)·Σ(yi − ŷi)²)  (formule 6) — pénalise les grosses erreurs.
- Calculés sur le jeu de **test** (chronologiquement après l'entraînement), par horizon
  (1 h, 6 h, 12 h, 24 h) pour montrer la dégradation.
- Un modèle n'est intégré que s'il bat les deux baselines sur MAE **et** RMSE.

## 5. Export et intégration (étapes « export du modèle » et « intégration »)

- Export **ONNX** hors de Spring Boot ; un modèle par capteur (`models/energy_<sensor>.onnx` +
  `models/energy_<sensor>.json`) produit par `ml/train_forecast.py` (scikit-learn + skl2onnx),
  chargement via **DJL** (§18) :
  `Criteria.builder().setTypes(float[].class, float[].class).optModelPath(Paths.get("models/energy_<sensor>.onnx")).build()`.
  Fallback sur le modèle global `models/energy.onnx` si le modèle du capteur est absent.
- Chemin résolu par `ForecastService` (`resolveModelPath`) à partir de `smartenergy.analytics.forecast.model-path`
  (`AnalyticsProperties.Forecast`), activation par le flag `forecast.enabled`.
- Intégration dans `ForecastService` (le 501 est déjà en place) : un prédicteur est chargé à la demande
  par capteur (cache `ConcurrentHashMap`) ; remplir `ForecastResponse` (points + `metrics` MAE/RMSE).
- Réentraînement/rafraîchissement : hors du MVP (documenter la fréquence et le déclencheur).

## 6. Ce qui doit figurer dans le rapport (CDC §17.4 et §18)

- la **taille de la fenêtre temporelle d'entrée** (24 h, justification) ;
- les **variables utilisées** (cible horaire, cyclicité heure/jour) ;
- le **traitement des valeurs manquantes** (interpolation, rejet > 6 h) ;
- la **méthode de normalisation** (min-max sur l'entraînement seul) ;
- les **métriques d'évaluation** (MAE, RMSE, par horizon) ;
- les **limites du modèle** (voir ci-dessous).

## 7. Limites attendues (à expliquer, critère de validation du CDC)

- Une prévision horaire ne prédit **pas** les pics brefs (le niveau B les détecte, il ne les
  prévoit pas) : MAE faible ne signifie pas « utile pour les pics ».
- Saisonnalité **hebdomadaire** non capturée par une fenêtre de 24 h seule : discuter une fenêtre
  168 h ou des features calendaires.
- Dérive de régime (installation d'un équipement, saison) : le modèle vieillit ; documenter la
  politique de réentraînement.
- Le score z du niveau B (Welford cumulé, sans fenêtre glissante) et la prévision partagent la
  même faiblesse sur les régimes lents : c'est assumé pour la simplicité du MVP.

## 8. Hors périmètre du MVP

`AnomalyType.FORECAST_RESIDUAL` est **réservé** (résidus de prévision détectés comme anomalies)
mais non utilisé tant que le modèle n'existe pas. Le réentraînement automatique, la détection de
dérive et le serveur de modèles sont hors MVP.
