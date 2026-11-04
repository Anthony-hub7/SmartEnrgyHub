# Carte des classes à implémenter

Généré à partir des en-têtes `TODO(...)` du code (`make todo` donne la même liste en ligne de commande).
Chaque fichier contient : le guide/CDC de référence, les étapes à suivre, les pièges, les tests attendus,
puis un **squelette commenté** à décommenter (`Ctrl+/`) et à compléter.

Ordre conseillé : de haut en bas. Les classes déjà opérationnelles (contrats, plomberie web, sensor-service,
simulateur, scripts de base de données, Docker) ne figurent pas ici.

## Étape 1 · Contrat de données  _(plan : J2)_ — ✅ terminée le 2026-10-21

| Fichier | À faire |
|---|---|
| ~~`collector-service/.../validation/MeasurementTimeValidator.java`~~ | ✅ Règles temporelles 24 h / 5 min + test unitaire des bornes |

## Étape 3 · Collector Service  _(plan : J2)_ — ✅ terminée le 2026-10-21

| Fichier | À faire |
|---|---|
| ~~`collector-service/.../stream/MeasurementPublisher.java`~~ | ✅ XADD MAXLEN ~, 503 si Redis KO, compteurs Micrometer |
| ~~`collector-service/.../web/MeasurementController.java`~~ | ✅ POST /api/measurements → 202 + X-Event-Id |
| `collector-service/.../web/BodySizeLimitFilter.java` | ✅ 413 au-delà de 10 Ko (étape 12, livré avec l'étape 3) |
| `collector-service/.../CollectorIntegrationTest.java` | ✅ Scénarios 1, 2, 8, 9 + coercion + 413 (Testcontainers Redis) |

Preuves : `preuves/tests/resultats-2026-10-21-collector.md`. Correction transverse :
`GlobalExceptionHandler` (platform-web) manquait `@ControllerAdvice` (erreurs en 500).

## Étape 4 · Redis Streams  _(plan : J3)_ — ✅ terminée le 2026-10-26

| Fichier | À faire |
|---|---|
| ~~`platform/platform-stream/src/main/java/com/smartenergy/platform/stream/AbstractStreamConsumer.java`~~ | ✅ XGROUP MKSTREAM, XREADGROUP, ACK après traitement, reprise XPENDING/XCLAIM, DLQ |

## Étape 5 · Cassandra  _(plan : J4)_ — ✅ terminée le 2026-10-27

| Fichier | À faire |
|---|---|
| ~~`storage-service/.../entity/MeasurementKey.java`~~ | ✅ Cle composite `(sensor_id, day)` PARTITIONED + `measured_at` CLUSTERED DESC, equals/hashCode |
| ~~`storage-service/.../entity/MeasurementByDayEntity.java`~~ | ✅ Entite `measurements_by_sensor_day` + fabrique `from(EnergyMeasurement)` (tombstones D-10) |
| ~~`storage-service/.../entity/LatestMeasurementEntity.java`~~ | ✅ Entite `latest_measurement_by_sensor` (ecriture USING TIMESTAMP = etape 7) |
| `storage-service/.../repository/MeasurementByDayRepository.java` *(nouveau)* | ✅ `@Query` : UNE requete par jour (jamais de `IN` sur la partition, pas d'ALLOW FILTERING) |

Preuves : `preuves/tests/resultats-2026-10-27-cassandra.md` (4 tests verts, perf 10k lignes).
Le test rejoue `scripts/init-cassandra.cql` sur la stack de l'app et verifie les colonnes.
Correction notable : prefixe `spring.data.cassandra.*` DEPRECIE en Boot 3.5 → migre
`spring.cassandra.*` (test de non-régression `configurationCassandra_estLieeViaLePrefixeSpringCassandra`).

## Étape 7 · Storage Service  _(plan : J3-J4)_ — ✅ terminée le 2026-10-27

| Fichier | À faire |
|---|---|
| ~~`storage-service/.../service/CassandraMeasurementWriter.java`~~ | ✅ Ecriture idempotente (upsert par PK), `USING TIMESTAMP` D-09, `unset()` D-10, async + attente groupée, métriques `energy_persist_duration_seconds`/`energy_persist_errors_total` |
| ~~`storage-service/.../stream/StorageStreamConsumer.java`~~ | ✅ `@Component` extends `AbstractStreamConsumer`, `processBatch` → writer, service `storage-service` |

Preuves : `preuves/tests/resultats-2026-10-27-storage-etape7.md` (8 tests verts, critère CDC
« mesure retrouvée dans Cassandra », D-09/D-10 vérifiés sur la stack). Le test rejoue le cycle
`XADD` → consumer → les deux tables → `pending=0`.

## Étape 8 · API de consultation  _(plan : J5)_ — ✅ terminée le 2026-10-27

| Fichier | À faire |
|---|---|
| ~~`storage-service/.../service/HistoryCursor.java`~~ | ✅ record `(jour, measured_at)` encode/decode base64url ; jeton corrompu → `INVALID_PARAMETER` |
| ~~`storage-service/.../service/MeasurementQueryService.java`~~ | ✅ historique (défauts 24 h, limit [1..1000], plage ≤ 7 j, une requête par jour `LIMIT n+1`, saut des jours parcourus, asc/desc) + `latest` cache-aside `cache:latest:{id}` 30 s → 404 `MEASUREMENT_NOT_FOUND` |
| ~~`storage-service/.../web/MeasurementQueryController.java`~~ | ✅ 2 routes GET + OpenAPI (`@Operation`, exemples requête/réponse, codes d'erreur) |
| ~~`analytics-service/.../service/AnalyticsQueryService.java`~~ | ✅ summary (moyenne pondérée, bornes horaires D-13, ≤ 31 j), daily-summary, anomalies paginées, exceedances (état courant / période) ; 404 `NO_DATA` si vide |
| ~~`analytics-service/.../web/AnalyticsController.java`~~ | ✅ 5 routes `/api/analytics/**` + alias `/api/sensors/{id}/daily-summary` (D-17) + `forecast` → 501 `FORECAST_DISABLED` |

Preuves : `preuves/tests/resultats-2026-10-27-api-etape8.md` — 16 tests storage + 9 analytics
verts sur la stack ; services réels démarrés (Swagger 200, routes conformes, API p95 201 ms) ;
prérequis Mongo (`analytics_app`/`sensor_app`) créés manuellement. Les routes analytics
renvoient 404 `NO_DATA` jusqu'à l'étape 9 (consumer analytics).

## Étape 9 · Analytics (niveaux A et B)  _(plan : J6)_ — ✅ terminée le 2026-10-30

| Fichier | À faire |
|---|---|
| ~~`analytics-service/src/main/java/com/smartenergy/analytics/rules/AnalyticsRules.java`~~ | ✅ Regles pures : energie (formule 2, plafond des trous), seuil strict (3), score z/Welford (4) |
| ~~`analytics-service/src/main/java/com/smartenergy/analytics/service/AggregateWriter.java`~~ | ✅ Micro-batch D-14 (une ecriture Mongo/document/lot) + anomalies idempotentes `_id` deterministe `$setOnInsert` |
| ~~`analytics-service/src/main/java/com/smartenergy/analytics/stream/AnalyticsStreamConsumer.java`~~ | ✅ Consumer `analytics-group` : dedup (verifier avant / marquer apres), seuil 1x/capteur, tri des timestamps, metrique `energy_anomalies_detected_total` |

Preuves : `preuves/tests/resultats-2026-10-30-analytics-etape9ab.md` — 24 tests analytics verts
(8 regles + 7 integration A/B + 9 lecture) sur la stack de l'app, 41 au total sans regression ;
preuve croisée reelle storage (6 mesures) = analytics (`count` 6), 1 anomalie THRESHOLD,
latence résumé médiane 13 ms / p95 19 ms. Niveau C reporte (demarche dans
`docs/forecast-niveau-c.md`, aucune IA tant que A/B non valides).

## Étape 11 · Observabilité  _(plan : J7-J8)_

| Fichier | À faire |
|---|---|
| `platform/platform-stream/src/main/java/com/smartenergy/platform/stream/StreamLagMetrics.java` | Metriques Micrometer du stream (pending, lag) |

## Étape 12 · Sécurité  _(plan : J8)_

| Fichier | À faire |
|---|---|
| `collector-service/src/main/java/com/smartenergy/collector/web/BodySizeLimitFilter.java` | Limite de taille du corps (10 Ko) -> 413 |

## Étape 9C · Prévision IA (extension)  _(plan : J8)_

| Fichier | À faire |
|---|---|
| `analytics-service/src/main/java/com/smartenergy/analytics/forecast/ForecastService.java` | ✅ flag + 501 `FORECAST_DISABLED` + horizon [1,24] (étape 8) ; [EXTENSION] baseline puis modèle ONNX = étape 9C |

## Tests d'intégration et unitaires  _(plan : au fil de l'eau)_

| Fichier | À faire |
|---|---|
| `collector-service/src/test/java/com/smartenergy/collector/CollectorIntegrationTest.java` | Test d'integration du collector (Testcontainers Redis) |
| `storage-service/src/test/java/com/smartenergy/storage/StorageIntegrationTest.java` | ✅ étapes 5, 7 et 8 (écriture/lecture, doublon, perf 10k, schema, branche­ment stream, USING TIMESTAMP, D-10, scénario 3 API, `/latest`, 400, Swagger) ; DLQ/reprise = test plateforme (étape 4) |
| ~~`analytics-service/src/test/java/com/smartenergy/analytics/rules/AnalyticsRulesTest.java`~~ | ✅ 8 tests unitaires des regles (reference 4 kW/1 h = 4,0 kWh ; [1..5] → mean 3, σ 1,5811, z(10) = 4,427 ; trou 600 s → 10 s) |
| ~~`analytics-service/src/test/java/com/smartenergy/analytics/AnalyticsIntegrationTest.java`~~ | ✅ Scénarios 4, 8, 11 via consumer (étape 9). Les lectures sont déjà testées par `AnalyticsQueryIntegrationTest` ✅ (étape 8, stack de l'app, sans Testcontainers) |

**Total : 8 fichiers à compléter.**
