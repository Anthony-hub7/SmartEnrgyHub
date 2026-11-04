# Rapport de tests — Cassandra séries temporelles (étape 5)

- **Date** : 2026-10-27
- **Environnement** : Linux (Ubuntu), Docker 28.2.2, OpenJDK 17.0.18, Maven 3.8.7
- **Cible** : stack `docker-compose` de l'application — `smartenergy-cassandra` (cassandra:4.1,
  `localhost:9042` via `docker-compose.override.yml`), **pas** de conteneur dédié au test
- **Module** : `storage-service`

## 1. Tests automatisés

```bash
mvn -B -pl storage-service -am test -Dtest=StorageIntegrationTest
```

Résultat : `BUILD SUCCESS` — **4 tests**, 0 échec.

| Test | Vérifie | Résultat |
|---|---|---|
| `ecriturePuisLectureHistoriqueUnJour_ordonneDESC_etBornesClustering` | écriture d'entités puis lecture d'**une** journée : exactement les lignes du jour (jamais les autres partitions), ordre `measured_at DESC` par clustering, bornes `[from, to)` (from inclus / to exclu), valeurs conservées, champs optionnels absents relus `null` (D-10, colonnes omises) | OK |
| `doublon_memeMessageRejoue_neCreeQuUneLigne` | rejeu de la même mesure (même clé primaire) → **1 seule ligne** (upsert idempotent par PK) | OK |
| `perfLectureUnJour_sur10kLignes` | ~10 000 lignes (5 capteurs × 4 jours × 500) + 20 lectures chronométrées d'une journée | OK |
| `configurationCassandra_estLieeViaLePrefixeSpringCassandra` | garde-fou du préfixe `spring.cassandra.*` (voir §4) + colonnes serveur conformes à l'entité | OK |

Chaque exécution **rejoue `scripts/init-cassandra.cql`** (idempotent, comme `cassandra-init`
du compose) puis **vérifie les colonnes réelles** : un schéma obsolète échoue avec un message
d'aide explicite (`DROP KEYSPACE smartenergy` + rejeu du script) au lieu d'une erreur obscure
au premier INSERT.

## 2. Mesures de performance (livrable « temps de réponse sur un jeu plus important »)

| Mesure | Valeur (2026-10-27) | Cible |
|---|---|---|
| Insertion de 10 000 lignes (repository, en ligne) | 21,4 s | — |
| Lecture d'un jour (500 lignes) — médiane | **62 ms** | — |
| Lecture d'un jour (500 lignes) — p95 | **81 ms** | p95 < 300 ms : **OK** |

Conditions : 5 capteurs × 4 jours × 500 mesures (pas de 172,8 s), 3 lectures de chauffe puis
20 mesures, Cassandra local (`localhost:9042`). Relevé consigné aussi dans `docs/tests.md`.

## 3. Critère de validation (guide 13.1) — choix de la clé de partition

```sql
PRIMARY KEY ((sensor_id, day), measured_at)  CLUSTERING ORDER BY (measured_at DESC)
```

- **Pourquoi `(sensor_id, day)` et pas `sensor_id` seul** : avec une mesure toutes les 2 s,
  un capteur génère ≈ 43 200 lignes/jour. Une partition par capteur seule accumulerait
  plusieurs années de mesures dans **une** partition (illisible, mémoire du cache partition,
  compaction difficile) ; le couple `(sensor_id, day)` **borne la partition à une journée**,
  taille maîtrisée même pour un capteur très actif.
- **`measured_at` en clustering DESC** : la lecture « historique d'un capteur entre deux
  dates » renvoie les mesures les plus récentes d'abord, **sans tri applicatif**, et les
  bornes `measured_at >= ? AND < ?` sont un simple filtre de clustering.
- **Requêtes** : UNE requête par jour de la plage (`sensor_id` + `day` = clé de partition
  complète), jamais de `IN` sur une clé de partition, jamais de `ALLOW FILTERING` —
  le service d'historique (étape 8) fusionnera les jours.
- **TWCS** (fenêtre d'un jour, déjà dans le script) : aligné sur la clé de partition,
  purge propre des anciennes fenêtres.
- Table `latest_measurement_by_sensor` (clé simple) : lecture O(1) de la dernière mesure
  (étape 8), écriture `USING TIMESTAMP` = temps d'événement (D-09, étape 7).

## 4. Corrections notables rencontrées pendant l'étape

1. **Schéma obsolète de la stack** : la table `measurements_by_sensor_day` existait avec un
   ancien modèle (`event_id text` inclus dans la clé de clustering, colonnes `current_a`,
   `frequency_hz`, `state` absentes — `CREATE ... IF NOT EXISTS` ne corrige jamais une table
   existante). Correction : `DROP KEYSPACE smartenergy` + rejeu de `scripts/init-cassandra.cql`.
   Le test vérifie désormais les colonnes avant tout INSERT et rappelle la procédure.
2. **Préfixe de configuration déprécié** : `application.yml` utilisait `spring.data.cassandra.*`,
   **déprécié depuis Spring Boot 3.4** (remplacement officiel : `spring.cassandra.*`). Migration
   effectuée + test de non-régression ajouté (le préfixe non lié laisserait silencieusement les
   valeurs par défaut).
3. **Tombstones (D-10)** : le mapper Spring Data omet les propriétés `null` à l'INSERT
   (`insertNulls` désactivé par défaut) — testé : une mesure sans `temperature`/`current_a`/
   `frequency_hz`/`state` est relue avec ces champs `null`, sans colonne creuse écrite.

## 5. Environnement / mémoire (demandé avec l'étape)

- Tous les conteneurs étrangers à SmartEnergy ont été supprimés ; seuls `smartenergy-cassandra`,
  `smartenergy-redis`, `smartenergy-mongodb` tournent.
- Limites mémoire **câblées pour de vrai** dans `docker-compose.yml` depuis `.env`
  (elles existaient dans `.env` mais n'étaient référencées nulle part) : cassandra 1 g,
  mongo 512 m, redis (descendu à 64 m en live) ; services Java : `SPRING_MEM_LIMIT=512 m`
  + `JAVA_OPTS` (`MaxRAMPercentage=60`, GC leger) désormais effectifs via le `Dockerfile`.
- Elles s'appliqueront au prochain `docker compose up -d` de l'utilisateur (aucune
  recréation de conteneur imposée ici — l'empilement `cassandra:4.1` en cours est conservé).
