# Plan — Exécution des tests fonctionnels backend (stream + analytics + simulateur)

## Objectif
Valider de bout en bout le pipeline streaming : Simulateur → Collector → Redis Streams → Storage (Cassandra + latest) + Analytics (MongoDB). Référence : `.kilo/plans/1793783453611-plan-tests-fonctionnels-backend-stream-analytics-simulateur.md` (scénarios T1–T11).

## Contexte vérifié
- 4 services Spring Boot présents : `collector-service`, `storage-service`, `analytics-service`, `sensor-service` (sources + pom.xml lus).
- `docker-compose.yml` définit déjà les 4 services + infra (redis, mongo, cassandra, cassandra-init).
- Cassandra est en `cassandra:4` (déjà en place, pas de modification requise).
- `.env` présent avec `MEASUREMENT_MAX_AGE=P31D`, `ANALYTICS_FORECAST_ENABLED=true`.
- Environnement léger (`docker-compose.light.yml`, `.env.light`, `start-light.sh`) déjà créé et validé (Cassandra + Redis uniquement, healthy).
- Docker Compose est le binaire standalone `docker-compose` (pas le plugin `docker compose`).

## Décisions d'exécution
1. **Stack complète** requise pour T1–T11 : `docker-compose.yml` (redis, mongo, cassandra + 4 services). L'environnement léger ne couvre que Cassandra+Redis, insuffisant pour les tests analytics/storage.
2. **Cassandra allégée** pour éviter les crashes : réduire `CASSANDRA_MAX_HEAP` à `384M`, `CASSANDRA_MEM_LIMIT` à `768m` dans `.env` (ou via override), garder `cassandra:4`.
3. **Build complet requis** : `docker-compose.yml --profile app up -d --build` (les images services sont construites par le Dockerfile unique).
4. **Scénarios manuels** (panne/redémarrage T8, T9) exécutés à la main via `docker-compose` ; scénarios HTTP (T1–T7, T11) automatisables via `scripts/e2e.sh`.
5. **Simulateur** : `docker-compose run --rm simulator` avec overrides `SMARTENERGY_SIMULATOR_SENSORS=5`, `INTERVAL=PT2S`, `DURATION=PT2M`, `REGISTER_SENSORS=true`.

## Tâches (ordre d'exécution)

### 1. Préparation de l'environnement
1.1. `./infra/linux/se.sh doctor` → vérifier Docker, docker-compose, mémoire, ports.
1.2. Vérifier `.env` : `MEASUREMENT_MAX_AGE=P31D`, `ANALYTICS_FORECAST_ENABLED=true` présents.
1.3. Alléger Cassandra pour éviter les crashes : dans `.env`, passer `CASSANDRA_MAX_HEAP=384M`, `CASSANDRA_HEAP_NEWSIZE=96M`, `CASSANDRA_MEM_LIMIT=768m`, `CASSANDRA_MEM_RESERVATION=384m`. (Cassandra déjà en `cassandra:4`.)
1.4. Nettoyer : `docker-compose --profile app down -v`.
1.5. Build + start : `docker-compose --profile app up -d --build`.
1.6. Attendre healthy : `./infra/linux/se.sh health` et `./infra/linux/se.sh status` (cassandra ~1–2 min).
1.7. Init schema Cassandra : `docker exec -i smartenergy-cassandra cqlsh < scripts/init-cassandra.cql`.

### 2. T1–T2 : Santé et Collector (automatisable)
2.1. `GET /actuator/health` sur 8081, 8082, 8083, 8084 → 200, tous UP.
2.2. POST mesure valide sur 8081 → 202 + X-Event-Id, XLEN +1.
2.3. POST sans sensorId → 400, XLEN inchangé.
2.4. POST timestamp futur → 400 MEASUREMENT_IN_FUTURE.
2.5. POST timestamp trop ancien → 400 MEASUREMENT_TOO_OLD.
2.6. POST corps > 10 Ko → 413.
2.7. Doublon identique ×2 → 2×202, upsert idempotent.

### 3. T3 : Simulateur en flux continu
3.1. `docker-compose run --rm simulator -e SMARTENERGY_SIMULATOR_SENSORS=5 -e SMARTENERGY_SIMULATOR_INTERVAL=PT2S -e SMARTENERGY_SIMULATOR_DURATION=PT2M -e SMARTENERGY_SIMULATOR_REGISTER_SENSORS=true`.
3.2. Résumé simulateur : envoyées, acceptées, rejetées, erreurs=0.
3.3. `XLEN energy-measurements` ≈ mesures acceptées.
3.4. `pending ≈ 0` pour storage-group et analytics-group.

### 4. T4 : Stream Redis et consommateurs
4.1. `XINFO GROUPS energy-measurements` → 2 groupes, lag=0, pending=0.
4.2. `XLEN energy-measurements-dlq` → 0.
4.3. (optionnel) `./infra/linux/stream-watch.sh` en parallèle.

### 5. T5 : Storage historique et latest
5.1–5.6. Appels API storage (8082) : pagination, latest, 404 inconnu, 400 plage > 7j, 400 param invalide.
5.7. `./infra/linux/se.sh count SENSOR-001 YYYY-MM-DD` = mesures acceptées ce jour.

### 6. T6 : Analytics
6.1–6.12. Appels API analytics (8083) : summary, daily-summary, anomalies THRESHOLD/ZSCORE, exceedances, forecast 501, pagination, 400 cas limites.

### 7. T7 : Preuve croisée stockage ↔ analytics
7.1–7.2. count summary analytics = count Cassandra ; maxPowerKw cohérent.

### 8. T8 : Scénarios de panne (manuels, Docker)
8.1–8.6. stop/start storage-service, redis, sensor-service ; vérifier 503, reprise, seuil DEFAULT.

### 9. T9 : Redémarrage complet
9.1. `docker-compose --profile app down` puis `up -d --build`; vérifier persistance des données.

### 10. T10 : Doublons et idempotence
10.1–10.3. Publier même eventId ×2 → 1 ligne Cassandra, compteurs analytics inchangés.

### 11. T11 : Swagger / OpenAPI
11.1–11.4. `GET /swagger-ui.html` et `/v3/api-docs` sur 8082 et 8083 → 200.

## Livrables
- Stack complète démarrée et healthy.
- scripts/e2e.sh complété (scénarios HTTP purs) — optionnel, à implémenter par un agent code.
- Compte-rendu d'exécution T1–T11 avec preuves (curl, XINFO GROUPS, cqlsh, mongosh).

## Validation (critères de succès)
| Critère | Seuil |
|---|---|
| Santé 4 services | 4× UP |
| Mesure valide | 202 + X-Event-Id + XLEN +1 |
| Mesure invalide | 4xx, XLEN inchangé |
| Simulateur | erreurs=0, acceptées ≈ envoyées |
| Stream | lag=0, pending=0, DLQ=0 |
| Historique | pagination sans trou, latest 200/404 |
| Analytics | cohérent avec stockage |
| Preuve croisée | count analytics = count Cassandra |
| Panne storage | reprise sans perte (pending=0) |
| Panne Redis | collector 503 + Retry-After |
| Panne sensor-service | seuil DEFAULT, anomalie créée |
| Redémarrage complet | données présentes |
| Doublon | 1 ligne Cassandra, compteurs inchangés |
| Swagger | 200 sur 8082 et 8083 |

## Risques / questions ouvertes
- Build Maven lourd : premier `up -d --build` peut être lent (dépendances Maven). Prévoir du temps.
- Cassandra peut encore être lente au premier démarrage (start_period 60s déjà configuré).
- `.env` définit `SIMULATOR_SENSORS` comme liste d'IDs mais le simulateur attend un nombre → override `SMARTENERGY_SIMULATOR_SENSORS=5` requis.
- Pas de `.env.example` dans le dépôt ; `.env` existe déjà.
- `ANALYTICS_FORECAST_ENABLED=true` → `/forecast` répond 501 (niveau C non fait), comportement attendu.
