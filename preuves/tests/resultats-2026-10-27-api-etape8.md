# Rapport de tests — API de consultation documentée et testable (étape 8)

- **Date** : 2026-10-27
- **Environnement** : Linux (Ubuntu), Docker 28.2.2, OpenJDK 17.0.18, Maven 3.8.7
- **Cible** : stack `docker-compose` de l'APPLICATION — `smartenergy-cassandra` (4.1, `localhost:9042`),
  `smartenergy-redis` (7, `localhost:6379`, mot de passe `change-me`), `smartenergy-mongodb`
  (7, `analytics_db` via `analytics_app`), **pas** de conteneur dédié au test.
- **Modules** : `storage-service` (étape 8a) + `analytics-service` (étape 8b, routes de lecture ;
  les agrégats seront écrits par le consumer à l'étape 9).

## 1. Périmètre (arbitrages validés)

- **Storage + analytics read** : les 5 squelettes `TODO(etape-8)` sont activés. Les routes analytics
  sont créées et testées maintenant ; elles renvoient **404 NO_DATA** tant que l'étape 9 n'a pas
  rempli les agrégats (par conception).
- **Cache-aside Redis implémenté** sur `GET /latest` : `cache:latest:{sensorId}`
  (`RedisKeys.CACHE_LATEST_TTL` = 30 s), repli silencieux sur Cassandra si Redis est indisponible
  ou le cache corrompu (jamais de 500 à cause d'un cache).

## 2. Endpoints livrés (CDC 16.1)

| Endpoint | Méthode | Service | Réponses testées |
|---|---|---|---|
| `/api/measurements/{sensorId}?from&to&limit&cursor&order` | GET | storage | 200 (pagination 2 pages, asc/desc, bornes), 400 `INVALID_PARAMETER`/`RANGE_TOO_LARGE` |
| `/api/measurements/{sensorId}/latest` | GET | storage | 200 + cache TTL ≤ 30 s, 404 `MEASUREMENT_NOT_FOUND` |
| `/api/analytics/{sensorId}/summary?from&to` | GET | analytics | 200 (moyenne pondérée, bornes horaires), 404 `NO_DATA`, 400 |
| `/api/analytics/{sensorId}/daily-summary?date` | GET | analytics | 200, 404 `NO_DATA` |
| `/api/sensors/{id}/daily-summary?date` (alias D-17) | GET | analytics | 200 — même méthode, deux chemins |
| `/api/analytics/{sensorId}/anomalies?from&to&type&page&size` | GET | analytics | 200 page vide/paginée/filtrée, 400 |
| `/api/analytics/exceedances?threshold&from&to` | GET | analytics | 200 liste vide/remplie, 400 sans threshold |
| `/api/analytics/{sensorId}/forecast?horizonHours` | GET | analytics | 501 `FORECAST_DISABLED`, 400 horizon hors [1,24] |
| `/api/sensors`, `/api/sensors/{id}` | GET | sensor-service | déjà livrés à l'étape 6 (CRUD) |

## 3. Tests automatisés

```bash
mvn -B -pl storage-service,analytics-service -am test
```

Résultat : **BUILD SUCCESS** — 0 échec sur toute la réaction (contrats 11 + platform-stream 6 +
**storage 16** + **analytics 9**).

### storage-service — 16 tests

| Test | Vérifie | Résultat |
|---|---|---|
| `apiHistorique_fromTo_paginationCurseur_etOrdres` (sc. 3) | `XADD` → consumer → `GET` : 6 items bornés `[from,to)` ordonnés **desc** ; borne exclusive à minuit ; `limit=4` → `nextCursor` puis page 2 sans trou ni doublon ; `order=asc` ; capteur inconnu → **200 items vide** | OK |
| `apiLatest_200_avecCacheAside_puis404PourCapteurInconnu` | 200 = table `latest` (D-09) ; cle `cache:latest:*` écrite avec **TTL ∈ [0, 30] s** ; capteur inconnu → 404 `MEASUREMENT_NOT_FOUND` | OK |
| `apiParametresInvalides_repondent400_problemPlusJson` | `limit=0/1001`, `order=sideways`, `cursor` corrompu, `from>=to`, `from=hier`, plage > 7 j → **400** avec le bon `code` | OK |
| `openApi_documenteLesRoutesDeLecture` | `/v3/api-docs` contient les 2 routes GET ; `/swagger-ui.html` répond 2xx/3xx | OK |
| `HistoryCursorTest` (4 tests) | aller-retour exact, base64url sans padding ni `+/`, jetons corrompus → `INVALID_PARAMETER`, jetons distincts | OK |
| Étapes 5 + 7 (8 tests) | rejoués sans régression ; perf : médiane 48 ms, p95 60 ms sur 10 000 lignes | OK |

### analytics-service — 9 tests

| Test | Vérifie | Résultat |
|---|---|---|
| `summary_sansAgregat_repond404NoData` | aucun agrégat → 404 `NO_DATA` (étape 9 remplira) | OK |
| `summary_avecAgregats_moyennePonderee_etBornesHoraires` | 36/8 = **4.5** (la moyenne des moyennes donnerait 4.0 → biais non détecté), min/max/énergie/ compteurs, `thresholdKw` depuis le cache Redis, bornes `[10:30, 11:30)` → **[10:00, 12:00)** (D-13) | OK |
| `summary_plageInvalide_400_…` | `from > to` → 400 `INVALID_PARAMETER` ; 44 jours → 400 `RANGE_TOO_LARGE` (31 j max) | OK |
| `dailySummary_404SansAgregat_puis200_surLesDeuxChemins` | 404 sans agrégat ; 200 moyenne pondérée sur les **deux** chemins, dont l'alias guide `/api/sensors/{id}/daily-summary` (D-17) | OK |
| `anomalies_pageVide_puisPaginationEtFiltre` | page vide → **200** (`totalElements=0`) ; 3 anomalies → page 0/1 sans doublon, tri du plus récent ; filtre `type` ; type inconnu → 400 | OK |
| `anomalies_paginationInvalides_400` | `page=-1`, `size=0` → 400 `INVALID_PARAMETER` | OK |
| `exceedances_etatCourant_puisPeriode_400SansThreshold` | vide → 200 `[]` ; état courant (seuil 10 kW → seul `SENSOR-HAUT` 12.4) ; avec `from/to` → max quotidien 15.0 ; sans threshold → 400 | OK |
| `forecast_repond501_…` | flag off → **501 `FORECAST_DISABLED`** ; horizon 99 → 400 | OK |
| `openApi_documenteToutesLesRoutesDeLecture` | `/v3/api-docs` : les **6 routes** analytics dont l'alias D-17 ; Swagger UI joignable | OK |

Les collections Mongo sont purgées avant/après chaque test : le consumer analytics (étape 9)
n'écrit pas encore et ces tests posent eux-mêmes leurs agrégats.

## 4. Exécution réelle des services (java -jar, hors tests)

Les deux jars empaquetés ont été démarrés contre la stack, vérifiés puis arrêtés.

### storage-service :8082

```text
readiness            = 200
/swagger-ui.html     = 200        <- critere CDC : documente et testable depuis Swagger UI
/v3/api-docs         = /api/health, /api/measurements/{sensorId}, /api/measurements/{sensorId}/latest

GET /api/measurements/SENSOR-API-HIST?from=2026-11-05T00:00:00Z&to=2026-11-07T00:00:00Z&limit=2
  -> 200 {"sensorId":"SENSOR-API-HIST", ... "items":[09:00:04, 09:00:02], "nextCursor":"MjAyNi0xMS0wNnwxNzkzOTU1NjAyMDAw"}

GET /api/measurements/SENSOR-API-LATEST/latest            -> 200 (timestamp = D-09)
GET /api/measurements/SENSOR-INCONNU/latest               -> 404 code=MEASUREMENT_NOT_FOUND (problem+json)
GET ...?from=2026-11-01&to=2026-11-15 (14 jours)          -> 400 RANGE_TOO_LARGE
```

**Latence de l'API historique** (`limit=1000` sur la journée de 500 lignes du jeu 10 000 lignes,
20 mesures) : **médiane 113 ms, p95 201 ms** (min 63, max 610 dont première requête = préparation
des statements) → **cible CDC p95 < 300 ms : OK**.

### analytics-service :8083

```text
readiness            = 200
/swagger-ui.html     = 200
/v3/api-docs         = /api/analytics/exceedances
                      /api/analytics/{sensorId}/anomalies
                      /api/analytics/{sensorId}/daily-summary
                      /api/analytics/{sensorId}/forecast
                      /api/analytics/{sensorId}/summary
                      /api/sensors/{sensorId}/daily-summary   <- alias D-17 route
                      /api/health

GET /api/analytics/SENSOR-001/summary          -> 404 code=NO_DATA (pas encore d'agregats, etape 9)
GET /api/sensors/SENSOR-001/daily-summary?date -> 404 code=NO_DATA (alias actif)
GET /api/analytics/SENSOR-001/forecast         -> 501 code=FORECAST_DISABLED
GET /api/analytics/exceedances (sans seuil)    -> 400 code=INVALID_PARAMETER
```

Réponses en `application/problem+json` avec `code` + `traceId` (RFC 9457, `GlobalExceptionHandler`).

## 5. Décisions techniques de l'étape 8

- **Curseur maison « jour|epochMillis » base64url** plutôt que le paging state du pilote : le
  parcours traverse **une partition par jour** (D-08), le paging state ne couvre qu'une partition.
  Un jeton corrompu → 400 `INVALID_PARAMETER`, jamais un 500.
- **Reprise de pagination exacte** : en `desc`, saut des jours déjà parcourus + borne haute
  *exclusive* = dernier item servi ; en `asc`, borne basse *exclusive* (`+1 ms`, précision ms
  des horodatages). `LIMIT limit+1` par requête : dès la limite atteinte, les jours restants
  **ne sont pas lus** (une seule requête CQL par jour, sans `ALLOW FILTERING`).
- **404 vs 200 vide** : `summary`/`daily-summary` (ponctuels) → 404 `NO_DATA` ; `anomalies`/
  `exceedances` (listes) → 200 avec liste vide (« aucune anomalie » est une réponse, pas une
  erreur) ; historique sans mesure → 200 `items` vide (pas de couplage sensor-service).
- **Moyenne pondérée** : `ΣsumPowerKw / Σcount` sur les agrégats horaires — discriminant de test
  : 4.5 attendu vs 4.0 pour la moyenne des moyennes.
- **Forecast** : validation de forme d'abord (horizon [1,24] → 400), puis flag
  `smartenergy.analytics.forecast.enabled=false` → 501 (le modèle ONNX = extension 9C).

## 6. Prérequis Mongo (fait manuellement à cette étape)

Le volume Mongo avait été initialisé **sans** les variables de création d'utilisateurs. Exécuté
dans le conteneur en marche (root, idempotent, équivalent de `scripts/init-mongo.js`) :

- `sensor_app` (readWrite `sensor_db`) + `analytics_app` (readWrite `analytics_db`, mot de passe
  `change-me-analytics`) — vérifié par une connexion applicative directe ;
- collections `sensors`, `hourly_aggregates`, `daily_aggregates`, `anomalies`, `sensor_state`
  et leurs index (`uk_sensorId`, `ix_sensorId_hour`, `ix_sensorId_date`, `uk_eventId_type`,
  `ix_sensorId_timestamp`, `ix_lastPowerKw`).

## 7. Corrections survenues pendant l'étape

1. Tri d'historique inversé : la partition Cassandra arrive déjà en `measured_at DESC` pour un
   parcours `desc` — l'inversion ne s'applique qu'en `asc` (détecté par l'assertion du scénario 3).
2. Curseur : seuls le jour du curseur était traité — les jours **plus récents** déjà parcourus
   devaient être sautés en `desc` (page 2 rendait 4 items au lieu de 2).
3. `nextCursor` sérialisé en `null` explicite (`@JsonInclude(ALWAYS)`) : assertions `nullValue()`
   plutôt que `doesNotExist()` ; projection JsonPath `$.items[*].timestamp`.
4. Javadoc : le motif `/*/daily-summary` fermait prématurément le commentaire (`*/`) → entité HTML.
5. Spring Data Redis : `getExpire` renvoie `Long` (secondes), pas `Duration`.

## 8. Reste à venir

- **Étape 9** : consumer analytics → les agrégats/anomalies passeront des 404 NO_DATA à des
  données réelles ; test croisé du CDC (count de summary = lignes Cassandra de la même plage).
- **Étape 9C (extension)** : `ForecastService` au-delà du 501 (baseline puis modèle ONNX).
- Mesures de bout en bout et métriques stream : étape 11.
