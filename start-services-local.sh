#!/usr/bin/env bash
# Lancement des 4 services Spring Boot en local contre l'infra Docker (Cassandra+Redis+MongoDB)
# Usage : ./start-services-local.sh [start|stop]
set -u

ROOT="/home/hakari/Documents/SmartEnrgyHub"
cd "$ROOT" || exit 1

export SPRING_PROFILES_ACTIVE=docker
export REDIS_HOST=localhost REDIS_PORT=6379 REDIS_PASSWORD=change-me
export CASSANDRA_HOST=localhost CASSANDRA_PORT=9042 CASSANDRA_DC=datacenter1
export MONGO_HOST=localhost MONGO_PORT=27017
export MONGO_ROOT_USER=root MONGO_ROOT_PASSWORD=root_dev
export MONGO_SENSOR_PASSWORD=change-me-sensor MONGO_ANALYTICS_PASSWORD=change-me-analytics
export SENSOR_SERVICE_URL=http://localhost:8084
export SERVER_PORT_COLLECTOR=8081 SERVER_PORT_STORAGE=8082 SERVER_PORT_ANALYTICS=8083 SERVER_PORT_SENSOR=8084

PIDFILE="/tmp/smartenergy-services.pids"

case "${1:-start}" in
  start)
    : > "$PIDFILE"
    for svc in collector-service storage-service sensor-service analytics-service; do
      case "$svc" in
        collector-service) PORT=8081;;
        storage-service)   PORT=8082;;
        analytics-service) PORT=8083;;
        sensor-service)    PORT=8084;;
      esac
      export SERVER_PORT="$PORT"
      LOG="/tmp/${svc}.log"
      nohup mvn -q spring-boot:run > "$LOG" 2>&1 &
      echo "$svc $! $PORT" >> "$PIDFILE"
      echo "Lancement $svc (PID $!, port $PORT) ..."
    done
    echo "Tous les services sont lancés. Logs dans /tmp/*.log"
    echo "Attendez ~30s puis testez : curl http://localhost:8081/actuator/health/readiness"
    ;;
  stop)
    if [[ -f "$PIDFILE" ]]; then
      while read -r svc pid port; do
        [[ -z "$svc" ]] && continue
        if kill -0 "$pid" 2>/dev/null; then
          kill "$pid" 2>/dev/null && echo "Arrêt $svc (PID $pid)"
        fi
      done < "$PIDFILE"
      rm -f "$PIDFILE"
    fi
    pkill -f "spring-boot:run" 2>/dev/null || true
    echo "Services arrêtés."
    ;;
  *)
    echo "Usage : $0 [start|stop]"
    ;;
esac
