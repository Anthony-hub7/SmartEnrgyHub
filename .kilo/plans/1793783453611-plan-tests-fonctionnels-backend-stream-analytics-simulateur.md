# Plan — Tests fonctionnels backend (stream + analytics + simulateur)

## Objectif

Valider de bout en bout le pipeline streaming : **Simulateur → Collector → Redis Streams (energy-measurements) → Storage (Cassandra + latest cache) + Analytics (MongoDB agrégats/anomalies)**. Ce sont des **tests fonctionnels (recette)**, pas des tests unitaires. Objectif : vérifier que la chaîne fonctionne correctement en stream avant de développer l'interface utilisateur sur les analytics.

## Contexte / état actuel

- La chaîne est implémentée et déjà validée par des preuves existantes (collector étape 3, stream étape 4, Cassandra étape 7, API étape 8, analytics niveaux A+B étape 9).
- `make e2e` (`scripts/e2e.sh`) ne couvre que les scénarios 1 et 2 (collector). Les scénarios 3 à 11 sont marqués TODO dans ce script.
- Une preuve complète existe déjà (`preuves/tests/resultats-2026-11-02-api-complet.md`) : 63 vérifications OK sur la stack réelle avec le simulateur, mais sans automatisation réutilisable pour l'interface à venir.
- Les niveaux A et B analytics sont livrés (24 tests verts). Le niveau C (prévision IA) est désactivé (501 FORECAST_DISABLED) et hors périmètre de ce plan.
- `.env` actuel : `MEASUREMENT_MAX_AGE=P31D`, `ANALYTICS_FORECAST_ENABLED=true`, `SIMULATOR_SENSORS=SENSOR-001..005` (liste d'IDs, mais le service attend un nombre — override requis `SMARTENERGY_SIMULATOR_SENSORS=5`).

## Périmètre (in scope)

| Bloc | Ce qui est testé |
|------|------------------|
| T1 | Santé des 4 services applicatifs (`/actuator/health`) |
| T2 | Collector : mesure valide → 202 + X-Event-Id + entrée Redis ; mesure invalide → 4xx, aucune entrée |
| T3 | Simulateur en flux continu (5 capteurs × 2 s, 2 min) → mesures acceptées/rejetées cohérentes |
| T4 | Stream Redis : XLEN augmente, groupes storage-group / analytics-group consomment, pending ≈ 0, DLQ vide |
| T5 | Storage : historique paginé (curseur), `/latest`, cohérence Cassandra = mesures acceptées |
| T6 | Analytics : summary, daily-summary, anomalies (THRESHOLD + ZSCORE), exceedances ; métriques |
| T7 | Preuve croisée : count stockage = count résumé analytics |
| T8 | Scénarios de panne : storage arrêté (messages conservés, reprise sans perte), Redis arrêté (503 + Retry-After), sensor-service arrêté (seuil par défaut) |
| T9 | Redémarrage complet de la plateforme (volumes conservés) |
| T10 | Doublons : idempotence (compteurs et anomalies inchangés) |
| T11 | Swagger UI + `/v3/api-docs` sur storage et analytics |

## Périmètre (out of scope)

- Tests unitaires (déjà couverts par `mvn test`).
- Niveau C prévision IA (route `/forecast` → 501, désactivée, démarche dans `docs/forecast-niveau-c.md`).
- Interface utilisateur (dashboard web) — ce plan la prépare, ne la construit pas.
- API Gateway, JWT, alertes externes, Kafka/Spark, Kubernetes, Prometheus/Grafana (exclus du MVP, README §8).
- Backfill 30 jours (extension 9C), hors périmètre fonctionnel immédiat.

## Décisions

1. **Environnement de test** : Docker Compose complet (`docker compose --profile app up -d --build`), override dev pour exposer les ports des bases (6379, 27017, 9042).
2. **Cassandra version 4** (décision utilisateur, finale) : `docker-compose.yml` doit utiliser `image: cassandra:4` au lieu de `cassandra:5` pour le démarrage des tests. Le `cassandra-init` et `scripts/init-cassandra.cql` restent inchangés (CQL compatible v4).
3. **Données de test** : simulateur Docker (`docker compose run --rm simulator`) avec overrides `SMARTENERGY_SIMULATOR_SENSORS=5`, `SMARTENERGY_SIMULATOR_INTERVAL=PT2S`, `SMARTENERGY_SIMULATOR_DURATION=PT2M`, `SMARTENERGY_SIMULATOR_REGISTER_SENSORS=true`, plus pic sur SENSOR-003 (`--anomaly`) et chaos (`--chaos` : invalid-rate 0.05, duplicate-rate 0.02).
4. **Oracle de vérification** : appels HTTP `curl` aux 4 services + requêtes directes Redis (`XLEN`, `XINFO GROUPS`), Cassandra (`cqlsh`), MongoDB (`mongosh`). Pas de nouveau code Java/Python à écrire.
5. **Automatisation** : compléter `scripts/e2e.sh` avec les scénarios 3 à 11. Décision retenue : automatiser les scénarios HTTP purs (1, 2, 3, 5-lecture, 6, 11) ; garder manuels les scénarios d'arrêt/redémarrage de conteneurs (panne storage 8.1-8.2, panne Redis 8.3-8.4, panne sensor-service 8.5-8.6, redémarrage complet 9) qui nécessitent l'orchestration Docker. Ces scénarios manuels restent documentés dans le plan avec les commandes exactes à exécuter.
6. **`.env`** : déjà présent (pas de `.env.example` dans le dépôt). Vérifier que `MEASUREMENT_MAX_AGE=P31D` et `ANALYTICS_FORECAST_ENABLED=true` sont bien renseignés (déjà le cas).
7. **`ANALYTICS_FORECAST_ENABLED=true`** conservé (déjà dans `.env`) — la route répondra 501 car aucun modèle n'est entraîné (niveau C non fait), ce qui est le comportement attendu.
8. **`MEASUREMENT_MAX_AGE=P31D`** conservé (déjà dans `.env`) pour accepter les timestamps du simulateur ; à remettre à `PT24H` après les tests si nécessaire.

## Tâches (ordre d'exécution)

### 1. Préparation de l'environnement

1.1. Vérifier que Docker et Docker Compose répondent (`./infra/linux/se.sh doctor`).
1.2. S'assurer que `.env` existe (déjà présent, pas de `.env.example` dans le dépôt) et que `MEASUREMENT_MAX_AGE=P31D`, `ANALYTICS_FORECAST_ENABLED=true` sont renseignés.
1.3. **Cassandra version 4** : dans `docker-compose.yml`, remplacer `image: cassandra:5` par `image: cassandra:4` (ligne 67 `cassandra` et ligne 85 `cassandra-init`).
1.4. Nettoyer les volumes pour un départ propre (`docker compose --profile app down -v`), puis reconstruire et démarrer la stack complète (`docker compose --profile app up -d --build`).
1.5. Attendre que redis, mongo, cassandra soient `healthy` et les 4 services applicatifs `UP` (`./infra/linux/se.sh health`, `./infra/linux/se.sh status`).
1.6. Initialiser le schéma Cassandra (`docker exec -i smartenergy-cassandra cqlsh < scripts/init-cassandra.cql`).

### 2. Tests de santé et collector (scénarios 1, 2, 3b)

2.1. Appeler `GET /actuator/health` (ou `/api/health`) sur les 4 services : attendu 200, tous `UP`.
2.2. POST mesure valide sur collector : attendu 202, en-tête `X-Event-Id`, `XLEN energy-measurements` +1.
2.3. POST mesure sans `sensorId` : attendu 400 Problem Details, `XLEN` inchangé.
2.4. POST timestamp futur (> 5 min) : attendu 400 `MEASUREMENT_IN_FUTURE`.
2.5. POST timestamp trop ancien (> 24 h) : attendu 400 `MEASUREMENT_TOO_OLD`.
2.6. POST corps > 10 Ko : attendu 413.
2.7. Doublon (2 envois identiques) : 2×202, dédup aval (upsert idempotent).

### 3. Simulateur en flux continu (scénario 3)

3.1. Lancer le simulateur : `docker compose run --rm simulator` avec overrides `SMARTENERGY_SIMULATOR_SENSORS=5`, `SMARTENERGY_SIMULATOR_INTERVAL=PT2S`, `SMARTENERGY_SIMULATOR_DURATION=PT2M`, `SMARTENERGY_SIMULATOR_REGISTER_SENSORS=true`.
3.2. Vérifier le résumé du simulateur : `envoyées`, `acceptées(202)`, `rejetées(4xx)`, `erreurs=0`.
3.3. Vérifier `XLEN energy-measurements` correspond (dans une marge de tolérance) aux mesures acceptées.
3.4. Vérifier que `pending ≈ 0` pour les deux groupes (storage-group, analytics-group) après fin du simulateur.

### 4. Stream Redis et consommateurs (scénarios 4, 10)

4.1. Lire `XINFO GROUPS energy-measurements` : `storage-group` et `analytics-group` présents, `lag = 0`, `pending = 0`.
4.2. Lire `XLEN energy-measurements-dlq` : attendu 0 (sauf scénario poison séparé).
4.3. Lancer `./infra/linux/stream-watch.sh` en parallèle d'une seconde injection simulateur pour vérifier le flux en direct (lecture seule, aucun ACK).

### 5. Storage : historique et latest (scénarios 3, 5, 6)

5.1. `GET /api/measurements/SENSOR-001?from=…&to=…&limit=100` : 200, `items` non vide, trié desc, `nextCursor` présent si plus de 100.
5.2. Suivre `nextCursor` jusqu'à `null` : aucune ligne perdue ni dupliquée entre les pages.
5.3. `GET /api/measurements/SENSOR-001/latest` : 200, correspond à la mesure la plus récente.
5.4. `GET /api/measurements/SENSOR-INCONNU/latest` : 404 `MEASUREMENT_NOT_FOUND`.
5.5. Plage > 7 jours : 400 `RANGE_TOO_LARGE`.
5.6. `limit=0` ou curseur corrompu : 400 `INVALID_PARAMETER`.
5.7. Compter les lignes Cassandra pour un capteur/jour (`se.sh count SENSOR-001 YYYY-MM-DD`) : égal au nombre de mesures acceptées pour ce capteur ce jour.

### 6. Analytics : résumés, anomalies, dépassements (scénarios 4, 6, 11)

6.1. `GET /api/analytics/SENSOR-003/summary?from=…&to=…` : 200, `count`, `avgPowerKw`, `minPowerKw`, `maxPowerKw`, `energyKwh`, `thresholdExceededCount`, `anomalyCount`.
6.2. Capteur inconnu : 404 `NO_DATA`.
6.3. `from >= to` : 400 `INVALID_PARAMETER`. Plage > 31 jours : 400 `RANGE_TOO_LARGE`.
6.4. `GET /api/analytics/SENSOR-003/daily-summary?date=…` : 200, agrégat quotidien cohérent avec le summary.
6.5. Alias `GET /api/sensors/SENSOR-003/daily-summary` : réponse identique (même payload).
6.6. `GET /api/analytics/SENSOR-003/anomalies?type=THRESHOLD` : 200, anomalies `THRESHOLD`/`HIGH` avec `thresholdSource=SENSOR` ou `DEFAULT`.
6.7. `GET /api/analytics/SENSOR-003/anomalies?type=ZSCORE` : anomalies `ZSCORE`/`MEDIUM` avec `zScore`.
6.8. Pagination anomalies : `size=50`, page 0 puis page 1 ; `size>500` : 400.
6.9. `GET /api/analytics/exceedances?threshold=8.0` : 200, liste des capteurs en dépassement.
6.10. `GET /api/analytics/exceedances` sans `threshold` : 400.
6.11. `GET /api/analytics/SENSOR-001/forecast?horizonHours=6` : 501 `FORECAST_DISABLED` (niveau C non fait).
6.12. `GET /api/analytics/SENSOR-001/forecast?horizonHours=99` : 400 `INVALID_PARAMETER`.

### 7. Preuve croisée stockage ↔ analytics (scénario 7)

7.1. Pour un capteur donné sur une plage donnée : `count` du summary analytics = nombre de lignes Cassandra pour ce capteur sur la plage.
7.2. `maxPowerKw` du summary = max de `power_kw` dans Cassandra pour le capteur sur la plage.

### 8. Scénarios de panne et reprise (scénarios 5, 6, 9)

8.1. Arrêter `storage-service` (`docker compose stop storage-service`), envoyer des mesures : `XINFO GROUPS` montre `pending`/`lag` en hausse, collector répond toujours 202.
8.2. Redémarrer `storage-service` (`docker compose start storage-service`) : reprise du consumer, `pending=0`, `lag=0`, mesures persistées dans Cassandra (comptage = mesures acceptées).
8.3. Arrêter Redis : collector répond 503 + `Retry-After: 5` (jamais 202 sans XADD).
8.4. Redémarrer Redis : le flux reprend, aucun message perdu (pending préservé grâce à l'AOF + volume).
8.5. Arrêter `sensor-service` : analytics traite avec le seuil par défaut (anomalie `thresholdSource = DEFAULT`, 10,0 kW).
8.6. Redémarrer `sensor-service` : analytics repasse au seuil capteur (cache Redis 60 s).

### 9. Redémarrage complet de la plateforme (scénario 7)

9.1. `docker compose --profile app down` (volumes conservés), puis `docker compose --profile app up -d --build`.
9.2. Attendre que tous les services soient `healthy`/`UP`.
9.3. Vérifier que l'historique est toujours présent via l'API storage (données survivent au redémarrage).

### 10. Doublons et idempotence (scénario 8)

10.1. Publier deux fois le même événement (même `eventId` = même `sensorId|timestamp`) : 2×202 au collector.
10.2. Vérifier Cassandra : une seule ligne pour cette clé primaire.
10.3. Vérifier analytics : compteurs et anomalies inchangés après rejet du doublon (`dedup:analytics` marqué, TTL 24 h).

### 11. Swagger / documentation API (scénario 11)

11.1. `GET http://localhost:8082/swagger-ui.html` : 200.
11.2. `GET http://localhost:8082/v3/api-docs` : 200, OpenAPI valide.
11.3. `GET http://localhost:8083/swagger-ui.html` : 200.
11.4. `GET http://localhost:8083/v3/api-docs` : 200, OpenAPI valide.

## Livrables

- Plan de tests fonctionnels (ce fichier, `.kilo/plans/1793783453611-plan-tests-fonctionnels-backend-stream-analytics-simulateur.md`).
- `scripts/e2e.sh` complété avec les scénarios 3 à 11 (à implémenter par l'agent d'exécution, pas dans ce plan).
- Compte-rendu d'exécution des tests (résultats obtenus, écarts vs attendus, preuves : captures `curl`, `XINFO GROUPS`, `cqlsh`, `mongosh`).

## Validation (critères de succès)

| Critère | Seuil attendu |
|---------|---------------|
| Santé 4 services | 4× UP |
| Mesure valide | 202 + `X-Event-Id` + XLEN +1 |
| Mesure invalide | 4xx, XLEN inchangé |
| Simulateur | `erreurs=0`, `acceptées` ≈ `envoyées` |
| Stream | `lag=0`, `pending=0` pour les 2 groupes, DLQ = 0 |
| Historique | pagination sans trou ni doublon, `latest` 200/404 |
| Analytics | summary/daily/anomalies/exceedances cohérents avec le stockage |
| Preuve croisée | count analytics = count Cassandra |
| Panne storage | reprise sans perte (pending=0 après redémarrage) |
| Panne Redis | collector 503 + `Retry-After` |
| Panne sensor-service | seuil DEFAULT, anomalie créée |
| Redémarrage complet | données présentes après redémarrage |
| Doublon | 1 ligne Cassandra, compteurs analytics inchangés |
| Swagger | 200 sur `/swagger-ui.html` et `/v3/api-docs` (8082, 8083) |

## Risques et questions ouvertes

- `.env` définit `SIMULATOR_SENSORS` comme une liste d'IDs (`SENSOR-001,…`) mais le simulateur attend un **nombre** (int). Override `SMARTENERGY_SIMULATOR_SENSORS=5` requis (déjà documenté dans `preuves/tests/resultats-2026-11-02-api-complet.md` §Simulateur).
- Les arguments CLI passés après le nom du service dans `docker compose run --rm simulator` ne sont pas transmis par ce binaire Compose ; utiliser les variables d'environnement `SMARTENERGY_SIMULATOR_*`.
- Cassandra met 1 à 2 min à devenir `healthy` ; le script d'attente (`wait_ready` dans `se.sh`) gère cela avec un `start_period` de 60 s.
- `ANALYTICS_FORECAST_ENABLED=true` est déjà dans `.env` mais aucun modèle n'est entraîné (niveau C non fait) : la route `/forecast` répondra 501, comportement attendu à documenter comme tel, non comme une anomalie.
- Le `.env` actuel a `MEASUREMENT_MAX_AGE=P31D` (backfill) au lieu de `PT24H` (défaut) : conservé pour les tests, à remettre à `PT24H` après si la plateforme revient en mode nominal.
- Prévoir un nettoyage des clés dedup Redis (TTL 24 h) entre les runs si on rejoue les mêmes événements (scénario 8).

## Ouvert / non résolu

- L'interface utilisateur future consommera quels endpoints exactement ? À préciser avec le concepteur UI (probablement `/summary`, `/daily-summary`, `/anomalies`, `/exceedances`, `/latest`, historique).

## Décisions résolues

- **Cassandra version 4** : `docker-compose.yml` utilise `image: cassandra:4` (lignes `cassandra` et `cassandra-init`) pour le démarrage des tests, conformément à l'instruction utilisateur.
- **Automatisation e2e.sh** : automatiser les scénarios HTTP purs (1, 2, 3, 5-lecture, 6, 11) ; garder manuels les scénarios d'arrêt/redémarrage de conteneurs (panne storage 8.1-8.2, panne Redis 8.3-8.4, panne sensor-service 8.5-8.6, redémarrage complet 9). Ces scénarios manuels restent documentés dans le plan (section Tâches 8 et 9) avec les commandes Docker exactes.