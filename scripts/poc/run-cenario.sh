#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/run-cenario.sh <feliz|falha|cancelamento>

Variaveis opcionais:
  API_BASE=http://localhost:8082
  DB_HOST=localhost
  DB_PORT=5435
  DB_NAME=agua_viva_oop_test
  DB_USER=postgres
  DB_PASSWORD=postgres
  DB_CONTAINER=postgres-oop-test
  DB_SERVICE=postgres-oop-test
  COMPOSE_FILE=compose.yml
  ATENDENTE_EMAIL=poc.atendente@aguaviva.local
  ENTREGADOR_EMAIL=poc.entregador@aguaviva.local
  NUM_ENTREGADORES_ATIVOS=1
  TELEFONE=(38) 99876-9901
  METODO_PAGAMENTO=PIX
  QUANTIDADE_GALOES=1
  CLIENTE_NOME=Cliente PoC
  CLIENTE_ENDERECO=Rua PoC, 100
  CLIENTE_LAT=-16.7310
  CLIENTE_LON=-43.8710
  VALE_SALDO=10
  MOTIVO_FALHA=cliente ausente
  MOTIVO_CANCELAMENTO=cliente cancelou
  COBRANCA_CANCELAMENTO_CENTAVOS=2500
  EXTERNAL_EVENT_PREFIX=poc-evento
  WAIT_EXECUCAO_MAX_ATTEMPTS=45
  WAIT_EXECUCAO_SLEEP_SECONDS=1
  START_ROTA_MAX_ATTEMPTS=30
  START_ROTA_SLEEP_SECONDS=1
  WAIT_TIMELINE_MAX_ATTEMPTS=30
  WAIT_TIMELINE_SLEEP_SECONDS=1
  DEBOUNCE_SEGUNDOS=0
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

sql_escape() {
  printf "%s" "$1" | sed "s/'/''/g"
}

psql_query() {
  local sql="$1"
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASSWORD" psql \
      -h "$DB_HOST" \
      -p "$DB_PORT" \
      -U "$DB_USER" \
      -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 \
      -q \
      -Atc "$sql"
    return
  fi

  if command -v docker >/dev/null 2>&1; then
    docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
      psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -Atc "$sql"
    return
  fi

  echo "Nenhuma forma de executar SQL encontrada (psql local ou docker)." >&2
  exit 1
}

api_request() {
  local method="$1"
  local path="$2"
  local payload="${3:-}"
  api_request_capture "$method" "$path" "$payload"

  if [[ "$API_LAST_STATUS" -lt 200 || "$API_LAST_STATUS" -ge 300 ]]; then
    echo "Falha HTTP $API_LAST_STATUS em $method $path" >&2
    echo "$API_LAST_BODY" | jq . >&2 || echo "$API_LAST_BODY" >&2
    exit 1
  fi

  printf '%s' "$API_LAST_BODY"
}

api_request_capture() {
  local method="$1"
  local path="$2"
  local payload="${3:-}"
  local raw

  if [[ "$method" == "GET" ]]; then
    raw="$(curl -sS -w $'\n%{http_code}' "$API_BASE$path")"
  else
    raw="$(curl -sS -w $'\n%{http_code}' \
      -X "$method" \
      -H "Content-Type: application/json" \
      --data "$payload" \
      "$API_BASE$path")"
  fi

  API_LAST_STATUS="${raw##*$'\n'}"
  API_LAST_BODY="${raw%$'\n'*}"
}

print_step() {
  echo
  echo "==> $1"
}

print_json() {
  local label="$1"
  local json="$2"
  echo "--- $label"
  echo "$json" | jq .
}

debounce_if_needed() {
  local reason="$1"
  if [[ "$DEBOUNCE_SEGUNDOS" -le 0 ]]; then
    return 0
  fi
  echo "[run-cenario] pausa ${DEBOUNCE_SEGUNDOS}s: ${reason}"
  sleep "$DEBOUNCE_SEGUNDOS"
}

extract_single_value() {
  awk 'NF > 0 { print; exit }' | tr -d '[:space:]'
}

wait_for_execucao_com_rota() {
  local pedido_id="$1"
  local max_attempts="${2:-20}"
  local wait_seconds="${3:-1}"
  local tentativa response rota rota_primaria status

  for tentativa in $(seq 1 "$max_attempts"); do
    api_request_capture GET "/api/pedidos/${pedido_id}/execucao"
    status="$API_LAST_STATUS"
    response="$API_LAST_BODY"

    if [[ "$status" == "404" ]]; then
      sleep "$wait_seconds"
      continue
    fi

    if [[ "$status" != "200" ]]; then
      echo "Falha HTTP $status em GET /api/pedidos/${pedido_id}/execucao" >&2
      echo "$response" | jq . >&2 || echo "$response" >&2
      return 1
    fi

    rota_primaria="$(echo "$response" | jq -r '.rotaPrimariaId // 0')"
    rota="$(echo "$response" | jq -r '.rotaId // 0')"
    if [[ "$rota_primaria" != "0" && "$rota_primaria" != "null" && -n "$rota_primaria" ]]; then
      printf '%s' "$response"
      return 0
    fi

    # A camada pode estar em transicao; basta ter rota candidata e o start da rota
    # faz retry de 409 ate a promocao ficar consistente.
    if [[ "$rota" != "0" && "$rota" != "null" && -n "$rota" ]]; then
      printf '%s' "$response"
      return 0
    fi
    sleep "$wait_seconds"
  done

  return 1
}

wait_for_timeline_status() {
  local pedido_id="$1"
  local expected_status="$2"
  local max_attempts="${3:-20}"
  local wait_seconds="${4:-1}"
  local tentativa timeline status_atual

  for tentativa in $(seq 1 "$max_attempts"); do
    timeline="$(api_request GET "/api/pedidos/${pedido_id}/timeline")"
    status_atual="$(echo "$timeline" | jq -r '.statusAtual // ""')"
    if [[ "$status_atual" == "$expected_status" ]]; then
      printf '%s' "$timeline"
      return 0
    fi
    sleep "$wait_seconds"
  done

  return 1
}

start_rota_with_retry() {
  local pedido_id="$1"
  local max_attempts="$2"
  local wait_seconds="$3"
  local tentativa execucao_atual rota_candidata payload event_id
  execucao_atual=""

  for tentativa in $(seq 1 "$max_attempts"); do
    if [[ -z "$execucao_atual" ]]; then
      api_request_capture GET "/api/pedidos/${pedido_id}/execucao"
      if [[ "$API_LAST_STATUS" == "404" ]]; then
        sleep "$wait_seconds"
        continue
      fi
      if [[ "$API_LAST_STATUS" != "200" ]]; then
        echo "Falha HTTP $API_LAST_STATUS em GET /api/pedidos/${pedido_id}/execucao" >&2
        echo "$API_LAST_BODY" | jq . >&2 || echo "$API_LAST_BODY" >&2
        return 1
      fi
      execucao_atual="$API_LAST_BODY"
    fi

    rota_candidata="$(echo "$execucao_atual" | jq -r '.rotaPrimariaId // .rotaId // empty')"
    if [[ -z "$rota_candidata" || "$rota_candidata" == "null" ]]; then
      sleep "$wait_seconds"
      execucao_atual=""
      continue
    fi

    event_id="${EXTERNAL_EVENT_PREFIX}-${EXTERNAL_CALL_ID}-rota-${tentativa}"
    payload="$(jq -n \
      --arg externalEventId "$event_id" \
      --argjson rotaId "$rota_candidata" \
      '{ eventType: "ROTA_INICIADA", externalEventId: $externalEventId, rotaId: $rotaId }')"

    api_request_capture POST "/api/eventos" "$payload"
    if [[ "$API_LAST_STATUS" -ge 200 && "$API_LAST_STATUS" -lt 300 ]]; then
      printf '%s' "$API_LAST_BODY"
      return 0
    fi

    if [[ "$API_LAST_STATUS" != "409" ]]; then
      echo "Falha HTTP $API_LAST_STATUS em POST /api/eventos (ROTA_INICIADA)" >&2
      echo "$API_LAST_BODY" | jq . >&2 || echo "$API_LAST_BODY" >&2
      return 1
    fi

    # Conflito transiente enquanto a execucao ainda promove a rota primaria.
    sleep "$wait_seconds"
    execucao_atual=""
  done

  echo "Nao foi possivel iniciar rota apos ${max_attempts} tentativa(s)." >&2
  echo "$execucao_atual" | jq . >&2 || echo "$execucao_atual" >&2
  return 1
}

SCENARIO="${1:-}"
if [[ -z "$SCENARIO" ]]; then
  usage
  exit 1
fi

case "$SCENARIO" in
  feliz|falha|cancelamento) ;;
  *)
    echo "Cenario invalido: $SCENARIO" >&2
    usage
    exit 1
    ;;
esac

require_cmd curl
require_cmd jq

API_BASE="${API_BASE:-http://localhost:8082}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
ATENDENTE_EMAIL="${ATENDENTE_EMAIL:-poc.atendente@aguaviva.local}"
ENTREGADOR_EMAIL="${ENTREGADOR_EMAIL:-poc.entregador@aguaviva.local}"
NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-1}"
TELEFONE="${TELEFONE:-"(38) 99876-9901"}"
METODO_PAGAMENTO="${METODO_PAGAMENTO:-PIX}"
QUANTIDADE_GALOES="${QUANTIDADE_GALOES:-1}"
CLIENTE_NOME="${CLIENTE_NOME:-Cliente PoC}"
CLIENTE_ENDERECO="${CLIENTE_ENDERECO:-Rua PoC, 100}"
CLIENTE_LAT="${CLIENTE_LAT:--16.7310}"
CLIENTE_LON="${CLIENTE_LON:--43.8710}"
VALE_SALDO="${VALE_SALDO:-10}"
MOTIVO_FALHA="${MOTIVO_FALHA:-cliente ausente}"
MOTIVO_CANCELAMENTO="${MOTIVO_CANCELAMENTO:-cliente cancelou}"
COBRANCA_CANCELAMENTO_CENTAVOS="${COBRANCA_CANCELAMENTO_CENTAVOS:-2500}"
EXTERNAL_EVENT_PREFIX="${EXTERNAL_EVENT_PREFIX:-poc-evento}"
WAIT_EXECUCAO_MAX_ATTEMPTS="${WAIT_EXECUCAO_MAX_ATTEMPTS:-45}"
WAIT_EXECUCAO_SLEEP_SECONDS="${WAIT_EXECUCAO_SLEEP_SECONDS:-1}"
START_ROTA_MAX_ATTEMPTS="${START_ROTA_MAX_ATTEMPTS:-30}"
START_ROTA_SLEEP_SECONDS="${START_ROTA_SLEEP_SECONDS:-1}"
WAIT_TIMELINE_MAX_ATTEMPTS="${WAIT_TIMELINE_MAX_ATTEMPTS:-30}"
WAIT_TIMELINE_SLEEP_SECONDS="${WAIT_TIMELINE_SLEEP_SECONDS:-1}"
DEBOUNCE_SEGUNDOS="${DEBOUNCE_SEGUNDOS:-0}"

# Evita colisao de idempotencia entre execucoes proximas no mesmo segundo.
EXTERNAL_CALL_ID="poc-${SCENARIO}-$(date +%s)-${RANDOM}-$$"

if ! [[ "$NUM_ENTREGADORES_ATIVOS" =~ ^[0-9]+$ ]] || [[ "$NUM_ENTREGADORES_ATIVOS" -le 0 ]]; then
  echo "NUM_ENTREGADORES_ATIVOS invalido: $NUM_ENTREGADORES_ATIVOS (use inteiro > 0)" >&2
  exit 1
fi

for n in \
  "$WAIT_EXECUCAO_MAX_ATTEMPTS" \
  "$WAIT_EXECUCAO_SLEEP_SECONDS" \
  "$START_ROTA_MAX_ATTEMPTS" \
  "$START_ROTA_SLEEP_SECONDS" \
  "$WAIT_TIMELINE_MAX_ATTEMPTS" \
  "$WAIT_TIMELINE_SLEEP_SECONDS"; do
  if ! [[ "$n" =~ ^[0-9]+$ ]] || [[ "$n" -le 0 ]]; then
    echo "Parametro de espera invalido: $n (use inteiro > 0)" >&2
    exit 1
  fi
done

if ! [[ "$DEBOUNCE_SEGUNDOS" =~ ^[0-9]+$ ]]; then
  echo "DEBOUNCE_SEGUNDOS invalido: $DEBOUNCE_SEGUNDOS (use inteiro >= 0)" >&2
  exit 1
fi

print_step "Health check"
health_response="$(api_request GET "/health")"
print_json "GET /health" "$health_response"

print_step "Seed tecnico minimo (atendente, entregador e cliente com coordenada)"
atendente_email_esc="$(sql_escape "$ATENDENTE_EMAIL")"
entregador_email_esc="$(sql_escape "$ENTREGADOR_EMAIL")"
telefone_esc="$(sql_escape "$TELEFONE")"
cliente_nome_esc="$(sql_escape "$CLIENTE_NOME")"
cliente_endereco_esc="$(sql_escape "$CLIENTE_ENDERECO")"

atendente_id="$(psql_query "INSERT INTO users (nome, email, senha_hash, papel, ativo)
VALUES ('Atendente PoC', '${atendente_email_esc}', 'poc_hash_nao_usado', 'atendente', true)
ON CONFLICT (email) DO UPDATE SET ativo = true
RETURNING id;" | extract_single_value)"

entregador_id="$(psql_query "INSERT INTO users (nome, email, senha_hash, papel, ativo)
VALUES ('Entregador PoC', '${entregador_email_esc}', 'poc_hash_nao_usado', 'entregador', true)
ON CONFLICT (email) DO UPDATE SET ativo = true
RETURNING id;" | extract_single_value)"

if [[ "$NUM_ENTREGADORES_ATIVOS" -gt 1 ]]; then
  psql_query "INSERT INTO users (nome, email, senha_hash, papel, ativo)
SELECT
  'Entregador PoC ' || n,
  'poc.entregador.' || n || '@aguaviva.local',
  'poc_hash_nao_usado',
  'entregador',
  true
FROM generate_series(1, GREATEST(${NUM_ENTREGADORES_ATIVOS} - 1, 0)) AS n
ON CONFLICT (email) DO UPDATE SET ativo = true;" >/dev/null
fi

cliente_id="$(psql_query "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude)
VALUES ('${cliente_nome_esc}', '${telefone_esc}', 'PF', '${cliente_endereco_esc}', ${CLIENTE_LAT}, ${CLIENTE_LON})
ON CONFLICT (telefone) DO UPDATE
SET nome = EXCLUDED.nome,
    endereco = EXCLUDED.endereco,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    atualizado_em = CURRENT_TIMESTAMP
RETURNING id;" | extract_single_value)"

if [[ "$METODO_PAGAMENTO" == "VALE" ]]; then
  psql_query "INSERT INTO saldo_vales (cliente_id, quantidade)
VALUES (${cliente_id}, ${VALE_SALDO})
ON CONFLICT (cliente_id) DO UPDATE
SET quantidade = EXCLUDED.quantidade,
    atualizado_em = CURRENT_TIMESTAMP;" >/dev/null
fi

echo "atendente_id=${atendente_id}"
echo "entregador_id=${entregador_id}"
echo "cliente_id=${cliente_id}"
echo "entregadores_ativos_seed=${NUM_ENTREGADORES_ATIVOS}"

print_step "Criar pedido via atendimento"
atendimento_payload="$(jq -n \
  --arg externalCallId "$EXTERNAL_CALL_ID" \
  --arg telefone "$TELEFONE" \
  --argjson quantidadeGaloes "$QUANTIDADE_GALOES" \
  --argjson atendenteId "$atendente_id" \
  --arg metodoPagamento "$METODO_PAGAMENTO" \
  '{
    externalCallId: $externalCallId,
    telefone: $telefone,
    quantidadeGaloes: $quantidadeGaloes,
    atendenteId: $atendenteId,
    metodoPagamento: $metodoPagamento
  }')"

atendimento_response="$(api_request POST "/api/atendimento/pedidos" "$atendimento_payload")"
print_json "POST /api/atendimento/pedidos" "$atendimento_response"
debounce_if_needed "apos atendimento"

pedido_id="$(echo "$atendimento_response" | jq -r '.pedidoId // empty')"
if [[ -z "$pedido_id" ]]; then
  echo "Nao foi possivel extrair pedidoId da resposta de atendimento" >&2
  exit 1
fi

print_step "Timeline inicial do pedido"
timeline_inicial="$(api_request GET "/api/pedidos/${pedido_id}/timeline")"
print_json "GET /api/pedidos/{pedidoId}/timeline (inicial)" "$timeline_inicial"

print_step "Aguardar roteirizacao automatica por evento (PEDIDO_CRIADO)"
if ! execucao_inicial="$(wait_for_execucao_com_rota "$pedido_id" "$WAIT_EXECUCAO_MAX_ATTEMPTS" "$WAIT_EXECUCAO_SLEEP_SECONDS")"; then
  echo "Execucao nao retornou rota para pedido ${pedido_id} no tempo esperado." >&2
  exit 1
fi
print_json "GET /api/pedidos/{pedidoId}/execucao (inicial)" "$execucao_inicial"
debounce_if_needed "antes de iniciar rota"

rota_id="$(echo "$execucao_inicial" | jq -r '.rotaId // .rotaPrimariaId // empty')"
entrega_id="$(echo "$execucao_inicial" | jq -r '.entregaId // empty')"

if [[ -z "$rota_id" || "$rota_id" == "null" ]]; then
  echo "Execucao nao retornou rota para pedido ${pedido_id}. Verifique solver/worker orientado a eventos." >&2
  exit 1
fi

echo "rota_id=${rota_id}"
echo "entrega_id_inicial=${entrega_id:-indefinido}"

print_step "Iniciar rota"
rota_iniciada_response="$(start_rota_with_retry "$pedido_id" "$START_ROTA_MAX_ATTEMPTS" "$START_ROTA_SLEEP_SECONDS")"
print_json "POST /api/eventos (ROTA_INICIADA)" "$rota_iniciada_response"

execucao_pos_rota="$(api_request GET "/api/pedidos/${pedido_id}/execucao")"
print_json "GET /api/pedidos/{pedidoId}/execucao (apos rota iniciada)" "$execucao_pos_rota"
debounce_if_needed "apos rota iniciada"

entrega_id="$(echo "$execucao_pos_rota" | jq -r '.entregaAtivaId // .entregaId // empty')"
if [[ -z "$entrega_id" || "$entrega_id" == "null" ]]; then
  echo "Execucao apos rota iniciada nao retornou entrega ativa para pedido ${pedido_id}." >&2
  exit 1
fi
echo "entrega_id=${entrega_id}"

print_step "Evento terminal do cenario: ${SCENARIO}"
expected_timeline_status=""
case "$SCENARIO" in
  feliz)
    expected_timeline_status="ENTREGUE"
    evento_terminal_payload="$(jq -n \
      --arg externalEventId "${EXTERNAL_EVENT_PREFIX}-${EXTERNAL_CALL_ID}-terminal" \
      --argjson entregaId "$entrega_id" \
      '{ eventType: "PEDIDO_ENTREGUE", externalEventId: $externalEventId, entregaId: $entregaId }')"
    ;;
  falha)
    expected_timeline_status="CANCELADO"
    evento_terminal_payload="$(jq -n \
      --arg externalEventId "${EXTERNAL_EVENT_PREFIX}-${EXTERNAL_CALL_ID}-terminal" \
      --argjson entregaId "$entrega_id" \
      --arg motivo "$MOTIVO_FALHA" \
      '{ eventType: "PEDIDO_FALHOU", externalEventId: $externalEventId, entregaId: $entregaId, motivo: $motivo }')"
    ;;
  cancelamento)
    expected_timeline_status="CANCELADO"
    evento_terminal_payload="$(jq -n \
      --arg externalEventId "${EXTERNAL_EVENT_PREFIX}-${EXTERNAL_CALL_ID}-terminal" \
      --argjson entregaId "$entrega_id" \
      --arg motivo "$MOTIVO_CANCELAMENTO" \
      --argjson cobrancaCancelamentoCentavos "$COBRANCA_CANCELAMENTO_CENTAVOS" \
      '{
        eventType: "PEDIDO_CANCELADO",
        externalEventId: $externalEventId,
        entregaId: $entregaId,
        motivo: $motivo,
        cobrancaCancelamentoCentavos: $cobrancaCancelamentoCentavos
      }')"
    ;;
esac

evento_terminal_response="$(api_request POST "/api/eventos" "$evento_terminal_payload")"
print_json "POST /api/eventos (terminal)" "$evento_terminal_response"
debounce_if_needed "apos evento terminal"

print_step "Timeline final"
if ! timeline_final="$(wait_for_timeline_status "$pedido_id" "$expected_timeline_status" "$WAIT_TIMELINE_MAX_ATTEMPTS" "$WAIT_TIMELINE_SLEEP_SECONDS")"; then
  timeline_final="$(api_request GET "/api/pedidos/${pedido_id}/timeline")"
  echo "Timeline nao atingiu status esperado=${expected_timeline_status} para pedido_id=${pedido_id} no tempo limite." >&2
  print_json "GET /api/pedidos/{pedidoId}/timeline (final)" "$timeline_final"
  exit 1
fi
print_json "GET /api/pedidos/{pedidoId}/timeline (final)" "$timeline_final"

echo
echo "Cenario concluido com sucesso."
echo "pedido_id=${pedido_id}"
echo "rota_id=${rota_id}"
echo "entrega_id=${entrega_id}"
