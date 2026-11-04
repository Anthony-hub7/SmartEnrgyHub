# Simulateur Python (stdlib uniquement)

Aucune dependance externe requise (Python 3).

## Lancer

```bash
cd simulator
python3 simulator.py \
  --url http://localhost:8081/api/measurements \
  --sensors 5 --interval 2 --duration 120 \
  --pbase 3.0 --amplitude 1.5 --period 60 --noise 0.2 \
  --anomaly --anomaly-sensor SENSOR-003 --anomaly-start 30 --anomaly-duration 20 --anomaly-factor 3
```

## Paramètres

| Option | Variable d'env | Défaut | Rôle |
|---|---|---|---|
| `--url` | `SIMULATOR_COLLECTOR_URL` | `http://localhost:8081/api/measurements` | Cible collector |
| `--sensors` | `SIMULATOR_SENSORS` | `5` | Nombre de capteurs (SENSOR-001..N) |
| `--interval` | `SIMULATOR_INTERVAL` | `2` | Secondes entre deux envois par capteur |
| `--duration` | `SIMULATOR_DURATION` | `120` | Duree totale secondes (0 = sans fin) |
| `--pbase` | `SIMULATOR_PBASE` | `3.0` | Puissance de base kW |
| `--amplitude` | `SIMULATOR_AMPLITUDE` | `1.5` | Amplitude sinusoidale kW |
| `--period` | `SIMULATOR_PERIOD` | `60` | Periode sinusoidale secondes |
| `--noise` | `SIMULATOR_NOISE` | `0.2` | Ecart-type bruit gaussien kW |
| `--seed` | - | `42` | Graine aleatoire |
| `--anomaly` | `SIMULATOR_ANOMALY` | `false` | Activer pic de puissance |
| `--anomaly-sensor` | `SIMULATOR_ANOMALY_SENSOR` | `SENSOR-001` | Capteur cible de l'anomalie |
| `--anomaly-start` | `SIMULATOR_ANOMALY_START` | `30` | Debut anomalie secondes |
| `--anomaly-duration` | `SIMULATOR_ANOMALY_DURATION` | `20` | Duree anomalie secondes |
| `--anomaly-factor` | `SIMULATOR_ANOMALY_FACTOR` | `3.0` | Facteur multiplicateur puissance |

## Modele

P(t) = Pbase + A * sin(2*pi*t / T + phase) + bruit gaussien, puissance clampee dans [0, 1000] kW.
Lors de l'anomalie, puissance x facteur sur la fenetre [start, start+duration].

## Corps envoye (POST /api/measurements)

Conforme au contrat `EnergyMeasurementRequest` du collector :
`sensorId`, `timestamp` (ISO-8601 UTC), `powerKw`, `voltage`, `temperature`, `currentA`, `frequencyHz`, `state` (ON/OFF/STANDBY/FAULT).

## Codes de sortie

- 0 : aucune erreur (toutes les reponses 202 ou absence d'erreur reseau)
- 1 : au moins une erreur (5xx / exception reseau)

## Exemple d'execution (validation)

5 capteurs pendant 2 minutes, anomalie sur SENSOR-003 de la 30e a la 50e seconde (x3) :

```bash
python3 simulator.py \
  --sensors 5 --interval 2 --duration 120 \
  --anomaly --anomaly-sensor SENSOR-003 --anomaly-start 30 --anomaly-duration 20 --anomaly-factor 3
```

Resultat attendu : 280 requêtes envoyees, 280 acceptees (202), 0 rejetee, 0 erreur, et un pic x3 sur SENSOR-003.
