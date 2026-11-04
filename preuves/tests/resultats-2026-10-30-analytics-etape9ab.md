# Rapport de tests — Analytics niveaux A et B (étape 9)

- **Date** : 2026-10-30
- **Environnement** : Linux (Ubuntu), Docker (Redis 7.4, MongoDB 7, Cassandra 4.1), OpenJDK 17, Maven
- **Module** : `analytics-service` (+ `contracts`, `platform-stream`)
- **Périmètre** : CDC §17.1 (niveau A — calcul descriptif), §17.2 (niveau B — détection d'anomalies),
  en préparant le niveau C (`docs/forecast-niveau-c.md`, §17.3-§17.4/§18).

## 1. Tests automatisés

```bash
mvn -B -pl storage-service,analytics-service -am test
```

Résultat : `BUILD SUCCESS` — **41 tests**, 0 échec, aucune régression.

| Module | Tests | Détail |
|---|---|---|
| `contracts` | 11 | codec + ids d'évènement |
| `platform-stream` | 6 | consumer de base (group, ACK, reprise, DLQ) |
| `storage-service` | 16 | Cassandra + perf rejouée (médiane 69 ms, p95 76 ms) |
| `analytics-service` | **24** | **8 règles pures + 7 intégration A/B + 9 lecture (étape 8)** |

Focus analytics (24 tests) :

| Classe | Tests | Ce qu'elle prouve |
|---|---|---|
| `AnalyticsRulesTest` | 8 | Formules (2)/(3)/(4) **vérifiables à la main** (voir §2) |
| `AnalyticsIntegrationTest` | 7 | Boucle complète stream → consumer → Mongo → API (sc. 4, 8, 11) |
| `AnalyticsQueryIntegrationTest` | 9 | Lectures summary/daily/anomalies (étape 8, inchangées) |

## 2. Niveau A — calcul descriptif (CDC §17.1)

Règles **pures** (`AnalyticsRules`), sans Spring ni base — testées unitairement :

| Indicateur (obligatoire) | Test de référence | Résultat |
|---|---|---|
| Énergie `E = Σ(Pi × Δt)` (formule 2) | 4 kW constants sur 1 h (1800 pas de 2 s) | **4,0 kWh** exactement |
| Plafond des trous | trou de 10 min (600 s) | Δt plafonné à **10 s** (2 s × gapFactor 5) |
| Première mesure / rejeu / retard | sans pas précédent, ou `ts ≤ base` | **0,0** (jamais de double compte) |
| Moyenne / min / max / count | 3 mesures (4,0 / 4,5 / 5,0) | count 3, somme 13,5, min 4,0, max 5,0, moyenne 4,5 |

Écrits dans `hourly_aggregates` **et** `daily_aggregates` **et** `sensor_state`, puis **relus via
les routes de l'étape 8** (`/summary`, `/daily-summary`) : moyenne pondérée `sumPowerKw/count`
recalculée à la lecture = 4,5 ✓.

## 3. Niveau B — anomalies (CDC §17.2)

| Règle | Test | Résultat |
|---|---|---|
| Seuil **strict** (formule 3) | 10,01 > 10,0 → vrai ; 10,0 = 10,0 → **faux** | OK |
| Score z (formule 4) | [1..5] → mean 3, σ 1,5811, **z(10) = 4,427** | OK |
| `minSamples` (30) | n = 5 → `zScore = null` (pas de score) | OK |
| σ nul (serie constante) | 40 × 4,0 → σ = 0 → `zScore = null` (pas de division par 0) | OK |
| Welford immuable | `add()` renvoie un nouvel état (n, mean, m2 intacts) | OK |

Sévérités : `THRESHOLD` → **HIGH**, `ZSCORE` → **MEDIUM** (HUD attendu sc. 4).

## 4. Scénarios du CDC (docs/tests.md)

| Sc. | Objectif | Résultat |
|---|---|---|
| **4** | Pic de puissance (×3-8 du nominal, > seuil capteur) → anomalie `THRESHOLD` listée | **OK** — anomalie `HIGH`, `thresholdSource=SENSOR`, visible `/anomalies`, métrique `+1`, Welford non pollué (n = 31 sur 32) |
| **8** | Évènement dupliqué (redelivrance) → compteurs **et** anomalies inchangés | **OK** — `_id` déterministe `sensorId|eventId|type` + upsert `$setOnInsert` total ; `updatedAt` identique ; `dedup:analytics:<eventId>` marqué (TTL 24 h) |
| **11** | `sensor-service` arrêté → traitement au **seuil par défaut** | **OK** — `thresholdSource=DEFAULT`, 10,0 kW, anomalie `HIGH` créée |

Métrique du CDC : `energy_anomalies_detected_total{type=…}` incrémentée uniquement pour les
anomalies **fraîches** (`getUpsertedId() != null`) — pas de double comptage en rejeu.

## 5. Preuve croisée (critère : count du résumé = mesures du stockage)

Services **réels** démarrés (storage `:8082`, analytics `:8083`) sur la stack de l'app. Publication
de **6 mesures réelles** dans `energy-measurements` (5 nominales 4,0 kW + 1 pic 32,0 kW à
`2026-10-30T09:00:10Z`, seuil capteur seedé à `10.0|true`) :

| Source | Requête | Résultat |
|---|---|---|
| **Storage** (Cassandra) | `GET :8082/api/measurements/SENSOR-9CROSS?from=…09:00Z&to=…10:00Z&limit=100` | **items = 6** |
| **Analytics** (Mongo) | `GET :8083/api/analytics/SENSOR-9CROSS/summary?from&to` | **count = 6** ✓ identique |

Détail du résumé horaire (contrôlé à la main) :

```json
{ "count": 6, "avgPowerKw": 8.6667, "minPowerKw": 4.0, "maxPowerKw": 32.0,
  "energyKwh": 0.011111, "thresholdKw": 10.0, "thresholdExceededCount": 1, "anomalyCount": 1 }
```

- `avgPowerKw = 8,6667` = (5 × 4,0 + 32,0) / 6 = 52 / 6 ✓
- `energyKwh = 0,011111` = 5 pas × 4,0 kW × 2 s / 3600 = 40/3600 ✓ (le pic est en dernière position : aucun pas suivant)
- `anomalies` → **1 THRESHOLD**, `_id` = `SENSOR-9CROSS|<eventId>|THRESHOLD`, `severity=HIGH`, `thresholdSource=SENSOR` ✓
- `daily-summary?date=2026-10-30` → mêmes agrégats (heure unique) ✓

Latence `GET /summary` (×20 après échauffement) : **médiane 13,08 ms, p95 19,30 ms**
(min 8,92 / max 20,87).

## 6. Limites documentées (critère CDC : expliquer les limites)

- **Énergie** : incrément `E = P_prev × Δt` attribué à l'heure/jour **UTC de la mesure courante**
  (D-9a) → biais d'ordre **2 s** aux frontières d'heure.
- **Welford cumulé** (pas de fenêtre glissante) : un profil lentement croissant est suivi avec
  retard ; un profil sinusoidal gonfle σ (mesures nominales paraissent moins « loin »).
- **μ/σ non pollués** : les mesures anormales ne mettent **pas** à jour Welford ; en revanche
  `lastEnergyBasisTs`/`lastPowerKw` avancent pour **toute** mesure plus récente (sinon l'énergie
  serait comptée deux fois).
- **Reprise après panne** : entre l'upsert d'agrégat (`$inc`) et le marquage du dedup, une panne peut
  compter une mesure **deux fois** dans les agrégats — biais **négligible** et assumé (CDC 6.5) ; les
  **anomalies** restent uniques grâce à leur `_id` déterministe.
- **Seuil** : comparaison **strictement supérieure** ; un `Pt` exactement égal au seuil n'est pas un
  dépassement (choix assumé, testé).

## 7. Niveau C — préparation (aucune IA implémentée)

Conformément au CDC (« après validation des niveaux précédents »), **aucun modèle n'est entraîné ni
intégré** : `GET /forecast` reste `501 FORECAST_DISABLED`. La **démarche** (§17.3 : préparation des
données, découpage chronologique, baseline à battre, MAE/RMSE ; §17.4/§18 : export ONNX/DJL, limites)
est figée dans `docs/forecast-niveau-c.md`.
