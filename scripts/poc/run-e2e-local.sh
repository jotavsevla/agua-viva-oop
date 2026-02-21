#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/run-e2e-local.sh [--mode strict|observe] [--no-playwright] [--no-suite] [--keep-running] [--rounds N] [--no-reset] [--no-promocoes-check] [--no-reset-before-suite]

Executa o gate E2E operacional local em um comando:
1) sobe dependencias (Postgres + solver, quando necessario)
2) aplica migrations
3) sobe API Java (se nao estiver online)
4) sobe UI estatica (se nao estiver online)
5) roda Playwright PoC M1
6) roda suite PoC (feliz/falha/cancelamento + gate VALE)

Flags:
  --mode MODE       define o perfil: strict (padrao) ou observe
  --no-playwright   pula execucao Playwright
  --no-suite        pula scripts/poc/run-suite.sh
  --keep-running    nao encerra API/UI iniciadas por este script
  --rounds N        executa N rodadas consecutivas (padrao: 1)
  --no-reset        nao reseta estado do banco antes de cada rodada
  --no-promocoes-check  desabilita validacao automatica de promocoes de fila
  --promocoes-strict  exige transicoes CONFIRMADO->EM_ROTA e PENDENTE->CONFIRMADO no check de promocoes
  --no-reset-before-suite  quando roda Playwright + suite na mesma rodada, nao reseta entre as fases
  -h, --help        mostra ajuda

Variaveis opcionais:
  COMPOSE_FILE=compose.yml
  API_PORT=8082
  API_BASE=http://localhost:8082
  UI_PORT=4174
  UI_BASE=http://localhost:4174
  SOLVER_URL=http://localhost:8080
  SOLVER_REBUILD=1
  START_POSTGRES=1
  START_SOLVER=1
  APPLY_MIGRATIONS=1
  PLAYWRIGHT_INSTALL=1
  ROUNDS=1
  RESET_EACH_ROUND=1
  PROMOCOES_CHECK=1
  PROMOCOES_STRICT=1
  RESET_BEFORE_SUITE=1
  MODE=strict
  ARTIFACT_DIR=artifacts/poc/e2e-<timestamp>
  DB_CONTAINER=postgres-oop-test
  DB_SERVICE=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  POSTGRES_HOST=localhost
  POSTGRES_PORT=5435
  POSTGRES_DB=agua_viva_oop_test
  POSTGRES_USER=postgres
  POSTGRES_PASSWORD=postgres
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

RUN_PLAYWRIGHT=1
RUN_SUITE=1
KEEP_RUNNING=0
MODE="${MODE:-strict}"
ROUNDS="${ROUNDS:-1}"
RESET_EACH_ROUND="${RESET_EACH_ROUND:-1}"
RUN_PROMOCOES_CHECK="${PROMOCOES_CHECK:-1}"
PROMOCOES_STRICT="${PROMOCOES_STRICT:-1}"
RESET_BEFORE_SUITE="${RESET_BEFORE_SUITE:-1}"
FLAG_NO_PLAYWRIGHT=0
FLAG_NO_SUITE=0
FLAG_NO_PROMOCOES=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --mode exige um valor (strict|observe)" >&2
        usage
        exit 1
      fi
      MODE="$2"
      shift
      ;;
    --mode=*)
      MODE="${1#*=}"
      ;;
    --no-playwright)
      FLAG_NO_PLAYWRIGHT=1
      RUN_PLAYWRIGHT=0
      ;;
    --no-suite)
      FLAG_NO_SUITE=1
      RUN_SUITE=0
      ;;
    --keep-running)
      KEEP_RUNNING=1
      ;;
    --rounds)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --rounds exige um valor inteiro" >&2
        usage
        exit 1
      fi
      ROUNDS="$2"
      shift
      ;;
    --rounds=*)
      ROUNDS="${1#*=}"
      ;;
    --no-reset)
      RESET_EACH_ROUND=0
      ;;
    --no-promocoes-check)
      FLAG_NO_PROMOCOES=1
      RUN_PROMOCOES_CHECK=0
      ;;
    --promocoes-strict)
      PROMOCOES_STRICT=1
      ;;
    --no-reset-before-suite)
      RESET_BEFORE_SUITE=0
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

if [[ "$MODE" != "strict" && "$MODE" != "observe" ]]; then
  echo "Modo invalido: ${MODE}. Use strict ou observe." >&2
  exit 1
fi

if [[ "$MODE" == "strict" ]]; then
  if [[ "$FLAG_NO_PLAYWRIGHT" -eq 1 || "$FLAG_NO_SUITE" -eq 1 || "$FLAG_NO_PROMOCOES" -eq 1 ]]; then
    echo "Modo strict nao aceita flags diagnosticas (--no-playwright/--no-suite/--no-promocoes-check)." >&2
    exit 1
  fi
  if [[ "$RUN_PLAYWRIGHT" -ne 1 || "$RUN_SUITE" -ne 1 || "$RUN_PROMOCOES_CHECK" -ne 1 ]]; then
    echo "Modo strict exige Playwright, suite e check de promocoes habilitados." >&2
    exit 1
  fi
  PROMOCOES_STRICT=1
fi

if [[ "$RUN_PLAYWRIGHT" -eq 0 && "$RUN_SUITE" -eq 0 ]]; then
  echo "Nada para executar: habilite Playwright e/ou suite." >&2
  exit 1
fi

if ! [[ "$ROUNDS" =~ ^[0-9]+$ ]] || [[ "$ROUNDS" -le 0 ]]; then
  echo "Parametro invalido para --rounds: ${ROUNDS}. Use inteiro > 0." >&2
  exit 1
fi

COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
API_PORT="${API_PORT:-8082}"
API_BASE="${API_BASE:-http://localhost:${API_PORT}}"
UI_PORT="${UI_PORT:-4174}"
UI_BASE="${UI_BASE:-http://localhost:${UI_PORT}}"
SOLVER_URL="${SOLVER_URL:-http://localhost:8080}"
SOLVER_REBUILD="${SOLVER_REBUILD:-1}"
START_POSTGRES="${START_POSTGRES:-1}"
START_SOLVER="${START_SOLVER:-1}"
APPLY_MIGRATIONS="${APPLY_MIGRATIONS:-1}"
PLAYWRIGHT_INSTALL="${PLAYWRIGHT_INSTALL:-1}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5435}"
POSTGRES_DB="${POSTGRES_DB:-$DB_NAME}"
POSTGRES_USER="${POSTGRES_USER:-$DB_USER}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"

ARTIFACT_DIR="${ARTIFACT_DIR:-$ROOT_DIR/artifacts/poc/e2e-$(date +%Y%m%d-%H%M%S)}"
if [[ "$ARTIFACT_DIR" != /* ]]; then
  ARTIFACT_DIR="$ROOT_DIR/$ARTIFACT_DIR"
fi
mkdir -p "$ARTIFACT_DIR"
ROUNDS_NDJSON="$ARTIFACT_DIR/rounds.ndjson"
: > "$ROUNDS_NDJSON"

API_PID=""
UI_PID=""
API_STARTED=0
UI_STARTED=0

log() {
  echo "[poc-e2e] $*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

http_ok() {
  local url="$1"
  curl -fsS "$url" >/dev/null 2>&1
}

wait_http() {
  local url="$1"
  local label="$2"
  local timeout="${3:-90}"
  local start now
  start="$(date +%s)"
  while true; do
    if http_ok "$url"; then
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

wait_db_service_ready() {
  local service="$1"
  local timeout="${2:-90}"
  local start now
  start="$(date +%s)"
  while true; do
    if docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$service" \
      pg_isready -U "$POSTGRES_USER" >/dev/null 2>&1; then
      return 0
    fi
    now="$(date +%s)"
    if (( now - start >= timeout )); then
      echo "Timeout aguardando service ${service} pronto para conexao." >&2
      return 1
    fi
    sleep 1
  done
}

cleanup() {
  local exit_code=$?
  if [[ "$KEEP_RUNNING" -eq 0 ]]; then
    if [[ "$API_STARTED" -eq 1 && -n "$API_PID" ]] && kill -0 "$API_PID" >/dev/null 2>&1; then
      log "Encerrando API iniciada por este script (pid=${API_PID})"
      kill "$API_PID" >/dev/null 2>&1 || true
    fi
    if [[ "$UI_STARTED" -eq 1 && -n "$UI_PID" ]] && kill -0 "$UI_PID" >/dev/null 2>&1; then
      log "Encerrando UI iniciada por este script (pid=${UI_PID})"
      kill "$UI_PID" >/dev/null 2>&1 || true
    fi
  else
    log "Mantendo API/UI em execucao (--keep-running)."
  fi

  if [[ "$exit_code" -ne 0 ]]; then
    log "Falha no gate E2E. Logs em: $ARTIFACT_DIR"
  fi
}
trap cleanup EXIT

require_cmd docker
require_cmd curl
require_cmd jq
require_cmd mvn
require_cmd python3
require_cmd npm
require_cmd npx

if [[ "$START_POSTGRES" -eq 1 ]]; then
  log "Subindo Postgres test via compose"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" up -d postgres-oop-test >/dev/null
  wait_db_service_ready "$DB_SERVICE" 90
fi

if [[ "$START_SOLVER" -eq 1 ]]; then
  if [[ "$SOLVER_REBUILD" -eq 1 ]]; then
    log "Sincronizando solver com codigo atual (build local)"
    docker compose -f "$ROOT_DIR/$COMPOSE_FILE" build solver >/dev/null
    docker compose -f "$ROOT_DIR/$COMPOSE_FILE" up -d --no-deps solver >/dev/null
    wait_http "${SOLVER_URL}/health" "solver" 180
  elif http_ok "${SOLVER_URL}/health"; then
    log "Solver ja esta online em ${SOLVER_URL}"
  else
    log "Subindo solver via compose"
    docker compose -f "$ROOT_DIR/$COMPOSE_FILE" up -d --no-deps solver >/dev/null
    wait_http "${SOLVER_URL}/health" "solver" 180
  fi
fi

if [[ "$APPLY_MIGRATIONS" -eq 1 ]]; then
  log "Aplicando migrations"
  (
    cd "$ROOT_DIR"
    DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" POSTGRES_USER="$POSTGRES_USER" POSTGRES_DB="$POSTGRES_DB" \
      ./apply-migrations.sh > "$ARTIFACT_DIR/migrations.log" 2>&1
  )
fi

if http_ok "${API_BASE}/health"; then
  log "API ja esta online em ${API_BASE}"
else
  log "Subindo API Java em ${API_BASE}"
  (
    cd "$ROOT_DIR"
    POSTGRES_HOST="$POSTGRES_HOST" \
      POSTGRES_PORT="$POSTGRES_PORT" \
      POSTGRES_DB="$POSTGRES_DB" \
      POSTGRES_USER="$POSTGRES_USER" \
      POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
      SOLVER_URL="$SOLVER_URL" API_PORT="$API_PORT" \
      mvn -DskipTests exec:java -Dexec.mainClass=com.aguaviva.App -Dexec.args=api \
      > "$ARTIFACT_DIR/api.log" 2>&1
  ) &
  API_PID=$!
  API_STARTED=1
  wait_http "${API_BASE}/health" "api" 120
fi

if http_ok "$UI_BASE"; then
  log "UI ja esta online em ${UI_BASE}"
else
  log "Subindo UI estatica em ${UI_BASE}"
  (
    cd "$ROOT_DIR/produto-ui/prototipo"
    python3 -m http.server "$UI_PORT" > "$ARTIFACT_DIR/ui.log" 2>&1
  ) &
  UI_PID=$!
  UI_STARTED=1
  wait_http "$UI_BASE" "ui" 60
fi

if [[ "$RUN_PLAYWRIGHT" -eq 1 ]]; then
  log "Preparando Playwright (PoC M1 + gate VALE)"
  (
    cd "$ROOT_DIR/produto-ui/prototipo"
    if [[ ! -d node_modules ]]; then
      npm ci > "$ARTIFACT_DIR/npm-ci.log" 2>&1
    fi
    if [[ "$PLAYWRIGHT_INSTALL" -eq 1 ]]; then
      npx playwright install chromium > "$ARTIFACT_DIR/playwright-install.log" 2>&1
    fi
  )
fi

overall_failed=0
rounds_ok=0
rounds_fail=0

for ((round=1; round<=ROUNDS; round++)); do
  round_label="$(printf 'round-%02d' "$round")"
  round_dir="$ARTIFACT_DIR/$round_label"
  mkdir -p "$round_dir"
  log "Iniciando ${round_label}/${ROUNDS}"

  round_playwright_exit=-1
  round_suite_exit=-1
  round_promocoes_exit=-1
  round_reset_before_suite_exit=-1
  round_status="OK"
  round_detail="ok"

  if [[ "$RESET_EACH_ROUND" -eq 1 ]]; then
    set +e
    (
      cd "$ROOT_DIR"
      DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" scripts/poc/reset-test-state.sh \
        > "$round_dir/reset.log" 2>&1
    )
    reset_exit="$?"
    set -e
    if [[ "$reset_exit" -ne 0 ]]; then
      round_status="FAIL"
      overall_failed=1
      rounds_fail=$((rounds_fail + 1))
      round_playwright_exit=-1
      round_suite_exit=-1
      jq -cn \
        --arg round "$round_label" \
        --arg status "$round_status" \
        --arg artifactDir "$round_dir" \
        --argjson resetExit "$reset_exit" \
        --argjson playwrightExit "$round_playwright_exit" \
        --argjson suiteExit "$round_suite_exit" \
        --argjson promocoesExit "$round_promocoes_exit" \
        --argjson resetBeforeSuiteExit "$round_reset_before_suite_exit" \
        '{round:$round,status:$status,resetExit:$resetExit,resetBeforeSuiteExit:$resetBeforeSuiteExit,playwrightExit:$playwrightExit,suiteExit:$suiteExit,promocoesExit:$promocoesExit,artifactDir:$artifactDir}' \
        >> "$ROUNDS_NDJSON"
      log "${round_label}: falha ao resetar estado (exit=${reset_exit})"
      continue
    fi
  fi

  if [[ "$RUN_PLAYWRIGHT" -eq 1 ]]; then
    log "${round_label}: executando Playwright"
    set +e
    (
      cd "$ROOT_DIR/produto-ui/prototipo"
      API_BASE="$API_BASE" UI_BASE="$UI_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
        npx playwright test e2e/poc-m1-ui.spec.js --workers=1 | tee "$round_dir/playwright.log"
    )
    round_playwright_exit="$?"
    set -e
    if [[ "$round_playwright_exit" -ne 0 ]]; then
      round_status="FAIL"
      round_detail="playwright_exit_${round_playwright_exit}"
    fi
  fi

  if [[ "$RUN_PLAYWRIGHT" -eq 1 && "$RUN_SUITE" -eq 1 && "$RESET_BEFORE_SUITE" -eq 1 ]]; then
    log "${round_label}: reset entre Playwright e suite"
    set +e
    (
      cd "$ROOT_DIR"
      DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" scripts/poc/reset-test-state.sh \
        > "$round_dir/reset-before-suite.log" 2>&1
    )
    round_reset_before_suite_exit="$?"
    set -e
    if [[ "$round_reset_before_suite_exit" -ne 0 ]]; then
      round_status="FAIL"
      round_detail="reset_before_suite_exit_${round_reset_before_suite_exit}"
      log "${round_label}: falha no reset-before-suite (exit=${round_reset_before_suite_exit})"
    fi
  fi

  if [[ "$RUN_SUITE" -eq 1 ]]; then
    if [[ "$round_status" == "FAIL" && "$round_reset_before_suite_exit" -ne -1 ]]; then
      log "${round_label}: suite pulada por falha no reset-before-suite"
    else
      log "${round_label}: executando scripts/poc/run-suite.sh"
      set +e
      (
        cd "$ROOT_DIR"
        API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
          OUT_DIR="$round_dir/poc-suite" scripts/poc/run-suite.sh | tee "$round_dir/poc-suite.log"
      )
      round_suite_exit="$?"
      set -e
      if [[ "$round_suite_exit" -ne 0 ]]; then
        round_status="FAIL"
        round_detail="suite_exit_${round_suite_exit}"
      fi
    fi
  fi

  if [[ "$RUN_PROMOCOES_CHECK" -eq 1 ]]; then
    log "${round_label}: validando promocoes de fila"
    require_confirmado_em_rota=0
    require_pendente_confirmado=0
    if [[ "$PROMOCOES_STRICT" -eq 1 ]]; then
      require_confirmado_em_rota=1
      require_pendente_confirmado=1
    fi
    set +e
    (
      cd "$ROOT_DIR"
      API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
        WORK_DIR="$round_dir/promocoes" SUMMARY_FILE="$round_dir/promocoes-summary.json" \
        REQUIRE_CONFIRMADO_EM_ROTA="$require_confirmado_em_rota" \
        REQUIRE_PENDENTE_CONFIRMADO="$require_pendente_confirmado" \
        scripts/poc/observe-promocoes.sh | tee "$round_dir/promocoes.log"
    )
    round_promocoes_exit="$?"
    set -e
    if [[ "$round_promocoes_exit" -ne 0 ]]; then
      round_status="FAIL"
      round_detail="promocoes_exit_${round_promocoes_exit}"
    fi
  fi

  if [[ "$MODE" == "strict" ]]; then
    if [[ "$round_playwright_exit" -eq -1 ]]; then
      round_status="FAIL"
      round_detail="playwright_not_executed"
    fi
    if [[ "$round_suite_exit" -eq -1 ]]; then
      round_status="FAIL"
      round_detail="suite_not_executed"
    fi
    if [[ "$round_promocoes_exit" -eq -1 ]]; then
      round_status="FAIL"
      round_detail="promocoes_not_executed"
    fi
  fi

  if [[ "$round_status" == "OK" ]]; then
    rounds_ok=$((rounds_ok + 1))
    log "${round_label}: OK"
  else
    rounds_fail=$((rounds_fail + 1))
    overall_failed=1
    log "${round_label}: FAIL (playwright=${round_playwright_exit}, suite=${round_suite_exit}, promocoes=${round_promocoes_exit})"
  fi

  jq -cn \
    --arg round "$round_label" \
    --arg status "$round_status" \
    --arg artifactDir "$round_dir" \
    --argjson resetExit 0 \
    --argjson resetBeforeSuiteExit "$round_reset_before_suite_exit" \
    --argjson playwrightExit "$round_playwright_exit" \
    --argjson suiteExit "$round_suite_exit" \
    --argjson promocoesExit "$round_promocoes_exit" \
    --arg detail "$round_detail" \
    '{round:$round,status:$status,resetExit:$resetExit,resetBeforeSuiteExit:$resetBeforeSuiteExit,playwrightExit:$playwrightExit,suiteExit:$suiteExit,promocoesExit:$promocoesExit,detail:$detail,artifactDir:$artifactDir}' \
    >> "$ROUNDS_NDJSON"
done

jq -s \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --arg uiBase "$UI_BASE" \
  --arg mode "$MODE" \
  --argjson roundsRequested "$ROUNDS" \
  --argjson roundsOk "$rounds_ok" \
  --argjson roundsFail "$rounds_fail" \
  --argjson promocoesCheckEnabled "$RUN_PROMOCOES_CHECK" \
  --argjson resetBeforeSuiteEnabled "$RESET_BEFORE_SUITE" \
  '
  {
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    uiBase: $uiBase,
    mode: $mode,
    roundsRequested: $roundsRequested,
    roundsOk: $roundsOk,
    roundsFail: $roundsFail,
    promocoesCheckEnabled: ($promocoesCheckEnabled == 1),
    resetBeforeSuiteEnabled: ($resetBeforeSuiteEnabled == 1),
    rounds: .
  }
  ' "$ROUNDS_NDJSON" > "$ARTIFACT_DIR/gate-summary.json"

if [[ "$overall_failed" -ne 0 ]]; then
  log "Gate E2E concluido com falhas."
  log "Resumo: $ARTIFACT_DIR/gate-summary.json"
  exit 1
fi

if [[ "$RUN_PLAYWRIGHT" -eq 1 ]]; then
  log "Playwright executado em ${ROUNDS} rodada(s)."
fi

if [[ "$RUN_SUITE" -eq 1 ]]; then
  log "Suite PoC executada em ${ROUNDS} rodada(s)."
fi

if [[ "$RUN_PROMOCOES_CHECK" -eq 1 ]]; then
  log "Validacao automatica de promocoes executada em ${ROUNDS} rodada(s)."
fi

log "Gate E2E concluido com sucesso."
log "Resumo: $ARTIFACT_DIR/gate-summary.json"
log "Artefatos: $ARTIFACT_DIR"
