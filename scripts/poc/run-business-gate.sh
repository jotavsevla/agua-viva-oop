#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/run-business-gate.sh [--mode strict|observe] [--rounds N] [--api-base URL]
                                   [--db-container NAME] [--db-service NAME] [--db-user USER] [--db-name DB]
                                   [--keep-running]

Gate oficial de plano de negocio da PoC operacional.

Defaults:
  --mode strict
  --rounds 1
  --api-base http://localhost:8082
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
    R01|R02|R03|R04|R05|R06|R07|R08|R09|R10|R11|R12|R13|R14|R15|R16|R17|R18|R19|R23|R24|R25|R26|R27|R28|R29|R30|R31)
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
        SOLVER_REBUILD="$SOLVER_REBUILD" ARTIFACT_DIR="$E2E_DIR" "${cmd[@]}"
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

  # R14 e R15
  check_dir="$(new_check_dir R14-R15)"
  if reset_state_for_check "$check_dir"; then
    index14="$(psql_query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND indexname='uk_rotas_planejada_entregador_data';" | extract_single_value)"
    index15="$(psql_query "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND indexname='uk_rotas_andamento_entregador_data';" | extract_single_value)"

    prepare_base_users

    set +e
    psql_exec "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 101, 'PLANEJADA');
INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 102, 'PLANEJADA');"
    dup14_exit="$?"
    set -e

    set +e
    psql_exec "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 201, 'EM_ANDAMENTO');
INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (${ENTREGADOR_BASE_ID}, CURRENT_DATE, 202, 'EM_ANDAMENTO');"
    dup15_exit="$?"
    set -e

    {
      echo "index14=$index14"
      echo "index15=$index15"
      echo "dup14_exit=$dup14_exit"
      echo "dup15_exit=$dup15_exit"
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

  # R17
  check_dir="$(new_check_dir R17)"
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
        REQUIRE_CONFIRMADO_EM_ROTA=1 REQUIRE_PENDENTE_CONFIRMADO=1 NUM_ENTREGADORES_ATIVOS=2 \
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

  # R19
  check_dir="$(new_check_dir R19)"
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

  # R20
  check_dir="$(new_check_dir R20)"
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

  # R21
  check_dir="$(new_check_dir R21)"
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

  # R22
  check_dir="$(new_check_dir R22)"
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

  # R23
  check_dir="$(new_check_dir R23)"
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

  # R24
  check_dir="$(new_check_dir R24)"
  set +e
  (
    cd "$ROOT_DIR"
    mvn -Dtest=ContractsV1Test,ApiContractDriftTest test
  ) > "$check_dir/mvn-contract-tests.log" 2>&1
  r24_exit="$?"
  set -e
  if [[ "$r24_exit" -eq 0 ]]; then
    record_check "R24" "Testes de contrato falham se houver drift" "PASS" "$check_dir/mvn-contract-tests.log" "Suite de contrato executou sem drift detectado."
  else
    record_check "R24" "Testes de contrato falham se houver drift" "FAIL" "$check_dir/mvn-contract-tests.log" "Suite de contrato falhou (possivel drift)."
  fi

  # R25
  check_dir="$(new_check_dir R25)"
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

  # R26
  check_dir="$(new_check_dir R26)"
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

  # R27
  check_dir="$(new_check_dir R27)"
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
    r27_rota_id="$(psql_query "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version)
VALUES (${r27_entregador_id}, CURRENT_DATE, ${r27_numero_rota}, 'PLANEJADA', ${r27_plan_version})
RETURNING id;" | extract_single_value)"
    r27_entrega_id="$(psql_query "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status, plan_version)
VALUES (${r27_pedido_id}, ${r27_rota_id}, 1, 'PENDENTE', ${r27_plan_version})
RETURNING id;" | extract_single_value)"
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

  # R30
  check_dir="$(new_check_dir R30)"
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

  # R31
  check_dir="$(new_check_dir R31)"
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

SUMMARY_JSON="$ARTIFACT_DIR/business-summary.json"
SUMMARY_TXT="$ARTIFACT_DIR/business-summary.txt"

jq -s \
  --arg schemaVersion "1.0.0" \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg mode "$MODE" \
  --argjson roundsRequested "$ROUNDS" \
  '
  def count_status($s): map(select(.status == $s)) | length;
  {
    schemaVersion: $schemaVersion,
    generatedAt: $generatedAt,
    mode: $mode,
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
      R11|R12|R17|R18|R19|R21)
        ;;
      *)
        only_flaky_required=0
        break
        ;;
    esac
  done
fi

if [[ "${BG_RETRY_ATTEMPT:-0}" == "0" && "$only_flaky_required" -eq 1 ]]; then
  log "Falha em checks potencialmente intermitentes (${failed_required_ids:-nenhum}). Reexecutando gate uma vez."
  BG_RETRY_ATTEMPT=1 SOLVER_REBUILD="${SOLVER_REBUILD:-0}" exec "$0" "${ORIGINAL_ARGS[@]}"
fi

log "Business gate reprovado."
exit 1
