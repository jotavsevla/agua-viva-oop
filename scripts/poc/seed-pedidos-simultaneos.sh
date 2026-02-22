#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

API_BASE="${API_BASE:-http://localhost:8082}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
DB_FORCE_CONTAINER="${DB_FORCE_CONTAINER:-0}"

RESET_STATE="${RESET_STATE:-1}"
SEED_MONTES_CLAROS="${SEED_MONTES_CLAROS:-1}"
NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-2}"
PEDIDOS_TOTAIS="${PEDIDOS_TOTAIS:-20}"
QUANTIDADE_GALOES="${QUANTIDADE_GALOES:-1}"
METODO_PAGAMENTO="${METODO_PAGAMENTO:-PIX}"
WAIT_ATTEMPTS="${WAIT_ATTEMPTS:-40}"
WAIT_SECONDS="${WAIT_SECONDS:-1}"
EXTERNAL_CALL_PREFIX="${EXTERNAL_CALL_PREFIX:-seed-sim}"
SUMMARY_FILE="${SUMMARY_FILE:-$ROOT_DIR/artifacts/poc/seed-pedidos-simultaneos-summary.json}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/seed-pedidos-simultaneos.sh

Variaveis opcionais:
  API_BASE=http://localhost:8082
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres
  DB_FORCE_CONTAINER=0
  RESET_STATE=1
  SEED_MONTES_CLAROS=1
  NUM_ENTREGADORES_ATIVOS=2
  PEDIDOS_TOTAIS=20
  QUANTIDADE_GALOES=1
  METODO_PAGAMENTO=PIX
  WAIT_ATTEMPTS=40
  WAIT_SECONDS=1
  EXTERNAL_CALL_PREFIX=seed-sim
  SUMMARY_FILE=artifacts/poc/seed-pedidos-simultaneos-summary.json

Comportamento:
  1) Opcionalmente reseta estado e reaplica seed georreferenciado.
  2) Cria N pedidos via API usando clientes com coordenadas validas.
  3) Gera resumo com total, status e pendentes sem entrega.
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

is_positive_int() {
  [[ "$1" =~ ^[0-9]+$ ]] && [[ "$1" -gt 0 ]]
}

extract_single_value() {
  awk 'NF > 0 { print; exit }' | tr -d '[:space:]'
}

psql_query() {
  local sql="$1"

  if [[ "$DB_FORCE_CONTAINER" != "1" ]] && command -v psql >/dev/null 2>&1; then
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

  require_cmd docker
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -Atc "$sql"
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

wait_dispatch_quiet() {
  local attempt due
  due="1"

  for attempt in $(seq 1 "$WAIT_ATTEMPTS"); do
    due="$(psql_query "SELECT COUNT(*) FROM dispatch_events WHERE status::text='PENDENTE' AND available_em <= CURRENT_TIMESTAMP;" | extract_single_value)"
    if [[ "$due" == "0" ]]; then
      return 0
    fi
    if [[ "$attempt" -lt "$WAIT_ATTEMPTS" ]]; then
      sleep "$WAIT_SECONDS"
    fi
  done

  echo "Aviso: dispatch_events ainda pendentes apos timeout (due=${due})." >&2
  return 1
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

for n in "$NUM_ENTREGADORES_ATIVOS" "$PEDIDOS_TOTAIS" "$QUANTIDADE_GALOES" "$WAIT_ATTEMPTS" "$WAIT_SECONDS"; do
  if ! is_positive_int "$n"; then
    echo "Parametro invalido (esperado inteiro > 0): $n" >&2
    exit 1
  fi
done

if [[ "$RESET_STATE" != "0" && "$RESET_STATE" != "1" ]]; then
  echo "RESET_STATE invalido: $RESET_STATE (use 0 ou 1)" >&2
  exit 1
fi

if [[ "$SEED_MONTES_CLAROS" != "0" && "$SEED_MONTES_CLAROS" != "1" ]]; then
  echo "SEED_MONTES_CLAROS invalido: $SEED_MONTES_CLAROS (use 0 ou 1)" >&2
  exit 1
fi

require_cmd curl
require_cmd jq

if [[ "$RESET_STATE" == "1" ]]; then
  DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
    DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_PASSWORD="$DB_PASSWORD" DB_FORCE_CONTAINER="$DB_FORCE_CONTAINER" \
    NUM_ENTREGADORES_ATIVOS="$NUM_ENTREGADORES_ATIVOS" SEED_MONTES_CLAROS="$SEED_MONTES_CLAROS" \
    "$ROOT_DIR/scripts/poc/reset-test-state.sh" >/dev/null
fi

if ! curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "API indisponivel em $API_BASE (suba com scripts/poc/start-test-env.sh)." >&2
  exit 1
fi

atendente_id="$(psql_query "SELECT id FROM users WHERE papel='atendente' AND ativo=true ORDER BY id LIMIT 1;" | extract_single_value)"
if [[ -z "$atendente_id" ]]; then
  echo "Nenhum atendente ativo encontrado." >&2
  exit 1
fi

phones=()
while IFS= read -r line; do
  if [[ -n "$line" ]]; then
    phones+=("$line")
  fi
done < <(psql_query "SELECT telefone
FROM clientes
WHERE latitude IS NOT NULL
  AND longitude IS NOT NULL
  AND trim(COALESCE(endereco, '')) <> ''
  AND lower(trim(endereco)) <> 'endereco pendente'
ORDER BY id;")

if [[ "${#phones[@]}" -eq 0 ]]; then
  echo "Nenhum cliente com coordenada/endereco valido para gerar pedidos." >&2
  exit 1
fi

run_id="$(date +%Y%m%d-%H%M%S)"
ok=0
fail=0
created_ids='[]'

echo "[seed-pedidos-simultaneos] Criando ${PEDIDOS_TOTAIS} pedidos (clientes base com geo: ${#phones[@]})"
for i in $(seq 1 "$PEDIDOS_TOTAIS"); do
  idx_phone=$(( (i - 1) % ${#phones[@]} ))
  phone="${phones[$idx_phone]}"
  external_id="${EXTERNAL_CALL_PREFIX}-${run_id}-${i}"

  payload="$(jq -cn \
    --arg externalCallId "$external_id" \
    --arg telefone "$phone" \
    --argjson quantidadeGaloes "$QUANTIDADE_GALOES" \
    --argjson atendenteId "$atendente_id" \
    --arg metodoPagamento "$METODO_PAGAMENTO" \
    '{externalCallId:$externalCallId, telefone:$telefone, quantidadeGaloes:$quantidadeGaloes, atendenteId:$atendenteId, metodoPagamento:$metodoPagamento}')"

  api_post_capture "/api/atendimento/pedidos" "$payload"
  if [[ "$API_LAST_STATUS" == "200" ]]; then
    pedido_id="$(printf '%s' "$API_LAST_BODY" | jq -r '.pedidoId // 0')"
    created_ids="$(jq -cn --argjson arr "$created_ids" --argjson id "$pedido_id" '$arr + [$id]')"
    ok=$((ok + 1))
  else
    echo "[seed-pedidos-simultaneos] Falha no pedido ${i}: HTTP ${API_LAST_STATUS}" >&2
    fail=$((fail + 1))
  fi
done

wait_dispatch_quiet || true

total_pedidos="$(psql_query "SELECT COUNT(*) FROM pedidos;" | extract_single_value)"
pendentes="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='PENDENTE';" | extract_single_value)"
confirmados="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='CONFIRMADO';" | extract_single_value)"
em_rota="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='EM_ROTA';" | extract_single_value)"
entregues="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='ENTREGUE';" | extract_single_value)"
cancelados="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='CANCELADO';" | extract_single_value)"
pendentes_sem_entrega="$(psql_query "SELECT COUNT(*)
FROM pedidos p
WHERE p.status::text='PENDENTE'
  AND NOT EXISTS (
    SELECT 1 FROM entregas e
    WHERE e.pedido_id = p.id
      AND e.status::text IN ('PENDENTE','EM_EXECUCAO')
  );" | extract_single_value)"

if [[ "$SUMMARY_FILE" != /* ]]; then
  SUMMARY_FILE="$ROOT_DIR/$SUMMARY_FILE"
fi
mkdir -p "$(dirname "$SUMMARY_FILE")"

jq -n \
  --arg runId "$run_id" \
  --arg apiBase "$API_BASE" \
  --argjson pedidosSolicitados "$PEDIDOS_TOTAIS" \
  --argjson pedidosCriados "$ok" \
  --argjson pedidosFalha "$fail" \
  --argjson pedidosIds "$created_ids" \
  --argjson totalPedidos "$total_pedidos" \
  --argjson pendentes "$pendentes" \
  --argjson confirmados "$confirmados" \
  --argjson emRota "$em_rota" \
  --argjson entregues "$entregues" \
  --argjson cancelados "$cancelados" \
  --argjson pendentesSemEntrega "$pendentes_sem_entrega" \
  '{
    runId: $runId,
    apiBase: $apiBase,
    solicitacao: {
      pedidosSolicitados: $pedidosSolicitados,
      pedidosCriados: $pedidosCriados,
      pedidosFalha: $pedidosFalha
    },
    statusAtual: {
      totalPedidos: $totalPedidos,
      pendentes: $pendentes,
      confirmados: $confirmados,
      emRota: $emRota,
      entregues: $entregues,
      cancelados: $cancelados,
      pendentesSemEntrega: $pendentesSemEntrega
    },
    pedidosIdsCriados: $pedidosIds
  }' > "$SUMMARY_FILE"

echo "[seed-pedidos-simultaneos] pedidos_solicitados=${PEDIDOS_TOTAIS} pedidos_criados=${ok} pedidos_falha=${fail}"
echo "[seed-pedidos-simultaneos] total=${total_pedidos} pendentes=${pendentes} confirmados=${confirmados} em_rota=${em_rota} entregues=${entregues} cancelados=${cancelados}"
echo "[seed-pedidos-simultaneos] pendentes_sem_entrega=${pendentes_sem_entrega}"
echo "[seed-pedidos-simultaneos] summary=${SUMMARY_FILE}"

if [[ "$fail" -gt 0 ]]; then
  exit 1
fi
