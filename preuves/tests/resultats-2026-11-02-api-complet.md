# Résultats · Test API complet hors prévision — 2026-11-02

**Objectif** : valider la totalité des APIs de la chaîne (Collector, Sensor-service, Storage,
Analytics) **prévision exclue**, avec le simulateur, pour préparer la future interface.

**Stack** : Docker Compose `smartenergy-hub` — redis, mongo, cassandra, cassandra-init,
collector (8081), storage (8082), analytics (8083), sensor (8084). Tous `health UP`.

## Recompositions faites avant les tests

1. Le binaire `docker-compose` local ne peut pas exécuter le Dockerfile (option
   `--mount=type=cache` exigée par BuildKit, plugin buildx absent). Les images ont été
   produites en 2 temps : compilation Maven 17 sur l'hôte (`mvn -pl <module> -am package`)
   puis images runtime depuis un contexte minimal (jar seul + `eclipse-temurin:17-jre-jammy`
   identique au Dockerfile du dépôt). Aucune modification du Dockerfile du dépôt.
2. `ForecastService` ne démarrait pas : classe `@Component` avec 2 constructeurs (1 public +
   1 package-private de test) sans `@Autowired` → Spring retombe sur le constructeur par
   défaut absent. **Correctif 1 ligne** (câblage Spring uniquement, aucune logique prévision) :
   `@org.springframework.beans.factory.annotation.Autowired` sur le constructeur public.
3. La machine a **rebooté** en cours de test (Cassandra/Redis/Mongo exit 255, non-OOM) ;
   les conteneurs sauf Cassandra sont repartis seuls, `docker start cassandra` a suffi,
   volumes intacts (aucune perte : pending=0 après reprise).

## Simulateur

- Scénario : 5 capteurs, pas PT2S, 2 min, pic ×3 sur `SENSOR-003` (à t+45 s, 10 s),
  invalid-rate 0.05, duplicate-rate 0.02 (`docker-compose run --rm simulator` + overrides env).
- Résultat : `envoyees=308 acceptees(202)=289 rejetees(4xx)=19 erreurs=0`.
- Noté : les arguments CLI `--smartenergy.simulator.*` après le nom de service ne sont **pas
  transmit** par ce binaire compose ; passer par les variables d'environnement
  `SMARTENERGY_SIMULATOR_*` (honorees, cf. run de control PT5S). Le `.env` définit
  `SIMULATOR_SENSORS` comme liste d'IDs mais le service attend un **nombre** (int) : override
  `SMARTENERGY_SIMULATOR_SENSORS=5` requis.

## Résultats des tests (63 ✓ / 0 ✗)

| # | Bloc | Vérifs | Statut |
|---|---|---|---|
| 1 | Santé 4 services (`/actuator/health`) | 4 | ✅ UP |
| 2 | Swagger UI + `/v3/api-docs` (8082, 8083) | 4 | ✅ 200 |
| 3 | Collector `POST /api/measurements` : 202 + `X-Event-Id`, payload vide → 400 Problem Details, timestamp futur → 400, corps 11 Ko → 413 | 6 | ✅ |
| 3b | Doublon (2 envois identiques) : 2×202, dédup aval (upsert idempotent) | 2 | ✅ |
| 4 | Sensor-service : liste + 404 inconnu, SENSOR-001..003 enregistrés (registre par simulateur) | 6 | ✅ |
| 5 | Storage historique : SENSOR-001 (58 items), SENSOR-003 max **13.108 kW** (pic retrouvé) | 4 | ✅ |
| 6 | Pagination curseur : page 1 (`limit=20`) + `nextCursor` + page 2 + `order=asc` trié | 6 | ✅ |
| 7 | Erreurs métier : plage 8 j → 400 `RANGE_TOO_LARGE`, `limit=0` → 400, curseur corrompu → 400 `INVALID_PARAMETER` | 6 | ✅ |
| 8 | `latest` : 200 + 404 `MEASUREMENT_NOT_FOUND` | 3 | ✅ |
| 9 | Analytics `summary` : croisé storage ; capteur inconnu → 404 `NO_DATA` ; `from>=to` → 400 | 6 | ✅ |
| 10 | `daily-summary` + alias D-17 `/api/sensors/{id}/daily-summary` (réponses identiques), date illisible → 400 | 5 | ✅ |
| 11 | Anomalies du pic : formats, filtres `type=THRESHOLD/ZSCORE`, pagination (`size>500` → 400) | 7 | ✅ |
| 12 | `exceedances` : `threshold` obligatoire (400), état courant [], plage > 31 j → 400 | 5 | ✅ |
| 13 | `forecast` : **501 `FORECAST_DISABLED`** (défaut + horizon 24), horizon 99 → 400 | 4 | ✅ |
| 14 | Transverse : Redis XLEN 12246, `pending=0` (storage-group & analytics-group) | — | ✅ |

## Analyse correcte du pic (preuve croisée)

- Storage : max `powerKw` SENSOR-003 sur la fenêtre = **13.108**.
- Analytics summary SENSOR-003 : `count=54, max=13.108, thresholdExceededCount=3,
  anomalyCount=6` (seuil capteur 8.0 kW lu dans sensor-service).
- Mongo `analytics_db` : `anomalies=6` (3 `THRESHOLD`/HIGH + 3 `ZSCORE`), `_id` déterministe
  `SENSOR-003|<eventId>|<type>` (idempotent), `hourly_aggregates=3612`, `daily_aggregates=165`
  (runs antérieurs inclus), `forecasts=0` (prévision éteinte).
- `exceedances` avec dates (mode maximum quotidien) :
  `[{"sensorId":"SENSOR-003","thresholdKw":8.0,"maxPowerKw":13.108}]`.
- Cassandra : 209 lignes `SENSOR-001` pour le jour UTC (runs cumulés), cache `latest` OK.

## Conclusion

La chaîne complète Simulateur → Collector → Redis → Storage/Cassandra + Analytics/Mongo
fonctionne de bout en bout, avec un comportement d'analyse conforme aux CDC (dépassements,
anomalies THRESHOLD + ZSCORE, agrégats horaires/quotidiens, idempotence des doublons) et la
prévision proprement désactivée (501). **Reste au prévisionnel 9C** (code en cours de rédaction,
non bloquant) : l'anomalie initiale concernait uniquement le câblage Spring (corrigé, 1 ligne).
