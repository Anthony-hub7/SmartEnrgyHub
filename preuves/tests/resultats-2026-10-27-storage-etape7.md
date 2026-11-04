# Rapport de tests — Storage Service : consumer Redis + persistance Cassandra (étape 7)

- **Date** : 2026-10-27
- **Environnement** : Linux (Ubuntu), Docker 28.2.2, OpenJDK 17.0.18, Maven 3.8.7
- **Cible** : stack `docker-compose` de l'APPLICATION — `smartenergy-cassandra` (cassandra:4.1,
  `localhost:9042`) + `smartenergy-redis` (redis:7, `localhost:6379`, mot de passe `change-me`),
  **pas** de conteneur dédié au test.
- **Module** : `storage-service`

## 1. Traitements CDC implémentés (section 7.3)

| Étape du CDC | Où | Statut |
|---|---|---|
| 1. Lire un événement Redis | `AbstractStreamConsumer` (boucle XREADGROUP) | ✅ étape 4 |
| 2. Vérifier la structure | `MeasurementEventCodec.fromFields` → `MalformedEventException` → DLQ | ✅ étape 4 |
| 3. Enregistrer dans Cassandra | `CassandraMeasurementWriter.writeBatch` | ✅ **étape 7** |
| 4. Mettre à jour un cache/agrégat | table `latest_measurement_by_sensor` (D-09) — MongoDB = analytics (D-02) | ✅ **étape 7** |
| 5. Accuser le message (ACK) | ACK après `processBatch` sans exception | ✅ étape 4 |
| 6. Journaliser sans arrêter le consumer | catch + log ; reprise (XPENDING/XCLAIM) ; DLQ après 5 livraisons | ✅ étape 4 |

**Critère de validation (CDC étape 7)** : « une mesure envoyée au Collector est retrouvée dans
Cassandra » — prouvé par `critereValidation_mesurePubliee_estRetrouveeDansCassandra_puisAcquittee`
(publication `XADD` identique au collector → `StorageStreamConsumer` → les deux tables → `pending=0`).

## 2. Tests automatisés

```bash
mvn -B -pl storage-service -am test -Dtest=StorageIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Résultat : `BUILD SUCCESS` — **8 tests**, 0 échec (étape 5 + étape 7).

### Étape 7 (nouveaux)

| Test | Vérifie | Résultat |
|---|---|---|
| `critereValidation_mesurePubliee_estRetrouveeDansCassandra_puisAcquittee` | publication `XADD` (codec collector) → consumer auto-démarré → mesure dans `measurements_by_sensor_day` **et** `latest_measurement_by_sensor` → `pending=0` (ACK) | OK |
| `writeBatch_rejoue_neCreeQuUneLigne` (sc. 8) | rejeu du même événement → **1 seule ligne** ; `latest` cohérent | OK |
| `mesureEnRetard_nEcrasePasLaPlusRecente_usingTimestamp` (D-09) | mesure récente écrite, puis mesure **en retard** → `latest` garde la récente ; les 2 lignes restent dans l'historique | OK |
| `writeBatch_ometLesChampsAbsents_etEnregistreLesMetriques` (D-10) | champs absents → colonnes non écrites (relues `null`) ; Timer `energy_persist_duration_seconds` + Counter `energy_persist_errors_total` enregistrés | OK |

### Étape 5 (inchangés, rejoués)

`ecriturePuisLectureHistoriqueUnJour…`, `doublon_memeMessageRejoue…`, `perfLectureUnJour_sur10kLignes`
(médiane 51 ms, p95 63 ms cette exécution), `configurationCassandra_estLieeViaLePrefixeSpringCassandra`.

## 3. Vérifications directes sur la stack (cqlsh / redis-cli)

```text
-- table principale, partition complète (sensor_id + day) : les DEUX mesures de SENSOR-TS-1
sensor_id   | day        | measured_at               | state
SENSOR-TS-1 | 2026-11-02 | 2026-11-02 12:00:10+0000  | ON    <- plus récente
SENSOR-TS-1 | 2026-11-02 | 2026-11-02 12:00:00+0000  | OFF   <- en retard, NON écrasée dans "latest"

-- latest (D-09) : SENSOR-TS-1 -> 12:00:10 (la récente), malgré l'arrivée de 12:00:00 APRÈS
sensor_id    | measured_at                | power_kw
SENSOR-E2E-1 | 2026-11-01 10:00:00+0000   | 3.72
SENSOR-TS-1  | 2026-11-02 12:00:10+0000   | 3.72

-- D-10 : SENSOR-NULL-1 -> temperature/current_a/state absentes (jamais écrites en null)

-- Redis : storage-group, consumer actif, tout acquitté
xinfo groups energy-measurements -> pending=0, lag=0, entries-read=3, consumers=1
xpending energy-measurements storage-group -> 0
xlen energy-measurements-dlq -> 0
```

## 4. Décisions techniques de l'écriture (writer)

- **Idempotence** : chaque upsert est idempotent par clé primaire ; `eventId` déterministe (D-07)
  rend le rejeu transparent.
- **D-09 (`USING TIMESTAMP`)** : écriture `latest` avec temps d'**écriture** = temps d'**événement**
  (`measured_at` en µs) → une mesure en retard n'écrase jamais une plus récente (conflit résolu par
  le serveur Cassandra, pas par l'ordre d'arrivée).
- **D-10 (pas de tombstones)** : colonnes optionnelles absentes → `unset()` sur le `BoundStatement`
  (jamais `null` lié) → la valeur existante est conservée, aucune colonne creuse.
- **Débit** : `executeAsync` sur les deux tables + attente groupée (`CompletableFuture.allOf`) ;
  la première erreur se propage → pas d'ACK → re-tentative (sans danger, writer idempotent).
- **Pas de batch « logged »** entre les deux tables : deux upserts indépendants suffisent.

## 5. Notes d'environnement

- Redis recréé **avec** `--requirepass change-me` (aligné sur `spring.data.redis.password` et
  `REDIS_PASSWORD` du `.env`), volume `smartenergy_redis_data` conservé, port `6379` publié.
- Le consumer (`StorageStreamConsumer`) auto-démarre dans le contexte de test (SmartLifecycle) :
  il crée `storage-group` et consomme ; un vieux message résiduel (SENSOR-001, 2026-10-21) est
  consommé de façon inoffensive au démarrage (idempotent, autre jour/capteur que les données de test).
- MongoDB non sollicité à l'étape 7 (appartient à analytics-service, décision D-02).
