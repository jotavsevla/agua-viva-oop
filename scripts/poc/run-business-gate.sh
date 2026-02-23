#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/run-business-gate.sh [--mode strict|observe] [--rounds N] [--api-base URL]
                                   [--db-container NAME] [--db-service NAME] [--db-user USER] [--db-name DB]
                                   [--pace-seconds N] [--timed]
                                   [--only-check RXX[,RYY]] [--from-check RXX] [--to-check RYY]
                                   [--keep-running]

Gate oficial de plano de negocio da PoC operacional.

Defaults:
  --mode strict
  --rounds 1
  --api-base http://localhost:8082
  --pace-seconds 0
  --db-container postgres-oop-test
  --db-service postgres-oop-test
  --db-user postgres
  --db-name agua_viva_oop_test

Status dos checks:
  PASS | FAIL | SKIPPED | NOT_ELIGIBLE

No modo strict:
  - checks obrigatorios com status SKIPPED/NOT_ELIGIBLE viram FAIL
  - aprovacao final exige todos checks obrigatorios em PASS
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORIGINAL_ARGS=("$@")

MODE="${MODE:-strict}"
ROUNDS="${ROUNDS:-1}"
API_BASE="${API_BASE:-http://localhost:8082}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
PACE_SECONDS="${PACE_SECONDS:-0}"
TIMED_MODE=0
PACE_SET_BY_CLI=0
ONLY_CHECKS_CSV=""
FROM_CHECK=""
TO_CHECK=""
KEEP_RUNNING=0
SOLVER_REBUILD="${SOLVER_REBUILD:-1}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --mode exige valor" >&2
        usage
        exit 1
      fi
      MODE="$2"
      shift
      ;;
    --mode=*)
      MODE="${1#*=}"
      ;;
    --rounds)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --rounds exige valor" >&2
        usage
        exit 1
      fi
      ROUNDS="$2"
      shift
      ;;
    --rounds=*)
      ROUNDS="${1#*=}"
      ;;
    --api-base)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --api-base exige valor" >&2
        usage
        exit 1
      fi
      API_BASE="$2"
      shift
      ;;
    --api-base=*)
      API_BASE="${1#*=}"
      ;;
    --db-container)
      DB_CONTAINER="$2"
      shift
      ;;
    --db-container=*)
      DB_CONTAINER="${1#*=}"
      ;;
    --db-service)
      DB_SERVICE="$2"
      shift
      ;;
    --db-service=*)
      DB_SERVICE="${1#*=}"
      ;;
    --db-user)
      DB_USER="$2"
      shift
      ;;
    --db-user=*)
      DB_USER="${1#*=}"
      ;;
    --db-name)
      DB_NAME="$2"
      shift
      ;;
    --db-name=*)
      DB_NAME="${1#*=}"
      ;;
    --pace-seconds)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --pace-seconds exige valor" >&2
        usage
        exit 1
      fi
      PACE_SECONDS="$2"
      PACE_SET_BY_CLI=1
      shift
      ;;
    --pace-seconds=*)
      PACE_SECONDS="${1#*=}"
      PACE_SET_BY_CLI=1
      ;;
    --timed)
      TIMED_MODE=1
      ;;
    --only-check)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --only-check exige valor" >&2
        usage
        exit 1
      fi
      if [[ -z "$ONLY_CHECKS_CSV" ]]; then
        ONLY_CHECKS_CSV="$2"
      else
        ONLY_CHECKS_CSV="${ONLY_CHECKS_CSV},$2"
      fi
      shift
      ;;
    --only-check=*)
      if [[ -z "$ONLY_CHECKS_CSV" ]]; then
        ONLY_CHECKS_CSV="${1#*=}"
      else
        ONLY_CHECKS_CSV="${ONLY_CHECKS_CSV},${1#*=}"
      fi
      ;;
    --from-check)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --from-check exige valor" >&2
        usage
        exit 1
      fi
      FROM_CHECK="$2"
      shift
      ;;
    --from-check=*)
      FROM_CHECK="${1#*=}"
      ;;
    --to-check)
      if [[ $# -lt 2 ]]; then
        echo "Parametro invalido: --to-check exige valor" >&2
        usage
        exit 1
      fi
      TO_CHECK="$2"
      shift
      ;;
    --to-check=*)
      TO_CHECK="${1#*=}"
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

if [[ "$MODE" != "strict" && "$MODE" != "observe" ]]; then
  echo "Modo invalido: $MODE (use strict|observe)" >&2
  exit 1
fi

if ! [[ "$PACE_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "Parametro invalido para --pace-seconds: ${PACE_SECONDS} (use inteiro >= 0)" >&2
  exit 1
fi

if [[ "$TIMED_MODE" -eq 1 && "$PACE_SET_BY_CLI" -eq 0 && "$PACE_SECONDS" -eq 0 ]]; then
  PACE_SECONDS=5
fi

if [[ "$DB_SERVICE" == "postgres-oop-test" && "$DB_CONTAINER" != "postgres-oop-test" ]]; then
  DB_SERVICE="$DB_CONTAINER"
fi

if ! [[ "$ROUNDS" =~ ^[0-9]+$ ]] || [[ "$ROUNDS" -le 0 ]]; then
  echo "Parametro invalido para --rounds: ${ROUNDS}" >&2
  exit 1
fi

if [[ "$DB_NAME" != *_test ]]; then
  echo "Guard-rail: DB_NAME deve terminar com _test para o business gate. Atual: $DB_NAME" >&2
  exit 1
fi

CHECK_SEQUENCE=(
  R01 R02 R03 R04 R05 R06 R07 R08 R09 R10 R11 R12 R13 R14 R15 R16 R17 R18
  R19 R20 R21 R22 R23 R24 R25 R26 R27 R28 R29 R30 R31 R32 R33 R34 R35
  R36 R37 R38 R39 R40 R41 R42 R43 R44 R45 R46 R47 R48 R49
)
SELECTED_CHECKS=()
SELECTED_CHECKS_COUNT=0
CHECK_FILTER_ACTIVE=0
CHECK_FILTER_DESC="ALL"

normalize_check_id() {
  printf '%s' "$1" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]'
}

check_index_of() {
  local id="$1"
  local i
  for i in "${!CHECK_SEQUENCE[@]}"; do
    if [[ "${CHECK_SEQUENCE[$i]}" == "$id" ]]; then
      echo "$i"
      return 0
    fi
  done
  echo "-1"
  return 1
}

is_known_check_id() {
  local id="$1"
  [[ "$(check_index_of "$id")" -ge 0 ]]
}

is_selected_marked() {
  local id="$1"
  local selected
  for selected in "${SELECTED_CHECKS[@]-}"; do
    if [[ "$selected" == "$id" ]]; then
      return 0
    fi
  done
  return 1
}

mark_selected_check() {
  local id="$1"
  if ! is_selected_marked "$id"; then
    SELECTED_CHECKS+=("$id")
    SELECTED_CHECKS_COUNT=$((SELECTED_CHECKS_COUNT + 1))
  fi
}

if [[ -n "$ONLY_CHECKS_CSV" && ( -n "$FROM_CHECK" || -n "$TO_CHECK" ) ]]; then
  echo "Parametros invalidos: use --only-check OU --from-check/--to-check (nao ambos)." >&2
  exit 1
fi

if [[ -n "$ONLY_CHECKS_CSV" ]]; then
  CHECK_FILTER_ACTIVE=1
  IFS=',' read -r -a only_items <<< "$ONLY_CHECKS_CSV"
  for raw_id in "${only_items[@]}"; do
    id="$(normalize_check_id "$raw_id")"
    if [[ -z "$id" ]]; then
      continue
    fi
    if ! is_known_check_id "$id"; then
      echo "Check invalido em --only-check: $raw_id" >&2
      exit 1
    fi
    mark_selected_check "$id"
  done
  if [[ "$SELECTED_CHECKS_COUNT" -eq 0 ]]; then
    echo "Nenhum check valido foi informado em --only-check." >&2
    exit 1
  fi
  CHECK_FILTER_DESC="ONLY:$(printf '%s\n' "${SELECTED_CHECKS[@]-}" | sort | paste -sd, -)"
elif [[ -n "$FROM_CHECK" || -n "$TO_CHECK" ]]; then
  CHECK_FILTER_ACTIVE=1
  from_id="$(normalize_check_id "${FROM_CHECK:-${CHECK_SEQUENCE[0]}}")"
  to_id="$(normalize_check_id "${TO_CHECK:-${CHECK_SEQUENCE[${#CHECK_SEQUENCE[@]}-1]}}")"
  if ! is_known_check_id "$from_id"; then
    echo "Check invalido em --from-check: $from_id" >&2
    exit 1
  fi
  if ! is_known_check_id "$to_id"; then
    echo "Check invalido em --to-check: $to_id" >&2
    exit 1
  fi
  from_idx="$(check_index_of "$from_id")"
  to_idx="$(check_index_of "$to_id")"
  if [[ "$from_idx" -gt "$to_idx" ]]; then
    echo "Intervalo invalido: --from-check $from_id vem depois de --to-check $to_id." >&2
    exit 1
  fi
  for id in "${CHECK_SEQUENCE[@]}"; do
    idx="$(check_index_of "$id")"
    if [[ "$idx" -ge "$from_idx" && "$idx" -le "$to_idx" ]]; then
      mark_selected_check "$id"
    fi
  done
  CHECK_FILTER_DESC="RANGE:${from_id}-${to_id}"
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

require_cmd curl
require_cmd jq
require_cmd docker
require_cmd mvn

log() {
  echo "[business-gate] $*"
}

infer_java_major() {
  if ! command -v java >/dev/null 2>&1; then
    return 0
  fi
  java -version 2>&1 | awk -F[\".] 'NR==1 { if ($2 == "1") print $3; else print $2 }'
}

CONTRACT_TEST_MVN_ARGS="${CONTRACT_TEST_MVN_ARGS:-}"
if [[ -z "$CONTRACT_TEST_MVN_ARGS" ]]; then
  JAVA_MAJOR="$(infer_java_major)"
  if [[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" != "25" ]]; then
    CONTRACT_TEST_MVN_ARGS="-Denforcer.skip=true"
    log "Java ${JAVA_MAJOR} detectado fora da faixa 25.x; contrato rodara com ${CONTRACT_TEST_MVN_ARGS}."
  fi
fi
CONTRACT_TEST_MVN_ARGS_ARRAY=()
if [[ -n "$CONTRACT_TEST_MVN_ARGS" ]]; then
  read -r -a CONTRACT_TEST_MVN_ARGS_ARRAY <<< "$CONTRACT_TEST_MVN_ARGS"
fi

is_check_selected() {
  local id="$1"
  if [[ "$CHECK_FILTER_ACTIVE" -eq 0 ]]; then
    return 0
  fi
  is_selected_marked "$id"
}

expand_scope_checks() {
  local scope_id="$1"
  case "$scope_id" in
    R05-R11-R13)
      echo "R05 R06 R07 R08 R09 R10 R11 R13"
      return
      ;;
  esac
  if [[ "$scope_id" =~ ^R[0-9]{2}$ ]]; then
    echo "$scope_id"
    return
  fi
  printf '%s' "$scope_id" | grep -oE 'R[0-9]{2}' 2>/dev/null | tr '\n' ' ' || true
}

should_run_scope() {
  local scope_id="$1"
  local ids
  ids="$(expand_scope_checks "$scope_id")"
  if [[ -z "$ids" ]]; then
    return 0
  fi
  for id in $ids; do
    if is_check_selected "$id"; then
      return 0
    fi
  done
  return 1
}

pace_sleep() {
  local reason="$1"
  if [[ "$PACE_SECONDS" -le 0 ]]; then
    return 0
  fi
  log "Pausa timed (${PACE_SECONDS}s): ${reason}" >&2
  sleep "$PACE_SECONDS"
}

contains_fixed_text() {
  local needle="$1"
  local file="$2"
  if command -v rg >/dev/null 2>&1; then
    rg -F "$needle" "$file" >/dev/null 2>&1
    return
  fi
  grep -F "$needle" "$file" >/dev/null 2>&1
}

sql_escape() {
  printf "%s" "$1" | sed "s/'/''/g"
}

psql_query() {
  local sql="$1"
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="${DB_PASSWORD:-postgres}" psql \
      -h "${DB_HOST:-localhost}" \
      -p "${DB_PORT:-5435}" \
      -U "$DB_USER" \
      -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 \
      -q \
      -Atc "$sql"
    return
  fi

  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -Atc "$sql"
}

psql_exec() {
  local sql="$1"
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="${DB_PASSWORD:-postgres}" psql \
      -h "${DB_HOST:-localhost}" \
      -p "${DB_PORT:-5435}" \
      -U "$DB_USER" \
      -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 \
      -q \
      -c "$sql" >/dev/null
    return
  fi

  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -c "$sql" >/dev/null
}

api_post_capture() {
  local path="$1"
  local payload="$2"
  local raw
  raw="$(curl -sS -w $'\n%{http_code}' -X POST "$API_BASE$path" \
    -H 'Content-Type: application/json' \
    --data "$payload")"
  API_LAST_STATUS="${raw##*$'\n'}"
  API_LAST_BODY="${raw%$'\n'*}"
}

api_post_capture_with_header() {
  local path="$1"
  local payload="$2"
  local header_name="$3"
  local header_value="$4"
  local raw
  raw="$(curl -sS -w $'\n%{http_code}' -X POST "$API_BASE$path" \
    -H 'Content-Type: application/json' \
    -H "$header_name: $header_value" \
    --data "$payload")"
  API_LAST_STATUS="${raw##*$'\n'}"
  API_LAST_BODY="${raw%$'\n'*}"
}

api_post_capture_with_two_headers() {
  local path="$1"
  local payload="$2"
  local header_name_1="$3"
  local header_value_1="$4"
  local header_name_2="$5"
  local header_value_2="$6"
  local raw
  raw="$(curl -sS -w $'\n%{http_code}' -X POST "$API_BASE$path" \
    -H 'Content-Type: application/json' \
    -H "$header_name_1: $header_value_1" \
    -H "$header_name_2: $header_value_2" \
    --data "$payload")"
  API_LAST_STATUS="${raw##*$'\n'}"
  API_LAST_BODY="${raw%$'\n'*}"
}

api_get_capture() {
  local path="$1"
  local raw
  raw="$(curl -sS -w $'\n%{http_code}' "$API_BASE$path")"
  API_LAST_STATUS="${raw##*$'\n'}"
  API_LAST_BODY="${raw%$'\n'*}"
}

extract_single_value() {
  awk 'NF > 0 { print; exit }' | tr -d '[:space:]'
}

wait_for_execucao_with_rota() {
  local pedido_id="$1"
  local attempts="${2:-20}"
  local pause_seconds="${3:-1}"
  local body rota

  for _ in $(seq 1 "$attempts"); do
    api_get_capture "/api/pedidos/${pedido_id}/execucao"
    body="$API_LAST_BODY"
    rota="$(echo "$body" | jq -r '.rotaId // .rotaPrimariaId // 0')"
    if [[ "$API_LAST_STATUS" == "200" && "$rota" != "0" && "$rota" != "null" && -n "$rota" ]]; then
      printf '%s' "$body"
      return 0
    fi
    sleep "$pause_seconds"
  done

  return 1
}

is_required_check() {
  local id="$1"
  case "$id" in
    R01|R02|R03|R04|R05|R06|R07|R08|R09|R10|R11|R12|R13|R14|R15|R16|R17|R18|R19|R23|R24|R25|R26|R27|R28|R29|R30|R31|R32|R33|R34|R35|R36|R37|R38|R39|R40|R41|R42|R43|R44|R45|R46|R47|R48|R49)
      echo "true"
      ;;
    *)
      echo "false"
      ;;
  esac
}

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ARTIFACT_DIR="${ARTIFACT_DIR:-$ROOT_DIR/artifacts/poc/business-gate-$TIMESTAMP}"
mkdir -p "$ARTIFACT_DIR"
CHECKS_NDJSON="$ARTIFACT_DIR/checks.ndjson"
: > "$CHECKS_NDJSON"

OVERALL_FAIL=0

record_check() {
  local id="$1"
  local name="$2"
  local status="$3"
  local evidence="$4"
  local detail="$5"
  local required
  local final_status="$status"
  local final_detail="$detail"

  if ! is_check_selected "$id"; then
    return 0
  fi

  required="$(is_required_check "$id")"

  if [[ "$MODE" == "strict" && "$required" == "true" ]]; then
    if [[ "$final_status" == "SKIPPED" || "$final_status" == "NOT_ELIGIBLE" ]]; then
      final_status="FAIL"
      final_detail="Status $status invalido para check obrigatorio em modo strict. $detail"
    fi
  fi

  if [[ "$required" == "true" && "$final_status" != "PASS" ]]; then
    OVERALL_FAIL=1
  fi

  jq -cn \
    --arg id "$id" \
    --arg name "$name" \
    --argjson required "$required" \
    --arg status "$final_status" \
    --arg evidencePath "$evidence" \
    --arg detail "$final_detail" \
    '{id:$id,name:$name,required:$required,status:$status,evidencePath:$evidencePath,detail:$detail}' \
    >> "$CHECKS_NDJSON"
}

reset_state_for_check() {
  local check_dir="$1"
  local scope_id
  scope_id="$(basename "$check_dir")"
  if ! should_run_scope "$scope_id"; then
    return 2
  fi
  set +e
  (
    cd "$ROOT_DIR"
    DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" scripts/poc/reset-test-state.sh \
      > "$check_dir/reset.log" 2>&1
  )
  local reset_exit="$?"
  set -e
  return "$reset_exit"
}

seed_cliente() {
  local telefone="$1"
  local nome="$2"
  local saldo="$3"
  local telefone_esc nome_esc
  telefone_esc="$(sql_escape "$telefone")"
  nome_esc="$(sql_escape "$nome")"

  psql_query "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude)
VALUES ('${nome_esc}', '${telefone_esc}', 'PF', 'Rua Business Gate', -16.7310, -43.8710)
ON CONFLICT (telefone) DO UPDATE
SET nome = EXCLUDED.nome,
    endereco = EXCLUDED.endereco,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    atualizado_em = CURRENT_TIMESTAMP
RETURNING id;" | extract_single_value

  if [[ -n "$saldo" ]]; then
    psql_exec "INSERT INTO saldo_vales (cliente_id, quantidade)
SELECT id, ${saldo} FROM clientes WHERE telefone='${telefone_esc}'
ON CONFLICT (cliente_id) DO UPDATE
SET quantidade = EXCLUDED.quantidade,
    atualizado_em = CURRENT_TIMESTAMP;"
  fi
}

ATENDENTE_ID=""
ENTREGADOR_BASE_ID=""

prepare_base_users() {
  ATENDENTE_ID="$(psql_query "SELECT id FROM users WHERE email = 'base.atendente@aguaviva.local' LIMIT 1;" | extract_single_value)"
  ENTREGADOR_BASE_ID="$(psql_query "SELECT id FROM users WHERE email = 'base.entregador@aguaviva.local' LIMIT 1;" | extract_single_value)"
}

new_check_dir() {
  local id="$1"
  local dir="$ARTIFACT_DIR/$id"
  mkdir -p "$dir"
  if should_run_scope "$id"; then
    pace_sleep "antes de executar escopo ${id}"
    log "Escopo ${id}: EXECUTAR" >&2
  else
    log "Escopo ${id}: PULAR (fora do recorte selecionado)" >&2
  fi
  echo "$dir"
}

save_api_response() {
  local file="$1"
  {
    echo "status=$API_LAST_STATUS"
    echo "$API_LAST_BODY" | jq . 2>/dev/null || echo "$API_LAST_BODY"
  } > "$file"
}

log "Artifact dir: $ARTIFACT_DIR"
log "Pacing: ${PACE_SECONDS}s"
if [[ "$CHECK_FILTER_ACTIVE" -eq 1 ]]; then
  log "Filtro de checks ativo: ${CHECK_FILTER_DESC}"
else
  log "Filtro de checks: ALL"
fi

API_PORT="$(printf '%s' "$API_BASE" | sed -nE 's#^https?://[^:]+:([0-9]+).*$#\1#p')"
if [[ -z "$API_PORT" ]]; then
  API_PORT="8082"
fi

START_LOG="$ARTIFACT_DIR/start-test-env.log"
set +e
(
  cd "$ROOT_DIR"
  API_PORT="$API_PORT" \
  API_BASE="$API_BASE" \
  FORCE_API_RESTART=1 \
  DB_CONTAINER="$DB_CONTAINER" \
  POSTGRES_DB="$DB_NAME" \
  POSTGRES_USER="$DB_USER" \
  SOLVER_REBUILD="$SOLVER_REBUILD" \
  scripts/poc/start-test-env.sh
) > "$START_LOG" 2>&1
START_EXIT="$?"
set -e

if [[ "$START_EXIT" -ne 0 ]]; then
  record_check "R01" "Pedido so entra com atendenteId valido" "FAIL" "$START_LOG" "Falha no bootstrap do ambiente de teste."
  record_check "R02" "externalCallId idempotente nao duplica pedido" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R03" "Checkout VALE sem saldo bloqueia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R04" "Checkout VALE com saldo permite" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R05" "Debito de VALE unico na entrega" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R06" "Evento terminal so em EM_EXECUCAO" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R07" "ROTA_INICIADA idempotente" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R08" "externalEventId igual + payload igual retorna idempotente" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R09" "externalEventId igual + payload divergente retorna 409" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R10" "actorEntregadorId divergente bloqueia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R11" "CANCELADO/FALHOU disparam trilha de replanejamento" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R12" "Replanejamento sem 500 no cenario canonico" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R13" "EM_EXECUCAO nao reatribuido indevidamente" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R14" "Maximo 1 rota PLANEJADA por entregador/dia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R15" "Maximo 1 rota EM_ANDAMENTO por entregador/dia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R16" "Capacidade por entregador respeitada" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R17" "Promocao de fila quando elegivel" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R18" "Timeline final coerente com eventos" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R19" "dispatch_events pendente/processado coerentes" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R20" "Painel operacional consistente com banco" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R21" "Feed operacional ordena e limita corretamente" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R22" "Mapa operacional sem mistura indevida de camada" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R23" "OpenAPI cobre endpoints operacionais efetivos" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R24" "Testes de contrato bloqueiam drift" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R25" "Nenhum pedido com mais de uma entrega aberta" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R26" "Feed de jobs de replanejamento consistente e limitado" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R27" "Detalhe de job correlaciona plan_version com rotas e pedidos" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R28" "Clientes e deposito dentro da geofence operacional" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R29" "Cenario oficial 1/2/N entregadores com capacidade igual/diferente" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R30" "Perfil de frota MOTO/CARRO parametriza capacidade sem alterar regra core" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R31" "SLA minimo operacional (pedido->rota, rota->inicio)" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R32" "Escala 4 entregadores/50 pedidos com janelas mistas e giro operacional" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R33" "Manual cria cliente novo e pedido com dados completos" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R34" "Canal automatico exige sourceEventId e rejeita manualRequestId" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R35" "Idempotencia omnichannel por origemCanal+sourceEventId" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R36" "Manual retorna pedido aberto mesmo com cadastro degradado" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R37" "Schema aplica unicidade por telefone normalizado" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R38" "Reuso de chave idempotente com payload divergente retorna conflito" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R39" "MANUAL aplica fallback de externalCallId como chave idempotente" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R40" "MANUAL aceita X-Idempotency-Key como alias de chave idempotente" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R41" "externalCallId deve ser consistente com sourceEventId/manualRequestId" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R42" "MANUAL com Idempotency-Key e externalCallId exige consistencia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R43" "MANUAL com X-Idempotency-Key e externalCallId exige consistencia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R44" "Origem omitida com externalCallId + Idempotency-Key exige consistencia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R45" "Origem omitida com externalCallId + X-Idempotency-Key exige consistencia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R46" "Origem omitida com externalCallId + headers duplos exige consistencia completa" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R47" "Canal automatico com sourceEventId e header exige consistencia" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R48" "Canal automatico com sourceEventId + headers duplos exige consistencia completa" "SKIPPED" "$START_LOG" "Bootstrap falhou"
  record_check "R49" "Origem omitida com sourceEventId segue regras de canal automatico" "SKIPPED" "$START_LOG" "Bootstrap falhou"

  log "Bootstrap do ambiente falhou (exit=$START_EXIT). Evidencia completa em: $START_LOG"
  if [[ -s "$START_LOG" ]]; then
    log "Tail do bootstrap (ultimas 120 linhas):"
    while IFS= read -r line; do
      echo "[business-gate][bootstrap] $line"
    done < <(tail -n 120 "$START_LOG")
  else
    log "Arquivo de log do bootstrap ausente ou vazio."
  fi
else
  # R01
  check_dir="$(new_check_dir R01)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    payload="$(jq -n --arg externalCallId "bg-r01-$(date +%s)-$RANDOM" --arg telefone "(38) 99990-1001" --argjson quantidadeGaloes 1 --argjson atendenteId 999999 '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')"
    api_post_capture "/api/atendimento/pedidos" "$payload"
    save_api_response "$check_dir/response.txt"
    if [[ "$API_LAST_STATUS" == "400" ]]; then
      record_check "R01" "Pedido so entra com atendenteId valido" "PASS" "$check_dir/response.txt" "API rejeitou atendenteId invalido com 400."
    else
      record_check "R01" "Pedido so entra com atendenteId valido" "FAIL" "$check_dir/response.txt" "Esperado 400 para atendenteId invalido, obtido $API_LAST_STATUS."
    fi
  else
    record_check "R01" "Pedido so entra com atendenteId valido" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R02
  check_dir="$(new_check_dir R02)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    seed_cliente "38999901002" "Cliente BG R02" "0" >/dev/null
    call_id="bg-r02-$(date +%s)-$RANDOM"
    payload="$(jq -n --arg externalCallId "$call_id" --arg telefone "(38) 99990-1002" --argjson quantidadeGaloes 1 --argjson atendenteId "$ATENDENTE_ID" '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')"
    api_post_capture "/api/atendimento/pedidos" "$payload"
    status1="$API_LAST_STATUS"
    body1="$API_LAST_BODY"
    api_post_capture "/api/atendimento/pedidos" "$payload"
    status2="$API_LAST_STATUS"
    body2="$API_LAST_BODY"
    {
      echo "first_status=$status1"
      echo "$body1" | jq .
      echo "second_status=$status2"
      echo "$body2" | jq .
    } > "$check_dir/response.txt"

    pedido1="$(echo "$body1" | jq -r '.pedidoId // 0')"
    pedido2="$(echo "$body2" | jq -r '.pedidoId // 0')"
    idem2="$(echo "$body2" | jq -r '.idempotente // false')"
    if [[ "$status1" == "200" && "$status2" == "200" && "$pedido1" == "$pedido2" && "$idem2" == "true" ]]; then
      record_check "R02" "externalCallId idempotente nao duplica pedido" "PASS" "$check_dir/response.txt" "Mesmo externalCallId retornou mesmo pedido e idempotente=true."
    else
      record_check "R02" "externalCallId idempotente nao duplica pedido" "FAIL" "$check_dir/response.txt" "Esperado idempotencia forte por externalCallId."
    fi
  else
    record_check "R02" "externalCallId idempotente nao duplica pedido" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R03 + R04
  check_dir="$(new_check_dir R03-R04)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    seed_cliente "38999901003" "Cliente BG R03" "0" >/dev/null
    call_id_r03="bg-r03-$(date +%s)-$RANDOM"
    payload_r03="$(jq -n --arg externalCallId "$call_id_r03" --arg telefone "(38) 99990-1003" --argjson quantidadeGaloes 1 --argjson atendenteId "$ATENDENTE_ID" '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"VALE"}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r03"
    r03_status="$API_LAST_STATUS"
    r03_body="$API_LAST_BODY"

    psql_exec "UPDATE saldo_vales SET quantidade = 10 WHERE cliente_id = (SELECT id FROM clientes WHERE telefone='38999901003');"
    call_id_r04="bg-r04-$(date +%s)-$RANDOM"
    payload_r04="$(jq -n --arg externalCallId "$call_id_r04" --arg telefone "(38) 99990-1003" --argjson quantidadeGaloes 1 --argjson atendenteId "$ATENDENTE_ID" '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"VALE"}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r04"
    r04_status="$API_LAST_STATUS"
    r04_body="$API_LAST_BODY"

    {
      echo "r03_status=$r03_status"
      echo "$r03_body" | jq . 2>/dev/null || echo "$r03_body"
      echo "r04_status=$r04_status"
      echo "$r04_body" | jq . 2>/dev/null || echo "$r04_body"
    } > "$check_dir/response.txt"

    if [[ "$r03_status" == "400" ]]; then
      record_check "R03" "Checkout VALE sem saldo bloqueia" "PASS" "$check_dir/response.txt" "Checkout VALE sem saldo retornou 400."
    else
      record_check "R03" "Checkout VALE sem saldo bloqueia" "FAIL" "$check_dir/response.txt" "Esperado 400 no VALE sem saldo, obtido $r03_status."
    fi

    if [[ "$r04_status" == "200" ]]; then
      record_check "R04" "Checkout VALE com saldo permite" "PASS" "$check_dir/response.txt" "Checkout VALE com saldo retornou 200."
    else
      record_check "R04" "Checkout VALE com saldo permite" "FAIL" "$check_dir/response.txt" "Esperado 200 no VALE com saldo, obtido $r04_status."
    fi
  else
    record_check "R03" "Checkout VALE sem saldo bloqueia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R04" "Checkout VALE com saldo permite" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R05-R10 + R11 + R13 (fluxo operacional controlado)
  check_dir="$(new_check_dir R05-R11-R13)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    seed_cliente "38999901005" "Cliente BG Fluxo" "10" >/dev/null

    call_id="bg-flow-$(date +%s)-$RANDOM"
    payload_at="$(jq -n --arg externalCallId "$call_id" --arg telefone "(38) 99990-1005" --argjson quantidadeGaloes 1 --argjson atendenteId "$ATENDENTE_ID" '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"VALE"}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_at"
    pedido_status="$API_LAST_STATUS"
    pedido_body="$API_LAST_BODY"
    pedido_id="$(echo "$pedido_body" | jq -r '.pedidoId // 0')"

    if exec_body_1="$(wait_for_execucao_with_rota "$pedido_id" 30 1)"; then
      exec_status_1="200"
    else
      exec_status_1="408"
      exec_body_1='{}'
    fi
    rota_id="$(echo "$exec_body_1" | jq -r '.rotaId // .rotaPrimariaId // 0')"
    entrega_id="$(echo "$exec_body_1" | jq -r '.entregaId // 0')"

    # R06: terminal fora de EM_EXECUCAO
    ev_term_pre="$(jq -n --arg externalEventId "bg-r06-$(date +%s)-$RANDOM" --argjson entregaId "$entrega_id" '{eventType:"PEDIDO_ENTREGUE",externalEventId:$externalEventId,entregaId:$entregaId}')"
    api_post_capture "/api/eventos" "$ev_term_pre"
    r06_status="$API_LAST_STATUS"
    r06_body="$API_LAST_BODY"

    # R10: actor divergente
    ev_actor_bad="$(jq -n --arg externalEventId "bg-r10-$(date +%s)-$RANDOM" --argjson rotaId "$rota_id" --argjson actorEntregadorId 999999 '{eventType:"ROTA_INICIADA",externalEventId:$externalEventId,rotaId:$rotaId,actorEntregadorId:$actorEntregadorId}')"
    api_post_capture "/api/eventos" "$ev_actor_bad"
    r10_status="$API_LAST_STATUS"

    # R07: rota iniciada idempotente
    key_rota="bg-r07-$(date +%s)-$RANDOM"
    ev_rota="$(jq -n --arg externalEventId "$key_rota" --argjson rotaId "$rota_id" '{eventType:"ROTA_INICIADA",externalEventId:$externalEventId,rotaId:$rotaId}')"
    api_post_capture "/api/eventos" "$ev_rota"
    r07_status_1="$API_LAST_STATUS"
    r07_body_1="$API_LAST_BODY"
    api_post_capture "/api/eventos" "$ev_rota"
    r07_status_2="$API_LAST_STATUS"
    r07_body_2="$API_LAST_BODY"

    api_get_capture "/api/pedidos/${pedido_id}/execucao"
    exec_status_2="$API_LAST_STATUS"
    exec_body_2="$API_LAST_BODY"
    entrega_ativa="$(echo "$exec_body_2" | jq -r '.entregaAtivaId // .entregaId // 0')"

    # R13: cria segundo pedido e replaneja sem trocar entrega ativa
    call_id_2="bg-flow2-$(date +%s)-$RANDOM"
    payload_at2="$(jq -n --arg externalCallId "$call_id_2" --arg telefone "(38) 99990-1006" --argjson quantidadeGaloes 1 --argjson atendenteId "$ATENDENTE_ID" '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_at2"
    sleep 1
    api_get_capture "/api/pedidos/${pedido_id}/execucao"
    exec_status_3="$API_LAST_STATUS"
    exec_body_3="$API_LAST_BODY"
    entrega_ativa_pos_rep="$(echo "$exec_body_3" | jq -r '.entregaAtivaId // .entregaId // 0')"
    camada_pos_rep="$(echo "$exec_body_3" | jq -r '.camada // ""')"

    # R08/R09/R11/R05
    key_terminal="bg-r08-r09-$(date +%s)-$RANDOM"
    ev_cancel="$(jq -n --arg externalEventId "$key_terminal" --argjson entregaId "$entrega_ativa" '{eventType:"PEDIDO_CANCELADO",externalEventId:$externalEventId,entregaId:$entregaId,motivo:"check idempotencia",cobrancaCancelamentoCentavos:2500}')"
    api_post_capture "/api/eventos" "$ev_cancel"
    r08_status_1="$API_LAST_STATUS"
    r08_body_1="$API_LAST_BODY"
    api_post_capture "/api/eventos" "$ev_cancel"
    r08_status_2="$API_LAST_STATUS"
    r08_body_2="$API_LAST_BODY"
    ev_divergente="$(jq -n --arg externalEventId "$key_terminal" --argjson entregaId "$entrega_ativa" '{eventType:"PEDIDO_FALHOU",externalEventId:$externalEventId,entregaId:$entregaId,motivo:"payload divergente"}')"
    api_post_capture "/api/eventos" "$ev_divergente"
    r09_status="$API_LAST_STATUS"

    # Novo fluxo entregue para validar debito unico (R05)
    call_id_3="bg-r05-$(date +%s)-$RANDOM"
    payload_at3="$(jq -n --arg externalCallId "$call_id_3" --arg telefone "(38) 99990-1007" --argjson quantidadeGaloes 1 --argjson atendenteId "$ATENDENTE_ID" '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"VALE"}')"
    seed_cliente "38999901007" "Cliente BG R05" "10" >/dev/null
    api_post_capture "/api/atendimento/pedidos" "$payload_at3"
    pedido_r05="$(echo "$API_LAST_BODY" | jq -r '.pedidoId // 0')"
    if exec_body_r05="$(wait_for_execucao_with_rota "$pedido_r05" 30 1)"; then
      rota_r05="$(echo "$exec_body_r05" | jq -r '.rotaId // .rotaPrimariaId // 0')"
      entrega_r05="$(echo "$exec_body_r05" | jq -r '.entregaId // 0')"
    else
      rota_r05="0"
      entrega_r05="0"
    fi
    key_rota_r05="bg-r05-rota-$(date +%s)-$RANDOM"
    ev_rota_r05="$(jq -n --arg externalEventId "$key_rota_r05" --argjson rotaId "$rota_r05" '{eventType:"ROTA_INICIADA",externalEventId:$externalEventId,rotaId:$rotaId}')"
    api_post_capture "/api/eventos" "$ev_rota_r05"
    api_get_capture "/api/pedidos/${pedido_r05}/execucao"
    entrega_r05_exec="$(echo "$API_LAST_BODY" | jq -r '.entregaAtivaId // .entregaId // 0')"
    key_entrega_r05="bg-r05-entrega-$(date +%s)-$RANDOM"
    ev_entregue_r05="$(jq -n --arg externalEventId "$key_entrega_r05" --argjson entregaId "$entrega_r05_exec" '{eventType:"PEDIDO_ENTREGUE",externalEventId:$externalEventId,entregaId:$entregaId}')"
    api_post_capture "/api/eventos" "$ev_entregue_r05"
    r05_status_1="$API_LAST_STATUS"
    api_post_capture "/api/eventos" "$ev_entregue_r05"
    r05_status_2="$API_LAST_STATUS"
    r05_body_2="$API_LAST_BODY"
    debito_count="$(psql_query "SELECT COUNT(*) FROM movimentacao_vales WHERE pedido_id = ${pedido_r05} AND tipo = 'DEBITO';" | extract_single_value)"

    # Fluxo dedicado para gerar PEDIDO_FALHOU real (R11)
    call_id_falha="bg-r11-falha-$(date +%s)-$RANDOM"
    payload_falha="$(jq -n --arg externalCallId "$call_id_falha" --arg telefone "(38) 99990-1008" --argjson quantidadeGaloes 1 --argjson atendenteId "$ATENDENTE_ID" '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')"
    seed_cliente "38999901008" "Cliente BG R11" "" >/dev/null
    api_post_capture "/api/atendimento/pedidos" "$payload_falha"
    pedido_falha="$(echo "$API_LAST_BODY" | jq -r '.pedidoId // 0')"
    if exec_body_falha="$(wait_for_execucao_with_rota "$pedido_falha" 30 1)"; then
      rota_falha="$(echo "$exec_body_falha" | jq -r '.rotaId // .rotaPrimariaId // 0')"
    else
      rota_falha="0"
    fi
    ev_rota_falha="$(jq -n --arg externalEventId "bg-r11-rota-$(date +%s)-$RANDOM" --argjson rotaId "$rota_falha" '{eventType:"ROTA_INICIADA",externalEventId:$externalEventId,rotaId:$rotaId}')"
    api_post_capture "/api/eventos" "$ev_rota_falha"
    api_get_capture "/api/pedidos/${pedido_falha}/execucao"
    entrega_falha="$(echo "$API_LAST_BODY" | jq -r '.entregaAtivaId // .entregaId // 0')"
    ev_falha="$(jq -n --arg externalEventId "bg-r11-falha-evt-$(date +%s)-$RANDOM" --argjson entregaId "$entrega_falha" '{eventType:"PEDIDO_FALHOU",externalEventId:$externalEventId,entregaId:$entregaId,motivo:"teste r11"}')"
    api_post_capture "/api/eventos" "$ev_falha"
    r11_falha_status="$API_LAST_STATUS"

    # R11: gatilho cancel/falha no outbox
    count_cancel_evt="$(psql_query "SELECT COUNT(*) FROM dispatch_events WHERE event_type='PEDIDO_CANCELADO';" | extract_single_value)"
    count_falha_evt="$(psql_query "SELECT COUNT(*) FROM dispatch_events WHERE event_type='PEDIDO_FALHOU';" | extract_single_value)"

    {
      echo "pedido_status=$pedido_status"
      echo "exec_status_1=$exec_status_1"
      echo "exec_status_2=$exec_status_2"
      echo "exec_status_3=$exec_status_3"
      echo "r06_status=$r06_status"
      echo "$r06_body" | jq . 2>/dev/null || echo "$r06_body"
      echo "r10_status=$r10_status"
      echo "r07_status_1=$r07_status_1"
      echo "r07_status_2=$r07_status_2"
      echo "$r07_body_2" | jq . 2>/dev/null || echo "$r07_body_2"
      echo "entrega_ativa=$entrega_ativa"
      echo "entrega_ativa_pos_rep=$entrega_ativa_pos_rep"
      echo "camada_pos_rep=$camada_pos_rep"
      echo "r08_status_1=$r08_status_1"
      echo "r08_status_2=$r08_status_2"
      echo "$r08_body_2" | jq . 2>/dev/null || echo "$r08_body_2"
      echo "r09_status=$r09_status"
      echo "r05_status_1=$r05_status_1"
      echo "r05_status_2=$r05_status_2"
      echo "$r05_body_2" | jq . 2>/dev/null || echo "$r05_body_2"
      echo "debito_count=$debito_count"
      echo "r11_falha_status=$r11_falha_status"
      echo "count_cancel_evt=$count_cancel_evt"
      echo "count_falha_evt=$count_falha_evt"
    } > "$check_dir/evidence.txt"

    # Avaliacoes
    if [[ "$r05_status_1" == "200" && "$r05_status_2" == "200" && "$debito_count" == "1" ]]; then
      record_check "R05" "Debito de VALE acontece uma unica vez" "PASS" "$check_dir/evidence.txt" "Debito de VALE unico por pedido validado."
    else
      record_check "R05" "Debito de VALE acontece uma unica vez" "FAIL" "$check_dir/evidence.txt" "Falha na idempotencia do debito de VALE."
    fi

    if [[ "$r06_status" == "409" ]]; then
      record_check "R06" "Evento terminal so em EM_EXECUCAO" "PASS" "$check_dir/evidence.txt" "Evento terminal fora de EM_EXECUCAO retornou 409."
    else
      record_check "R06" "Evento terminal so em EM_EXECUCAO" "FAIL" "$check_dir/evidence.txt" "Esperado 409, obtido $r06_status."
    fi

    r07_idem="$(echo "$r07_body_2" | jq -r '.idempotente // false')"
    if [[ "$r07_status_1" == "200" && "$r07_status_2" == "200" && "$r07_idem" == "true" ]]; then
      record_check "R07" "ROTA_INICIADA idempotente" "PASS" "$check_dir/evidence.txt" "Replay de ROTA_INICIADA retornou idempotente=true."
    else
      record_check "R07" "ROTA_INICIADA idempotente" "FAIL" "$check_dir/evidence.txt" "Idempotencia de ROTA_INICIADA nao comprovada."
    fi

    r08_idem="$(echo "$r08_body_2" | jq -r '.idempotente // false')"
    if [[ "$r08_status_1" == "200" && "$r08_status_2" == "200" && "$r08_idem" == "true" ]]; then
      record_check "R08" "externalEventId igual + payload igual retorna idempotente" "PASS" "$check_dir/evidence.txt" "Replay identico retornou idempotente=true."
    else
      record_check "R08" "externalEventId igual + payload igual retorna idempotente" "FAIL" "$check_dir/evidence.txt" "Replay identico nao retornou idempotente."
    fi

    if [[ "$r09_status" == "409" ]]; then
      record_check "R09" "externalEventId igual + payload diferente retorna 409" "PASS" "$check_dir/evidence.txt" "Conflito por payload divergente validado."
    else
      record_check "R09" "externalEventId igual + payload diferente retorna 409" "FAIL" "$check_dir/evidence.txt" "Esperado 409 para payload divergente, obtido $r09_status."
    fi

    if [[ "$r10_status" == "409" ]]; then
      record_check "R10" "actorEntregadorId divergente bloqueia" "PASS" "$check_dir/evidence.txt" "Ownership operacional bloqueou actor divergente com 409."
    else
      record_check "R10" "actorEntregadorId divergente bloqueia" "FAIL" "$check_dir/evidence.txt" "Esperado 409 para actor divergente, obtido $r10_status."
    fi

    if [[ "$r11_falha_status" == "200" && "$count_cancel_evt" -ge 1 && "$count_falha_evt" -ge 1 ]]; then
      record_check "R11" "CANCELADO/FALHOU disparam trilha de replanejamento" "PASS" "$check_dir/evidence.txt" "Eventos terminal publicados no outbox de dispatch."
    else
      record_check "R11" "CANCELADO/FALHOU disparam trilha de replanejamento" "FAIL" "$check_dir/evidence.txt" "Nao foi possivel comprovar ambos eventos no outbox."
    fi

    if [[ "$entrega_ativa" == "$entrega_ativa_pos_rep" && "$camada_pos_rep" == "PRIMARIA_EM_EXECUCAO" ]]; then
      record_check "R13" "EM_EXECUCAO nao e reatribuido indevidamente" "PASS" "$check_dir/evidence.txt" "Entrega ativa permaneceu a mesma apos replanejamento."
    else
      record_check "R13" "EM_EXECUCAO nao e reatribuido indevidamente" "FAIL" "$check_dir/evidence.txt" "Entrega ativa/camada mudou de forma indevida no replanejamento."
    fi
  else
    record_check "R05" "Debito de VALE acontece uma unica vez" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R06" "Evento terminal so em EM_EXECUCAO" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R07" "ROTA_INICIADA idempotente" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R08" "externalEventId igual + payload igual retorna idempotente" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R09" "externalEventId igual + payload diferente retorna 409" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R10" "actorEntregadorId divergente bloqueia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R11" "CANCELADO/FALHOU disparam trilha de replanejamento" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R13" "EM_EXECUCAO nao e reatribuido indevidamente" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R12 + R18 via gate e2e oficial
  check_dir="$(new_check_dir R12-R18)"
  if should_run_scope "R12-R18"; then
    E2E_DIR="$check_dir/e2e"
    mkdir -p "$E2E_DIR"
    E2E_EXIT=1
    gate_summary="$E2E_DIR/gate-summary.json"
    rounds_ok=0
    rounds_requested=0
    e2e_attempt=1
    e2e_max_attempts=2
    : > "$check_dir/run-e2e.log"
    while [[ "$e2e_attempt" -le "$e2e_max_attempts" ]]; do
      set +e
      (
        cd "$ROOT_DIR"
        cmd=(scripts/poc/run-e2e-local.sh --mode "$MODE" --rounds "$ROUNDS")
        if [[ "$KEEP_RUNNING" -eq 1 ]]; then
          cmd+=(--keep-running)
        fi
        API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
          SOLVER_REBUILD="$SOLVER_REBUILD" SUITE_DEBOUNCE_SEGUNDOS="$PACE_SECONDS" ARTIFACT_DIR="$E2E_DIR" "${cmd[@]}"
      ) >> "$check_dir/run-e2e.log" 2>&1
      E2E_EXIT="$?"
      set -e

      rounds_ok=0
      rounds_requested=0
      if [[ -f "$gate_summary" ]]; then
        rounds_ok="$(jq -r '.roundsOk // 0' "$gate_summary")"
        rounds_requested="$(jq -r '.roundsRequested // 0' "$gate_summary")"
      fi

      if [[ "$E2E_EXIT" -eq 0 && "$rounds_ok" == "$rounds_requested" ]]; then
        break
      fi
      if [[ "$e2e_attempt" -lt "$e2e_max_attempts" ]]; then
        sleep 2
      fi
      e2e_attempt=$((e2e_attempt + 1))
    done

    if [[ "$E2E_EXIT" -eq 0 && "$rounds_ok" == "$rounds_requested" ]]; then
      record_check "R12" "Replanejamento nao retorna 500 no cenario canonico" "PASS" "$check_dir/run-e2e.log" "Gate E2E strict executou com sucesso em todas as rodadas."
    else
      record_check "R12" "Replanejamento nao retorna 500 no cenario canonico" "FAIL" "$check_dir/run-e2e.log" "Gate E2E falhou ou nao concluiu todas as rodadas (ok=$rounds_ok req=$rounds_requested)."
    fi

    timeline_ok=1
    if [[ -f "$gate_summary" ]]; then
      while IFS= read -r round_dir; do
        summary_file="$round_dir/poc-suite/summary.json"
        if [[ ! -f "$summary_file" ]]; then
          timeline_ok=0
          continue
        fi
        if ! jq -e '.scenarios | all(.validacao == "OK")' "$summary_file" >/dev/null 2>&1; then
          timeline_ok=0
        fi
      done < <(jq -r '.rounds[].artifactDir' "$gate_summary")
    else
      timeline_ok=0
    fi

    if [[ "$timeline_ok" -eq 1 ]]; then
      record_check "R18" "Timeline final coerente com evento operacional" "PASS" "$gate_summary" "Todos os cenarios da suite terminaram com validacao=OK."
    else
      record_check "R18" "Timeline final coerente com evento operacional" "FAIL" "$check_dir/run-e2e.log" "Falha de coerencia em timelines/summaries da suite PoC."
    fi
  fi

  # R14 e R15
  check_dir="$(new_check_dir R14-R15)"
  if reset_state_for_check "$check_dir"; then
    index14="$(psql_query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND indexname='uk_rotas_planejada_entregador_data';" | extract_single_value)"
    index15="$(psql_query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND indexname='uk_rotas_andamento_entregador_data';" | extract_single_value)"

    prepare_base_users

    set +e
    psql_exec "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 101, 'PLANEJADA');
INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 102, 'PLANEJADA');" \
      >/dev/null 2>"$check_dir/dup14-error.log"
    dup14_exit="$?"
    set -e

    set +e
    psql_exec "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 201, 'EM_ANDAMENTO');
INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 202, 'EM_ANDAMENTO');" \
      >/dev/null 2>"$check_dir/dup15-error.log"
    dup15_exit="$?"
    set -e

    {
      echo "index14=$index14"
      echo "index15=$index15"
      echo "dup14_exit=$dup14_exit"
      echo "dup15_exit=$dup15_exit"
      if [[ -s "$check_dir/dup14-error.log" ]]; then
        echo "dup14_error=$(tr '\n' ' ' < "$check_dir/dup14-error.log" | sed 's/[[:space:]]\+/ /g')"
      fi
      if [[ -s "$check_dir/dup15-error.log" ]]; then
        echo "dup15_error=$(tr '\n' ' ' < "$check_dir/dup15-error.log" | sed 's/[[:space:]]\+/ /g')"
      fi
    } > "$check_dir/evidence.txt"

    if [[ "$index14" -ge 1 && "$dup14_exit" -ne 0 ]]; then
      record_check "R14" "No maximo 1 rota PLANEJADA por entregador/dia" "PASS" "$check_dir/evidence.txt" "Indice unico parcial ativo e bloqueando duplicidade."
    else
      record_check "R14" "No maximo 1 rota PLANEJADA por entregador/dia" "FAIL" "$check_dir/evidence.txt" "Restricao de rota PLANEJADA nao comprovada."
    fi

    if [[ "$index15" -ge 1 && "$dup15_exit" -ne 0 ]]; then
      record_check "R15" "No maximo 1 rota EM_ANDAMENTO por entregador/dia" "PASS" "$check_dir/evidence.txt" "Indice unico parcial ativo e bloqueando duplicidade."
    else
      record_check "R15" "No maximo 1 rota EM_ANDAMENTO por entregador/dia" "FAIL" "$check_dir/evidence.txt" "Restricao de rota EM_ANDAMENTO nao comprovada."
    fi
  else
    record_check "R14" "No maximo 1 rota PLANEJADA por entregador/dia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
    record_check "R15" "No maximo 1 rota EM_ANDAMENTO por entregador/dia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R16
  check_dir="$(new_check_dir R16)"
  if should_run_scope "R16"; then
    cap_ok="$(psql_query "WITH cap AS (
  SELECT valor::int AS capacidade FROM configuracoes WHERE chave = 'capacidade_veiculo'
), cargas AS (
  SELECT r.id, COALESCE(SUM(p.quantidade_galoes), 0) AS carga
  FROM rotas r
  LEFT JOIN entregas e ON e.rota_id = r.id AND e.status::text IN ('PENDENTE', 'EM_EXECUCAO')
  LEFT JOIN pedidos p ON p.id = e.pedido_id
  WHERE r.data = CURRENT_DATE AND r.status::text IN ('PLANEJADA', 'EM_ANDAMENTO')
  GROUP BY r.id
)
SELECT CASE
  WHEN NOT EXISTS (SELECT 1 FROM cargas) THEN 'true'
  ELSE CASE WHEN (SELECT MAX(carga) FROM cargas) <= (SELECT capacidade FROM cap) THEN 'true' ELSE 'false' END
END;")"
  echo "cap_ok=$cap_ok" > "$check_dir/evidence.txt"
  if [[ "$cap_ok" == "true" ]]; then
    record_check "R16" "Capacidade por entregador e respeitada" "PASS" "$check_dir/evidence.txt" "Carga ativa por rota dentro da capacidade configurada."
  else
    record_check "R16" "Capacidade por entregador e respeitada" "FAIL" "$check_dir/evidence.txt" "Detectada carga acima da capacidade configurada."
  fi
  fi

  # R17
  check_dir="$(new_check_dir R17)"
  if should_run_scope "R17"; then
    r17_exit=1
    r17_confirmado=0
    r17_pendente=0
    r17_attempt=1
    r17_max_attempts=3
    : > "$check_dir/observe-promocoes.log"
    while [[ "$r17_attempt" -le "$r17_max_attempts" ]]; do
      set +e
      (
        cd "$ROOT_DIR"
        API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
          WORK_DIR="$check_dir/promocoes" SUMMARY_FILE="$check_dir/promocoes-summary.json" \
          REQUIRE_CONFIRMADO_EM_ROTA=1 REQUIRE_PENDENTE_CONFIRMADO=1 REQUIRE_NO_EM_ROTA_CONFIRMADO=1 \
          PROMO_DEBOUNCE_SEGUNDOS="$PACE_SECONDS" NUM_ENTREGADORES_ATIVOS=2 \
          scripts/poc/observe-promocoes.sh
      ) >> "$check_dir/observe-promocoes.log" 2>&1
      r17_exit="$?"
      set -e

      r17_confirmado=0
      r17_pendente=0
      if [[ -f "$check_dir/promocoes-summary.json" ]]; then
        r17_confirmado="$(jq -r '.transitions.confirmadoParaEmRota // 0' "$check_dir/promocoes-summary.json")"
        r17_pendente="$(jq -r '.transitions.pendenteParaConfirmado // 0' "$check_dir/promocoes-summary.json")"
      fi

      if [[ "$r17_exit" -eq 0 && "$r17_confirmado" -ge 1 && "$r17_pendente" -ge 1 ]]; then
        break
      fi
      if [[ "$r17_attempt" -lt "$r17_max_attempts" ]]; then
        sleep 1
      fi
      r17_attempt=$((r17_attempt + 1))
    done

    if [[ "$r17_exit" -eq 0 && "$r17_confirmado" -ge 1 && "$r17_pendente" -ge 1 ]]; then
      record_check "R17" "Promocao de fila ocorre quando elegivel" "PASS" "$check_dir/promocoes-summary.json" "Transicoes confirmadas em cenario elegivel controlado."
    else
      if [[ "$MODE" == "observe" && "$r17_pendente" -eq 0 ]]; then
        record_check "R17" "Promocao de fila ocorre quando elegivel" "NOT_ELIGIBLE" "$check_dir/observe-promocoes.log" "Sem promocao observavel no cenario atual."
      else
        record_check "R17" "Promocao de fila ocorre quando elegivel" "FAIL" "$check_dir/observe-promocoes.log" "Falha no setup/execucao de promocao estrita."
      fi
    fi
  fi

  # R19
  check_dir="$(new_check_dir R19)"
  if should_run_scope "R19"; then
    api_post_capture "/api/replanejamento/run" '{"debounceSegundos":0,"limiteEventos":200}'
    manual_replanejamento_status="$API_LAST_STATUS"
    manual_replanejamento_body="$API_LAST_BODY"
    pending_dispatch_stale=""
    processed_dispatch=""
    r19_attempt=1
    r19_max_attempts=5
    while [[ "$r19_attempt" -le "$r19_max_attempts" ]]; do
      pending_dispatch_stale="$(psql_query "SELECT COUNT(*) FROM dispatch_events WHERE status::text = 'PENDENTE' AND available_em <= (CURRENT_TIMESTAMP - INTERVAL '1 hour');" | extract_single_value)"
      processed_dispatch="$(psql_query "SELECT COUNT(*) FROM dispatch_events WHERE status::text = 'PROCESSADO';" | extract_single_value)"
      if [[ "$pending_dispatch_stale" == "0" ]]; then
        break
      fi
      if [[ "$r19_attempt" -lt "$r19_max_attempts" ]]; then
        sleep 1
      fi
      r19_attempt=$((r19_attempt + 1))
    done
    {
      echo "manual_replanejamento_status=$manual_replanejamento_status"
      echo "$manual_replanejamento_body" | jq . 2>/dev/null || echo "$manual_replanejamento_body"
      echo "pending_dispatch_stale=$pending_dispatch_stale"
      echo "processed_dispatch=$processed_dispatch"
    } > "$check_dir/evidence.txt"
    if [[ "$manual_replanejamento_status" == "409" && "$pending_dispatch_stale" == "0" && "$processed_dispatch" -ge 0 ]]; then
      record_check "R19" "dispatch_events pendente/processado coerentes" "PASS" "$check_dir/evidence.txt" "Endpoint manual desativado (409) e outbox sem pendentes envelhecidos."
    else
      record_check "R19" "dispatch_events pendente/processado coerentes" "FAIL" "$check_dir/evidence.txt" "Inconsistencia no estado do outbox dispatch_events."
    fi
  fi

  # R20
  check_dir="$(new_check_dir R20)"
  if should_run_scope "R20"; then
    api_get_capture "/api/operacao/painel"
    painel_status="$API_LAST_STATUS"
    painel_body="$API_LAST_BODY"
    sql_pendente="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='PENDENTE';" | extract_single_value)"
    sql_confirmado="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='CONFIRMADO';" | extract_single_value)"
    sql_em_rota="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='EM_ROTA';" | extract_single_value)"
    sql_entregue="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='ENTREGUE';" | extract_single_value)"
    sql_cancelado="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='CANCELADO';" | extract_single_value)"
    {
      echo "painel_status=$painel_status"
      echo "$painel_body" | jq . 2>/dev/null || echo "$painel_body"
      echo "sql_pendente=$sql_pendente"
      echo "sql_confirmado=$sql_confirmado"
      echo "sql_em_rota=$sql_em_rota"
      echo "sql_entregue=$sql_entregue"
      echo "sql_cancelado=$sql_cancelado"
    } > "$check_dir/evidence.txt"
    painel_ok=0
    if [[ "$painel_status" == "200" ]]; then
      if echo "$painel_body" | jq -e \
        --argjson p "$sql_pendente" \
        --argjson c "$sql_confirmado" \
        --argjson e "$sql_em_rota" \
        --argjson en "$sql_entregue" \
        --argjson ca "$sql_cancelado" \
        '.pedidosPorStatus.pendente == $p and .pedidosPorStatus.confirmado == $c and .pedidosPorStatus.emRota == $e and .pedidosPorStatus.entregue == $en and .pedidosPorStatus.cancelado == $ca' >/dev/null 2>&1; then
        painel_ok=1
      fi
    fi
    if [[ "$painel_ok" -eq 1 ]]; then
      record_check "R20" "Painel operacional consistente com banco" "PASS" "$check_dir/evidence.txt" "Contadores do painel coerentes com SQL."
    else
      record_check "R20" "Painel operacional consistente com banco" "FAIL" "$check_dir/evidence.txt" "Divergencia entre painel e SQL."
    fi
  fi

  # R21
  check_dir="$(new_check_dir R21)"
  if should_run_scope "R21"; then
    api_get_capture "/api/operacao/eventos?limite=5"
    feed_status="$API_LAST_STATUS"
    feed_body="$API_LAST_BODY"
    feed_ok=0
    feed_attempt=1
    feed_max_attempts=3
    while [[ "$feed_attempt" -le "$feed_max_attempts" ]]; do
      if [[ "$feed_status" == "200" ]]; then
        if echo "$feed_body" | jq -e '.eventos | (length <= 5)' >/dev/null 2>&1 \
          && echo "$feed_body" | jq -e '.eventos as $e | (($e | length) < 2 or (reduce range(1; ($e | length)) as $i (true; . and ($e[$i-1].id >= $e[$i].id))))' >/dev/null 2>&1; then
          feed_ok=1
          break
        fi
      fi
      if [[ "$feed_attempt" -lt "$feed_max_attempts" ]]; then
        sleep 1
        api_get_capture "/api/operacao/eventos?limite=5"
        feed_status="$API_LAST_STATUS"
        feed_body="$API_LAST_BODY"
      fi
      feed_attempt=$((feed_attempt + 1))
    done
    {
      echo "feed_status=$feed_status"
      echo "$feed_body" | jq . 2>/dev/null || echo "$feed_body"
    } > "$check_dir/evidence.txt"
    if [[ "$feed_ok" -eq 1 ]]; then
      record_check "R21" "Feed operacional ordena e limita corretamente" "PASS" "$check_dir/evidence.txt" "Limite e ordenacao (id desc) validados no feed."
    else
      record_check "R21" "Feed operacional ordena e limita corretamente" "FAIL" "$check_dir/evidence.txt" "Feed nao respeitou limite/ordenacao esperados."
    fi
  fi

  # R22
  check_dir="$(new_check_dir R22)"
  if should_run_scope "R22"; then
    api_get_capture "/api/operacao/mapa"
    mapa_status="$API_LAST_STATUS"
    mapa_body="$API_LAST_BODY"
    {
      echo "mapa_status=$mapa_status"
      echo "$mapa_body" | jq . 2>/dev/null || echo "$mapa_body"
    } > "$check_dir/evidence.txt"
    mapa_ok=0
    if [[ "$mapa_status" == "200" ]]; then
      if echo "$mapa_body" | jq -e 'all(.rotas[]?; ((.statusRota=="EM_ANDAMENTO" and .camada=="PRIMARIA") or (.statusRota=="PLANEJADA" and .camada=="SECUNDARIA")))' >/dev/null 2>&1; then
        mapa_ok=1
      fi
    fi
    if [[ "$mapa_ok" -eq 1 ]]; then
      record_check "R22" "Mapa operacional nao mistura camada primaria/secundaria" "PASS" "$check_dir/evidence.txt" "Camada do mapa coerente com status da rota."
    else
      record_check "R22" "Mapa operacional nao mistura camada primaria/secundaria" "FAIL" "$check_dir/evidence.txt" "Camada inconsistente no mapa operacional."
    fi
  fi

  # R23
  check_dir="$(new_check_dir R23)"
  if should_run_scope "R23"; then
    OPENAPI_FILE="$ROOT_DIR/contracts/v1/openapi.yaml"
    r23_ok=1
    for required_path in \
      '/api/pedidos/{pedidoId}/execucao:' \
      '/api/entregadores/{entregadorId}/roteiro:' \
      '/api/operacao/painel:' \
      '/api/operacao/eventos:' \
      '/api/operacao/mapa:' \
      '/api/operacao/replanejamento/jobs:' \
      '/api/operacao/replanejamento/jobs/{jobId}:' \
      '/api/operacao/rotas/prontas/iniciar:' \
      '/api/replanejamento/run:'
    do
      if ! contains_fixed_text "$required_path" "$OPENAPI_FILE"; then
        r23_ok=0
      fi
    done
    if ! contains_fixed_text 'externalEventId:' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    if ! contains_fixed_text 'sourceEventId:' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    if ! contains_fixed_text 'manualRequestId:' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    if ! contains_fixed_text 'origemCanal:' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    if ! contains_fixed_text 'Idempotency-Key' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    if ! contains_fixed_text 'X-Idempotency-Key' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    if ! contains_fixed_text 'actorEntregadorId:' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    if ! contains_fixed_text 'Endpoint desativado para execucao manual' "$OPENAPI_FILE"; then
      r23_ok=0
    fi
    {
      echo "openapi=$OPENAPI_FILE"
      echo "r23_ok=$r23_ok"
    } > "$check_dir/evidence.txt"
    if [[ "$r23_ok" -eq 1 ]]; then
      record_check "R23" "OpenAPI cobre endpoints operacionais efetivos" "PASS" "$check_dir/evidence.txt" "OpenAPI contem endpoints operacionais e campos criticos de evento."
    else
      record_check "R23" "OpenAPI cobre endpoints operacionais efetivos" "FAIL" "$check_dir/evidence.txt" "OpenAPI nao contem todos endpoints/campos criticos."
    fi
  fi

  # R24
  check_dir="$(new_check_dir R24)"
  if should_run_scope "R24"; then
    set +e
    (
      cd "$ROOT_DIR"
      mvn "${CONTRACT_TEST_MVN_ARGS_ARRAY[@]}" -Dtest=ContractsV1Test,ApiContractDriftTest test
    ) > "$check_dir/mvn-contract-tests.log" 2>&1
    r24_exit="$?"
    set -e
    if [[ "$r24_exit" -eq 0 ]]; then
      record_check "R24" "Testes de contrato falham se houver drift" "PASS" "$check_dir/mvn-contract-tests.log" "Suite de contrato executou sem drift detectado."
    else
      record_check "R24" "Testes de contrato falham se houver drift" "FAIL" "$check_dir/mvn-contract-tests.log" "Suite de contrato falhou (possivel drift)."
    fi
  fi

  # R25
  check_dir="$(new_check_dir R25)"
  if should_run_scope "R25"; then
    pedidos_com_multiplas_entregas_abertas="$(psql_query "SELECT COUNT(*) FROM (
    SELECT e.pedido_id
    FROM entregas e
    WHERE e.status::text IN ('PENDENTE', 'EM_EXECUCAO')
    GROUP BY e.pedido_id
    HAVING COUNT(*) > 1
  ) duplicados;" | extract_single_value)"
    {
      echo "pedidos_com_multiplas_entregas_abertas=$pedidos_com_multiplas_entregas_abertas"
    } > "$check_dir/evidence.txt"
    if [[ "$pedidos_com_multiplas_entregas_abertas" == "0" ]]; then
      record_check "R25" "Nenhum pedido com mais de uma entrega aberta" "PASS" "$check_dir/evidence.txt" "Invariante de entrega aberta por pedido preservada."
    else
      record_check "R25" "Nenhum pedido com mais de uma entrega aberta" "FAIL" "$check_dir/evidence.txt" "Detectados pedidos com entregas abertas duplicadas."
    fi
  fi

  # R26
  check_dir="$(new_check_dir R26)"
  if should_run_scope "R26"; then
    api_get_capture "/api/operacao/replanejamento/jobs?limite=3"
    jobs_status="$API_LAST_STATUS"
    jobs_body="$API_LAST_BODY"
    jobs_ok=0
    if [[ "$jobs_status" == "200" ]]; then
      if echo "$jobs_body" | jq -e '.jobs | length <= 3' >/dev/null 2>&1 \
        && echo "$jobs_body" | jq -e '.jobs as $j | (($j | length) < 2 or (reduce range(1; ($j | length)) as $i (true; . and ($j[$i-1].solicitadoEm >= $j[$i].solicitadoEm))))' >/dev/null 2>&1 \
        && echo "$jobs_body" | jq -e 'all(.jobs[]?; has("jobId") and has("status") and has("cancelRequested") and has("hasRequestPayload") and has("hasResponsePayload"))' >/dev/null 2>&1; then
        jobs_ok=1
      fi
    fi
    {
      echo "jobs_status=$jobs_status"
      echo "$jobs_body" | jq . 2>/dev/null || echo "$jobs_body"
    } > "$check_dir/evidence.txt"
    if [[ "$jobs_ok" -eq 1 ]]; then
      record_check "R26" "Feed de jobs de replanejamento consistente e limitado" "PASS" "$check_dir/evidence.txt" "Endpoint operacional retornou limite/ordenacao/campos obrigatorios dos jobs."
    else
      record_check "R26" "Feed de jobs de replanejamento consistente e limitado" "FAIL" "$check_dir/evidence.txt" "Endpoint de jobs nao respeitou limite, ordenacao ou campos esperados."
    fi
  fi

  # R27
  check_dir="$(new_check_dir R27)"
  if should_run_scope "R27"; then
    r27_job_id="bg-r27-$(date +%s)-$RANDOM"
    r27_plan_version="$((900000 + RANDOM))"
    r27_atendente_id="$(psql_query "SELECT id FROM users WHERE papel = 'atendente' AND ativo = true ORDER BY id LIMIT 1;" | extract_single_value)"
    if [[ -z "$r27_atendente_id" ]]; then
      r27_atendente_id="$(psql_query "INSERT INTO users (nome, email, senha_hash, papel, ativo)
  VALUES ('Atendente BG R27', 'bg.r27.atendente.${RANDOM}@aguaviva.local', 'hash-r27', 'atendente', true)
  RETURNING id;" | extract_single_value)"
    fi
    r27_entregador_id="$(psql_query "INSERT INTO users (nome, email, senha_hash, papel, ativo)
  VALUES ('Entregador BG R27', 'bg.r27.entregador.${RANDOM}@aguaviva.local', 'hash-r27', 'entregador', true)
  RETURNING id;" | extract_single_value)"
    r27_has_job_id="$(psql_query "SELECT CASE
    WHEN EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_name = 'rotas' AND column_name = 'job_id'
    ) AND EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_name = 'entregas' AND column_name = 'job_id'
    ) THEN 1 ELSE 0 END;" | extract_single_value)"
    r27_ok=0

    if [[ -n "$r27_entregador_id" && -n "$r27_atendente_id" ]]; then
      r27_cliente_id="$(psql_query "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude)
  VALUES ('Cliente BG R27', '3899999${RANDOM}', 'PF', 'Rua R27', -16.7310, -43.8710)
  RETURNING id;" | extract_single_value)"
      r27_pedido_id="$(psql_query "INSERT INTO pedidos (cliente_id, quantidade_galoes, janela_tipo, status, criado_por)
  VALUES (${r27_cliente_id}, 1, 'ASAP', 'CONFIRMADO', ${r27_atendente_id})
  RETURNING id;" | extract_single_value)"
      r27_numero_rota="$(psql_query "SELECT COALESCE(MAX(numero_no_dia), 0) + 1
  FROM rotas
  WHERE entregador_id = ${r27_entregador_id}
    AND data = CURRENT_DATE;" | extract_single_value)"
      if [[ "$r27_has_job_id" == "1" ]]; then
        r27_rota_id="$(psql_query "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version, job_id)
  VALUES (${r27_entregador_id}, CURRENT_DATE, ${r27_numero_rota}, 'PLANEJADA', ${r27_plan_version}, '${r27_job_id}')
  RETURNING id;" | extract_single_value)"
        r27_entrega_id="$(psql_query "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status, plan_version, job_id)
  VALUES (${r27_pedido_id}, ${r27_rota_id}, 1, 'PENDENTE', ${r27_plan_version}, '${r27_job_id}')
  RETURNING id;" | extract_single_value)"
      else
        r27_rota_id="$(psql_query "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version)
  VALUES (${r27_entregador_id}, CURRENT_DATE, ${r27_numero_rota}, 'PLANEJADA', ${r27_plan_version})
  RETURNING id;" | extract_single_value)"
        r27_entrega_id="$(psql_query "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status, plan_version)
  VALUES (${r27_pedido_id}, ${r27_rota_id}, 1, 'PENDENTE', ${r27_plan_version})
  RETURNING id;" | extract_single_value)"
      fi
      psql_exec "INSERT INTO solver_jobs
        (job_id, plan_version, status, cancel_requested, solicitado_em, iniciado_em, finalizado_em, erro, request_payload, response_payload)
      VALUES
        ('${r27_job_id}', ${r27_plan_version}, 'CONCLUIDO', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, '{}'::jsonb, '{\"rotas\":[]}'::jsonb);"

      api_get_capture "/api/operacao/replanejamento/jobs/${r27_job_id}"
      r27_status="$API_LAST_STATUS"
      r27_body="$API_LAST_BODY"
      if [[ "$r27_status" == "200" ]]; then
        if echo "$r27_body" | jq -e \
          --arg jobId "$r27_job_id" \
          --argjson planVersion "$r27_plan_version" \
          --argjson rotaId "$r27_rota_id" \
          --argjson pedidoId "$r27_pedido_id" \
          --argjson entregaId "$r27_entrega_id" \
          '.job.jobId == $jobId
            and .job.planVersion == $planVersion
            and any(.job.rotasImpactadas[]?; .rotaId == $rotaId)
            and any(.job.pedidosImpactados[]?; .pedidoId == $pedidoId and .entregaId == $entregaId)' >/dev/null 2>&1; then
          r27_ok=1
        fi
      fi
    else
      r27_status="0"
      r27_body="{}"
      r27_cliente_id=""
      r27_pedido_id=""
      r27_rota_id=""
      r27_entrega_id=""
    fi

    {
      echo "r27_status=$r27_status"
      echo "r27_job_id=$r27_job_id"
      echo "r27_plan_version=$r27_plan_version"
      echo "r27_has_job_id=$r27_has_job_id"
      echo "r27_entregador_id=$r27_entregador_id"
      echo "r27_atendente_id=$r27_atendente_id"
      echo "r27_cliente_id=${r27_cliente_id:-}"
      echo "r27_pedido_id=${r27_pedido_id:-}"
      echo "r27_rota_id=${r27_rota_id:-}"
      echo "r27_entrega_id=${r27_entrega_id:-}"
      echo "$r27_body" | jq . 2>/dev/null || echo "$r27_body"
    } > "$check_dir/evidence.txt"
    if [[ "$r27_ok" -eq 1 ]]; then
      record_check "R27" "Detalhe de job correlaciona plan_version com rotas e pedidos" "PASS" "$check_dir/evidence.txt" "Detalhe de job retornou correlacao esperada de rota e pedido."
    else
      record_check "R27" "Detalhe de job correlaciona plan_version com rotas e pedidos" "FAIL" "$check_dir/evidence.txt" "Endpoint de detalhe nao comprovou correlacao por plan_version."
    fi
  fi

  # R28
  check_dir="$(new_check_dir R28)"
  if reset_state_for_check "$check_dir"; then
    set +e
    (
      cd "$ROOT_DIR"
      DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
        DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5435}" DB_PASSWORD="${DB_PASSWORD:-postgres}" \
        GEOFENCE_SUMMARY_FILE="$check_dir/geofence-summary.json" \
        scripts/poc/check-clientes-geofence.sh
    ) > "$check_dir/geofence.log" 2>&1
    r28_exit="$?"
    set -e

    if [[ "$r28_exit" -eq 0 ]]; then
      record_check "R28" "Clientes e deposito dentro da geofence operacional" "PASS" "$check_dir/geofence-summary.json" "Clientes e deposito validaram geofence no estado controlado."
    else
      record_check "R28" "Clientes e deposito dentro da geofence operacional" "FAIL" "$check_dir/geofence.log" "Falha na validacao geoespacial de clientes/deposito."
    fi
  else
    record_check "R28" "Clientes e deposito dentro da geofence operacional" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R29
  check_dir="$(new_check_dir R29)"
  if should_run_scope "R29"; then
    set +e
    (
      cd "$ROOT_DIR"
      API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
        DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5435}" DB_PASSWORD="${DB_PASSWORD:-postgres}" \
        SUMMARY_FILE="$check_dir/entregadores-summary.json" \
        scripts/poc/check-cenario-entregadores.sh
    ) > "$check_dir/entregadores.log" 2>&1
    r29_exit="$?"
    set -e
    if [[ "$r29_exit" -eq 0 ]]; then
      record_check "R29" "Cenario oficial 1/2/N entregadores com capacidade igual/diferente" "PASS" "$check_dir/entregadores-summary.json" "Cenario oficial validou escala de entregadores com asserts por capacidade."
    else
      record_check "R29" "Cenario oficial 1/2/N entregadores com capacidade igual/diferente" "FAIL" "$check_dir/entregadores.log" "Falha no cenario oficial de 1/2/N entregadores."
    fi
  fi

  # R30
  check_dir="$(new_check_dir R30)"
  if should_run_scope "R30"; then
    set +e
    (
      cd "$ROOT_DIR"
      API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
        DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5435}" DB_PASSWORD="${DB_PASSWORD:-postgres}" \
        SUMMARY_FILE="$check_dir/frota-perfil-summary.json" \
        scripts/poc/check-frota-perfil.sh
    ) > "$check_dir/frota-perfil.log" 2>&1
    r30_exit="$?"
    set -e
    if [[ "$r30_exit" -eq 0 ]]; then
      record_check "R30" "Perfil de frota MOTO/CARRO parametriza capacidade sem alterar regra core" "PASS" "$check_dir/frota-perfil-summary.json" "Perfil de frota validou capacidade por configuracao mantendo solver unico."
    else
      record_check "R30" "Perfil de frota MOTO/CARRO parametriza capacidade sem alterar regra core" "FAIL" "$check_dir/frota-perfil.log" "Falha ao validar perfil de frota MOTO/CARRO."
    fi
  fi

  # R31
  check_dir="$(new_check_dir R31)"
  if should_run_scope "R31"; then
    set +e
    (
      cd "$ROOT_DIR"
      API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
        DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5435}" DB_PASSWORD="${DB_PASSWORD:-postgres}" \
        SUMMARY_FILE="$check_dir/sla-operacional-summary.json" \
        scripts/poc/check-sla-operacional.sh
    ) > "$check_dir/sla-operacional.log" 2>&1
    r31_exit="$?"
    set -e
    if [[ "$r31_exit" -eq 0 ]]; then
      record_check "R31" "SLA minimo operacional (pedido->rota, rota->inicio)" "PASS" "$check_dir/sla-operacional-summary.json" "SLA minimo validado com relatorio por rodada."
    else
      record_check "R31" "SLA minimo operacional (pedido->rota, rota->inicio)" "FAIL" "$check_dir/sla-operacional.log" "Falha no SLA minimo operacional."
    fi
  fi

  # R32
  check_dir="$(new_check_dir R32)"
  if should_run_scope "R32"; then
    set +e
    (
      cd "$ROOT_DIR"
      API_BASE="$API_BASE" DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
        DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5435}" DB_PASSWORD="${DB_PASSWORD:-postgres}" \
        SUMMARY_FILE="$check_dir/frota-escala-4x50-summary.json" \
        NUM_ENTREGADORES_ATIVOS=4 PEDIDOS_TOTAIS=50 QUANTIDADE_GALOES=1 \
        JANELA_MODE=MIXED HARD_RATIO_PERCENT=40 MIN_ENTREGUES=8 \
        scripts/poc/check-frota-escala-4x50.sh
    ) > "$check_dir/frota-escala-4x50.log" 2>&1
    r32_exit="$?"
    set -e
    if [[ "$r32_exit" -eq 0 ]]; then
      record_check "R32" "Escala 4 entregadores/50 pedidos com janelas mistas e giro operacional" "PASS" "$check_dir/frota-escala-4x50-summary.json" "Escala 4x50 validada com giro de rotas e entregas."
    else
      record_check "R32" "Escala 4 entregadores/50 pedidos com janelas mistas e giro operacional" "FAIL" "$check_dir/frota-escala-4x50.log" "Falha no cenario de escala 4x50 com janelas mistas."
    fi
  fi

  # R33
  check_dir="$(new_check_dir R33)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    r33_phone_digits="38999901333"
    r33_phone_fmt="(38) 99990-1333"
    r33_manual_request_id="bg-r33-manual-$(date +%s)-$RANDOM"
    payload_r33="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg manualRequestId "$r33_manual_request_id" \
      --arg telefone "$r33_phone_fmt" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,manualRequestId:$manualRequestId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R33",endereco:"Rua BG R33, 10",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r33"
    r33_status="$API_LAST_STATUS"
    r33_body="$API_LAST_BODY"
    save_api_response "$check_dir/response.txt"
    r33_cliente_criado="$(echo "$r33_body" | jq -r '.clienteCriado // false')"
    r33_clientes_count="$(psql_query "SELECT COUNT(*) FROM clientes WHERE regexp_replace(telefone, '[^0-9]', '', 'g') = '${r33_phone_digits}';" | extract_single_value)"
    r33_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r33_phone_digits}';" | extract_single_value)"
    {
      echo "status=$r33_status"
      echo "clienteCriado=$r33_cliente_criado"
      echo "clientes_count=$r33_clientes_count"
      echo "pedidos_count=$r33_pedidos_count"
      echo "$r33_body" | jq .
    } > "$check_dir/evidence.txt"
    if [[ "$r33_status" == "200" && "$r33_cliente_criado" == "true" && "$r33_clientes_count" == "1" && "$r33_pedidos_count" == "1" ]]; then
      record_check "R33" "Manual cria cliente novo e pedido com dados completos" "PASS" "$check_dir/evidence.txt" "Fluxo manual criou cliente e pedido no mesmo atendimento."
    else
      record_check "R33" "Manual cria cliente novo e pedido com dados completos" "FAIL" "$check_dir/evidence.txt" "Fluxo manual nao comprovou criacao de cliente/pedido com dados completos."
    fi
  else
    record_check "R33" "Manual cria cliente novo e pedido com dados completos" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R34
  check_dir="$(new_check_dir R34)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    payload_r34_sem_source="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg telefone "(38) 99990-1341" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R34A",endereco:"Rua BG R34, 41",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r34_sem_source"
    r34_status_sem_source="$API_LAST_STATUS"
    r34_body_sem_source="$API_LAST_BODY"
    r34_msg_sem_source="$(echo "$r34_body_sem_source" | jq -r '.erro // ""')"

    payload_r34_sem_source_header="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg telefone "(38) 99990-1344" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R34D",endereco:"Rua BG R34, 44",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r34_sem_source_header" "Idempotency-Key" "bg-r34-auto-sem-source-header"
    r34_status_sem_source_header="$API_LAST_STATUS"
    r34_body_sem_source_header="$API_LAST_BODY"
    r34_msg_sem_source_header="$(echo "$r34_body_sem_source_header" | jq -r '.erro // ""')"

    payload_r34_manual_key_auto="$(jq -n \
      --arg origemCanal "BINA_FIXO" \
      --arg sourceEventId "bg-r34-bina-$(date +%s)-$RANDOM" \
      --arg manualRequestId "bg-r34-manual-key" \
      --arg telefone "(38) 99990-1342" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,manualRequestId:$manualRequestId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R34B",endereco:"Rua BG R34, 42",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r34_manual_key_auto"
    r34_status_manual_key="$API_LAST_STATUS"
    r34_body_manual_key="$API_LAST_BODY"
    r34_msg_manual_key="$(echo "$r34_body_manual_key" | jq -r '.erro // ""')"

    payload_r34_source_manual="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg sourceEventId "bg-r34-manual-source-$(date +%s)-$RANDOM" \
      --arg telefone "(38) 99990-1343" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R34C",endereco:"Rua BG R34, 43",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r34_source_manual"
    r34_status_source_manual="$API_LAST_STATUS"
    r34_body_source_manual="$API_LAST_BODY"
    r34_msg_source_manual="$(echo "$r34_body_source_manual" | jq -r '.erro // ""')"

    {
      echo "status_sem_source=$r34_status_sem_source"
      echo "$r34_body_sem_source" | jq .
      echo "status_sem_source_com_header=$r34_status_sem_source_header"
      echo "$r34_body_sem_source_header" | jq .
      echo "status_manual_key_auto=$r34_status_manual_key"
      echo "$r34_body_manual_key" | jq .
      echo "status_source_manual=$r34_status_source_manual"
      echo "$r34_body_source_manual" | jq .
    } > "$check_dir/evidence.txt"
    if [[ "$r34_status_sem_source" == "400" \
      && "$r34_msg_sem_source" == *"sourceEventId obrigatorio"* \
      && "$r34_status_sem_source_header" == "400" \
      && "$r34_msg_sem_source_header" == *"sourceEventId obrigatorio"* \
      && "$r34_status_manual_key" == "400" \
      && "$r34_msg_manual_key" == *"manualRequestId so pode ser usado"* \
      && "$r34_status_source_manual" == "400" \
      && "$r34_msg_source_manual" == *"sourceEventId nao pode ser usado"* ]]; then
      record_check "R34" "Canal automatico exige sourceEventId e rejeita manualRequestId" "PASS" "$check_dir/evidence.txt" "Regras de consistencia de canal/chaves validadas na API (automatico exige sourceEventId e MANUAL rejeita sourceEventId)."
    else
      record_check "R34" "Canal automatico exige sourceEventId e rejeita manualRequestId" "FAIL" "$check_dir/evidence.txt" "Regras de canal/chaves nao foram comprovadas."
    fi
  else
    record_check "R34" "Canal automatico exige sourceEventId e rejeita manualRequestId" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R35
  check_dir="$(new_check_dir R35)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    r35_source_event_id="bg-r35-wa-$(date +%s)-$RANDOM"
    r35_source_event_id_esc="$(sql_escape "$r35_source_event_id")"
    r35_phone_digits="38999901351"
    payload_r35="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r35_source_event_id" \
      --arg telefone "(38) 99990-1351" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R35",endereco:"Rua BG R35, 51",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r35"
    r35_status_1="$API_LAST_STATUS"
    r35_body_1="$API_LAST_BODY"
    api_post_capture "/api/atendimento/pedidos" "$payload_r35"
    r35_status_2="$API_LAST_STATUS"
    r35_body_2="$API_LAST_BODY"
    r35_pedido_1="$(echo "$r35_body_1" | jq -r '.pedidoId // 0')"
    r35_pedido_2="$(echo "$r35_body_2" | jq -r '.pedidoId // 0')"
    r35_idempotente_2="$(echo "$r35_body_2" | jq -r '.idempotente // false')"
    r35_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r35_phone_digits}';" | extract_single_value)"
    r35_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r35_source_event_id_esc}';" | extract_single_value)"

    r35_fallback_event_id="bg-r35-wa-fallback-$(date +%s)-$RANDOM"
    r35_fallback_event_id_esc="$(sql_escape "$r35_fallback_event_id")"
    r35_fallback_phone_digits="38999901352"
    payload_r35_fallback="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg externalCallId "$r35_fallback_event_id" \
      --arg telefone "(38) 99990-1352" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R35 Fallback",endereco:"Rua BG R35, 52",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r35_fallback"
    r35_fallback_status_1="$API_LAST_STATUS"
    r35_fallback_body_1="$API_LAST_BODY"
    api_post_capture "/api/atendimento/pedidos" "$payload_r35_fallback"
    r35_fallback_status_2="$API_LAST_STATUS"
    r35_fallback_body_2="$API_LAST_BODY"
    r35_fallback_pedido_1="$(echo "$r35_fallback_body_1" | jq -r '.pedidoId // 0')"
    r35_fallback_pedido_2="$(echo "$r35_fallback_body_2" | jq -r '.pedidoId // 0')"
    r35_fallback_idempotente_2="$(echo "$r35_fallback_body_2" | jq -r '.idempotente // false')"
    r35_fallback_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r35_fallback_phone_digits}';" | extract_single_value)"
    r35_fallback_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r35_fallback_event_id_esc}';" | extract_single_value)"

    r35_long_source_event_id="$(printf 'z%.0s' $(seq 1 110))"
    r35_long_source_event_id_esc="$(sql_escape "$r35_long_source_event_id")"
    r35_long_phone_digits="38999901353"
    payload_r35_long_source="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r35_long_source_event_id" \
      --arg telefone "(38) 99990-1353" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R35 Long Source",endereco:"Rua BG R35, 53",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r35_long_source"
    r35_long_status_1="$API_LAST_STATUS"
    r35_long_body_1="$API_LAST_BODY"
    api_post_capture "/api/atendimento/pedidos" "$payload_r35_long_source"
    r35_long_status_2="$API_LAST_STATUS"
    r35_long_body_2="$API_LAST_BODY"
    r35_long_pedido_1="$(echo "$r35_long_body_1" | jq -r '.pedidoId // 0')"
    r35_long_pedido_2="$(echo "$r35_long_body_2" | jq -r '.pedidoId // 0')"
    r35_long_idempotente_2="$(echo "$r35_long_body_2" | jq -r '.idempotente // false')"
    r35_long_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r35_long_phone_digits}';" | extract_single_value)"
    r35_long_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r35_long_source_event_id_esc}';" | extract_single_value)"
    r35_long_external_call_len="$(psql_query "SELECT COALESCE(length(external_call_id), 0) FROM pedidos WHERE id = ${r35_long_pedido_1};" | extract_single_value)"

    {
      echo "status_1=$r35_status_1"
      echo "$r35_body_1" | jq .
      echo "status_2=$r35_status_2"
      echo "$r35_body_2" | jq .
      echo "pedidos_count=$r35_pedidos_count"
      echo "idempotencia_rows=$r35_idem_rows"
      echo "fallback_status_1=$r35_fallback_status_1"
      echo "$r35_fallback_body_1" | jq .
      echo "fallback_status_2=$r35_fallback_status_2"
      echo "$r35_fallback_body_2" | jq .
      echo "fallback_pedidos_count=$r35_fallback_pedidos_count"
      echo "fallback_idempotencia_rows=$r35_fallback_idem_rows"
      echo "long_source_status_1=$r35_long_status_1"
      echo "$r35_long_body_1" | jq .
      echo "long_source_status_2=$r35_long_status_2"
      echo "$r35_long_body_2" | jq .
      echo "long_source_pedidos_count=$r35_long_pedidos_count"
      echo "long_source_idempotencia_rows=$r35_long_idem_rows"
      echo "long_source_external_call_len=$r35_long_external_call_len"
    } > "$check_dir/evidence.txt"
    if [[ "$r35_status_1" == "200" \
      && "$r35_status_2" == "200" \
      && "$r35_pedido_1" == "$r35_pedido_2" \
      && "$r35_idempotente_2" == "true" \
      && "$r35_pedidos_count" == "1" \
      && "$r35_idem_rows" == "1" \
      && "$r35_fallback_status_1" == "200" \
      && "$r35_fallback_status_2" == "200" \
      && "$r35_fallback_pedido_1" == "$r35_fallback_pedido_2" \
      && "$r35_fallback_idempotente_2" == "true" \
      && "$r35_fallback_pedidos_count" == "1" \
      && "$r35_fallback_idem_rows" == "1" \
      && "$r35_long_status_1" == "200" \
      && "$r35_long_status_2" == "200" \
      && "$r35_long_pedido_1" == "$r35_long_pedido_2" \
      && "$r35_long_idempotente_2" == "true" \
      && "$r35_long_pedidos_count" == "1" \
      && "$r35_long_idem_rows" == "1" \
      && "$r35_long_external_call_len" == "64" ]]; then
      record_check "R35" "Idempotencia omnichannel por origemCanal+sourceEventId" "PASS" "$check_dir/evidence.txt" "Replay no canal WHATSAPP retornou o mesmo pedido sem duplicar (sourceEventId explicito, fallback de externalCallId e sourceEventId longo com external_call_id hasheado)."
    else
      record_check "R35" "Idempotencia omnichannel por origemCanal+sourceEventId" "FAIL" "$check_dir/evidence.txt" "Idempotencia omnichannel nao comprovada para origemCanal+sourceEventId."
    fi
  else
    record_check "R35" "Idempotencia omnichannel por origemCanal+sourceEventId" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R38
  check_dir="$(new_check_dir R38)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    r38_source_event_id="bg-r38-wa-$(date +%s)-$RANDOM"
    r38_source_event_id_esc="$(sql_escape "$r38_source_event_id")"
    r38_phone_auto_digits="38999901381"
    payload_r38_auto_primeiro="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r38_source_event_id" \
      --arg telefone "(38) 99990-1381" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R38",endereco:"Rua BG R38, 81",latitude:-16.7310,longitude:-43.8710}')"
    payload_r38_auto_divergente="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r38_source_event_id" \
      --arg telefone "(38) 99990-1381" \
      --argjson quantidadeGaloes 2 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R38",endereco:"Rua BG R38, 81",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r38_auto_primeiro"
    r38_auto_status_1="$API_LAST_STATUS"
    r38_auto_body_1="$API_LAST_BODY"
    r38_auto_pedido_1="$(echo "$r38_auto_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r38_auto_divergente"
    r38_auto_status_2="$API_LAST_STATUS"
    r38_auto_body_2="$API_LAST_BODY"
    r38_auto_msg_2="$(echo "$r38_auto_body_2" | jq -r '.erro // ""')"

    r38_auto_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r38_phone_auto_digits}';" | extract_single_value)"
    r38_auto_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r38_source_event_id_esc}';" | extract_single_value)"
    r38_auto_hash_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r38_source_event_id_esc}' AND request_hash IS NOT NULL AND length(request_hash) = 64;" | extract_single_value)"

    r38_manual_request_id="bg-r38-manual-$(date +%s)-$RANDOM"
    r38_manual_request_id_esc="$(sql_escape "$r38_manual_request_id")"
    r38_phone_manual_digits="38999901382"
    payload_r38_manual_primeiro="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg manualRequestId "$r38_manual_request_id" \
      --arg telefone "(38) 99990-1382" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,manualRequestId:$manualRequestId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R38 MANUAL",endereco:"Rua BG R38, 82",latitude:-16.7310,longitude:-43.8710}')"
    payload_r38_manual_divergente="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg manualRequestId "$r38_manual_request_id" \
      --arg telefone "(38) 99990-1382" \
      --argjson quantidadeGaloes 2 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,manualRequestId:$manualRequestId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R38 MANUAL",endereco:"Rua BG R38, 82",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r38_manual_primeiro"
    r38_manual_status_1="$API_LAST_STATUS"
    r38_manual_body_1="$API_LAST_BODY"
    r38_manual_pedido_1="$(echo "$r38_manual_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r38_manual_divergente"
    r38_manual_status_2="$API_LAST_STATUS"
    r38_manual_body_2="$API_LAST_BODY"
    r38_manual_msg_2="$(echo "$r38_manual_body_2" | jq -r '.erro // ""')"

    r38_manual_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r38_phone_manual_digits}';" | extract_single_value)"
    r38_manual_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r38_manual_request_id_esc}';" | extract_single_value)"
    r38_manual_hash_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r38_manual_request_id_esc}' AND request_hash IS NOT NULL AND length(request_hash) = 64;" | extract_single_value)"

    {
      echo "auto_status_1=$r38_auto_status_1"
      echo "$r38_auto_body_1" | jq .
      echo "auto_status_2=$r38_auto_status_2"
      echo "$r38_auto_body_2" | jq .
      echo "auto_pedidos_count=$r38_auto_pedidos_count"
      echo "auto_idempotencia_rows=$r38_auto_idem_rows"
      echo "auto_request_hash_rows=$r38_auto_hash_rows"
      echo "auto_pedido_1=$r38_auto_pedido_1"
      echo "manual_status_1=$r38_manual_status_1"
      echo "$r38_manual_body_1" | jq .
      echo "manual_status_2=$r38_manual_status_2"
      echo "$r38_manual_body_2" | jq .
      echo "manual_pedidos_count=$r38_manual_pedidos_count"
      echo "manual_idempotencia_rows=$r38_manual_idem_rows"
      echo "manual_request_hash_rows=$r38_manual_hash_rows"
      echo "manual_pedido_1=$r38_manual_pedido_1"
    } > "$check_dir/evidence.txt"
    if [[ "$r38_auto_status_1" == "200" \
      && "$r38_auto_status_2" == "409" \
      && "$r38_auto_msg_2" == *"payload divergente"* \
      && "$r38_auto_pedidos_count" == "1" \
      && "$r38_auto_idem_rows" == "1" \
      && "$r38_auto_hash_rows" == "1" \
      && "$r38_manual_status_1" == "200" \
      && "$r38_manual_status_2" == "409" \
      && "$r38_manual_msg_2" == *"payload divergente"* \
      && "$r38_manual_pedidos_count" == "1" \
      && "$r38_manual_idem_rows" == "1" \
      && "$r38_manual_hash_rows" == "1" ]]; then
      record_check "R38" "Reuso de chave idempotente com payload divergente retorna conflito" "PASS" "$check_dir/evidence.txt" "Reuso de sourceEventId (automatico) e manualRequestId (manual) com payload divergente retornou conflito sem criar pedido duplicado."
    else
      record_check "R38" "Reuso de chave idempotente com payload divergente retorna conflito" "FAIL" "$check_dir/evidence.txt" "Nao foi comprovado conflito idempotente para payload divergente."
    fi
  else
    record_check "R38" "Reuso de chave idempotente com payload divergente retorna conflito" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R39
  check_dir="$(new_check_dir R39)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    r39_manual_key="bg-r39-manual-legacy-$(date +%s)-$RANDOM"
    r39_manual_key_esc="$(sql_escape "$r39_manual_key")"
    r39_phone_digits="38999901391"
    payload_r39_primeiro="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg externalCallId "$r39_manual_key" \
      --arg telefone "(38) 99990-1391" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R39",endereco:"Rua BG R39, 91",latitude:-16.7310,longitude:-43.8710}')"
    payload_r39_divergente="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg externalCallId "$r39_manual_key" \
      --arg telefone "(38) 99990-1391" \
      --argjson quantidadeGaloes 2 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R39",endereco:"Rua BG R39, 91",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r39_primeiro"
    r39_status_1="$API_LAST_STATUS"
    r39_body_1="$API_LAST_BODY"
    r39_pedido_1="$(echo "$r39_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r39_primeiro"
    r39_status_2="$API_LAST_STATUS"
    r39_body_2="$API_LAST_BODY"
    r39_pedido_2="$(echo "$r39_body_2" | jq -r '.pedidoId // 0')"
    r39_idempotente_2="$(echo "$r39_body_2" | jq -r '.idempotente // false')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r39_divergente"
    r39_status_3="$API_LAST_STATUS"
    r39_body_3="$API_LAST_BODY"
    r39_msg_3="$(echo "$r39_body_3" | jq -r '.erro // ""')"

    r39_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r39_phone_digits}';" | extract_single_value)"
    r39_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r39_manual_key_esc}';" | extract_single_value)"
    r39_hash_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r39_manual_key_esc}' AND request_hash IS NOT NULL AND length(request_hash) = 64;" | extract_single_value)"
    r39_external_call_id="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r39_pedido_1};" | extract_single_value)"

    {
      echo "status_1=$r39_status_1"
      echo "$r39_body_1" | jq .
      echo "status_2=$r39_status_2"
      echo "$r39_body_2" | jq .
      echo "status_3=$r39_status_3"
      echo "$r39_body_3" | jq .
      echo "pedido_1=$r39_pedido_1"
      echo "pedido_2=$r39_pedido_2"
      echo "idempotente_2=$r39_idempotente_2"
      echo "pedidos_count=$r39_pedidos_count"
      echo "idempotencia_rows=$r39_idem_rows"
      echo "request_hash_rows=$r39_hash_rows"
      echo "pedido_external_call_id=$r39_external_call_id"
    } > "$check_dir/evidence.txt"
    if [[ "$r39_status_1" == "200" \
      && "$r39_status_2" == "200" \
      && "$r39_pedido_1" == "$r39_pedido_2" \
      && "$r39_idempotente_2" == "true" \
      && "$r39_status_3" == "409" \
      && "$r39_msg_3" == *"payload divergente"* \
      && "$r39_pedidos_count" == "1" \
      && "$r39_idem_rows" == "1" \
      && "$r39_hash_rows" == "1" \
      && "$r39_external_call_id" == "__NULL__" ]]; then
      record_check "R39" "MANUAL aplica fallback de externalCallId como chave idempotente" "PASS" "$check_dir/evidence.txt" "origemCanal=MANUAL tratou externalCallId como chave idempotente sem gravar external_call_id no pedido."
    else
      record_check "R39" "MANUAL aplica fallback de externalCallId como chave idempotente" "FAIL" "$check_dir/evidence.txt" "Fallback de externalCallId para MANUAL nao foi comprovado com idempotencia/conflito."
    fi
  else
    record_check "R39" "MANUAL aplica fallback de externalCallId como chave idempotente" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R40
  check_dir="$(new_check_dir R40)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r40_x_header_key="bg-r40-xheader-$(date +%s)-$RANDOM"
    r40_x_header_key_esc="$(sql_escape "$r40_x_header_key")"
    r40_phone_x_digits="38999901392"
    payload_r40_x_primeiro="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg telefone "(38) 99990-1392" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R40 X",endereco:"Rua BG R40, 92",latitude:-16.7310,longitude:-43.8710}')"
    payload_r40_x_divergente="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg telefone "(38) 99990-1392" \
      --argjson quantidadeGaloes 2 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R40 X",endereco:"Rua BG R40, 92",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r40_x_primeiro" "X-Idempotency-Key" "$r40_x_header_key"
    r40_x_status_1="$API_LAST_STATUS"
    r40_x_body_1="$API_LAST_BODY"
    r40_x_pedido_1="$(echo "$r40_x_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r40_x_primeiro" "X-Idempotency-Key" "$r40_x_header_key"
    r40_x_status_2="$API_LAST_STATUS"
    r40_x_body_2="$API_LAST_BODY"
    r40_x_pedido_2="$(echo "$r40_x_body_2" | jq -r '.pedidoId // 0')"
    r40_x_idempotente_2="$(echo "$r40_x_body_2" | jq -r '.idempotente // false')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r40_x_divergente" "X-Idempotency-Key" "$r40_x_header_key"
    r40_x_status_3="$API_LAST_STATUS"
    r40_x_body_3="$API_LAST_BODY"
    r40_x_msg_3="$(echo "$r40_x_body_3" | jq -r '.erro // ""')"

    r40_x_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r40_phone_x_digits}';" | extract_single_value)"
    r40_x_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r40_x_header_key_esc}';" | extract_single_value)"
    r40_x_hash_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r40_x_header_key_esc}' AND request_hash IS NOT NULL AND length(request_hash) = 64;" | extract_single_value)"
    r40_x_external_call_id="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r40_x_pedido_1};" | extract_single_value)"

    r40_default_key="bg-r40-default-manual-$(date +%s)-$RANDOM"
    r40_default_key_esc="$(sql_escape "$r40_default_key")"
    r40_phone_default_digits="38999901393"
    payload_r40_default="$(jq -n \
      --arg telefone "(38) 99990-1393" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R40 Default",endereco:"Rua BG R40, 93",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r40_default" "Idempotency-Key" "$r40_default_key"
    r40_default_status_1="$API_LAST_STATUS"
    r40_default_body_1="$API_LAST_BODY"
    r40_default_pedido_1="$(echo "$r40_default_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r40_default" "Idempotency-Key" "$r40_default_key"
    r40_default_status_2="$API_LAST_STATUS"
    r40_default_body_2="$API_LAST_BODY"
    r40_default_pedido_2="$(echo "$r40_default_body_2" | jq -r '.pedidoId // 0')"
    r40_default_idempotente_2="$(echo "$r40_default_body_2" | jq -r '.idempotente // false')"

    r40_default_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r40_phone_default_digits}';" | extract_single_value)"
    r40_default_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r40_default_key_esc}';" | extract_single_value)"
    r40_default_hash_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r40_default_key_esc}' AND request_hash IS NOT NULL AND length(request_hash) = 64;" | extract_single_value)"

    r40_double_key="bg-r40-double-key-$(date +%s)-$RANDOM"
    r40_double_key_esc="$(sql_escape "$r40_double_key")"
    r40_phone_double_digits="38999901394"
    payload_r40_double_igual="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg telefone "(38) 99990-1394" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R40 Double",endereco:"Rua BG R40, 94",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r40_double_igual" "Idempotency-Key" "$r40_double_key" "X-Idempotency-Key" "$r40_double_key"
    r40_double_status_1="$API_LAST_STATUS"
    r40_double_body_1="$API_LAST_BODY"
    r40_double_pedido_1="$(echo "$r40_double_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r40_double_igual" "Idempotency-Key" "$r40_double_key" "X-Idempotency-Key" "$r40_double_key"
    r40_double_status_2="$API_LAST_STATUS"
    r40_double_body_2="$API_LAST_BODY"
    r40_double_pedido_2="$(echo "$r40_double_body_2" | jq -r '.pedidoId // 0')"
    r40_double_idempotente_2="$(echo "$r40_double_body_2" | jq -r '.idempotente // false')"

    r40_double_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r40_phone_double_digits}';" | extract_single_value)"
    r40_double_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r40_double_key_esc}';" | extract_single_value)"
    r40_double_hash_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r40_double_key_esc}' AND request_hash IS NOT NULL AND length(request_hash) = 64;" | extract_single_value)"

    payload_r40_double_divergente="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg telefone "(38) 99990-1395" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R40 Divergente",endereco:"Rua BG R40, 95",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r40_double_divergente" "Idempotency-Key" "bg-r40-div-a" "X-Idempotency-Key" "bg-r40-div-b"
    r40_double_div_status="$API_LAST_STATUS"
    r40_double_div_body="$API_LAST_BODY"
    r40_double_div_msg="$(echo "$r40_double_div_body" | jq -r '.erro // ""')"
    r40_double_div_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901395';" | extract_single_value)"

    {
      echo "x_header_status_1=$r40_x_status_1"
      echo "$r40_x_body_1" | jq .
      echo "x_header_status_2=$r40_x_status_2"
      echo "$r40_x_body_2" | jq .
      echo "x_header_status_3=$r40_x_status_3"
      echo "$r40_x_body_3" | jq .
      echo "x_header_pedido_1=$r40_x_pedido_1"
      echo "x_header_pedido_2=$r40_x_pedido_2"
      echo "x_header_idempotente_2=$r40_x_idempotente_2"
      echo "x_header_pedidos_count=$r40_x_pedidos_count"
      echo "x_header_idempotencia_rows=$r40_x_idem_rows"
      echo "x_header_request_hash_rows=$r40_x_hash_rows"
      echo "x_header_pedido_external_call_id=$r40_x_external_call_id"
      echo "default_status_1=$r40_default_status_1"
      echo "$r40_default_body_1" | jq .
      echo "default_status_2=$r40_default_status_2"
      echo "$r40_default_body_2" | jq .
      echo "default_pedido_1=$r40_default_pedido_1"
      echo "default_pedido_2=$r40_default_pedido_2"
      echo "default_idempotente_2=$r40_default_idempotente_2"
      echo "default_pedidos_count=$r40_default_pedidos_count"
      echo "default_idempotencia_rows=$r40_default_idem_rows"
      echo "default_request_hash_rows=$r40_default_hash_rows"
      echo "double_status_1=$r40_double_status_1"
      echo "$r40_double_body_1" | jq .
      echo "double_status_2=$r40_double_status_2"
      echo "$r40_double_body_2" | jq .
      echo "double_pedido_1=$r40_double_pedido_1"
      echo "double_pedido_2=$r40_double_pedido_2"
      echo "double_idempotente_2=$r40_double_idempotente_2"
      echo "double_pedidos_count=$r40_double_pedidos_count"
      echo "double_idempotencia_rows=$r40_double_idem_rows"
      echo "double_request_hash_rows=$r40_double_hash_rows"
      echo "double_div_status=$r40_double_div_status"
      echo "$r40_double_div_body" | jq .
      echo "double_div_pedidos_count=$r40_double_div_pedidos_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r40_x_status_1" == "200" \
      && "$r40_x_status_2" == "200" \
      && "$r40_x_pedido_1" == "$r40_x_pedido_2" \
      && "$r40_x_idempotente_2" == "true" \
      && "$r40_x_status_3" == "409" \
      && "$r40_x_msg_3" == *"payload divergente"* \
      && "$r40_x_pedidos_count" == "1" \
      && "$r40_x_idem_rows" == "1" \
      && "$r40_x_hash_rows" == "1" \
      && "$r40_x_external_call_id" == "__NULL__" \
      && "$r40_default_status_1" == "200" \
      && "$r40_default_status_2" == "200" \
      && "$r40_default_pedido_1" == "$r40_default_pedido_2" \
      && "$r40_default_idempotente_2" == "true" \
      && "$r40_default_pedidos_count" == "1" \
      && "$r40_default_idem_rows" == "1" \
      && "$r40_default_hash_rows" == "1" \
      && "$r40_double_status_1" == "200" \
      && "$r40_double_status_2" == "200" \
      && "$r40_double_pedido_1" == "$r40_double_pedido_2" \
      && "$r40_double_idempotente_2" == "true" \
      && "$r40_double_pedidos_count" == "1" \
      && "$r40_double_idem_rows" == "1" \
      && "$r40_double_hash_rows" == "1" \
      && "$r40_double_div_status" == "400" \
      && "$r40_double_div_msg" == *"mesmo valor"* \
      && "$r40_double_div_pedidos_count" == "0" ]]; then
      record_check "R40" "MANUAL aceita X-Idempotency-Key como alias de chave idempotente" "PASS" "$check_dir/evidence.txt" "Alias X-Idempotency-Key, headers iguais e divergentes (400) e fallback manual com origem omitida validaram idempotencia sem duplicacao."
    else
      record_check "R40" "MANUAL aceita X-Idempotency-Key como alias de chave idempotente" "FAIL" "$check_dir/evidence.txt" "Nao foi comprovada idempotencia manual via alias/header no cenario R40."
    fi
  else
    record_check "R40" "MANUAL aceita X-Idempotency-Key como alias de chave idempotente" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R41
  check_dir="$(new_check_dir R41)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r41_auto_equal_event="bg-r41-auto-eq-$(date +%s)-$RANDOM"
    r41_auto_equal_event_esc="$(sql_escape "$r41_auto_equal_event")"
    payload_r41_auto_equal="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r41_auto_equal_event" \
      --arg externalCallId "$r41_auto_equal_event" \
      --arg telefone "(38) 99990-1396" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R41 Auto Eq",endereco:"Rua BG R41, 96",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r41_auto_equal"
    r41_auto_equal_status_1="$API_LAST_STATUS"
    r41_auto_equal_body_1="$API_LAST_BODY"
    r41_auto_equal_pedido_1="$(echo "$r41_auto_equal_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r41_auto_equal"
    r41_auto_equal_status_2="$API_LAST_STATUS"
    r41_auto_equal_body_2="$API_LAST_BODY"
    r41_auto_equal_pedido_2="$(echo "$r41_auto_equal_body_2" | jq -r '.pedidoId // 0')"
    r41_auto_equal_idempotente_2="$(echo "$r41_auto_equal_body_2" | jq -r '.idempotente // false')"
    r41_auto_equal_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901396';" | extract_single_value)"
    r41_auto_equal_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r41_auto_equal_event_esc}';" | extract_single_value)"

    payload_r41_auto_divergente="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "bg-r41-auto-div-a" \
      --arg externalCallId "bg-r41-auto-div-b" \
      --arg telefone "(38) 99990-1397" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R41 Auto Div",endereco:"Rua BG R41, 97",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r41_auto_divergente"
    r41_auto_div_status="$API_LAST_STATUS"
    r41_auto_div_body="$API_LAST_BODY"
    r41_auto_div_msg="$(echo "$r41_auto_div_body" | jq -r '.erro // ""')"
    r41_auto_div_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901397';" | extract_single_value)"

    r41_manual_equal_key="bg-r41-manual-eq-$(date +%s)-$RANDOM"
    r41_manual_equal_key_esc="$(sql_escape "$r41_manual_equal_key")"
    payload_r41_manual_equal="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg manualRequestId "$r41_manual_equal_key" \
      --arg externalCallId "$r41_manual_equal_key" \
      --arg telefone "(38) 99990-1398" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,manualRequestId:$manualRequestId,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R41 Manual Eq",endereco:"Rua BG R41, 98",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r41_manual_equal"
    r41_manual_equal_status_1="$API_LAST_STATUS"
    r41_manual_equal_body_1="$API_LAST_BODY"
    r41_manual_equal_pedido_1="$(echo "$r41_manual_equal_body_1" | jq -r '.pedidoId // 0')"

    api_post_capture "/api/atendimento/pedidos" "$payload_r41_manual_equal"
    r41_manual_equal_status_2="$API_LAST_STATUS"
    r41_manual_equal_body_2="$API_LAST_BODY"
    r41_manual_equal_pedido_2="$(echo "$r41_manual_equal_body_2" | jq -r '.pedidoId // 0')"
    r41_manual_equal_idempotente_2="$(echo "$r41_manual_equal_body_2" | jq -r '.idempotente // false')"
    r41_manual_equal_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901398';" | extract_single_value)"
    r41_manual_equal_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r41_manual_equal_key_esc}';" | extract_single_value)"

    payload_r41_manual_divergente="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg manualRequestId "bg-r41-manual-div-a" \
      --arg externalCallId "bg-r41-manual-div-b" \
      --arg telefone "(38) 99990-1399" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,manualRequestId:$manualRequestId,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R41 Manual Div",endereco:"Rua BG R41, 99",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r41_manual_divergente"
    r41_manual_div_status="$API_LAST_STATUS"
    r41_manual_div_body="$API_LAST_BODY"
    r41_manual_div_msg="$(echo "$r41_manual_div_body" | jq -r '.erro // ""')"
    r41_manual_div_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901399';" | extract_single_value)"

    {
      echo "auto_equal_status_1=$r41_auto_equal_status_1"
      echo "$r41_auto_equal_body_1" | jq .
      echo "auto_equal_status_2=$r41_auto_equal_status_2"
      echo "$r41_auto_equal_body_2" | jq .
      echo "auto_equal_pedido_1=$r41_auto_equal_pedido_1"
      echo "auto_equal_pedido_2=$r41_auto_equal_pedido_2"
      echo "auto_equal_idempotente_2=$r41_auto_equal_idempotente_2"
      echo "auto_equal_pedidos_count=$r41_auto_equal_pedidos_count"
      echo "auto_equal_idempotencia_rows=$r41_auto_equal_idem_rows"
      echo "auto_div_status=$r41_auto_div_status"
      echo "$r41_auto_div_body" | jq .
      echo "auto_div_pedidos_count=$r41_auto_div_pedidos_count"
      echo "manual_equal_status_1=$r41_manual_equal_status_1"
      echo "$r41_manual_equal_body_1" | jq .
      echo "manual_equal_status_2=$r41_manual_equal_status_2"
      echo "$r41_manual_equal_body_2" | jq .
      echo "manual_equal_pedido_1=$r41_manual_equal_pedido_1"
      echo "manual_equal_pedido_2=$r41_manual_equal_pedido_2"
      echo "manual_equal_idempotente_2=$r41_manual_equal_idempotente_2"
      echo "manual_equal_pedidos_count=$r41_manual_equal_pedidos_count"
      echo "manual_equal_idempotencia_rows=$r41_manual_equal_idem_rows"
      echo "manual_div_status=$r41_manual_div_status"
      echo "$r41_manual_div_body" | jq .
      echo "manual_div_pedidos_count=$r41_manual_div_pedidos_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r41_auto_equal_status_1" == "200" \
      && "$r41_auto_equal_status_2" == "200" \
      && "$r41_auto_equal_pedido_1" == "$r41_auto_equal_pedido_2" \
      && "$r41_auto_equal_idempotente_2" == "true" \
      && "$r41_auto_equal_pedidos_count" == "1" \
      && "$r41_auto_equal_idem_rows" == "1" \
      && "$r41_auto_div_status" == "400" \
      && "$r41_auto_div_msg" == *"sourceEventId diverge de externalCallId"* \
      && "$r41_auto_div_pedidos_count" == "0" \
      && "$r41_manual_equal_status_1" == "200" \
      && "$r41_manual_equal_status_2" == "200" \
      && "$r41_manual_equal_pedido_1" == "$r41_manual_equal_pedido_2" \
      && "$r41_manual_equal_idempotente_2" == "true" \
      && "$r41_manual_equal_pedidos_count" == "1" \
      && "$r41_manual_equal_idem_rows" == "1" \
      && "$r41_manual_div_status" == "400" \
      && "$r41_manual_div_msg" == *"manualRequestId diverge de externalCallId"* \
      && "$r41_manual_div_pedidos_count" == "0" ]]; then
      record_check "R41" "externalCallId deve ser consistente com sourceEventId/manualRequestId" "PASS" "$check_dir/evidence.txt" "API rejeitou divergencia entre chaves legacy e canonicas e manteve comportamento idempotente quando os valores coincidiram."
    else
      record_check "R41" "externalCallId deve ser consistente com sourceEventId/manualRequestId" "FAIL" "$check_dir/evidence.txt" "Consistencia entre externalCallId e chaves canonicas nao comprovada."
    fi
  else
    record_check "R41" "externalCallId deve ser consistente com sourceEventId/manualRequestId" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R42
  check_dir="$(new_check_dir R42)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r42_key_equal="bg-r42-manual-key-$(date +%s)-$RANDOM"
    r42_key_equal_esc="$(sql_escape "$r42_key_equal")"
    payload_r42_equal="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg externalCallId "$r42_key_equal" \
      --arg telefone "(38) 99990-1400" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R42 Eq",endereco:"Rua BG R42, 100",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r42_equal" "Idempotency-Key" "$r42_key_equal"
    r42_status_equal_1="$API_LAST_STATUS"
    r42_body_equal_1="$API_LAST_BODY"
    r42_pedido_equal_1="$(echo "$r42_body_equal_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r42_equal" "Idempotency-Key" "$r42_key_equal"
    r42_status_equal_2="$API_LAST_STATUS"
    r42_body_equal_2="$API_LAST_BODY"
    r42_pedido_equal_2="$(echo "$r42_body_equal_2" | jq -r '.pedidoId // 0')"
    r42_idempotente_equal_2="$(echo "$r42_body_equal_2" | jq -r '.idempotente // false')"
    r42_pedidos_equal_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901400';" | extract_single_value)"
    r42_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r42_key_equal_esc}';" | extract_single_value)"
    r42_external_call_equal="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r42_pedido_equal_1};" | extract_single_value)"

    payload_r42_div="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg externalCallId "bg-r42-manual-ext-b" \
      --arg telefone "(38) 99990-1401" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R42 Div",endereco:"Rua BG R42, 101",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r42_div" "Idempotency-Key" "bg-r42-manual-ext-a"
    r42_status_div="$API_LAST_STATUS"
    r42_body_div="$API_LAST_BODY"
    r42_msg_div="$(echo "$r42_body_div" | jq -r '.erro // ""')"
    r42_pedidos_div_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901401';" | extract_single_value)"

    {
      echo "equal_status_1=$r42_status_equal_1"
      echo "$r42_body_equal_1" | jq .
      echo "equal_status_2=$r42_status_equal_2"
      echo "$r42_body_equal_2" | jq .
      echo "equal_pedido_1=$r42_pedido_equal_1"
      echo "equal_pedido_2=$r42_pedido_equal_2"
      echo "equal_idempotente_2=$r42_idempotente_equal_2"
      echo "equal_pedidos_count=$r42_pedidos_equal_count"
      echo "equal_idempotencia_rows=$r42_idem_rows"
      echo "equal_pedido_external_call_id=$r42_external_call_equal"
      echo "div_status=$r42_status_div"
      echo "$r42_body_div" | jq .
      echo "div_pedidos_count=$r42_pedidos_div_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r42_status_equal_1" == "200" \
      && "$r42_status_equal_2" == "200" \
      && "$r42_pedido_equal_1" == "$r42_pedido_equal_2" \
      && "$r42_idempotente_equal_2" == "true" \
      && "$r42_pedidos_equal_count" == "1" \
      && "$r42_idem_rows" == "1" \
      && "$r42_external_call_equal" == "__NULL__" \
      && "$r42_status_div" == "400" \
      && "$r42_msg_div" == *"manualRequestId diverge de externalCallId"* \
      && "$r42_pedidos_div_count" == "0" ]]; then
      record_check "R42" "MANUAL com Idempotency-Key e externalCallId exige consistencia" "PASS" "$check_dir/evidence.txt" "Fluxo manual aceitou chave igual (header+external) e bloqueou divergencia sem criar pedido."
    else
      record_check "R42" "MANUAL com Idempotency-Key e externalCallId exige consistencia" "FAIL" "$check_dir/evidence.txt" "Consistencia entre Idempotency-Key e externalCallId no canal MANUAL nao comprovada."
    fi
  else
    record_check "R42" "MANUAL com Idempotency-Key e externalCallId exige consistencia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R43
  check_dir="$(new_check_dir R43)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r43_key_equal="bg-r43-manual-xkey-$(date +%s)-$RANDOM"
    r43_key_equal_esc="$(sql_escape "$r43_key_equal")"
    payload_r43_equal="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg externalCallId "$r43_key_equal" \
      --arg telefone "(38) 99990-1402" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R43 Eq",endereco:"Rua BG R43, 102",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r43_equal" "X-Idempotency-Key" "$r43_key_equal"
    r43_status_equal_1="$API_LAST_STATUS"
    r43_body_equal_1="$API_LAST_BODY"
    r43_pedido_equal_1="$(echo "$r43_body_equal_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r43_equal" "X-Idempotency-Key" "$r43_key_equal"
    r43_status_equal_2="$API_LAST_STATUS"
    r43_body_equal_2="$API_LAST_BODY"
    r43_pedido_equal_2="$(echo "$r43_body_equal_2" | jq -r '.pedidoId // 0')"
    r43_idempotente_equal_2="$(echo "$r43_body_equal_2" | jq -r '.idempotente // false')"
    r43_pedidos_equal_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901402';" | extract_single_value)"
    r43_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'MANUAL' AND source_event_id = '${r43_key_equal_esc}';" | extract_single_value)"
    r43_external_call_equal="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r43_pedido_equal_1};" | extract_single_value)"

    payload_r43_div="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg externalCallId "bg-r43-manual-ext-b" \
      --arg telefone "(38) 99990-1403" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R43 Div",endereco:"Rua BG R43, 103",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r43_div" "X-Idempotency-Key" "bg-r43-manual-ext-a"
    r43_status_div="$API_LAST_STATUS"
    r43_body_div="$API_LAST_BODY"
    r43_msg_div="$(echo "$r43_body_div" | jq -r '.erro // ""')"
    r43_pedidos_div_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901403';" | extract_single_value)"

    {
      echo "equal_status_1=$r43_status_equal_1"
      echo "$r43_body_equal_1" | jq .
      echo "equal_status_2=$r43_status_equal_2"
      echo "$r43_body_equal_2" | jq .
      echo "equal_pedido_1=$r43_pedido_equal_1"
      echo "equal_pedido_2=$r43_pedido_equal_2"
      echo "equal_idempotente_2=$r43_idempotente_equal_2"
      echo "equal_pedidos_count=$r43_pedidos_equal_count"
      echo "equal_idempotencia_rows=$r43_idem_rows"
      echo "equal_pedido_external_call_id=$r43_external_call_equal"
      echo "div_status=$r43_status_div"
      echo "$r43_body_div" | jq .
      echo "div_pedidos_count=$r43_pedidos_div_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r43_status_equal_1" == "200" \
      && "$r43_status_equal_2" == "200" \
      && "$r43_pedido_equal_1" == "$r43_pedido_equal_2" \
      && "$r43_idempotente_equal_2" == "true" \
      && "$r43_pedidos_equal_count" == "1" \
      && "$r43_idem_rows" == "1" \
      && "$r43_external_call_equal" == "__NULL__" \
      && "$r43_status_div" == "400" \
      && "$r43_msg_div" == *"manualRequestId diverge de externalCallId"* \
      && "$r43_pedidos_div_count" == "0" ]]; then
      record_check "R43" "MANUAL com X-Idempotency-Key e externalCallId exige consistencia" "PASS" "$check_dir/evidence.txt" "Fluxo manual aceitou chave igual (x-header+external) e bloqueou divergencia sem criar pedido."
    else
      record_check "R43" "MANUAL com X-Idempotency-Key e externalCallId exige consistencia" "FAIL" "$check_dir/evidence.txt" "Consistencia entre X-Idempotency-Key e externalCallId no canal MANUAL nao comprovada."
    fi
  else
    record_check "R43" "MANUAL com X-Idempotency-Key e externalCallId exige consistencia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R44
  check_dir="$(new_check_dir R44)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r44_key_equal="bg-r44-origem-omitida-$(date +%s)-$RANDOM"
    r44_key_equal_esc="$(sql_escape "$r44_key_equal")"
    payload_r44_equal="$(jq -n \
      --arg externalCallId "$r44_key_equal" \
      --arg telefone "(38) 99990-1404" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R44 Eq",endereco:"Rua BG R44, 104",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r44_equal" "Idempotency-Key" "$r44_key_equal"
    r44_status_equal_1="$API_LAST_STATUS"
    r44_body_equal_1="$API_LAST_BODY"
    r44_pedido_equal_1="$(echo "$r44_body_equal_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r44_equal" "Idempotency-Key" "$r44_key_equal"
    r44_status_equal_2="$API_LAST_STATUS"
    r44_body_equal_2="$API_LAST_BODY"
    r44_pedido_equal_2="$(echo "$r44_body_equal_2" | jq -r '.pedidoId // 0')"
    r44_idempotente_equal_2="$(echo "$r44_body_equal_2" | jq -r '.idempotente // false')"
    r44_pedidos_equal_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901404';" | extract_single_value)"
    r44_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'TELEFONIA_FIXO' AND source_event_id = '${r44_key_equal_esc}';" | extract_single_value)"
    r44_external_call_equal="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r44_pedido_equal_1};" | extract_single_value)"

    payload_r44_div="$(jq -n \
      --arg externalCallId "bg-r44-origem-omitida-b" \
      --arg telefone "(38) 99990-1405" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R44 Div",endereco:"Rua BG R44, 105",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r44_div" "Idempotency-Key" "bg-r44-origem-omitida-a"
    r44_status_div="$API_LAST_STATUS"
    r44_body_div="$API_LAST_BODY"
    r44_msg_div="$(echo "$r44_body_div" | jq -r '.erro // ""')"
    r44_pedidos_div_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901405';" | extract_single_value)"

    {
      echo "equal_status_1=$r44_status_equal_1"
      echo "$r44_body_equal_1" | jq .
      echo "equal_status_2=$r44_status_equal_2"
      echo "$r44_body_equal_2" | jq .
      echo "equal_pedido_1=$r44_pedido_equal_1"
      echo "equal_pedido_2=$r44_pedido_equal_2"
      echo "equal_idempotente_2=$r44_idempotente_equal_2"
      echo "equal_pedidos_count=$r44_pedidos_equal_count"
      echo "equal_idempotencia_rows=$r44_idem_rows"
      echo "equal_pedido_external_call_id=$r44_external_call_equal"
      echo "div_status=$r44_status_div"
      echo "$r44_body_div" | jq .
      echo "div_pedidos_count=$r44_pedidos_div_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r44_status_equal_1" == "200" \
      && "$r44_status_equal_2" == "200" \
      && "$r44_pedido_equal_1" == "$r44_pedido_equal_2" \
      && "$r44_idempotente_equal_2" == "true" \
      && "$r44_pedidos_equal_count" == "1" \
      && "$r44_idem_rows" == "1" \
      && "$r44_external_call_equal" == "$r44_key_equal" \
      && "$r44_status_div" == "400" \
      && "$r44_msg_div" == *"Idempotency-Key diverge de externalCallId"* \
      && "$r44_pedidos_div_count" == "0" ]]; then
      record_check "R44" "Origem omitida com externalCallId + Idempotency-Key exige consistencia" "PASS" "$check_dir/evidence.txt" "Origem omitida aceitou chave igual (external+header) e bloqueou divergencia sem criar pedido."
    else
      record_check "R44" "Origem omitida com externalCallId + Idempotency-Key exige consistencia" "FAIL" "$check_dir/evidence.txt" "Consistencia entre externalCallId e Idempotency-Key na origem omitida nao comprovada."
    fi
  else
    record_check "R44" "Origem omitida com externalCallId + Idempotency-Key exige consistencia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R45
  check_dir="$(new_check_dir R45)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r45_key_equal="bg-r45-origem-omitida-$(date +%s)-$RANDOM"
    r45_key_equal_esc="$(sql_escape "$r45_key_equal")"
    payload_r45_equal="$(jq -n \
      --arg externalCallId "$r45_key_equal" \
      --arg telefone "(38) 99990-1406" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R45 Eq",endereco:"Rua BG R45, 106",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r45_equal" "X-Idempotency-Key" "$r45_key_equal"
    r45_status_equal_1="$API_LAST_STATUS"
    r45_body_equal_1="$API_LAST_BODY"
    r45_pedido_equal_1="$(echo "$r45_body_equal_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r45_equal" "X-Idempotency-Key" "$r45_key_equal"
    r45_status_equal_2="$API_LAST_STATUS"
    r45_body_equal_2="$API_LAST_BODY"
    r45_pedido_equal_2="$(echo "$r45_body_equal_2" | jq -r '.pedidoId // 0')"
    r45_idempotente_equal_2="$(echo "$r45_body_equal_2" | jq -r '.idempotente // false')"
    r45_pedidos_equal_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901406';" | extract_single_value)"
    r45_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'TELEFONIA_FIXO' AND source_event_id = '${r45_key_equal_esc}';" | extract_single_value)"
    r45_external_call_equal="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r45_pedido_equal_1};" | extract_single_value)"

    payload_r45_div="$(jq -n \
      --arg externalCallId "bg-r45-origem-omitida-b" \
      --arg telefone "(38) 99990-1407" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R45 Div",endereco:"Rua BG R45, 107",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r45_div" "X-Idempotency-Key" "bg-r45-origem-omitida-a"
    r45_status_div="$API_LAST_STATUS"
    r45_body_div="$API_LAST_BODY"
    r45_msg_div="$(echo "$r45_body_div" | jq -r '.erro // ""')"
    r45_pedidos_div_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901407';" | extract_single_value)"

    {
      echo "equal_status_1=$r45_status_equal_1"
      echo "$r45_body_equal_1" | jq .
      echo "equal_status_2=$r45_status_equal_2"
      echo "$r45_body_equal_2" | jq .
      echo "equal_pedido_1=$r45_pedido_equal_1"
      echo "equal_pedido_2=$r45_pedido_equal_2"
      echo "equal_idempotente_2=$r45_idempotente_equal_2"
      echo "equal_pedidos_count=$r45_pedidos_equal_count"
      echo "equal_idempotencia_rows=$r45_idem_rows"
      echo "equal_pedido_external_call_id=$r45_external_call_equal"
      echo "div_status=$r45_status_div"
      echo "$r45_body_div" | jq .
      echo "div_pedidos_count=$r45_pedidos_div_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r45_status_equal_1" == "200" \
      && "$r45_status_equal_2" == "200" \
      && "$r45_pedido_equal_1" == "$r45_pedido_equal_2" \
      && "$r45_idempotente_equal_2" == "true" \
      && "$r45_pedidos_equal_count" == "1" \
      && "$r45_idem_rows" == "1" \
      && "$r45_external_call_equal" == "$r45_key_equal" \
      && "$r45_status_div" == "400" \
      && "$r45_msg_div" == *"Idempotency-Key diverge de externalCallId"* \
      && "$r45_pedidos_div_count" == "0" ]]; then
      record_check "R45" "Origem omitida com externalCallId + X-Idempotency-Key exige consistencia" "PASS" "$check_dir/evidence.txt" "Origem omitida aceitou chave igual (external+x-header) e bloqueou divergencia sem criar pedido."
    else
      record_check "R45" "Origem omitida com externalCallId + X-Idempotency-Key exige consistencia" "FAIL" "$check_dir/evidence.txt" "Consistencia entre externalCallId e X-Idempotency-Key na origem omitida nao comprovada."
    fi
  else
    record_check "R45" "Origem omitida com externalCallId + X-Idempotency-Key exige consistencia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R46
  check_dir="$(new_check_dir R46)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r46_key_equal="bg-r46-origem-omitida-$(date +%s)-$RANDOM"
    r46_key_equal_esc="$(sql_escape "$r46_key_equal")"
    payload_r46_equal="$(jq -n \
      --arg externalCallId "$r46_key_equal" \
      --arg telefone "(38) 99990-1408" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R46 Eq",endereco:"Rua BG R46, 108",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r46_equal" "Idempotency-Key" "$r46_key_equal" "X-Idempotency-Key" "$r46_key_equal"
    r46_status_equal_1="$API_LAST_STATUS"
    r46_body_equal_1="$API_LAST_BODY"
    r46_pedido_equal_1="$(echo "$r46_body_equal_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r46_equal" "Idempotency-Key" "$r46_key_equal" "X-Idempotency-Key" "$r46_key_equal"
    r46_status_equal_2="$API_LAST_STATUS"
    r46_body_equal_2="$API_LAST_BODY"
    r46_pedido_equal_2="$(echo "$r46_body_equal_2" | jq -r '.pedidoId // 0')"
    r46_idempotente_equal_2="$(echo "$r46_body_equal_2" | jq -r '.idempotente // false')"
    r46_pedidos_equal_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901408';" | extract_single_value)"
    r46_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'TELEFONIA_FIXO' AND source_event_id = '${r46_key_equal_esc}';" | extract_single_value)"
    r46_external_call_equal="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r46_pedido_equal_1};" | extract_single_value)"

    payload_r46_div_external="$(jq -n \
      --arg externalCallId "bg-r46-origem-omitida-b" \
      --arg telefone "(38) 99990-1409" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R46 Div External",endereco:"Rua BG R46, 109",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r46_div_external" "Idempotency-Key" "bg-r46-origem-omitida-a" "X-Idempotency-Key" "bg-r46-origem-omitida-a"
    r46_status_div_external="$API_LAST_STATUS"
    r46_body_div_external="$API_LAST_BODY"
    r46_msg_div_external="$(echo "$r46_body_div_external" | jq -r '.erro // ""')"
    r46_pedidos_div_external_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901409';" | extract_single_value)"

    payload_r46_div_headers="$(jq -n \
      --arg externalCallId "bg-r46-origem-omitida-c" \
      --arg telefone "(38) 99990-1410" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R46 Div Headers",endereco:"Rua BG R46, 110",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r46_div_headers" "Idempotency-Key" "bg-r46-headers-a" "X-Idempotency-Key" "bg-r46-headers-b"
    r46_status_div_headers="$API_LAST_STATUS"
    r46_body_div_headers="$API_LAST_BODY"
    r46_msg_div_headers="$(echo "$r46_body_div_headers" | jq -r '.erro // ""')"
    r46_pedidos_div_headers_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901410';" | extract_single_value)"

    {
      echo "equal_status_1=$r46_status_equal_1"
      echo "$r46_body_equal_1" | jq .
      echo "equal_status_2=$r46_status_equal_2"
      echo "$r46_body_equal_2" | jq .
      echo "equal_pedido_1=$r46_pedido_equal_1"
      echo "equal_pedido_2=$r46_pedido_equal_2"
      echo "equal_idempotente_2=$r46_idempotente_equal_2"
      echo "equal_pedidos_count=$r46_pedidos_equal_count"
      echo "equal_idempotencia_rows=$r46_idem_rows"
      echo "equal_pedido_external_call_id=$r46_external_call_equal"
      echo "div_external_status=$r46_status_div_external"
      echo "$r46_body_div_external" | jq .
      echo "div_external_pedidos_count=$r46_pedidos_div_external_count"
      echo "div_headers_status=$r46_status_div_headers"
      echo "$r46_body_div_headers" | jq .
      echo "div_headers_pedidos_count=$r46_pedidos_div_headers_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r46_status_equal_1" == "200" \
      && "$r46_status_equal_2" == "200" \
      && "$r46_pedido_equal_1" == "$r46_pedido_equal_2" \
      && "$r46_idempotente_equal_2" == "true" \
      && "$r46_pedidos_equal_count" == "1" \
      && "$r46_idem_rows" == "1" \
      && "$r46_external_call_equal" == "$r46_key_equal" \
      && "$r46_status_div_external" == "400" \
      && "$r46_msg_div_external" == *"Idempotency-Key diverge de externalCallId"* \
      && "$r46_pedidos_div_external_count" == "0" \
      && "$r46_status_div_headers" == "400" \
      && "$r46_msg_div_headers" == *"Idempotency-Key e X-Idempotency-Key devem ter o mesmo valor"* \
      && "$r46_pedidos_div_headers_count" == "0" ]]; then
      record_check "R46" "Origem omitida com externalCallId + headers duplos exige consistencia completa" "PASS" "$check_dir/evidence.txt" "Origem omitida aceitou headers iguais com external igual e bloqueou divergencia de external e de headers sem criar pedido."
    else
      record_check "R46" "Origem omitida com externalCallId + headers duplos exige consistencia completa" "FAIL" "$check_dir/evidence.txt" "Consistencia completa entre externalCallId e headers duplos na origem omitida nao comprovada."
    fi
  else
    record_check "R46" "Origem omitida com externalCallId + headers duplos exige consistencia completa" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R47
  check_dir="$(new_check_dir R47)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r47_key_idem="bg-r47-wa-$(date +%s)-$RANDOM"
    r47_key_idem_esc="$(sql_escape "$r47_key_idem")"
    payload_r47_idem="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r47_key_idem" \
      --arg telefone "(38) 99990-1411" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R47 Idem Header",endereco:"Rua BG R47, 111",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r47_idem" "Idempotency-Key" "$r47_key_idem"
    r47_status_idem_1="$API_LAST_STATUS"
    r47_body_idem_1="$API_LAST_BODY"
    r47_pedido_idem_1="$(echo "$r47_body_idem_1" | jq -r '.pedidoId // 0')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r47_idem" "Idempotency-Key" "$r47_key_idem"
    r47_status_idem_2="$API_LAST_STATUS"
    r47_body_idem_2="$API_LAST_BODY"
    r47_pedido_idem_2="$(echo "$r47_body_idem_2" | jq -r '.pedidoId // 0')"
    r47_idempotente_idem_2="$(echo "$r47_body_idem_2" | jq -r '.idempotente // false')"
    r47_idem_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901411';" | extract_single_value)"
    r47_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r47_key_idem_esc}';" | extract_single_value)"

    payload_r47_div_idem="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "bg-r47-wa-div-idem-b" \
      --arg telefone "(38) 99990-1412" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R47 Div Idem",endereco:"Rua BG R47, 112",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r47_div_idem" "Idempotency-Key" "bg-r47-wa-div-idem-a"
    r47_status_div_idem="$API_LAST_STATUS"
    r47_body_div_idem="$API_LAST_BODY"
    r47_msg_div_idem="$(echo "$r47_body_div_idem" | jq -r '.erro // ""')"
    r47_div_idem_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901412';" | extract_single_value)"

    r47_key_x="bg-r47-wa-x-$(date +%s)-$RANDOM"
    r47_key_x_esc="$(sql_escape "$r47_key_x")"
    payload_r47_x="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r47_key_x" \
      --arg telefone "(38) 99990-1413" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R47 Idem X",endereco:"Rua BG R47, 113",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r47_x" "X-Idempotency-Key" "$r47_key_x"
    r47_status_x_1="$API_LAST_STATUS"
    r47_body_x_1="$API_LAST_BODY"
    r47_pedido_x_1="$(echo "$r47_body_x_1" | jq -r '.pedidoId // 0')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r47_x" "X-Idempotency-Key" "$r47_key_x"
    r47_status_x_2="$API_LAST_STATUS"
    r47_body_x_2="$API_LAST_BODY"
    r47_pedido_x_2="$(echo "$r47_body_x_2" | jq -r '.pedidoId // 0')"
    r47_idempotente_x_2="$(echo "$r47_body_x_2" | jq -r '.idempotente // false')"
    r47_x_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901413';" | extract_single_value)"
    r47_x_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r47_key_x_esc}';" | extract_single_value)"

    payload_r47_div_x="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "bg-r47-wa-div-x-b" \
      --arg telefone "(38) 99990-1414" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R47 Div X",endereco:"Rua BG R47, 114",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r47_div_x" "X-Idempotency-Key" "bg-r47-wa-div-x-a"
    r47_status_div_x="$API_LAST_STATUS"
    r47_body_div_x="$API_LAST_BODY"
    r47_msg_div_x="$(echo "$r47_body_div_x" | jq -r '.erro // ""')"
    r47_div_x_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901414';" | extract_single_value)"

    {
      echo "idem_status_1=$r47_status_idem_1"
      echo "$r47_body_idem_1" | jq .
      echo "idem_status_2=$r47_status_idem_2"
      echo "$r47_body_idem_2" | jq .
      echo "idem_pedido_1=$r47_pedido_idem_1"
      echo "idem_pedido_2=$r47_pedido_idem_2"
      echo "idem_idempotente_2=$r47_idempotente_idem_2"
      echo "idem_pedidos_count=$r47_idem_count"
      echo "idem_idempotencia_rows=$r47_idem_rows"
      echo "div_idem_status=$r47_status_div_idem"
      echo "$r47_body_div_idem" | jq .
      echo "div_idem_pedidos_count=$r47_div_idem_count"
      echo "x_status_1=$r47_status_x_1"
      echo "$r47_body_x_1" | jq .
      echo "x_status_2=$r47_status_x_2"
      echo "$r47_body_x_2" | jq .
      echo "x_pedido_1=$r47_pedido_x_1"
      echo "x_pedido_2=$r47_pedido_x_2"
      echo "x_idempotente_2=$r47_idempotente_x_2"
      echo "x_pedidos_count=$r47_x_count"
      echo "x_idempotencia_rows=$r47_x_rows"
      echo "div_x_status=$r47_status_div_x"
      echo "$r47_body_div_x" | jq .
      echo "div_x_pedidos_count=$r47_div_x_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r47_status_idem_1" == "200" \
      && "$r47_status_idem_2" == "200" \
      && "$r47_pedido_idem_1" == "$r47_pedido_idem_2" \
      && "$r47_idempotente_idem_2" == "true" \
      && "$r47_idem_count" == "1" \
      && "$r47_idem_rows" == "1" \
      && "$r47_status_div_idem" == "400" \
      && "$r47_msg_div_idem" == *"sourceEventId diverge do header Idempotency-Key"* \
      && "$r47_div_idem_count" == "0" \
      && "$r47_status_x_1" == "200" \
      && "$r47_status_x_2" == "200" \
      && "$r47_pedido_x_1" == "$r47_pedido_x_2" \
      && "$r47_idempotente_x_2" == "true" \
      && "$r47_x_count" == "1" \
      && "$r47_x_rows" == "1" \
      && "$r47_status_div_x" == "400" \
      && "$r47_msg_div_x" == *"sourceEventId diverge do header Idempotency-Key"* \
      && "$r47_div_x_count" == "0" ]]; then
      record_check "R47" "Canal automatico com sourceEventId e header exige consistencia" "PASS" "$check_dir/evidence.txt" "Canal automatico aceitou headers iguais ao sourceEventId e bloqueou divergencias sem criar pedido."
    else
      record_check "R47" "Canal automatico com sourceEventId e header exige consistencia" "FAIL" "$check_dir/evidence.txt" "Consistencia entre sourceEventId e headers no canal automatico nao comprovada."
    fi
  else
    record_check "R47" "Canal automatico com sourceEventId e header exige consistencia" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R48
  check_dir="$(new_check_dir R48)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r48_key_equal="bg-r48-wa-$(date +%s)-$RANDOM"
    r48_key_equal_esc="$(sql_escape "$r48_key_equal")"
    payload_r48_equal="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "$r48_key_equal" \
      --arg telefone "(38) 99990-1415" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R48 Eq",endereco:"Rua BG R48, 115",latitude:-16.7310,longitude:-43.8710}')"

    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r48_equal" "Idempotency-Key" "$r48_key_equal" "X-Idempotency-Key" "$r48_key_equal"
    r48_status_equal_1="$API_LAST_STATUS"
    r48_body_equal_1="$API_LAST_BODY"
    r48_pedido_equal_1="$(echo "$r48_body_equal_1" | jq -r '.pedidoId // 0')"

    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r48_equal" "Idempotency-Key" "$r48_key_equal" "X-Idempotency-Key" "$r48_key_equal"
    r48_status_equal_2="$API_LAST_STATUS"
    r48_body_equal_2="$API_LAST_BODY"
    r48_pedido_equal_2="$(echo "$r48_body_equal_2" | jq -r '.pedidoId // 0')"
    r48_idempotente_equal_2="$(echo "$r48_body_equal_2" | jq -r '.idempotente // false')"
    r48_pedidos_equal_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901415';" | extract_single_value)"
    r48_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'WHATSAPP' AND source_event_id = '${r48_key_equal_esc}';" | extract_single_value)"

    payload_r48_div_source="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "bg-r48-wa-div-source-b" \
      --arg telefone "(38) 99990-1416" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R48 Div Source",endereco:"Rua BG R48, 116",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r48_div_source" "Idempotency-Key" "bg-r48-wa-div-source-a" "X-Idempotency-Key" "bg-r48-wa-div-source-a"
    r48_status_div_source="$API_LAST_STATUS"
    r48_body_div_source="$API_LAST_BODY"
    r48_msg_div_source="$(echo "$r48_body_div_source" | jq -r '.erro // ""')"
    r48_pedidos_div_source_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901416';" | extract_single_value)"

    payload_r48_div_headers="$(jq -n \
      --arg origemCanal "WHATSAPP" \
      --arg sourceEventId "bg-r48-wa-div-headers-c" \
      --arg telefone "(38) 99990-1417" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R48 Div Headers",endereco:"Rua BG R48, 117",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_two_headers "/api/atendimento/pedidos" "$payload_r48_div_headers" "Idempotency-Key" "bg-r48-wa-div-headers-a" "X-Idempotency-Key" "bg-r48-wa-div-headers-b"
    r48_status_div_headers="$API_LAST_STATUS"
    r48_body_div_headers="$API_LAST_BODY"
    r48_msg_div_headers="$(echo "$r48_body_div_headers" | jq -r '.erro // ""')"
    r48_pedidos_div_headers_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901417';" | extract_single_value)"

    {
      echo "equal_status_1=$r48_status_equal_1"
      echo "$r48_body_equal_1" | jq .
      echo "equal_status_2=$r48_status_equal_2"
      echo "$r48_body_equal_2" | jq .
      echo "equal_pedido_1=$r48_pedido_equal_1"
      echo "equal_pedido_2=$r48_pedido_equal_2"
      echo "equal_idempotente_2=$r48_idempotente_equal_2"
      echo "equal_pedidos_count=$r48_pedidos_equal_count"
      echo "equal_idempotencia_rows=$r48_idem_rows"
      echo "div_source_status=$r48_status_div_source"
      echo "$r48_body_div_source" | jq .
      echo "div_source_pedidos_count=$r48_pedidos_div_source_count"
      echo "div_headers_status=$r48_status_div_headers"
      echo "$r48_body_div_headers" | jq .
      echo "div_headers_pedidos_count=$r48_pedidos_div_headers_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r48_status_equal_1" == "200" \
      && "$r48_status_equal_2" == "200" \
      && "$r48_pedido_equal_1" == "$r48_pedido_equal_2" \
      && "$r48_idempotente_equal_2" == "true" \
      && "$r48_pedidos_equal_count" == "1" \
      && "$r48_idem_rows" == "1" \
      && "$r48_status_div_source" == "400" \
      && "$r48_msg_div_source" == *"sourceEventId diverge do header Idempotency-Key"* \
      && "$r48_pedidos_div_source_count" == "0" \
      && "$r48_status_div_headers" == "400" \
      && "$r48_msg_div_headers" == *"Idempotency-Key e X-Idempotency-Key devem ter o mesmo valor"* \
      && "$r48_pedidos_div_headers_count" == "0" ]]; then
      record_check "R48" "Canal automatico com sourceEventId + headers duplos exige consistencia completa" "PASS" "$check_dir/evidence.txt" "Canal automatico aceitou headers duplos iguais ao sourceEventId e bloqueou divergencias sem criar pedido."
    else
      record_check "R48" "Canal automatico com sourceEventId + headers duplos exige consistencia completa" "FAIL" "$check_dir/evidence.txt" "Consistencia completa entre sourceEventId e headers duplos no canal automatico nao comprovada."
    fi
  else
    record_check "R48" "Canal automatico com sourceEventId + headers duplos exige consistencia completa" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R49
  check_dir="$(new_check_dir R49)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users

    r49_key_equal="bg-r49-origem-omitida-source-$(date +%s)-$RANDOM"
    r49_key_equal_esc="$(sql_escape "$r49_key_equal")"
    payload_r49_equal="$(jq -n \
      --arg sourceEventId "$r49_key_equal" \
      --arg telefone "(38) 99990-1418" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R49 Eq",endereco:"Rua BG R49, 118",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r49_equal" "Idempotency-Key" "$r49_key_equal"
    r49_status_equal_1="$API_LAST_STATUS"
    r49_body_equal_1="$API_LAST_BODY"
    r49_pedido_equal_1="$(echo "$r49_body_equal_1" | jq -r '.pedidoId // 0')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r49_equal" "Idempotency-Key" "$r49_key_equal"
    r49_status_equal_2="$API_LAST_STATUS"
    r49_body_equal_2="$API_LAST_BODY"
    r49_pedido_equal_2="$(echo "$r49_body_equal_2" | jq -r '.pedidoId // 0')"
    r49_idempotente_equal_2="$(echo "$r49_body_equal_2" | jq -r '.idempotente // false')"
    r49_pedidos_equal_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901418';" | extract_single_value)"
    r49_idem_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'TELEFONIA_FIXO' AND source_event_id = '${r49_key_equal_esc}';" | extract_single_value)"
    r49_external_equal="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r49_pedido_equal_1};" | extract_single_value)"

    payload_r49_equal_x="$(jq -n \
      --arg sourceEventId "bg-r49-origem-omitida-x-$(date +%s)-$RANDOM" \
      --arg telefone "(38) 99990-1419" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R49 Eq X",endereco:"Rua BG R49, 119",latitude:-16.7310,longitude:-43.8710}')"
    r49_key_x="$(echo "$payload_r49_equal_x" | jq -r '.sourceEventId')"
    r49_key_x_esc="$(sql_escape "$r49_key_x")"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r49_equal_x" "X-Idempotency-Key" "$r49_key_x"
    r49_status_x_1="$API_LAST_STATUS"
    r49_body_x_1="$API_LAST_BODY"
    r49_pedido_x_1="$(echo "$r49_body_x_1" | jq -r '.pedidoId // 0')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r49_equal_x" "X-Idempotency-Key" "$r49_key_x"
    r49_status_x_2="$API_LAST_STATUS"
    r49_body_x_2="$API_LAST_BODY"
    r49_pedido_x_2="$(echo "$r49_body_x_2" | jq -r '.pedidoId // 0')"
    r49_idempotente_x_2="$(echo "$r49_body_x_2" | jq -r '.idempotente // false')"
    r49_pedidos_x_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901419';" | extract_single_value)"
    r49_idem_x_rows="$(psql_query "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = 'TELEFONIA_FIXO' AND source_event_id = '${r49_key_x_esc}';" | extract_single_value)"
    r49_external_x="$(psql_query "SELECT COALESCE(external_call_id, '__NULL__') FROM pedidos WHERE id = ${r49_pedido_x_1};" | extract_single_value)"

    payload_r49_div_header="$(jq -n \
      --arg sourceEventId "bg-r49-origem-omitida-div-b" \
      --arg telefone "(38) 99990-1420" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{sourceEventId:$sourceEventId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R49 Div Header",endereco:"Rua BG R49, 120",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture_with_header "/api/atendimento/pedidos" "$payload_r49_div_header" "Idempotency-Key" "bg-r49-origem-omitida-div-a"
    r49_status_div_header="$API_LAST_STATUS"
    r49_body_div_header="$API_LAST_BODY"
    r49_msg_div_header="$(echo "$r49_body_div_header" | jq -r '.erro // ""')"
    r49_pedidos_div_header_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901420';" | extract_single_value)"

    payload_r49_source_manual="$(jq -n \
      --arg sourceEventId "bg-r49-origem-omitida-source-manual-1" \
      --arg manualRequestId "bg-r49-origem-omitida-manual-1" \
      --arg telefone "(38) 99990-1421" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{sourceEventId:$sourceEventId,manualRequestId:$manualRequestId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R49 Source+Manual",endereco:"Rua BG R49, 121",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r49_source_manual"
    r49_status_source_manual="$API_LAST_STATUS"
    r49_body_source_manual="$API_LAST_BODY"
    r49_msg_source_manual="$(echo "$r49_body_source_manual" | jq -r '.erro // ""')"
    r49_pedidos_source_manual_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '38999901421';" | extract_single_value)"

    {
      echo "equal_status_1=$r49_status_equal_1"
      echo "$r49_body_equal_1" | jq .
      echo "equal_status_2=$r49_status_equal_2"
      echo "$r49_body_equal_2" | jq .
      echo "equal_pedido_1=$r49_pedido_equal_1"
      echo "equal_pedido_2=$r49_pedido_equal_2"
      echo "equal_idempotente_2=$r49_idempotente_equal_2"
      echo "equal_pedidos_count=$r49_pedidos_equal_count"
      echo "equal_idempotencia_rows=$r49_idem_rows"
      echo "equal_pedido_external_call_id=$r49_external_equal"
      echo "x_status_1=$r49_status_x_1"
      echo "$r49_body_x_1" | jq .
      echo "x_status_2=$r49_status_x_2"
      echo "$r49_body_x_2" | jq .
      echo "x_pedido_1=$r49_pedido_x_1"
      echo "x_pedido_2=$r49_pedido_x_2"
      echo "x_idempotente_2=$r49_idempotente_x_2"
      echo "x_pedidos_count=$r49_pedidos_x_count"
      echo "x_idempotencia_rows=$r49_idem_x_rows"
      echo "x_pedido_external_call_id=$r49_external_x"
      echo "div_header_status=$r49_status_div_header"
      echo "$r49_body_div_header" | jq .
      echo "div_header_pedidos_count=$r49_pedidos_div_header_count"
      echo "source_manual_status=$r49_status_source_manual"
      echo "$r49_body_source_manual" | jq .
      echo "source_manual_pedidos_count=$r49_pedidos_source_manual_count"
    } > "$check_dir/evidence.txt"

    if [[ "$r49_status_equal_1" == "200" \
      && "$r49_status_equal_2" == "200" \
      && "$r49_pedido_equal_1" == "$r49_pedido_equal_2" \
      && "$r49_idempotente_equal_2" == "true" \
      && "$r49_pedidos_equal_count" == "1" \
      && "$r49_idem_rows" == "1" \
      && "$r49_external_equal" == "$r49_key_equal" \
      && "$r49_status_x_1" == "200" \
      && "$r49_status_x_2" == "200" \
      && "$r49_pedido_x_1" == "$r49_pedido_x_2" \
      && "$r49_idempotente_x_2" == "true" \
      && "$r49_pedidos_x_count" == "1" \
      && "$r49_idem_x_rows" == "1" \
      && "$r49_external_x" == "$r49_key_x" \
      && "$r49_status_div_header" == "400" \
      && "$r49_msg_div_header" == *"sourceEventId diverge do header Idempotency-Key"* \
      && "$r49_pedidos_div_header_count" == "0" \
      && "$r49_status_source_manual" == "400" \
      && "$r49_msg_source_manual" == *"manualRequestId so pode ser usado com origemCanal=MANUAL"* \
      && "$r49_pedidos_source_manual_count" == "0" ]]; then
      record_check "R49" "Origem omitida com sourceEventId segue regras de canal automatico" "PASS" "$check_dir/evidence.txt" "Origem omitida com sourceEventId inferiu canal automatico (TELEFONIA_FIXO), respeitou idempotencia por header e bloqueou manualRequestId."
    else
      record_check "R49" "Origem omitida com sourceEventId segue regras de canal automatico" "FAIL" "$check_dir/evidence.txt" "Inferencia/consistencia de origem omitida com sourceEventId nao comprovada."
    fi
  else
    record_check "R49" "Origem omitida com sourceEventId segue regras de canal automatico" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R36
  check_dir="$(new_check_dir R36)"
  if reset_state_for_check "$check_dir"; then
    prepare_base_users
    r36_phone_digits="38999901361"
    r36_phone_fmt="(38) 99990-1361"
    r36_manual_request_id="bg-r36-manual-$(date +%s)-$RANDOM"
    payload_r36_primeiro="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg manualRequestId "$r36_manual_request_id" \
      --arg telefone "$r36_phone_fmt" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,manualRequestId:$manualRequestId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX",nomeCliente:"Cliente BG R36",endereco:"Rua BG R36, 61",latitude:-16.7310,longitude:-43.8710}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r36_primeiro"
    r36_status_1="$API_LAST_STATUS"
    r36_body_1="$API_LAST_BODY"
    r36_pedido_1="$(echo "$r36_body_1" | jq -r '.pedidoId // 0')"

    psql_exec "UPDATE clientes
SET endereco = 'Endereco pendente', latitude = NULL, longitude = NULL, atualizado_em = CURRENT_TIMESTAMP
WHERE regexp_replace(telefone, '[^0-9]', '', 'g') = '${r36_phone_digits}';"

    payload_r36_segundo="$(jq -n \
      --arg origemCanal "MANUAL" \
      --arg telefone "$r36_phone_fmt" \
      --argjson quantidadeGaloes 1 \
      --argjson atendenteId "$ATENDENTE_ID" \
      '{origemCanal:$origemCanal,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')"
    api_post_capture "/api/atendimento/pedidos" "$payload_r36_segundo"
    r36_status_2="$API_LAST_STATUS"
    r36_body_2="$API_LAST_BODY"
    r36_pedido_2="$(echo "$r36_body_2" | jq -r '.pedidoId // 0')"
    r36_idempotente_2="$(echo "$r36_body_2" | jq -r '.idempotente // false')"
    r36_pedidos_count="$(psql_query "SELECT COUNT(*) FROM pedidos p JOIN clientes c ON c.id = p.cliente_id WHERE regexp_replace(c.telefone, '[^0-9]', '', 'g') = '${r36_phone_digits}';" | extract_single_value)"

    {
      echo "status_1=$r36_status_1"
      echo "$r36_body_1" | jq .
      echo "status_2=$r36_status_2"
      echo "$r36_body_2" | jq .
      echo "pedidos_count=$r36_pedidos_count"
    } > "$check_dir/evidence.txt"
    if [[ "$r36_status_1" == "200" \
      && "$r36_status_2" == "200" \
      && "$r36_pedido_1" == "$r36_pedido_2" \
      && "$r36_idempotente_2" == "true" \
      && "$r36_pedidos_count" == "1" ]]; then
      record_check "R36" "Manual retorna pedido aberto mesmo com cadastro degradado" "PASS" "$check_dir/evidence.txt" "Fluxo manual retornou pedido aberto mesmo apos degradacao de endereco/geo."
    else
      record_check "R36" "Manual retorna pedido aberto mesmo com cadastro degradado" "FAIL" "$check_dir/evidence.txt" "Fluxo manual nao preservou idempotencia operacional apos degradacao cadastral."
    fi
  else
    record_check "R36" "Manual retorna pedido aberto mesmo com cadastro degradado" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi

  # R37
  check_dir="$(new_check_dir R37)"
  if reset_state_for_check "$check_dir"; then
    r37_idx_exists="$(psql_query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'uk_clientes_telefone_normalizado';" | extract_single_value)"
    r37_idx_raw_exists="$(psql_query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_clientes_telefone';" | extract_single_value)"
    r37_expected_dup_log="$check_dir/expected-duplicates.log"
    : > "$r37_expected_dup_log"
    r37_suffix="$(printf '%04d' $((RANDOM % 10000)))"
    r37_phone_a="(38) 99990-${r37_suffix}"
    r37_phone_b="38 99990 ${r37_suffix}"
    r37_phone_a_esc="$(sql_escape "$r37_phone_a")"
    r37_phone_b_esc="$(sql_escape "$r37_phone_b")"

    set +e
    psql_exec "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude)
VALUES ('Cliente BG R37 A', '${r37_phone_a_esc}', 'PF', 'Rua BG R37, 71', -16.7310, -43.8710);"
    r37_insert_a_exit="$?"
    r37_insert_b_exit=99
    r37_insert_raw_dup_exit=99
    if [[ "$r37_insert_a_exit" -eq 0 ]]; then
      psql_exec "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude)
VALUES ('Cliente BG R37 B', '${r37_phone_b_esc}', 'PF', 'Rua BG R37, 72', -16.7310, -43.8710);" \
        2>>"$r37_expected_dup_log"
      r37_insert_b_exit="$?"
      psql_exec "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude)
VALUES ('Cliente BG R37 C', '${r37_phone_a_esc}', 'PF', 'Rua BG R37, 73', -16.7310, -43.8710);" \
        2>>"$r37_expected_dup_log"
      r37_insert_raw_dup_exit="$?"
    fi
    set -e

    r37_duplicados_norm="$(psql_query "SELECT COUNT(*) FROM (SELECT regexp_replace(telefone, '[^0-9]', '', 'g') AS telefone_normalizado, COUNT(*) AS total FROM clientes GROUP BY 1 HAVING COUNT(*) > 1) d;" | extract_single_value)"
    {
      echo "indice_uk_clientes_telefone_normalizado=$r37_idx_exists"
      echo "indice_idx_clientes_telefone_raw=$r37_idx_raw_exists"
      echo "insert_primeiro_exit=$r37_insert_a_exit"
      echo "insert_segundo_mesmo_normalizado_exit=$r37_insert_b_exit"
      echo "insert_terceiro_mesmo_bruto_exit=$r37_insert_raw_dup_exit"
      echo "telefone_a=$r37_phone_a"
      echo "telefone_b=$r37_phone_b"
      echo "duplicidades_telefone_normalizado=$r37_duplicados_norm"
      echo "expected_duplicate_log=$r37_expected_dup_log"
    } > "$check_dir/evidence.txt"
    if [[ "$r37_idx_exists" =~ ^[0-9]+$ \
      && "$r37_idx_exists" -ge 1 \
      && "$r37_idx_raw_exists" =~ ^[0-9]+$ \
      && "$r37_idx_raw_exists" -ge 1 \
      && "$r37_insert_a_exit" -eq 0 \
      && "$r37_insert_b_exit" -ne 0 \
      && "$r37_insert_raw_dup_exit" -ne 0 \
      && "$r37_duplicados_norm" == "0" ]]; then
      record_check "R37" "Schema aplica unicidade por telefone normalizado" "PASS" "$check_dir/evidence.txt" "Unicidade por telefone bruto (legado) e normalizado (omnichannel) comprovada no schema."
    else
      record_check "R37" "Schema aplica unicidade por telefone normalizado" "FAIL" "$check_dir/evidence.txt" "Unicidade por telefone normalizado nao comprovada (indice/insercao/duplicidade)."
    fi
  else
    record_check "R37" "Schema aplica unicidade por telefone normalizado" "FAIL" "$check_dir/reset.log" "Falha ao resetar estado."
  fi
fi

SUMMARY_JSON="$ARTIFACT_DIR/business-summary.json"
SUMMARY_TXT="$ARTIFACT_DIR/business-summary.txt"

jq -s \
  --arg schemaVersion "1.0.0" \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg mode "$MODE" \
  --arg checkFilter "$CHECK_FILTER_DESC" \
  --argjson checkFilterActive "$CHECK_FILTER_ACTIVE" \
  --argjson paceSeconds "$PACE_SECONDS" \
  --argjson roundsRequested "$ROUNDS" \
  '
  def count_status($s): map(select(.status == $s)) | length;
  {
    schemaVersion: $schemaVersion,
    generatedAt: $generatedAt,
    mode: $mode,
    checkFilter: $checkFilter,
    checkFilterActive: $checkFilterActive,
    paceSeconds: $paceSeconds,
    roundsRequested: $roundsRequested,
    checks: .,
    totals: {
      pass: count_status("PASS"),
      fail: count_status("FAIL"),
      skipped: count_status("SKIPPED"),
      notEligible: count_status("NOT_ELIGIBLE")
    },
    approved: (map(select(.required == true and .status != "PASS")) | length == 0)
  }
  ' "$CHECKS_NDJSON" > "$SUMMARY_JSON"

{
  echo "Business Gate Summary"
  echo "generatedAt: $(jq -r '.generatedAt' "$SUMMARY_JSON")"
  echo "mode: $(jq -r '.mode' "$SUMMARY_JSON")"
  echo "checkFilter: $(jq -r '.checkFilter' "$SUMMARY_JSON")"
  echo "paceSeconds: $(jq -r '.paceSeconds' "$SUMMARY_JSON")"
  echo "roundsRequested: $(jq -r '.roundsRequested' "$SUMMARY_JSON")"
  echo "approved: $(jq -r '.approved' "$SUMMARY_JSON")"
  echo ""
  echo "Totals"
  echo "  PASS: $(jq -r '.totals.pass' "$SUMMARY_JSON")"
  echo "  FAIL: $(jq -r '.totals.fail' "$SUMMARY_JSON")"
  echo "  SKIPPED: $(jq -r '.totals.skipped' "$SUMMARY_JSON")"
  echo "  NOT_ELIGIBLE: $(jq -r '.totals.notEligible' "$SUMMARY_JSON")"
  echo ""
  echo "Checks"
  jq -r '.checks[] | "  [\(.status)] \(.id) \(.name) :: \(.detail)"' "$SUMMARY_JSON"
} > "$SUMMARY_TXT"

log "Resumo JSON: $SUMMARY_JSON"
log "Resumo TXT:  $SUMMARY_TXT"

log "Checks nao-PASS:"
if ! jq -r '.checks[] | select(.status != "PASS") | "  [\(.status)] \(.id) \(.name) :: \(.detail)"' "$SUMMARY_JSON"; then
  log "Falha ao renderizar checks no stdout."
fi

if jq -e '.approved == true' "$SUMMARY_JSON" >/dev/null 2>&1; then
  log "Business gate aprovado."
  exit 0
fi

failed_required_ids="$(jq -r '.checks[] | select(.required == true and .status != "PASS") | .id' "$SUMMARY_JSON" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
only_flaky_required=1
if [[ -n "$failed_required_ids" ]]; then
  for id in $failed_required_ids; do
    case "$id" in
      R11|R12|R17|R18|R19|R21|R32)
        ;;
      *)
        only_flaky_required=0
        break
        ;;
    esac
  done
fi

if [[ "$CHECK_FILTER_ACTIVE" -eq 1 ]]; then
  only_flaky_required=0
fi

if [[ "${BG_RETRY_ATTEMPT:-0}" == "0" && "$only_flaky_required" -eq 1 ]]; then
  log "Falha em checks potencialmente intermitentes (${failed_required_ids:-nenhum}). Reexecutando gate uma vez."
  BG_RETRY_ATTEMPT=1 SOLVER_REBUILD="${SOLVER_REBUILD:-0}" exec "$0" "${ORIGINAL_ARGS[@]}"
fi

log "Business gate reprovado."
exit 1
