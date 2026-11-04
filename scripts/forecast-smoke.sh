#!/usr/bin/env bash
# Smoke test de la route de prevision (extension 9C) — voir docs/forecast-niveau-c.md.
#
# Verifie, pour un capteur et un horizon donnes :
#   - HTTP 200 (le module IA est actif et le modele charge) ;
#   - nombre de points = horizonHours ;
#   - heures consecutives, commencant apres la derniere heure connue de hourly_aggregates ;
#   - puissances >= 0 et finies ;
#   - metrics MAE / RMSE presentes.
#
# Utilise python3 (et non jq, absent de la machine) pour le parsing JSON.
#
# Usage :
#   scripts/forecast-smoke.sh                       # SENSOR-001, horizon 6
#   scripts/forecast-smoke.sh SENSOR-003 12
#   scripts/forecast-smoke.sh SENSOR-001 6 http://localhost:8083
set -euo pipefail

SENSOR="${1:-SENSOR-001}"
HORIZON="${2:-6}"
BASE_URL="${3:-http://localhost:8083}"

py() { python3 - "$@"; }

URL="${BASE_URL}/api/analytics/${SENSOR}/forecast?horizonHours=${HORIZON}"
echo "== GET ${URL}"

BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT
HTTP_CODE="$(curl -s -o "$BODY" -w '%{http_code}' --max-time 30 "$URL" || echo 000)"
echo "   HTTP ${HTTP_CODE}"

if [ "$HTTP_CODE" != "200" ]; then
  echo "   ECHEC : code attendu 200, recu ${HTTP_CODE}"
  echo "   corps :"
  sed 's/^/     /' "$BODY"
  echo
  exit 1
fi

py "$BODY" "$HORIZON" "$SENSOR" <<'PY'
import json, sys

body = json.load(open(sys.argv[1]))
horizon = int(sys.argv[2])
sensor = sys.argv[3]
errors = []

if body.get("sensorId") != sensor:
    errors.append(f"sensorId = {body.get('sensorId')}, attendu {sensor}")
if body.get("horizonHours") != horizon:
    errors.append(f"horizonHours = {body.get('horizonHours')}, attendu {horizon}")

points = body.get("points")
if not isinstance(points, list):
    errors.append("champ 'points' absent ou non-liste")
    points = []
if len(points) != horizon:
    errors.append(f"nb points = {len(points)}, attendu {horizon}")

hours = []
for i, p in enumerate(points):
    power = p.get("powerKw")
    hour = p.get("hour")
    if not isinstance(power, (int, float)):
        errors.append(f"point {i} : powerKw absent/non-numerique")
        continue
    if power != power:                      # NaN
        errors.append(f"point {i} : powerKw = NaN")
    elif power < 0:
        errors.append(f"point {i} : powerKw negatif ({power})")
    if not hour:
        errors.append(f"point {i} : hour absente")
    else:
        hours.append(hour)

# heures consecutives a 1 h d'ecart
from datetime import datetime, timedelta
try:
    ts = [datetime.fromisoformat(h.replace("Z", "+00:00")) for h in hours]
    for i in range(1, len(ts)):
        if ts[i] - ts[i - 1] != timedelta(hours=1):
            errors.append(f"heures non consecutives a l'indice {i} : "
                          f"{hours[i-1]} -> {hours[i]}")
except ValueError as exc:
    errors.append(f"heure illisible : {exc}")

metrics = body.get("metrics") or {}
for key in ("mae", "rmse"):
    value = metrics.get(key)
    if not isinstance(value, (int, float)):
        errors.append(f"metrics.{key} absent")
    elif value != value:
        errors.append(f"metrics.{key} = NaN")

if errors:
    print("   ECHEC :")
    for e in errors:
        print(f"     - {e}")
    sys.exit(1)

print(f"   OK : {len(points)} points, {hours[0] if hours else '?'} -> "
      f"{hours[-1] if hours else '?'}")
print(f"   puissance : min={min(p['powerKw'] for p in points):.3f} kW, "
      f"max={max(p['powerKw'] for p in points):.3f} kW")
print(f"   metrics : mae={metrics['mae']:.4f}, rmse={metrics['rmse']:.4f}")
PY
