# Plan — Docker léger + Cassandra minimal pour présentation vendredi

## Objectif
Un environnement Docker léger et stable, adapté à un ordinateur de dev, qui fait tourner le minimum nécessaire au projet : **Cassandra (schéma + données) + les services qui en dépendent** (Storage → Collector → Redis). Éviter le rebuild lourd de l'image Maven multi-services à chaque démarrage.

## Contexte (état actuel)
- `docker-compose.yml` : Cassandra `cassandra:4`, `mem_limit: 1g`, `mem_reservation: 512m`, `CASSANDRA_MAX_HEAP=512M` / `HEAP_NEWSIZE=128M` (compose) ou `384M`/`96M` (.env).
- `cassandra-init` rejoue `scripts/init-cassandra.cql` (2 tables : `measurements_by_sensor_day`, `latest_measurement_by_sensor`).
- `storage-service` dépend de `cassandra-init` (condition `service_completed_successfully`).
- Build : Dockerfile unique, build-arg `SERVICE`, image `maven:3.9-eclipse-temurin-17` + `eclipse-temurin:17-jre`, lourd et lent.
- Pas de `.env.example` ; `.env` présent avec mots de passe par défaut.

## Décisions
1. **Fichier compose dédié léger** : `docker-compose.light.yml` (ne modifie pas le compose existant).
2. **Infra minimale** : uniquement `cassandra` + `redis` (+ `cassandra-init`). Mongo + analytics + sensor en dehors du périmètre "Cassandra minimal".
3. **Cassandra allégée** : `cassandra:4`, `mem_limit: 512m`, `mem_reservation: 256m`, `MAX_HEAP_SIZE=256M`, `HEAP_NEWSIZE=64M`, JVM GC sériel (`-XX:+UseSerialGC`), heap via `JVM_OPTS`.
4. **Redis allégé** : `redis:7`, `mem_limit: 128m`, `mem_reservation: 64m`, pas d'AOF (vitesse dev), persistence volume optionnelle.
5. **Pas de rebuild Maven à chaud** : les 4 services Spring Boot se lancent **en local** (JDK 17 / `./mvnw spring-boot:run`) pointant sur les conteneurs infra (Redis `localhost:6379`, Cassandra `localhost:9042`). Seul l'infra est dans Docker.
6. **Ports publiés** (dev) : Cassandra `9042`, Redis `6379`.
7. **`cassandra-init`** : conservé, idempotent, rejoué si volume cassandra_data vide.

## Tâches (ordre d'exécution)
1. Créer `.env.light` (ou réutiliser `.env`) avec :
   - `CASSANDRA_MAX_HEAP=256M`, `CASSANDRA_HEAP_NEWSIZE=64M`
   - `CASSANDRA_MEM_LIMIT=512m`, `CASSANDRA_MEM_RESERVATION=256m`
   - `REDIS_MEM_LIMIT=128m`, `REDIS_MEM_RESERVATION=64m`
   - Ports `CASSANDRA_PORT=9042`, `REDIS_PORT=6379`
   - `CASSANDRA_HOST=localhost`, `CASSANDRA_DC=datacenter1`
   - `REDIS_PASSWORD=change-me`
2. Créer `docker-compose.light.yml` :
   - services `redis`, `cassandra`, `cassandra-init`
   - limites mémoire réduites, healthchecks, volumes `cassandra_data`, `redis_data`
   - `cassandra-init` monte `scripts/init-cassandra.cql`, commande `cqlsh cassandra -f /init.cql`
3. Créer un script `start-light.sh` (ou ajouter à `infra/linux/se.sh`) :
   - `docker compose -f docker-compose.light.yml --env-file .env.light up -d`
   - attente `cassandra` healthy + `cassandra-init` exited 0
   - message : lancer les services Spring Boot en local avec `CASSANDRA_HOST=localhost REDIS_HOST=localhost`
4. Documenter dans le plan / README light comment lancer chaque service en local :
   - `cd collector-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.data.redis.host=localhost...`
   - idem storage-service (Cassandra `localhost:9042`)
5. Valider :
   - `docker compose -f docker-compose.light.yml ps` → cassandra healthy, redis healthy
   - `cqlsh localhost 9042 -e "describe keyspaces"` → `smartenergy`
   - `redis-cli -a change-me ping` → PONG
   - Un service Spring Boot démarre et rejoint Cassandra/Redis sans erreur

## Livrables
- `docker-compose.light.yml` (infra Cassandra + Redis légère)
- `.env.light` (variables allégées)
- `start-light.sh` (démarrage one-command)
- Note courte : comment lancer les services Spring Boot en local contre cette infra

## Validation (critères de succès)
- `docker compose -f docker-compose.light.yml up -d` réussit sans OOM
- `cassandra` passe `healthy` en < 2 min
- `cqlsh` accessible sur `localhost:9042`, keyspace `smartenergy` présent + 2 tables
- `redis-cli` accessible sur `localhost:6379`
- Un service Spring Boot (storage-service) démarre localement et écrit/lecture Cassandra OK

## Risques / questions ouvertes
- Lancer les 4 services Spring Boot en local nécessite JDK 17 + Maven sur la machine (vérifier `java -version`, `mvn -v`).
- Si la machine a < 4 Go RAM pour Docker, baisser encore `CASSANDRA_MAX_HEAP` à 192M.
- Le volume `cassandra_data` persiste : pour repartir propre, `docker compose -f docker-compose.light.yml down -v`.
- Le build complet des images (`docker compose up --build` sur le compose principal) reste hors périmètre de ce plan léger.
