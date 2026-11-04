# Plan : Simulateur de capteurs en Python (étape 10)

## Objectif
Remplacer la source de données par un simulateur Python simple, contrôlable, permettant de tester la plateforme sans matériel IoT réel. Génère plusieurs capteurs, envoie une mesure toutes les quelques secondes vers le Collector Service, supporte un profil sinusoïdal + bruit et un scénario d'anomalie (puissance ×3).

## Contexte vérifié
- Collector expose `POST /api/measurements` (port 8081 local), corps `EnergyMeasurementRequest` :
  `sensorId` (pattern `SensorIdPattern.REGEX`), `timestamp` (ISO-8601 UTC), `powerKw` [0,1000], `voltage` [0,500], `temperature` [-50,100], `currentA` [0,2000], `frequencyHz` [40,70], `state` optionnel.
- Réponse 202 + header `X-Event-Id` en cas de succès ; 400/413/503 sinon.
- Infra Docker déjà démarrée et healthy (redis, cassandra, mongo). 4 services Spring Boot en local sur 8081–8084.
- Python 3.12 disponible (prérequis README).

## Décisions de conception
1. **Langage** : Python 3,stdlib uniquement (`urllib.request`, `json`, `time`, `math`, `random`, `datetime`, `argparse`) — aucune dépendance externe à installer.
2. **Cible** : `http://localhost:8081/api/measurements` (configurable via `--url` / `SIMULATOR_COLLECTOR_URL`).
3. **Génération de puissance** : `Pt = Pbase + A·sin(2πt/T) + εt`, avec `εt` bruit gaussien borné. Valeur clampée dans [0, 1000].
4. **Anomalie** : option `--anomaly` activant `powerKw × 3` pendant une fenêtre configurée (`--anomaly-start`, `--anomaly-duration`, `--anomaly-factor`, défaut 3.0).
5. **Capteurs** : `--sensors 5` (IDs `SENSOR-001`…`SENSOR-005`), `--interval` (défaut 2s), `--duration` (défaut 120s).
6. **Horodatage** : `datetime.now(timezone.utc).isoformat()` remplacé par `+ "Z"`.
7. **Résilience** : tentatives limitées, logs structurés (requêtes envoyées/acceptées/rejetées/erreurs), code de sortie non-nul si erreurs > 0.

## Fichiers à créer
- `simulator/simulator.py` — point d'entrée principal (CLI + boucle de génération + envoi HTTP).
- `simulator/requirements.txt` — vide (stdlib uniquement) ou absent.

## Tâches d'implémentation
1. Créer `simulator/simulator.py` avec argparse : `--url`, `--sensors`, `--interval`, `--duration`, `--pbase`, `--amplitude`, `--period`, `--noise`, `--anomaly`, `--anomaly-start`, `--anomaly-duration`, `--anomaly-factor`, `--dry-run`.
2. Boucle : pour chaque capteur, à chaque intervalle, calculer `powerKp`, générer les autres champs (voltage ~226, temperature ~29, currentA, frequencyHz, state), POST JSON, compter statut HTTP.
3. Gérer l'anomalie : si `now` dans la fenêtre, multiplier `powerKw` par le facteur.
4. Afficher un résumé final : envoyées, acceptées (202), rejetées (4xx), erreurs (5xx/exceptions).
5. Valider que `X-Event-Id` est présent sur 202 (optionnel, affichage).

## Exemple d'exécution (validation)
```
cd simulator
python3 simulator.py \
  --url http://localhost:8081/api/measurements \
  --sensors 5 --interval 2 --duration 120 \
  --pbase 3.0 --amplitude 1.5 --period 60 --noise 0.2 \
  --anomaly --anomaly-start 30 --anomaly-duration 20 --anomaly-factor 3
```
Résultat attendu : ~300 requêtes envoyées (5 capteurs × 60 mesures), acceptées ≈ envoyées, erreurs=0, au moins un pic ×3 dans la fenêtre [30s, 50s].

## Critères de validation
- Le simulateur génère automatiquement des données valides (collector répond 202).
- Il permet d'activer au moins un scénario d'anomalie (puissance ×3).
- 5 capteurs pendant ≥ 2 minutes : nombre de requêtes reçues vérifiable côté collector (XLEN / logs).
- Code de sortie 0 si aucune erreur, non-nul sinon.

## Ouvert / non couvert
- Pas d'enregistrement des capteurs auprès du sensor-service (hors périmètre de cette étape).
- Pas de backfill (timestamps passés) : timestamps = maintenant.
- Pas de packaging Docker pour le simulateur Python (lancer en local).
