#!/usr/bin/env bash
set -euo pipefail

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
  DEBOUNCE_SEGUNDOS=0
  LIMITE_EVENTOS=100
  EXTERNAL_EVENT_PREFIX=poc-evento
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
    docker exec -i "$DB_CONTAINER" \
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
  local raw status body

  if [[ "$method" == "GET" ]]; then
    raw="$(curl -sS -w $'\n%{http_code}' "$API_BASE$path")"
  else
    raw="$(curl -sS -w $'\n%{http_code}' \
      -X "$method" \
      -H "Content-Type: application/json" \
      --data "$payload" \
      "$API_BASE$path")"
  fi

  status="${raw##*$'\n'}"
  body="${raw%$'\n'*}"

  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "Falha HTTP $status em $method $path" >&2
    echo "$body" | jq . >&2 || echo "$body" >&2
    exit 1
  fi

  printf '%s' "$body"
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

extract_single_value() {
  awk 'NF > 0 { print; exit }' | tr -d '[:space:]'
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
DEBOUNCE_SEGUNDOS="${DEBOUNCE_SEGUNDOS:-0}"
LIMITE_EVENTOS="${LIMITE_EVENTOS:-100}"
EXTERNAL_EVENT_PREFIX="${EXTERNAL_EVENT_PREFIX:-poc-evento}"

# Evita colisao de idempotencia entre execucoes proximas no mesmo segundo.
EXTERNAL_CALL_ID="poc-${SCENARIO}-$(date +%s)-${RANDOM}-$$"

if ! [[ "$NUM_ENTREGADORES_ATIVOS" =~ ^[0-9]+$ ]] || [[ "$NUM_ENTREGADORES_ATIVOS" -le 0 ]]; then
  echo "NUM_ENTREGADORES_ATIVOS invalido: $NUM_ENTREGADORES_ATIVOS (use inteiro > 0)" >&2
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

pedido_id="$(echo "$atendimento_response" | jq -r '.pedidoId // empty')"
if [[ -z "$pedido_id" ]]; then
  echo "Nao foi possivel extrair pedidoId da resposta de atendimento" >&2
  exit 1
fi

print_step "Timeline inicial do pedido"
timeline_inicial="$(api_request GET "/api/pedidos/${pedido_id}/timeline")"
print_json "GET /api/pedidos/{pedidoId}/timeline (inicial)" "$timeline_inicial"

print_step "Replanejamento inicial para criar rota/entrega"
replanejamento_payload="$(jq -n \
  --argjson debounceSegundos "$DEBOUNCE_SEGUNDOS" \
  --argjson limiteEventos "$LIMITE_EVENTOS" \
  '{ debounceSegundos: $debounceSegundos, limiteEventos: $limiteEventos }')"

replanejamento_inicial="$(api_request POST "/api/replanejamento/run" "$replanejamento_payload")"
print_json "POST /api/replanejamento/run (inicial)" "$replanejamento_inicial"

execucao_inicial="$(api_request GET "/api/pedidos/${pedido_id}/execucao")"
print_json "GET /api/pedidos/{pedidoId}/execucao (inicial)" "$execucao_inicial"

rota_id="$(echo "$execucao_inicial" | jq -r '.rotaId // .rotaPrimariaId // empty')"
entrega_id="$(echo "$execucao_inicial" | jq -r '.entregaId // empty')"

if [[ -z "$rota_id" || "$rota_id" == "null" ]]; then
  echo "Execucao nao retornou rota para pedido ${pedido_id}. Verifique solver/replanejamento." >&2
  exit 1
fi

echo "rota_id=${rota_id}"
echo "entrega_id_inicial=${entrega_id:-indefinido}"

print_step "Iniciar rota"
rota_iniciada_payload="$(jq -n \
  --arg externalEventId "${EXTERNAL_EVENT_PREFIX}-${EXTERNAL_CALL_ID}-rota" \
  --argjson rotaId "$rota_id" \
  '{ eventType: "ROTA_INICIADA", externalEventId: $externalEventId, rotaId: $rotaId }')"
rota_iniciada_response="$(api_request POST "/api/eventos" "$rota_iniciada_payload")"
print_json "POST /api/eventos (ROTA_INICIADA)" "$rota_iniciada_response"

execucao_pos_rota="$(api_request GET "/api/pedidos/${pedido_id}/execucao")"
print_json "GET /api/pedidos/{pedidoId}/execucao (apos rota iniciada)" "$execucao_pos_rota"

entrega_id="$(echo "$execucao_pos_rota" | jq -r '.entregaAtivaId // .entregaId // empty')"
if [[ -z "$entrega_id" || "$entrega_id" == "null" ]]; then
  echo "Execucao apos rota iniciada nao retornou entrega ativa para pedido ${pedido_id}." >&2
  exit 1
fi
echo "entrega_id=${entrega_id}"

print_step "Evento terminal do cenario: ${SCENARIO}"
case "$SCENARIO" in
  feliz)
    evento_terminal_payload="$(jq -n \
      --arg externalEventId "${EXTERNAL_EVENT_PREFIX}-${EXTERNAL_CALL_ID}-terminal" \
      --argjson entregaId "$entrega_id" \
      '{ eventType: "PEDIDO_ENTREGUE", externalEventId: $externalEventId, entregaId: $entregaId }')"
    ;;
  falha)
    evento_terminal_payload="$(jq -n \
      --arg externalEventId "${EXTERNAL_EVENT_PREFIX}-${EXTERNAL_CALL_ID}-terminal" \
      --argjson entregaId "$entrega_id" \
      --arg motivo "$MOTIVO_FALHA" \
      '{ eventType: "PEDIDO_FALHOU", externalEventId: $externalEventId, entregaId: $entregaId, motivo: $motivo }')"
    ;;
  cancelamento)
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

print_step "Replanejamento apos evento"
replanejamento_final="$(api_request POST "/api/replanejamento/run" "$replanejamento_payload")"
print_json "POST /api/replanejamento/run (final)" "$replanejamento_final"

print_step "Timeline final"
timeline_final="$(api_request GET "/api/pedidos/${pedido_id}/timeline")"
print_json "GET /api/pedidos/{pedidoId}/timeline (final)" "$timeline_final"

echo
echo "Cenario concluido com sucesso."
echo "pedido_id=${pedido_id}"
echo "rota_id=${rota_id}"
echo "entrega_id=${entrega_id}"
