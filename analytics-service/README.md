# analytics-service (port 8083)

**Rôle** : transformer les mesures en information — agrégats horaires/quotidiens, dépassements de seuil, anomalies, prévision
(EF-08 à EF-13). **Propriétaire** de MongoDB `analytics_db`. Consomme le stream via **`analytics-group`**
(indépendant du stockage).

## Données (`scripts/init-mongo.js`)
`sensor_state` (état + statistiques de Welford) · `hourly_aggregates` · `daily_aggregates` · `anomalies` (unique `eventId + type`) ·
`forecasts` (extension). Agrégats **additifs** : moyenne = `sumPowerKw / count`, calculée à la lecture.

## Contrat REST
| Route | Notes |
|---|---|
| `GET /api/analytics/{sensorId}/summary?from&to` | granularité **horaire** (bornes alignées), max 31 jours ; 404 `NO_DATA` |
| `GET /api/analytics/{sensorId}/daily-summary?date` et alias `GET /api/sensors/{id}/daily-summary` | agrégat quotidien |
| `GET /api/analytics/{sensorId}/anomalies?from&to&type&page&size` | anomalies récentes d'abord |
| `GET /api/analytics/exceedances?threshold&from&to` | sans dates : état courant ; avec : maximum quotidien |
| `GET /api/analytics/{sensorId}/forecast?horizonHours` | **extension** ; 501 `FORECAST_DISABLED` si inactif |

## Règles de calcul (CDC 6.5) — dans `rules/AnalyticsRules`
Énergie (rectangle à gauche, pas de temps plafonné à 5 × 2 s) · seuil strict `P > Pmax` · score z par Welford (min 30 échantillons, seuil 3,
mesures anormales exclues des statistiques) · sévérité `THRESHOLD`=HIGH, `ZSCORE`=MEDIUM.

## Déjà fait
Application, propriétés, `Clock`, client REST `SensorClient` (cache Redis 60 s → sensor-service avec retry + circuit breaker →
**seuil par défaut**, ne lève jamais d'exception), documents Mongo (records), `application.yml`, Resilience4j.

## À faire (dans l'ordre)
1. ~~`rules/AnalyticsRules` + `AnalyticsRulesTest`~~ ✅ formules (2)/(3)/(4) pures + 8 tests de référence (étape 9)
2. ~~`service/AggregateWriter`~~ ✅ micro-batch D-14 (`$inc`/`$min`/`$max`/`$setOnInsert`), anomalies idempotentes `_id` déterministe (étape 9)
3. ~~`stream/AnalyticsStreamConsumer`~~ ✅ dédup (vérifier avant, marquer après), seuil 1×/capteur, tri des timestamps, métrique (étape 9)
4. ~~`service/AnalyticsQueryService`, `web/AnalyticsController`~~ ✅ routes de lecture + alias D-17 + 501 forecast (étape 8) — *les agrégats sont désormais écrits par l'étape 9 : `NO_DATA` seulement si le capteur n'a aucune mesure*
5. ~~`AnalyticsIntegrationTest`~~ ✅ scénarios 4, 8, 11 (écritures via consumer, étape 9 ; lectures couvertes par `AnalyticsQueryIntegrationTest`, étape 8)
6. *(extension)* `forecast/ForecastService` : ✅ flag + 501 fait (étape 8) ; baseline puis `ml/` = étape 9C — démarche dans `docs/forecast-niveau-c.md`

**Critère CDC rempli** : service d'analyse avec les indicateurs obligatoires (count, moyenne, min/max,
énergie, dépassements, anomalies) + explication des limites (Welford cumulé, seuil strict, biais 2 s
de l'énergie) — preuves `preuves/tests/resultats-2026-10-30-analytics-etape9ab.md`.

## Pièges
- Traiter les mesures d'un lot **dans l'ordre des timestamps**.
- `summary` : moyenne = somme des sommes / somme des comptes (pas la moyenne des moyennes).
- Limite connue : mesures très en retard → énergie légèrement faussée (recalcul nocturne = amélioration possible).
- Un sinus gonfle l'écart-type : documenter la limite du z-score.
