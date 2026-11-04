# Rapport de tests — Redis Streams (étape 4)

- **Date** : 2026-10-26
- **Environnement** : Linux (Ubuntu), Docker 28.2.2, OpenJDK 17, Maven
- **Module** : `platform-stream`

## 1. Tests automatisés

```bash
mvn -B -pl platform/platform-stream -am test
```

Résultat : `BUILD SUCCESS` — **6 tests**, 0 échec (`AbstractStreamConsumerTest`, Testcontainers `redis:7`).

| Test | Résultat |
|---|---|
| Création idempotente de `storage-group` (`XGROUP CREATE … 0 MKSTREAM`, `BUSYGROUP` ignoré) | OK |
| Mesure valide consommée puis **ACK** (pending = 0) | OK |
| Évènement illisible → `energy-measurements-dlq` + le flux continue | OK |
| Arrêt du consumer, 5 publications, relance → 5 traités, lag/pending = 0 | OK |
| Échec de `processBatch` → pas d'ACK, retry après idle | OK |
| `maxDeliveries` dépassé → DLQ + ACK | OK |

## 2. Critère de validation (guide 12.2)

Aucune mesure produite pendant l'arrêt du consommateur ne disparaît : elles restent dans `energy-measurements` (lag du groupe), puis sont lues au redémarrage.

La persistance Cassandra et le `@Component` `StorageStreamConsumer` restent l'étape 7 : ACK **métier** = après écriture Cassandra, via `processBatch`.
