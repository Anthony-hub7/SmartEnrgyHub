# simulator

**Rôle** : source de données contrôlée (EF-01). Application en ligne de commande (sans serveur web) qui envoie, pour N capteurs,
une mesure par pas de temps au collector, puis affiche un résumé (envoyées / acceptées 202 / rejetées 4xx / erreurs).

Modèle : `P(t) = Pbase + A·sin(2πt/T + phase) + bruit gaussien` (phase et `Pbase` propres à chaque capteur, graine `seed` reproductible).

## Lancer
```bash
docker compose run --rm simulator                                   # 5 capteurs, 2 minutes
docker compose run --rm simulator \
  --smartenergy.simulator.anomaly.sensor=SENSOR-003 \
  --smartenergy.simulator.invalid-rate=0.05 --smartenergy.simulator.duplicate-rate=0.02
```

## Paramètres (`smartenergy.simulator.*`)
| Paramètre | Défaut | Rôle |
|---|---|---|
| `target-url` / `sensor-service-url` | collector :8081 / sensor :8084 | cibles |
| `register-sensors` | false (true dans Compose) | enregistre `SENSOR-001..N` dans sensor-service (409 ignoré) |
| `sensors`, `interval`, `duration` | 5, PT2S, PT2M | `PT0S` = sans fin |
| `seed` | 42 | reproductibilité |
| `p-base-kw`, `amplitude-kw`, `period-seconds`, `noise-sigma-kw` | 3.5, 1.0, 120, 0.1 | modèle de puissance (période comprimée) |
| `max-power-kw` | 8.0 | seuil des capteurs enregistrés (doit être < `factor` × `Pbase`) |
| `anomaly.sensor`, `at-seconds`, `duration-seconds`, `factor` | vide, 60, 10, 3.0 | pic injecté |
| `invalid-rate`, `duplicate-rate` | 0 | mesures invalides / doublons (scénarios 2 et 8) |
| `backfill-days`, `backfill-pace` | 0, 200 | mode backfill (jeu de données IA) : 0 = désactivé |

Un 4xx est compté « rejeté » sans réessai ; un 503 ou une erreur réseau est retenté 3 fois avec délai croissant.

## Mode backfill (génération du jeu de données pour la prévision, extension 9C)

Génère un **historique** en accéléré avec une horloge virtuelle : les timestamps des mesures
couvrent `[now − backfill-days, now]` (pas `interval` entre ticks), envoyés au rythme
`backfill-pace` ticks/s (par défaut 200) sans attendre le temps réel. La formule `P(t)` utilise
le temps virtuel, donc `period-seconds=86400` donne un cycle diurne réaliste.

```bash
# 30 jours d'historique, 1 mesure toutes les 5 min, cycle diurne, 5 capteurs (~43 200 envois)
SIMULATOR_BACKFILL_DAYS=30 SIMULATOR_INTERVAL=PT5M \
  docker compose run --rm simulator --smartenergy.simulator.period-seconds=86400
```

**Prérequis** : le collector rejette par défaut les mesures de plus de 24 h (règle CDC 5.2).
Pour un backfill, surcharger temporairement dans `.env` :

```bash
MEASUREMENT_MAX_AGE=P31D   # >= backfill-days + marge ; ISO-8601 : P31D = 31 jours (PT31D est invalide) ; remettre PT24H ensuite
```

Le backfill alimente toute la chaîne réelle : `hourly_aggregates` (MongoDB, niveau A) devient
la série horaire d'entraînement du pipeline `ml/` (voir `ml/README.md`).
