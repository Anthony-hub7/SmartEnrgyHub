#!/usr/bin/env bash
# SmartEnergy Hub - demarrage LEGER (Cassandra + Redis dans Docker, services en local)
# Usage : ./start-light.sh [up|down|reset|status|logs|shell-cassandra|shell-redis]
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT" || exit 1

COMPOSE_FILE="docker-compose.light.yml"
ENV_FILE=".env.light"
PROJECT="smartenergy-hub-light"
TIMEOUT="${START_LIGHT_TIMEOUT:-180}"

C_R=$'\033[31m'; C_G=$'\033[32m'; C_Y=$'\033[33m'; C_B=$'\033[36m'; C_N=$'\033[0m'
info() { printf '%s==>%s %s\n' "$C_B" "$C_N" "$*"; }
ok()   { printf '%s OK %s %s\n' "$C_G" "$C_N" "$*"; }
warn() { printf '%s !! %s %s\n' "$C_Y" "$C_N" "$*"; }
err()  { printf '%sERR %s %s\n' "$C_R" "$C_N" "$*" >&2; }
die()  { err "$*"; exit 1; }

dc() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" -p "$PROJECT" "$@"; }

cmd_up() {
  [[ -f "$ENV_FILE" ]] || die "$ENV_FILE introuvable"
  info "Demarrage de l'infra legere (Cassandra + Redis) ..."
  dc up -d || die "Echec du demarrage"
  info "Attente que Cassandra et Redis soient prets (timeout ${TIMEOUT}s) ..."
  dc up -d cassandra redis cassandra-init >/dev/null 2>&1 || true
  local start=$SECONDS last=-10
  while :; do
    local pending=()
    for svc in cassandra redis; do
      local state
      state="$(dc ps -q "$svc" | head -n1)"
      if [[ -z "$state" ]]; then pending+=("$svc(absent)"); continue; fi
      local status health
      status="$(docker inspect -f '{{.State.Status}}' "$state" 2>/dev/null || true)"
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$state" 2>/dev/null || true)"
      [[ -n "$health" ]] && state="$health" || state="$status"
      if [[ "$state" != healthy ]]; then pending+=("$svc($state)"); fi
    done
    # cassandra-init doit avoir termine avec code 0
    local init_state init_code
    init_state="$(dc ps -q cassandra-init | head -n1)"
    if [[ -n "$init_state" ]]; then
      init_code="$(docker inspect -f '{{.State.ExitCode}}' "$init_state" 2>/dev/null || true)"
      if [[ "$init_code" == "0" ]]; then
        :
      elif [[ "$init_code" != "" && "$init_code" != "0" ]]; then
        err "cassandra-init a echoue (code $init_code). Voir : $0 logs cassandra-init"
        return 1
      fi
    else
      pending+=("cassandra-init(running/absent)")
    fi

    local elapsed=$((SECONDS - start))
    if ((${#pending[@]} == 0)); then
      ok "Infra prete (${elapsed}s)"
      break
    fi
    if ((elapsed >= TIMEOUT)); then
      err "Timeout apres ${TIMEOUT}s. En attente : ${pending[*]}"
      warn "Relancez : $0 up"
      return 1
    fi
    if ((elapsed - last >= 10)); then info "  en attente : ${pending[*]}"; last=$elapsed; fi
    sleep 3
  done
  ok "Cassandra : localhost:9042  |  Redis : localhost:6379 (mot de passe : change-me)"
  cat <<'NEXT'

Services Spring Boot a lancer EN LOCAL (un terminal chacun) :
  cd collector-service  && ./mvnw spring-boot:run -Dspring.data.redis.host=localhost -Dspring.data.redis.port=6379 -Dspring.data.redis.password=change-me
  cd storage-service    && ./mvnw spring-boot:run -Dspring.data.redis.host=localhost -Dspring.data.redis.port=6379 -Dspring.data.redis.password=change-me -Dspring.cassandra.contact-points=localhost:9042 -Dspring.cassandra.local-datacenter=datacenter1
  cd sensor-service     && ./mvnw spring-boot:run -Dspring.data.mongodb.host=localhost (Mongo hors scope light)
  cd analytics-service  & idem (Mongo hors scope light)

Verification :
  cqlsh localhost 9042 -e "describe keyspaces"
  redis-cli -a change-me --no-auth-warning ping
NEXT
}

cmd_down() {
  dc down && ok "Conteneurs arretes (donnees conservees dans les volumes)."
}

cmd_reset() {
  warn "RESET : supprime les conteneurs ET les volumes (donnees Cassandra/Redis perdues)."
  [[ "${1:-}" == --yes || "${1:-}" == -y ]] || { info "Annule. Ajoutez --yes pour confirmer."; return 0; }
  dc down -v --remove-orphans && ok "Reset effectue. Le schema sera rejoue au prochain demarrage."
}

cmd_status() {
  dc --profile tools ps -a 2>/dev/null || dc ps -a
  printf '\n'
  for svc in cassandra redis cassandra-init; do
    local cid state
    cid="$(dc ps -q "$svc" 2>/dev/null | head -n1)"
    if [[ -z "$cid" ]]; then printf '  %-18s absent\n' "$svc"; continue; fi
    state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
    printf '  %-18s %s\n' "$svc" "$state"
  done
}

cmd_logs() {
  dc logs -f --tail=100 "${@:-cassandra redis cassandra-init}"
}

cmd_shell_cassandra() { dc exec cassandra cqlsh; }
cmd_shell_redis() { dc exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning'; }

command_name="${1:-help}"
shift || true
case "$command_name" in
  up) cmd_up "$@";;
  down) cmd_down;;
  reset) cmd_reset "${1:-}";;
  status|ps) cmd_status;;
  logs|log) cmd_logs "${@:-}";;
  shell-cassandra|cql) cmd_shell_cassandra;;
  shell-redis|redis-cli) cmd_shell_redis;;
  help|-h|--help|*)
    cat <<HELP
Usage : $0 <commande>

  up            Demarre Cassandra + Redis + init schema (attend que tout soit pret)
  down          Arrete les conteneurs (donnees conservees)
  reset [--yes] Supprime conteneurs et volumes (donnees perdues)
  status        Etat des conteneurs
  logs [svc]    Logs (par defaut : cassandra redis cassandra-init)
  shell-cassandra  Ouvre cqlsh sur Cassandra
  shell-redis     Ouvre redis-cli sur Redis

Variables : voir .env.light (CASSANDRA_MAX_HEAP=256M, CASSANDRA_MEM_LIMIT=512m, ...)
HELP
    ;;
esac
