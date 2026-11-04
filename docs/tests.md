# Preuves de tests (matrice de recette)

À compléter au fil de l'avancement. Les scénarios 1 à 7 reprennent le tableau du guide ; 8 à 11 sont ajoutés par le cahier des charges.
Automatisation partielle : `make e2e` (`scripts/e2e.sh`).

| N° | Scénario | Résultat attendu | Preuve | Résultat obtenu |
|---|---|---|---|---|
| 1 | Mesure valide | HTTP 202 et entrée dans le stream | réponse + `XLEN` | À compléter |
| 2 | Mesure sans `sensorId` | HTTP 400 Problem Details, aucune entrée | réponse + `XLEN` inchangé | À compléter |
| 3 | Historique d'un capteur | Liste ordonnée, paginée, bornée par `from`/`to` | appel API | **OK (étapes 5 + 8)** : `StorageIntegrationTest.apiHistorique_*` — bornes `[from, to)`, ordre `desc`/`asc`, pagination sur 2 pages par curseur opaque (sans trou ni doublon), capteur sans donnée → 200 `items` vide, bornes invalides → 400 ; revérifié sur service réel (`preuves/tests/resultats-2026-10-27-api-etape8.md`) |
| 4 | Injection d'un pic ×3 | Anomalie `THRESHOLD` listée | `/anomalies` | **OK (étapes 8 + 9)** : le consumer détecte le pic (`AnalyticsIntegrationTest.scenario4_*`) → anomalie `THRESHOLD` `HIGH` (seuil `SENSOR`), visible `/anomalies`, métrique `energy_anomalies_detected_total{type=THRESHOLD}+1`, μ/σ de Welford **non pollués** ; preuve réelle `preuves/tests/resultats-2026-10-30-analytics-etape9ab.md` |
| 5 | Arrêt de storage-service pendant l'envoi | Messages conservés dans Redis (pending/lag en hausse) | `XINFO GROUPS` | OK (étape 4, consumer) + étape 7 : à l'arrêt, `storage-group` garde les messages (pending/lag) ; au redémarrage ils sont persistés dans Cassandra |
| 6 | Redémarrage de storage-service | Reprise, aucune mesure perdue (comptage Cassandra = mesures acceptées) | comptage | OK (étapes 4 + 7) : reprise du consumer (`pending=0`, `lag=0`) ; persistance Cassandra prouvée (`StorageIntegrationTest.critereValidation_*`), comptage égal aux mesures acceptées |
| 7 | Redémarrage complet de la plateforme | Données toujours présentes (volumes) | appels API | À compléter |
| 8 | Doublon | Une seule ligne Cassandra, compteur analytics inchangé | API + agrégat | **OK (étapes 5 + 7 + 8 + 9)** : rejeu d'une même clé primaire → 1 seule ligne (`StorageIntegrationTest`) ; côté analytics, l'évènement redélivré ne change ni compteurs ni anomalies (`AnalyticsIntegrationTest.scenario8_*` : `_id` déterministe `sensorId|eventId|type`, upsert `$setOnInsert`, `updatedAt` identique, clé `dedup:analytics` marquée TTL 24 h) |
| 9 | Redis arrêté | Collector répond 503 + `Retry-After` | réponse | À compléter |
| 10 | Message poison dans le stream | Envoyé en DLQ, le flux continue | `XLEN` de la DLQ | OK (étape 4, `AbstractStreamConsumerTest`) |
| 11 | sensor-service arrêté | Analytics traite avec le seuil par défaut | anomalie `thresholdSource = DEFAULT` | **OK (étape 9)** : `sensor-service` injoignable (port 8084 fermé) → `SensorGateway` bascule en repli (circuit breaker) → anomalie `THRESHOLD` au seuil **par défaut** (`AnalyticsIntegrationTest.scenario11_*`, `thresholdSource=DEFAULT`, 10,0 kW, `HIGH`) |

## Mesures de performance (étapes 5 et 11)

| Mesure | Méthode | Résultat | Commentaire |
|---|---|---|---|
| Lecture d'un jour d'historique (`limit` 1000) | `StorageIntegrationTest.perfLectureUnJour_sur10kLignes` (stack de l'app, 10 000 lignes : 5 capteurs × 4 jours × 500, lecture de 500 lignes) | médiane 48 ms, p95 60 ms (20 mesures, 2026-10-27) | cible p95 < 300 ms : **OK** |
| `GET /api/measurements/{id}` (API complète, `limit=1000` sur 500 lignes) | service réel `java -jar` + `curl` × 20 (`preuves/…-api-etape8.md`) | médiane 113 ms, p95 201 ms (2026-10-27, min 63 / max 610 dont 1ʳᵉ requête) | cible CDC p95 < 300 ms : **OK** |
| Débit soutenu du collector | À compléter | | cible ≥ 200 mesures/s |
| Retard de bout en bout (réception → Cassandra) | Pipeline actif depuis l'étape 7 (`StorageIntegrationTest.critereValidation_*` : `XADD` → consumer → tables → `pending=0`) | mesure fine : étape 11 | cible p95 < 5 s |

## Checklist avant remise

Reprendre la checklist du guide (section 28) : compilation sans modification manuelle, README clair, Docker Compose, simulateur,
requêtes invalides rejetées, messages visibles dans Redis, mesures dans Cassandra, capteurs/agrégats dans MongoDB, historique via API,
résultats analytiques vérifiables, scénarios de panne testés, secrets absents du dépôt, Swagger accessible.
