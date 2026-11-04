# storage-service (port 8082)

**Rôle** : consommer le stream (`storage-group`), persister dans **Cassandra**, servir l'historique et la dernière mesure
(EF-04, EF-06, EF-07, EF-15). **Propriétaire** du keyspace `smartenergy` ; ne touche ni MongoDB ni les autres services.

## Données (voir `scripts/init-cassandra.cql`)
- `measurements_by_sensor_day` — partition `(sensor_id, day)`, clustering `measured_at DESC`, compaction TWCS
- `latest_measurement_by_sensor` — une ligne par capteur, écrite avec `USING TIMESTAMP` = date de mesure

## Contrat REST
| Route | Réponses |
|---|---|
| `GET /api/measurements/{sensorId}?from&to&limit&cursor&order` | 200 `MeasurementHistoryResponse` ; 400 `RANGE_TOO_LARGE` (> 7 jours) / `INVALID_PARAMETER` ; capteur sans donnée : 200, `items` vide |
| `GET /api/measurements/{sensorId}/latest` | 200 ; 404 `MEASUREMENT_NOT_FOUND` |

Plage `[from, to)` ; `limit` 100 par défaut, 1000 max ; `nextCursor` opaque, `null` en fin de liste.

## Configuration
`REDIS_*` · `CASSANDRA_HOST/PORT/DC` (`local-datacenter` obligatoire : `datacenter1`) · `schema-action: none` (schéma par script).

## Déjà fait
Application, `application.yml` (Redis + Cassandra), schéma CQL + conteneur d'initialisation, healthchecks.

## À faire (dans l'ordre)
1. ~~`entity/MeasurementKey`, `MeasurementByDayEntity`, `LatestMeasurementEntity`~~ ✅ mapping Spring Data Cassandra (étape 5)
2. ~~`platform-stream/AbstractStreamConsumer`~~ ✅ boucle de consommation (étape 4) *(partagée avec analytics)*
3. ~~`service/CassandraMeasurementWriter`~~ ✅ 2 upserts idempotents, **sans null explicite**, `USING TIMESTAMP` D-09 (étape 7)
4. ~~`stream/StorageStreamConsumer`~~ ✅ branche le writer sur la boucle (étape 7)
5. ~~`service/HistoryCursor`, `MeasurementQueryService`, `web/MeasurementQueryController`~~ ✅ routes GET, pagination curseur, cache-aside `latest`, OpenAPI (étape 8)
6. `StorageIntegrationTest` — ✅ scénarios 5, 6, 8 (étape 7) + scénario 3 complet, `/latest`, 400, Swagger (étape 8) ; scénarios 10/11 = étape 11

## Pièges
- **ACK après persistance** uniquement, sinon perte silencieuse.
- Une requête **par jour** de la plage ; jamais de `IN` sur la clé de partition.
- Null explicite = tombstone : omettre les colonnes absentes.
- Cassandra met 1 à 2 minutes à démarrer (le Compose attend `cassandra-init`).

## Critère de validation (étape 7)
Une mesure envoyée au collector est retrouvée dans Cassandra (prouvé au niveau base par
`StorageIntegrationTest.critereValidation_*` : `XADD` → consumer → les deux tables → `pending=0`) ;
l'arrêt puis le redémarrage du service ne perd rien (reprise, `lag=0`).

## Critère de validation (étape 8 — ✅)
Consultation depuis Swagger UI (`/swagger-ui.html`, vérifié sur service réel) : historique filtré
`from`/`to`, paginé par curseur sur 2 pages, `latest` (200/404 + cache 30 s), paramètres invalides
→ 400 Problem Details. Preuve : `preuves/tests/resultats-2026-10-27-api-etape8.md` (API p95 201 ms).
