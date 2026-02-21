#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

API_BASE="${API_BASE:-http://localhost:8082}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"

SLA_ROUNDS="${SLA_ROUNDS:-3}"
SLA_MAX_PEDIDO_ROTA_SEGUNDOS="${SLA_MAX_PEDIDO_ROTA_SEGUNDOS:-45}"
SLA_MAX_ROTA_INICIO_SEGUNDOS="${SLA_MAX_ROTA_INICIO_SEGUNDOS:-30}"
NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-1}"
SUMMARY_FILE="${SUMMARY_FILE:-$ROOT_DIR/artifacts/poc/sla-operacional-summary.json}"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/artifacts/poc/sla-operacional}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/check-sla-operacional.sh

Variaveis opcionais:
  API_BASE=http://localhost:8082
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres
  SLA_ROUNDS=3
  SLA_MAX_PEDIDO_ROTA_SEGUNDOS=45
  SLA_MAX_ROTA_INICIO_SEGUNDOS=30
  NUM_ENTREGADORES_ATIVOS=1
  SUMMARY_FILE=artifacts/poc/sla-operacional-summary.json
  WORK_DIR=artifacts/poc/sla-operacional
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

  require_cmd docker
  docker exec -i "$DB_CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -Atc "$sql"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd curl
require_cmd jq
require_cmd awk

for n in "$SLA_ROUNDS" "$SLA_MAX_PEDIDO_ROTA_SEGUNDOS" "$SLA_MAX_ROTA_INICIO_SEGUNDOS" "$NUM_ENTREGADORES_ATIVOS"; do
  if ! is_positive_int "$n"; then
    echo "Parametro numerico invalido: $n" >&2
    exit 1
  fi
done

if ! curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "API offline em $API_BASE" >&2
  exit 1
fi

mkdir -p "$WORK_DIR"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
rounds_file="$tmp_dir/rounds.ndjson"
: > "$rounds_file"

for round in $(seq 1 "$SLA_ROUNDS"); do
  round_id="round-$(printf '%02d' "$round")"
  round_dir="$WORK_DIR/$round_id"
  mkdir -p "$round_dir"

  set +e
  (
    cd "$ROOT_DIR"
    DB_CONTAINER="$DB_CONTAINER" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
      NUM_ENTREGADORES_ATIVOS="$NUM_ENTREGADORES_ATIVOS" SEED_MONTES_CLAROS=1 \
      scripts/poc/reset-test-state.sh
  ) > "$round_dir/reset.log" 2>&1
  reset_exit="$?"
  set -e

  run_exit=1
  pedido_id=""
  rota_id=""
  pedido_rota_segundos=""
  rota_inicio_segundos=""
  ok=true
  viol='[]'

  if [[ "$reset_exit" -ne 0 ]]; then
    ok=false
    viol="$(jq -cn --argjson arr "$viol" --arg msg "reset falhou (exit=$reset_exit)" '$arr + [$msg]')"
  else
    set +e
    (
      cd "$ROOT_DIR"
      NUM_ENTREGADORES_ATIVOS="$NUM_ENTREGADORES_ATIVOS" \
        scripts/poc/run-cenario.sh feliz
    ) > "$round_dir/run-cenario.log" 2>&1
    run_exit="$?"
    set -e

    if [[ "$run_exit" -ne 0 ]]; then
      ok=false
      viol="$(jq -cn --argjson arr "$viol" --arg msg "run-cenario falhou (exit=$run_exit)" '$arr + [$msg]')"
    fi

    pedido_id="$(awk -F= '/^pedido_id=/{print $2}' "$round_dir/run-cenario.log" | tail -n1 | tr -d '[:space:]')"
    rota_id="$(awk -F= '/^rota_id=/{print $2}' "$round_dir/run-cenario.log" | tail -n1 | tr -d '[:space:]')"

    if [[ -z "$pedido_id" || -z "$rota_id" ]]; then
      ok=false
      viol="$(jq -cn --argjson arr "$viol" --arg msg "pedido_id/rota_id ausentes no log" '$arr + [$msg]')"
    else
      pedido_rota_segundos="$(psql_query "SELECT CAST(EXTRACT(EPOCH FROM (r.criado_em - p.criado_em)) AS INTEGER)
FROM pedidos p
JOIN entregas e ON e.pedido_id = p.id
JOIN rotas r ON r.id = e.rota_id
WHERE p.id = ${pedido_id}
ORDER BY e.id
LIMIT 1;" | extract_single_value)"

      rota_inicio_segundos="$(psql_query "SELECT CAST(EXTRACT(EPOCH FROM (COALESCE(r.inicio, CURRENT_TIMESTAMP) - r.criado_em)) AS INTEGER)
FROM rotas r
WHERE r.id = ${rota_id};" | extract_single_value)"

      if [[ -z "$pedido_rota_segundos" || "$pedido_rota_segundos" == "null" ]]; then
        ok=false
        viol="$(jq -cn --argjson arr "$viol" --arg msg "nao foi possivel calcular pedido->rota" '$arr + [$msg]')"
      elif [[ "$pedido_rota_segundos" -lt 0 ]]; then
        ok=false
        viol="$(jq -cn --argjson arr "$viol" --arg msg "pedido->rota negativo (${pedido_rota_segundos})" '$arr + [$msg]')"
      elif [[ "$pedido_rota_segundos" -gt "$SLA_MAX_PEDIDO_ROTA_SEGUNDOS" ]]; then
        ok=false
        viol="$(jq -cn --argjson arr "$viol" --arg msg "pedido->rota acima do SLA (${pedido_rota_segundos}s > ${SLA_MAX_PEDIDO_ROTA_SEGUNDOS}s)" '$arr + [$msg]')"
      fi

      if [[ -z "$rota_inicio_segundos" || "$rota_inicio_segundos" == "null" ]]; then
        ok=false
        viol="$(jq -cn --argjson arr "$viol" --arg msg "nao foi possivel calcular rota->inicio" '$arr + [$msg]')"
      elif [[ "$rota_inicio_segundos" -lt 0 ]]; then
        ok=false
        viol="$(jq -cn --argjson arr "$viol" --arg msg "rota->inicio negativo (${rota_inicio_segundos})" '$arr + [$msg]')"
      elif [[ "$rota_inicio_segundos" -gt "$SLA_MAX_ROTA_INICIO_SEGUNDOS" ]]; then
        ok=false
        viol="$(jq -cn --argjson arr "$viol" --arg msg "rota->inicio acima do SLA (${rota_inicio_segundos}s > ${SLA_MAX_ROTA_INICIO_SEGUNDOS}s)" '$arr + [$msg]')"
      fi
    fi
  fi

  jq -n \
    --arg round "$round_id" \
    --argjson resetExit "$reset_exit" \
    --argjson runExit "$run_exit" \
    --argjson pedidoId "${pedido_id:-null}" \
    --argjson rotaId "${rota_id:-null}" \
    --argjson pedidoRotaSegundos "${pedido_rota_segundos:-null}" \
    --argjson rotaInicioSegundos "${rota_inicio_segundos:-null}" \
    --argjson ok "$ok" \
    --argjson violacoes "$viol" \
    --arg logFile "$round_dir/run-cenario.log" \
    '{
      round: $round,
      resetExit: $resetExit,
      runExit: $runExit,
      pedidoId: $pedidoId,
      rotaId: $rotaId,
      pedidoParaRotaSegundos: $pedidoRotaSegundos,
      rotaParaInicioSegundos: $rotaInicioSegundos,
      ok: $ok,
      violacoes: $violacoes,
      logFile: $logFile
    }' >> "$rounds_file"
done

rounds_json="$(jq -s '.' "$rounds_file")"
rounds_total="$(jq -r 'length' <<< "$rounds_json")"
rounds_ok="$(jq -r '[.[] | select(.ok == true)] | length' <<< "$rounds_json")"
max_pedido_rota="$(jq -r '[.[] | .pedidoParaRotaSegundos // 0] | max // 0' <<< "$rounds_json")"
max_rota_inicio="$(jq -r '[.[] | .rotaParaInicioSegundos // 0] | max // 0' <<< "$rounds_json")"
avg_pedido_rota="$(jq -r '[.[] | select(.pedidoParaRotaSegundos != null) | .pedidoParaRotaSegundos] as $x | if ($x|length)==0 then 0 else (($x|add)/($x|length)) end' <<< "$rounds_json")"
avg_rota_inicio="$(jq -r '[.[] | select(.rotaParaInicioSegundos != null) | .rotaParaInicioSegundos] as $x | if ($x|length)==0 then 0 else (($x|add)/($x|length)) end' <<< "$rounds_json")"
violacoes_total="$(jq -r '[.[] | (.violacoes | length)] | add // 0' <<< "$rounds_json")"

overall_ok=true
if [[ "$rounds_ok" -ne "$rounds_total" ]]; then
  overall_ok=false
fi

mkdir -p "$(dirname "$SUMMARY_FILE")"

jq -n \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --argjson slaRounds "$SLA_ROUNDS" \
  --argjson slaMaxPedidoRotaSegundos "$SLA_MAX_PEDIDO_ROTA_SEGUNDOS" \
  --argjson slaMaxRotaInicioSegundos "$SLA_MAX_ROTA_INICIO_SEGUNDOS" \
  --argjson numEntregadoresAtivos "$NUM_ENTREGADORES_ATIVOS" \
  --argjson rounds "$rounds_json" \
  --argjson roundsTotal "$rounds_total" \
  --argjson roundsOk "$rounds_ok" \
  --argjson maxPedidoRota "$max_pedido_rota" \
  --argjson maxRotaInicio "$max_rota_inicio" \
  --argjson avgPedidoRota "$avg_pedido_rota" \
  --argjson avgRotaInicio "$avg_rota_inicio" \
  --argjson violacoesTotal "$violacoes_total" \
  --argjson ok "$overall_ok" \
  '{
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    thresholds: {
      slaRounds: $slaRounds,
      maxPedidoParaRotaSegundos: $slaMaxPedidoRotaSegundos,
      maxRotaParaInicioSegundos: $slaMaxRotaInicioSegundos,
      numEntregadoresAtivos: $numEntregadoresAtivos
    },
    totals: {
      roundsTotal: $roundsTotal,
      roundsOk: $roundsOk,
      violacoesTotal: $violacoesTotal,
      maxPedidoParaRotaSegundos: $maxPedidoRota,
      maxRotaParaInicioSegundos: $maxRotaInicio,
      avgPedidoParaRotaSegundos: $avgPedidoRota,
      avgRotaParaInicioSegundos: $avgRotaInicio
    },
    rounds: $rounds,
    ok: $ok
  }' > "$SUMMARY_FILE"

echo "[check-sla-operacional] summary=$SUMMARY_FILE"
echo "[check-sla-operacional] rounds_ok=${rounds_ok}/${rounds_total}"

if [[ "$overall_ok" != "true" ]]; then
  echo "[check-sla-operacional] FALHA: SLA fora do limite." >&2
  jq -r '.rounds[] | select(.ok == false) | "- [\(.round)] \(.violacoes | join("; "))"' "$SUMMARY_FILE" >&2
  exit 1
fi

echo "[check-sla-operacional] OK: SLA operacional dentro dos limites definidos."
