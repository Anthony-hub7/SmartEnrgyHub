# collector-service (port 8081)

**Rôle** : recevoir les mesures, les valider, les publier dans Redis Streams **sans bloquer l'émetteur** (EF-02, EF-03).
Aucune base propre ; ne vérifie pas l'existence du capteur (disponibilité avant cohérence).

## Contrat
`POST /api/measurements` — corps `EnergyMeasurementRequest` (schéma : `contracts/schemas/energy-measurement-request.schema.json`)

| Code | Cas |
|---|---|
| 202 | publiée dans le stream ; en-tête `X-Event-Id` ; corps vide |
| 400 | JSON mal formé, règle violée, trop ancienne (> 24 h), dans le futur (> 5 min) — Problem Details |
| 413 / 415 | corps > 10 Ko / type non JSON |
| 503 | Redis indisponible — `Retry-After: 5`. **Jamais de 202 sans `XADD` réussi** |

Un doublon est **accepté** (202) : il est neutralisé en aval par l'idempotence.

## Configuration (`application.yml`)
`REDIS_HOST/PORT/PASSWORD` · `MEASUREMENT_MAX_AGE` (PT24H) · `MEASUREMENT_MAX_FUTURE` (PT5M) · `STREAM_NAME` · `STREAM_MAXLEN`.
Jackson : **pas de coercion** (`"3.72"` en chaîne est refusé).

## Déjà fait
Application, propriétés, bean `Clock`, `application.yml`, `/api/health` + erreurs + `X-Request-Id` (platform-web), Dockerfile/Compose.

**Étape 3 (CDC §11) — terminée le 2026-10-21** :
- `validation/MeasurementTimeValidator` — 400 `MEASUREMENT_TOO_OLD` (> 24 h) / `MEASUREMENT_IN_FUTURE` (> 5 min) ; test unitaire des bornes exactes.
- `stream/MeasurementPublisher` — `XADD` via `MeasurementEventCodec` avec `MAXLEN ~` ; 503 + `Retry-After: 5` si Redis KO ; compteurs Micrometer `energy_measurements_published_total` / `energy_measurement_publish`.
- `web/MeasurementController` — `POST /api/measurements` → 202 + `X-Event-Id` ; log `sensorId/eventId/redisId`.
- `web/BodySizeLimitFilter` + `config/FilterConfig` — 413 `PAYLOAD_TOO_LARGE` au-delà de 10 Ko (Content-Length ou chunked).
- `CollectorIntegrationTest` (Testcontainers Redis) + `MeasurementTimeValidatorTest` — **24 tests verts**.

Preuves : `preuves/tests/resultats-2026-10-21-collector.md`.

## À faire (dans l'ordre)
1. ~~`validation/MeasurementTimeValidator`~~ ✅
2. ~~`stream/MeasurementPublisher`~~ ✅
3. ~~`web/MeasurementController`~~ ✅
4. ~~`web/BodySizeLimitFilter`~~ ✅
5. ~~`CollectorIntegrationTest`~~ ✅ (scénarios 1, 2, 8, 9 + coercion + 413)

## Pièges
- Ne **jamais** réécrire la sérialisation : `MeasurementEventCodec.toFields` est le contrat.
- `timestamp` accepté en ISO-8601 UTC (`Z`) : tester le comportement avec un décalage `+03:00` et le documenter.
- Calculer `traceId` avec `RequestIdFilter.currentRequestId()`.

## Critère de validation (étape 3)
Une requête valide crée un évènement dans Redis ; une requête invalide n'en crée aucun.
