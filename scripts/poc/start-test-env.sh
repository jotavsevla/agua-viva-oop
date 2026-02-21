#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_PORT="${API_PORT:-8082}"
API_BASE="${API_BASE:-http://localhost:${API_PORT}}"
UI_PORT="${UI_PORT:-4174}"
UI_BASE="${UI_BASE:-http://localhost:${UI_PORT}}"
SOLVER_URL="${SOLVER_URL:-http://localhost:8080}"
API_HEALTH_TIMEOUT_SECONDS="${API_HEALTH_TIMEOUT_SECONDS:-240}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
SOLVER_REBUILD="${SOLVER_REBUILD:-1}"
FORCE_API_RESTART="${FORCE_API_RESTART:-0}"
POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5435}"
POSTGRES_DB="${POSTGRES_DB:-agua_viva_oop_test}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"

LOG_DIR="${LOG_DIR:-$ROOT_DIR/artifacts/poc/runtime}"
mkdir -p "$LOG_DIR"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

wait_http() {
  local url="$1"
  local label="$2"
  local timeout="${3:-90}"
  local start now
  start="$(date +%s)"
  while true; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    now="$(date +%s)"
    if (( now - start >= timeout )); then
      echo "Timeout aguardando ${label}: ${url}" >&2
      return 1
    fi
    sleep 1
  done
}

require_cmd docker
require_cmd curl
require_cmd mvn
require_cmd python3

cd "$ROOT_DIR"

echo "[start-test-env] Subindo Postgres test"
docker compose -f "$COMPOSE_FILE" up -d postgres-oop-test >/dev/null

echo "[start-test-env] Sincronizando solver"
if [[ "$SOLVER_REBUILD" -eq 1 ]]; then
  docker compose -f "$COMPOSE_FILE" build solver >/dev/null
  docker compose -f "$COMPOSE_FILE" up -d --no-deps solver >/dev/null
else
  docker compose -f "$COMPOSE_FILE" up -d --no-deps solver >/dev/null
fi
wait_http "$SOLVER_URL/health" "solver" 180

echo "[start-test-env] Aplicando migrations"
DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" POSTGRES_USER="$POSTGRES_USER" POSTGRES_DB="$POSTGRES_DB" \
  ./apply-migrations.sh > "$LOG_DIR/migrations.log" 2>&1

if [[ "$FORCE_API_RESTART" -eq 1 ]]; then
  echo "[start-test-env] Reinicio forcado da API habilitado (FORCE_API_RESTART=1)"
  pkill -f "com.aguaviva.App -Dexec.args=api" >/dev/null 2>&1 || true
  sleep 1
fi

if curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "[start-test-env] API ja online em $API_BASE"
else
  echo "[start-test-env] Subindo API em $API_BASE"
  API_LOG_FILE="$LOG_DIR/api-${API_PORT}.log"
  nohup env \
    POSTGRES_HOST="$POSTGRES_HOST" \
    POSTGRES_PORT="$POSTGRES_PORT" \
    POSTGRES_DB="$POSTGRES_DB" \
    POSTGRES_USER="$POSTGRES_USER" \
    POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    SOLVER_URL="$SOLVER_URL" \
    API_PORT="$API_PORT" \
    mvn -DskipTests compile exec:java -Dexec.mainClass=com.aguaviva.App -Dexec.args=api \
    > "$API_LOG_FILE" 2>&1 &
  API_PID=$!
fi

if ! wait_http "$API_BASE/health" "api" "$API_HEALTH_TIMEOUT_SECONDS"; then
  echo "[start-test-env] Falha ao subir API. Timeout=${API_HEALTH_TIMEOUT_SECONDS}s"
  if [[ -n "${API_PID:-}" ]] && ! kill -0 "$API_PID" >/dev/null 2>&1; then
    echo "[start-test-env] Processo da API encerrou antes de responder /health (pid=$API_PID)"
  fi
  if [[ -n "${API_LOG_FILE:-}" && -f "$API_LOG_FILE" ]]; then
    echo "[start-test-env] Tail do log da API ($API_LOG_FILE):"
    tail -n 200 "$API_LOG_FILE"
  else
    echo "[start-test-env] Log da API nao encontrado para diagnostico."
  fi
  exit 1
fi

if curl -fsS "$UI_BASE" >/dev/null 2>&1; then
  echo "[start-test-env] UI ja online em $UI_BASE"
else
  echo "[start-test-env] Subindo UI estatica em $UI_BASE"
  nohup sh -c "cd \"$ROOT_DIR/produto-ui/prototipo\" && python3 -m http.server \"$UI_PORT\"" \
    > "$LOG_DIR/ui-${UI_PORT}.log" 2>&1 &
fi
wait_http "$UI_BASE" "ui" 60

echo
echo "[start-test-env] Ambiente pronto"
echo "API:    $API_BASE"
echo "UI:     $UI_BASE"
echo "Solver: $SOLVER_URL"
echo "Logs:   $LOG_DIR"
