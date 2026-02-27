#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/tests/run-container-tests.sh [--skip-java] [--skip-solver] [--skip-ui] [--keep-running]

Executa suites de teste em container com Docker Compose:
  1) sobe dependencias de teste (postgres-oop-test + solver)
  2) aplica migrations no banco de teste
  3) roda Java tests (spotless + unit + integration)
  4) roda solver tests (pytest)
  5) roda UI smoke tests (node)

Flags:
  --skip-java      pula suite Java
  --skip-solver    pula suite solver
  --skip-ui        pula suite UI node
  --keep-running   nao derruba dependencias ao final
  -h, --help       mostra ajuda
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
SOLVER_URL="${SOLVER_URL:-http://localhost:8080}"

RUN_JAVA=1
RUN_SOLVER=1
RUN_UI=1
KEEP_RUNNING=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-java)
      RUN_JAVA=0
      ;;
    --skip-solver)
      RUN_SOLVER=0
      ;;
    --skip-ui)
      RUN_UI=0
      ;;
    --keep-running)
      KEEP_RUNNING=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Parametro invalido: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

if [[ "$RUN_JAVA" -eq 0 && "$RUN_SOLVER" -eq 0 && "$RUN_UI" -eq 0 ]]; then
  echo "Nada para executar: habilite ao menos uma suite." >&2
  exit 1
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

wait_http() {
  local url="$1"
  local label="$2"
  local timeout="${3:-120}"
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

log() {
  echo "[tests-container] $*"
}

cleanup() {
  local exit_code=$?
  if [[ "$KEEP_RUNNING" -eq 0 ]]; then
    log "Derrubando dependencias de teste"
    docker compose -f "$ROOT_DIR/$COMPOSE_FILE" --profile test stop postgres-oop-test solver >/dev/null 2>&1 || true
  else
    log "Mantendo dependencias ativas (--keep-running)"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

require_cmd docker
require_cmd curl

cd "$ROOT_DIR"

log "Subindo dependencias: postgres-oop-test + solver"
docker compose -f "$COMPOSE_FILE" --profile test up -d postgres-oop-test solver >/dev/null
wait_http "$SOLVER_URL/health" "solver" 180

log "Aplicando migrations no banco de teste"
docker compose -f "$COMPOSE_FILE" --profile test run --rm migrations-test

if [[ "$RUN_JAVA" -eq 1 ]]; then
  log "Rodando suite Java em container"
  docker compose -f "$COMPOSE_FILE" --profile test run --rm test-java
fi

if [[ "$RUN_SOLVER" -eq 1 ]]; then
  log "Rodando suite solver em container"
  docker compose -f "$COMPOSE_FILE" --profile test run --rm test-solver
fi

if [[ "$RUN_UI" -eq 1 ]]; then
  log "Rodando suite UI node em container"
  docker compose -f "$COMPOSE_FILE" --profile test run --rm test-ui-node
fi

log "Suites finalizadas com sucesso."
