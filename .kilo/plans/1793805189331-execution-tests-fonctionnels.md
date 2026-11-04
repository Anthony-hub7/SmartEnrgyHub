# Plan — Exécution des tests fonctionnels backend (stream + analytics + simulateur)

## Objectif
Valider de bout en bout le pipeline streaming : Simulateur → Collector → Redis Streams → Storage (Cassandra + latest) + Analytics (MongoDB). Scénarios T1–T11. Préparer le terrain pour une future interface.

## Contexte vérifié
- 4 services Spring Boot présents : `collector-service`, `storage-service`, `analytics-service`, `sensor-service`.
- `docker-compose.yml` définit les 4 services + infra (redis, mongo, cassandra, cassandra-init).
- Cassandra en `cassandra:4`. `.env` : `MEASUREMENT_MAX_AGE=P31D`, `ANALYTICS_FORECAST_ENABLED=true`.
- Environnement léger validé (Cassandra + Redis healthy, pas de crash).
- **Docker Compose standalone** : `docker-compose` (pas le plugin `docker compose`).
- Build Docker complet (`up -d --build`) a échoué (trop lourd/lent, OOM) → on ne l'utilise pas.
- Java 17 + Maven 3.8.7 disponibles en local.

## Décisions d'exécution (validées)
1. **Infra Docker uniquement** : `docker-compose up -d cassandra redis mongo cassandra-init` (pas de build Java dans Docker, pas de `--profile app`).
2. **4 services Spring Boot en local** : `mvn spring-boot:run` depuis chaque dossier, contre l'infra Docker (`localhost`).
3. **Cassandra allégée** : `CASSANDRA_MAX_HEAP=512M`, `CASSANDRA_HEAP_NEWSIZE=128M`, pas de `mem_limit` stricte (évite les OOM/crashs).
4. **Modules Maven pré-compilés** : `mvn -B -q install -DskipTests` déjà fait.
5. **Services lancés via script** : `start-services-local.sh start` (détache les processus).
6. **Latence/transaction** : non ajoutée par défaut (hors périmètre T1–T11). Peut être testée séparément si nécessaire pour l'interface.

## Tâches (ordre d'exécution)

### 1. Préparation de l'environnement
1.1. Vérifier `.env` : `CASSANDRA_MAX_HEAP=512M`, `CASSANDRA_HEAP_NEWSIZE=128M`, `MEASUREMENT_MAX_AGE=P31D`, `ANALYTICS_FORECAST_ENABLED=true`.
1.2. Vérifier infra Docker démarrée et healthy : `docker ps` (cassandra, redis, mongo healthy).
1.3. Init schéma Cassandra : `docker exec -i smartenergy-hub-cassandra-1 cqlsh < scripts/init-cassandra.cql`.
1.4. Vérifier tables : `docker exec smartenergy-hub-cassandra-1 cqlsh -e "use smartenergy; describe tables;"`.

### 2. Lancement des 4 services en local
2.1. `./start-services-local.sh start` → lance collector(8081), storage(8082), analytics(8083), sensor(8084).
2.2. Attendre ~45s que chaque service démarre (Maven recompile chaque service).
2.3. Vérifier logs : `/tmp/collector.log`, `/tmp/storage.log`, `/tmp/analytics.log`, `/tmp/sensor.log`.

### 3. T1–T2 : Santé et Collector
3.1. `curl http://localhost:8081/actuator/health/readiness` (idem 8082, 8083, 8084) → 200, UP.
3.2. POST mesure valide sur 8081 → 202 + X-Event-Id, XLEN +1.
3.3. POST sans sensorId → 400, XLEN inchangé.
3.4. POST timestamp futur → 400 MEASUREMENT_IN_FUTURE.
3.5. POST timestamp trop ancien → 400 MEASUREMENT_TOO_OLD.
3.6. POST corps > 10 Ko → 413.
3.7. Doublon identique ×2 → 2×202, upsert idempotent.

### 4. T3 : Simulateur en flux continu
4.1. `docker-compose run --rm simulator -e SMARTENERGY_SIMULATOR_SENSORS=5 -e SMARTENERGY_SIMULATOR_INTERVAL=PT2S -e SMARTENERGY_SIMULATOR_DURATION=PT2M -e SMARTENERGY_SIMULATOR_REGISTER_SENSORS=true`.
4.2. Résumé : envoyées, acceptées, rejetées, erreurs=0.
4.3. `XLEN energy-measurements` ≈ mesures acceptées.
4.4. `pending ≈ 0` storage-group et analytics-group.

### 5. T4 : Stream Redis et consommateurs
5.1. `XINFO GROUPS energy-measurements` → 2 groupes, lag=0, pending=0.
5.2. `XLEN energy-measurements-dlq` → 0.
5.3. (optionnel) `./infra/linux/stream-watch.sh` en parallèle.

### 6. T5 : Storage historique et latest
6.1–6.6. Appels API storage (8082) : pagination, latest, 404 inconnu, 400 plage > 7j, 400 param invalide.
6.7. `docker exec -it smartenergy-hub-cassandra-1 cqlsh -e "SELECT count(*) FROM smartenergy.measurements_by_sensor_day WHERE sensor_id='SENSOR-001' AND day='YYYY-MM-DD';"`.

### 7. T6 : Analytics
7.1–7.12. Appels API analytics (8083) : summary, daily-summary, anomalies THRESHOLD/ZSCORE, exceedances, forecast 501, pagination, 400 cas limites.

### 8. T7 : Preuve croisée stockage ↔ analytics
8.1–8.2. count summary analytics = count Cassandra ; maxPowerKw cohérent.

### 9. T8–T11 : Pannes, redémarrage, doublons, Swagger
9.1–9.6. Pannes stock/Redis/sensor (manuelles, docker-compose stop/start).
9.7. Redémarrage complet : `docker-compose down` puis `up -d`, vérifier persistance.
9.8. Doublons : 1 ligne Cassandra, compteurs analytics inchangés.
9.9. Swagger : `GET /swagger-ui.html` et `/v3/api-docs` sur 8082 et 8083 → 200.

## Livrables
- Stack complète démarrée et healthy (infra Docker + 4 services locaux).
- Compte-rendu d'exécution T1–T11 avec preuves (curl, XINFO GROUPS, cqlsh, mongosh).
- scripts/e2e.sh complété (optionnel, automatisation des scénarios HTTP).

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
- Mémoire limitée (~3.6 GB libre) : lancer les 4 services JVM en parallèle peut saturer. Si OOM, lancer les services un par un.
- Chaque `mvn spring-boot:run` recompile le service (quelques minutes). Prévoir du temps.
- Cassandra peut être lente au premier démarrage (start_period 60s).
- `SIMULATOR_SENSORS` dans `.env` est une liste d'IDs mais le simulateur attend un nombre → override `SMARTENERGY_SIMULATOR_SENSORS=5` requis.
- `/forecast` répond 501 (niveau C non fait), comportement attendu.

## Question ouverte
- L'interface future consommera quels endpoints exactement ? (probablement /summary, /daily-summary, /anomalies, /exceedances, /latest, historique)
