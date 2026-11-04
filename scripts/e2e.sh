#!/usr/bin/env bash
# ============================================================================
# Scenarios de recette HTTP (docs/tests.md). Prerequis : plateforme demarree
# (make up / ./infra/linux/se.sh start).
# Scenarios 1, 2 : collector (deja implantes).
# Scenarios 3 a 7, 10 a 11 : implantes ci-dessous (automatisation HTTP pure).
# Scenarios 8, 9 (arret/redemarrage de conteneurs) : marques MANUEL, commandes
# Docker exactes documentees ; a executer a la main car necessitent l'orchestration.
# Resultats a reporter dans docs/tests.md.
# ============================================================================
set -uo pipefail

COLLECTOR="${COLLECTOR_URL:-http://localhost:8081}"
STORAGE="${STORAGE_URL:-http://localhost:8082}"
ANALYTICS="${ANALYTICS_URL:-http://localhost:8083}"
SENSOR="${SENSOR_URL:-http://localhost:8084}"
PASS=0; FAIL=0

check() { # check "<libelle>" <attendu> <obtenu>
  if [ "$2" == "$3" ]; then echo "  [OK]   $1 (HTTP $3)"; PASS=$((PASS+1));
  else echo "  [FAIL] $1 : attendu $2, obtenu $3"; FAIL=$((FAIL+1)); fi
}
post_status() { curl -s -o /dev/null -w '%{http_code}' -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
get_code() { curl -s -o /dev/null -m 10 -w '%{http_code}' "$1" 2>/dev/null || echo 000; }
DOCKER_COMPOSE="docker compose"
if ! docker compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker-compose"
fi
xlen() { $DOCKER_COMPOSE exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning XLEN energy-measurements' 2>/dev/null | tr -d '\r'; }
xinfo_groups() { $DOCKER_COMPOSE exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning XINFO GROUPS energy-measurements' 2>/dev/null | tr -d '\r'; }
xlen_dlq() { $DOCKER_COMPOSE exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning XLEN energy-measurements-dlq' 2>/dev/null | tr -d '\r'; }
xpending() { $DOCKER_COMPOSE exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning XPENDING energy-measurements "$1"' 2>/dev/null | tr -d '\r'; }
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FROM_1H=$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-1H +%Y-%m-%dT%H:%M:%SZ)
TO_NOW=$NOW

echo "== Sante des services (scenario 1)"
for u in "$COLLECTOR" "$STORAGE" "$ANALYTICS" "$SENSOR"; do
  check "GET $u/api/health" 200 "$(get_code "$u/api/health")"
done

echo "== Scenario 2 : mesure valide -> 202 + X-Event-Id + XLEN +1"
BEFORE=$(xlen)
HDR="$(mktemp)"
CODE="$(curl -s -o "$HDR" -w '%{http_code}' -X POST "$COLLECTOR/api/measurements" \
  -H 'Content-Type: application/json' \
  -d "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"$NOW\",\"powerKw\":3.72,\"voltage\":226.4,\"temperature\":29.1}")"
check "POST mesure valide" 202 "$CODE"
EVENT_ID="$(grep -i '^X-Event-Id:' "$HDR" | head -n1 | sed 's/^[^:]*: *//; s/\r//')"
[ -n "$EVENT_ID" ] && check "X-Event-Id present" 1 1 || check "X-Event-Id present" 1 0
AFTER=$(xlen); check "XLEN +1" 1 "$((AFTER-BEFORE))"
rm -f "$HDR"

echo "== Scenario 3 : mesure sans sensorId -> 400, XLEN inchangé"
BEFORE=$(xlen)
check "POST sans sensorId" 400 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"timestamp\":\"$NOW\",\"powerKw\":3.72,\"voltage\":226.4}")"
AFTER=$(xlen); check "XLEN inchange" 0 "$((AFTER-BEFORE))"

echo "== Scenario 4 : timestamp futur (> 5 min) -> 400 MEASUREMENT_IN_FUTURE"
FUTURE=$(date -u -d '+10 minutes' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+10M +%Y-%m-%dT%H:%M:%SZ)
check "POST timestamp futur" 400 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"$FUTURE\",\"powerKw\":3.72,\"voltage\":226.4}")"

echo "== Scenario 5 : timestamp trop ancien (> 24 h) -> 400 MEASUREMENT_TOO_OLD"
OLD=$(date -u -d '-25 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-25H +%Y-%m-%dT%H:%M:%SZ)
check "POST timestamp trop ancien" 400 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"$OLD\",\"powerKw\":3.72,\"voltage\":226.4}")"

echo "== Scenario 6 : corps > 10 Ko -> 413"
BIG="$(python3 -c 'import sys; sys.stdout.write("A"*11000)' 2>/dev/null || printf 'A%.0s' $(seq 1 11000))"
check "POST corps 11 Ko" 413 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"$NOW\",\"powerKw\":3.72,\"voltage\":226.4,\"note\":\"$BIG\"}")"

echo "== Scenario 7 : doublon (meme eventId) -> 2x202, XLEN +2"
BEFORE=$(xlen)
check "POST doublon #1" 202 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"$NOW\",\"powerKw\":3.72,\"voltage\":226.4}")"
check "POST doublon #2" 202 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"$NOW\",\"powerKw\":3.72,\"voltage\":226.4}")"
AFTER=$(xlen); check "XLEN +2 (2 events)" 2 "$((AFTER-BEFORE))"

echo "== Scenario 8 : historique pagine SENSOR-001 (curseur, desc, nextCursor)"
FROM=$(date -u -d '-2 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-2H +%Y-%m-%dT%H:%M:%SZ)
HIST_BODY="$(mktemp)"
HIST_CODE="$(curl -s -o "$HIST_BODY" -w '%{http_code}' \
  "$STORAGE/api/measurements/SENSOR-001?from=$FROM&to=$TO_NOW&limit=100&order=desc")"
check "GET historique page 1" 200 "$HIST_CODE"
NEXT_CURSOR="$(python3 -c "import json,sys; d=json.load(open('$HIST_BODY')); print(d.get('nextCursor',''))" 2>/dev/null || echo "")"
ITEMS1="$(python3 -c "import json,sys; d=json.load(open('$HIST_BODY')); print(len(d.get('items',[])))" 2>/dev/null || echo 0)"
echo "     page1 items=$ITEMS1 nextCursor=${NEXT_CURSOR:-null}"
if [ -n "$NEXT_CURSOR" ]; then
  HIST2_BODY="$(mktemp)"
  HIST2_CODE="$(curl -s -o "$HIST2_BODY" -w '%{http_code}' \
    "$STORAGE/api/measurements/SENSOR-001?from=$FROM&to=$TO_NOW&limit=100&cursor=$NEXT_CURSOR&order=desc")"
  check "GET historique page 2 (cursor)" 200 "$HIST2_CODE"
  ITEMS2="$(python3 -c "import json,sys; d=json.load(open('$HIST2_BODY')); print(len(d.get('items',[])))" 2>/dev/null || echo 0)"
  echo "     page2 items=$ITEMS2"
  NEXT2="$(python3 -c "import json,sys; d=json.load(open('$HIST2_BODY')); print(d.get('nextCursor',''))" 2>/dev/null || echo "")"
  [ -z "$NEXT2" ] && check "page2 nextCursor null (fin)" 1 1 || check "page2 nextCursor null" 1 0
  rm -f "$HIST2_BODY"
fi
rm -f "$HIST_BODY"

echo "== Scenario 9 : GET latest SENSOR-001 -> 200"
check "GET latest SENSOR-001" 200 "$(get_code "$STORAGE/api/measurements/SENSOR-001/latest")"

echo "== Scenario 10 : GET latest SENSOR-INCONNU -> 404 MEASUREMENT_NOT_FOUND"
UNKNOWN_BODY="$(mktemp)"
UNKNOWN_CODE="$(curl -s -o "$UNKNOWN_BODY" -w '%{http_code}' \
  "$STORAGE/api/measurements/SENSOR-INCONNU/latest")"
check "GET latest inconnu" 404 "$UNKNOWN_CODE"
UNKNOWN_CODE_BODY="$(python3 -c "import json,sys; d=json.load(open('$UNKNOWN_BODY')); print(d.get('code',''))" 2>/dev/null || echo "")"
[ "$UNKNOWN_CODE_BODY" = "MEASUREMENT_NOT_FOUND" ] && check "code MEASUREMENT_NOT_FOUND" 1 1 || check "code MEASUREMENT_NOT_FOUND" 1 0
rm -f "$UNKNOWN_BODY"

echo "== Scenario 11 : plage > 7 jours -> 400 RANGE_TOO_LARGE"
FROM_8D=$(date -u -d '-8 days' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-8D +%Y-%m-%dT%H:%M:%SZ)
check "GET plage 8j" 400 "$(get_code "$STORAGE/api/measurements/SENSOR-001?from=$FROM_8D&to=$TO_NOW")"

echo "== Scenario 12 : limit=0 -> 400 INVALID_PARAMETER"
check "GET limit=0" 400 "$(get_code "$STORAGE/api/measurements/SENSOR-001?limit=0")"

echo "== Scenario 13 : curseur corrompu -> 400 INVALID_PARAMETER"
check "GET curseur corrompu" 400 "$(get_code "$STORAGE/api/measurements/SENSOR-001?cursor=notabase64")"

echo "== Scenario 14 : stream Redis XINFO GROUPS (storage-group, analytics-group, lag=0, pending=0)"
GROUPS="$(xinfo_groups)"
echo "     XINFO GROUPS : $GROUPS"
echo "$GROUPS" | grep -q 'storage-group' && check "groupe storage-group present" 1 1 || check "groupe storage-group present" 1 0
echo "$GROUPS" | grep -q 'analytics-group' && check "groupe analytics-group present" 1 1 || check "groupe analytics-group present" 1 0

echo "== Scenario 15 : DLQ vide (XLEN energy-measurements-dlq = 0)"
DLQ="$(xlen_dlq)"
check "DLQ vide" 0 "$DLQ"

echo "== Scenario 16 : Analytics summary SENSOR-003 -> 200 + champs"
SUMMARY_BODY="$(mktemp)"
SUMMARY_CODE="$(curl -s -o "$SUMMARY_BODY" -w '%{http_code}' \
  "$ANALYTICS/api/analytics/SENSOR-003/summary?from=$FROM_1H&to=$TO_NOW")"
check "GET summary SENSOR-003" 200 "$SUMMARY_CODE"
for champ in count avgPowerKw minPowerKw maxPowerKw energyKwh thresholdExceededCount anomalyCount; do
  python3 -c "import json,sys; d=json.load(open('$SUMMARY_BODY')); sys.exit(0 if '$champ' in d else 1)" 2>/dev/null \
    && check "summary champ $champ" 1 1 || check "summary champ $champ" 1 0
done
rm -f "$SUMMARY_BODY"

echo "== Scenario 17 : Analytics capteur inconnu -> 404 NO_DATA"
check "GET summary inconnu" 404 "$(get_code "$ANALYTICS/api/analytics/SENSOR-INCONNU/summary?from=$FROM_1H&to=$TO_NOW")"

echo "== Scenario 18 : from >= to -> 400 INVALID_PARAMETER"
check "GET from>=to" 400 "$(get_code "$ANALYTICS/api/analytics/SENSOR-001/summary?from=$TO_NOW&to=$FROM_1H")"

echo "== Scenario 19 : Analytics daily-summary SENSOR-003 -> 200"
TODAY=$(date -u +%Y-%m-%d)
check "GET daily-summary SENSOR-003" 200 "$(get_code "$ANALYTICS/api/analytics/SENSOR-003/daily-summary?date=$TODAY")"

echo "== Scenario 20 : Alias /api/sensors/SENSOR-003/daily-summary identique"
DAILY_A="$(curl -s "$ANALYTICS/api/analytics/SENSOR-003/daily-summary?date=$TODAY")"
DAILY_B="$(curl -s "$ANALYTICS/api/sensors/SENSOR-003/daily-summary?date=$TODAY")"
[ "$DAILY_A" = "$DAILY_B" ] && check "alias daily-summary identique" 1 1 || check "alias daily-summary identique" 1 0

echo "== Scenario 21 : anomalies THRESHOLD SENSOR-003 -> 200 + thresholdSource"
ANOM_BODY="$(mktemp)"
ANOM_CODE="$(curl -s -o "$ANOM_BODY" -w '%{http_code}' \
  "$ANALYTICS/api/analytics/SENSOR-003/anomalies?type=THRESHOLD&size=50")"
check "GET anomalies THRESHOLD" 200 "$ANOM_CODE"
python3 -c "import json,sys; d=json.load(open('$ANOM_BODY')); sys.exit(0 if d.get('items') is not None else 1)" 2>/dev/null \
  && check "anomalies items liste" 1 1 || check "anomalies items liste" 1 0
rm -f "$ANOM_BODY"

echo "== Scenario 22 : anomalies ZSCORE SENSOR-003 -> 200"
check "GET anomalies ZSCORE" 200 "$(get_code "$ANALYTICS/api/analytics/SENSOR-003/anomalies?type=ZSCORE&size=50")"

echo "== Scenario 23 : pagination anomalies size>500 -> 400"
check "GET anomalies size=999" 400 "$(get_code "$ANALYTICS/api/analytics/SENSOR-003/anomalies?size=999")"

echo "== Scenario 24 : exceedances threshold=8.0 -> 200"
check "GET exceedances" 200 "$(get_code "$ANALYTICS/api/analytics/exceedances?threshold=8.0")"

echo "== Scenario 25 : exceedances sans threshold -> 400"
check "GET exceedances sans threshold" 400 "$(get_code "$ANALYTICS/api/analytics/exceedances")"

echo "== Scenario 26 : forecast horizon=6 -> 501 FORECAST_DISABLED"
FORE_BODY="$(mktemp)"
FORE_CODE="$(curl -s -o "$FORE_BODY" -w '%{http_code}' \
  "$ANALYTICS/api/analytics/SENSOR-001/forecast?horizonHours=6")"
check "GET forecast h=6" 501 "$FORE_CODE"
FORE_CODE_BODY="$(python3 -c "import json,sys; d=json.load(open('$FORE_BODY')); print(d.get('code',''))" 2>/dev/null || echo "")"
[ "$FORE_CODE_BODY" = "FORECAST_DISABLED" ] && check "code FORECAST_DISABLED" 1 1 || check "code FORECAST_DISABLED" 1 0
rm -f "$FORE_BODY"

echo "== Scenario 27 : forecast horizon=99 -> 400 INVALID_PARAMETER"
check "GET forecast h=99" 400 "$(get_code "$ANALYTICS/api/analytics/SENSOR-001/forecast?horizonHours=99")"

echo "== Scenario 28 : Swagger storage 8082"
check "GET storage swagger-ui" 200 "$(get_code "http://localhost:8082/swagger-ui.html")"
check "GET storage v3/api-docs" 200 "$(get_code "http://localhost:8082/v3/api-docs")"

echo "== Scenario 29 : Swagger analytics 8083"
check "GET analytics swagger-ui" 200 "$(get_code "http://localhost:8083/swagger-ui.html")"
check "GET analytics v3/api-docs" 200 "$(get_code "http://localhost:8083/v3/api-docs")"

# ---------------------------------------------------------------- manuels
echo
echo "===== SCENARIOS MANUELS (arret/redemarrage conteneurs) ====="
echo "8.1  docker compose stop storage-service   ; envoyer mesures ; XINFO GROUPS pending/lag en hausse ; collector 202"
echo "8.2  docker compose start storage-service  ; pending=0, lag=0, comptage Cassandra = mesures acceptees"
echo "8.3  docker compose stop redis             ; collector 503 + Retry-After: 5"
echo "8.4  docker compose start redis            ; flux reprend, aucun message perdu"
echo "8.5  docker compose stop sensor-service    ; anomalies thresholdSource=DEFAULT (10,0 kW)"
echo "8.6  docker compose start sensor-service   ; seuil capteur (cache Redis 60 s)"
echo "9.1  docker compose --profile app down     ; (volumes conserves)"
echo "9.2  docker compose --profile app up -d --build ; attendre healthy/UP"
echo "9.3  verifier historique via API storage (donnees presentes)"

echo
echo "Resultat : $PASS OK, $FAIL echec(s)"
[ "$FAIL" -eq 0 ]
