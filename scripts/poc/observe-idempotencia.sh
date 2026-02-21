#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_BASE="${API_BASE:-http://localhost:8082}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

require_cmd curl
require_cmd jq
require_cmd docker

api_post() {
  local path="$1"
  local payload="$2"
  curl -sS -X POST "$API_BASE$path" \
    -H 'Content-Type: application/json' \
    --data "$payload"
}

api_post_status() {
  local path="$1"
  local payload="$2"
  curl -sS -w $'\n%{http_code}' -X POST "$API_BASE$path" \
    -H 'Content-Type: application/json' \
    --data "$payload"
}

psql_query() {
  local sql="$1"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$DB_USER" -d "$DB_NAME" -Atc "$sql"
}

psql_exec() {
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

wait_for_rota_do_pedido() {
  local pedido_id="$1"
  local attempts="${2:-20}"
  local pause="${3:-1}"
  local rota

  for _ in $(seq 1 "$attempts"); do
    rota="$(curl -sS "$API_BASE/api/pedidos/$pedido_id/execucao" | jq -r '.rotaId // .rotaPrimariaId // 0')"
    if [[ "$rota" != "0" && "$rota" != "null" && -n "$rota" ]]; then
      echo "$rota"
      return 0
    fi
    sleep "$pause"
  done
  return 1
}

if ! curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "API offline em $API_BASE" >&2
  exit 1
fi

echo "[observe-idempotencia] Preparando base de teste"
"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/reset-test-state.sh" >/dev/null

ATENDENTE_ID="$(psql_query "
INSERT INTO users (nome,email,senha_hash,papel,ativo)
VALUES ('Atendente Idempotencia','idempot.atendente@aguaviva.local','hash_nao_usado','atendente',true)
ON CONFLICT (email) DO UPDATE SET ativo=true
RETURNING id;
" | tail -n1 | tr -d '[:space:]')"

ENTREGADOR_ID="$(psql_query "
INSERT INTO users (nome,email,senha_hash,papel,ativo)
VALUES ('Entregador Idempotencia','idempot.entregador@aguaviva.local','hash_nao_usado','entregador',true)
ON CONFLICT (email) DO UPDATE SET ativo=true
RETURNING id;
" | tail -n1 | tr -d '[:space:]')"

psql_exec <<SQL >/dev/null
INSERT INTO clientes (nome,telefone,tipo,endereco,latitude,longitude)
VALUES ('Cliente Idempotencia','38998769011','PF','Rua Idempotencia, 1',-16.7310,-43.8710)
ON CONFLICT (telefone) DO UPDATE SET atualizado_em = CURRENT_TIMESTAMP;
SQL

CALL_ID="manual-eye-$(date +%s)-$RANDOM"
ATENDIMENTO_PAYLOAD="$(jq -n \
  --arg externalCallId "$CALL_ID" \
  --arg telefone "(38) 99876-9011" \
  --argjson quantidadeGaloes 1 \
  --argjson atendenteId "$ATENDENTE_ID" \
  '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')"

ATENDIMENTO_RESP="$(api_post "/api/atendimento/pedidos" "$ATENDIMENTO_PAYLOAD")"
PEDIDO_ID="$(echo "$ATENDIMENTO_RESP" | jq -r '.pedidoId')"

echo "[observe-idempotencia] Pedido criado: $PEDIDO_ID"
if ! ROTA_ID="$(wait_for_rota_do_pedido "$PEDIDO_ID" 25 1)"; then
  echo "Nao foi possivel resolver rota do pedido $PEDIDO_ID" >&2
  exit 1
fi

api_post "/api/eventos" "$(jq -n --argjson rotaId "$ROTA_ID" '{eventType:"ROTA_INICIADA",rotaId:$rotaId}')" >/dev/null
ENTREGA_ID="$(curl -sS "$API_BASE/api/pedidos/$PEDIDO_ID/execucao" | jq -r '.entregaAtivaId // .entregaId // 0')"
if [[ "$ENTREGA_ID" == "0" ]]; then
  echo "Nao foi possivel resolver entrega ativa do pedido $PEDIDO_ID" >&2
  exit 1
fi

KEY="manual-eye-$(date +%s)-$RANDOM"
PAYLOAD_OK="$(jq -n --arg key "$KEY" --argjson entregaId "$ENTREGA_ID" '{externalEventId:$key,eventType:"PEDIDO_CANCELADO",entregaId:$entregaId,motivo:"observacao manual",cobrancaCancelamentoCentavos:2500}')"
PAYLOAD_DIVERGENTE="$(jq -n --arg key "$KEY" --argjson entregaId "$ENTREGA_ID" '{externalEventId:$key,eventType:"PEDIDO_FALHOU",entregaId:$entregaId,motivo:"payload diferente"}')"

echo
echo "[observe-idempotencia] 1) primeira chamada"
api_post "/api/eventos" "$PAYLOAD_OK" | jq .

echo
echo "[observe-idempotencia] 2) replay identico"
api_post "/api/eventos" "$PAYLOAD_OK" | jq .

echo
echo "[observe-idempotencia] 3) replay divergente (espera 409)"
RAW_DIVERGENTE="$(api_post_status "/api/eventos" "$PAYLOAD_DIVERGENTE")"
STATUS_DIVERGENTE="${RAW_DIVERGENTE##*$'\n'}"
BODY_DIVERGENTE="${RAW_DIVERGENTE%$'\n'*}"
echo "$STATUS_DIVERGENTE"
echo "$BODY_DIVERGENTE" | jq .

echo
echo "[observe-idempotencia] Registro na tabela de idempotencia"
psql_query "
SELECT external_event_id,event_type,scope_type,scope_id,status_code
FROM eventos_operacionais_idempotencia
WHERE external_event_id = '$KEY';
"
