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

NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-4}"
PEDIDOS_TOTAIS="${PEDIDOS_TOTAIS:-50}"
QUANTIDADE_GALOES="${QUANTIDADE_GALOES:-1}"
METODO_PAGAMENTO="${METODO_PAGAMENTO:-PIX}"
JANELA_MODE="${JANELA_MODE:-MIXED}"
HARD_RATIO_PERCENT="${HARD_RATIO_PERCENT:-40}"
MAX_CYCLES="${MAX_CYCLES:-32}"
CYCLE_SLEEP_SECONDS="${CYCLE_SLEEP_SECONDS:-1}"
MIN_ENTREGUES="${MIN_ENTREGUES:-8}"
WAIT_ATTEMPTS="${WAIT_ATTEMPTS:-50}"
WAIT_SECONDS="${WAIT_SECONDS:-1}"
WAIT_ROTAS_ATTEMPTS="${WAIT_ROTAS_ATTEMPTS:-60}"
PROGRESS_GRACE_CYCLES="${PROGRESS_GRACE_CYCLES:-12}"
STALL_THRESHOLD="${STALL_THRESHOLD:-6}"
START_RETRY_ATTEMPTS="${START_RETRY_ATTEMPTS:-3}"
START_RETRY_SLEEP_SECONDS="${START_RETRY_SLEEP_SECONDS:-1}"
SUMMARY_FILE="${SUMMARY_FILE:-$ROOT_DIR/artifacts/poc/frota-escala-4x50-summary.json}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/check-frota-escala-4x50.sh

Variaveis opcionais:
  API_BASE=http://localhost:8082
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres
  DB_FORCE_CONTAINER=0
  NUM_ENTREGADORES_ATIVOS=4
  PEDIDOS_TOTAIS=50
  QUANTIDADE_GALOES=1
  METODO_PAGAMENTO=PIX
  JANELA_MODE=MIXED
  HARD_RATIO_PERCENT=40
  MAX_CYCLES=32
  CYCLE_SLEEP_SECONDS=1
  MIN_ENTREGUES=8
  WAIT_ATTEMPTS=50
  WAIT_SECONDS=1
  WAIT_ROTAS_ATTEMPTS=60
  PROGRESS_GRACE_CYCLES=12
  STALL_THRESHOLD=6
  START_RETRY_ATTEMPTS=3
  START_RETRY_SLEEP_SECONDS=1
  SUMMARY_FILE=artifacts/poc/frota-escala-4x50-summary.json

Comportamento:
  1) Reseta estado com 4 entregadores ativos e aplica seed geografico.
  2) Cria 50 pedidos simultaneos com janelas mistas HARD/FLEXIVEL.
  3) Executa ciclos de giro via API (iniciar rota pronta + concluir entregas EM_EXECUCAO).
  4) Valida metricas de escala e grava evidencia em JSON.
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

api_get_capture() {
  local path="$1"
  local raw

  raw="$(curl -sS -w $'\n%{http_code}' "$API_BASE$path")"
  API_LAST_STATUS="${raw##*$'\n'}"
  API_LAST_BODY="${raw%$'\n'*}"
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

fetch_painel_json() {
  api_get_capture "/api/operacao/painel"
  if [[ "$API_LAST_STATUS" != "200" ]]; then
    return 1
  fi
  printf '%s' "$API_LAST_BODY"
}

fetch_roteiro_json() {
  local entregador_id="$1"
  api_get_capture "/api/entregadores/${entregador_id}/roteiro"
  if [[ "$API_LAST_STATUS" != "200" ]]; then
    return 1
  fi
  printf '%s' "$API_LAST_BODY"
}

wait_rotas_disponiveis() {
  local attempts="$1"
  local seconds="$2"
  local attempt painel rotas_abertas

  for attempt in $(seq 1 "$attempts"); do
    if painel="$(fetch_painel_json)"; then
      rotas_abertas="$(jq -r '((.rotas.planejadas // []) | length) + ((.rotas.emAndamento // []) | length)' <<< "$painel")"
      if [[ "$rotas_abertas" -gt 0 ]]; then
        printf '%s' "$painel"
        return 0
      fi
    fi
    if [[ "$attempt" -lt "$attempts" ]]; then
      sleep "$seconds"
    fi
  done

  return 1
}

iniciar_rotas_prontas() {
  local painel_json="$1"
  local ids started conflicts errors retried_started
  started=0
  conflicts=0
  errors=0
  retried_started=0

  ids="$(jq -r '(.rotas.planejadas // []) | map(.entregadorId) | map(select(. != null)) | unique[]?' <<< "$painel_json")"

  while IFS= read -r entregador_id; do
    local status body erro conflito_retriavel contabilizar_conflito retry
    if [[ -z "$entregador_id" ]]; then
      continue
    fi

    api_post_capture "/api/operacao/rotas/prontas/iniciar" "$(jq -cn --argjson id "$entregador_id" '{entregadorId:$id}')"
    status="$API_LAST_STATUS"
    body="$API_LAST_BODY"
    if [[ "$status" == "200" ]]; then
      started=$((started + 1))
    elif [[ "$status" == "409" ]]; then
      contabilizar_conflito=1
      erro="$(jq -r '.erro // ""' <<< "$body" 2>/dev/null || true)"
      conflito_retriavel=0
      if [[ "$erro" == *"nao possui rota PLANEJADA pronta para iniciar"* ]]; then
        conflito_retriavel=1
      fi

      if [[ "$conflito_retriavel" -eq 1 ]]; then
        for retry in $(seq 1 "$START_RETRY_ATTEMPTS"); do
          sleep "$START_RETRY_SLEEP_SECONDS"
          api_post_capture "/api/operacao/rotas/prontas/iniciar" "$(jq -cn --argjson id "$entregador_id" '{entregadorId:$id}')"
          status="$API_LAST_STATUS"
          body="$API_LAST_BODY"

          if [[ "$status" == "200" ]]; then
            started=$((started + 1))
            retried_started=$((retried_started + 1))
            contabilizar_conflito=0
            break
          fi

          if [[ "$status" != "409" ]]; then
            errors=$((errors + 1))
            contabilizar_conflito=0
            break
          fi
        done
      fi

      if [[ "$contabilizar_conflito" -eq 1 ]]; then
        conflicts=$((conflicts + 1))
      fi
    else
      errors=$((errors + 1))
    fi
  done <<< "$ids"

  jq -cn \
    --argjson started "$started" \
    --argjson conflicts "$conflicts" \
    --argjson errors "$errors" \
    --argjson retriedStarted "$retried_started" \
    '{started:$started, conflicts:$conflicts, errors:$errors, retriedStarted:$retriedStarted}'
}

entregar_execucoes_ativas() {
  local painel_json="$1"
  local ids delivered conflicts errors roteiro entrega_ids entrega_id
  delivered=0
  conflicts=0
  errors=0

  ids="$(jq -r '[(.rotas.emAndamento // [])[]?.entregadorId] | map(select(. != null)) | unique[]?' <<< "$painel_json")"

  while IFS= read -r entregador_id; do
    if [[ -z "$entregador_id" ]]; then
      continue
    fi

    if ! roteiro="$(fetch_roteiro_json "$entregador_id")"; then
      errors=$((errors + 1))
      continue
    fi

    entrega_ids="$(jq -r '(.paradasPendentesExecucao // [])[]? | select((.status // "") | ascii_upcase == "EM_EXECUCAO") | .entregaId' <<< "$roteiro")"
    while IFS= read -r entrega_id; do
      if [[ -z "$entrega_id" ]]; then
        continue
      fi

      api_post_capture "/api/eventos" "$(jq -cn --argjson id "$entrega_id" '{eventType:"PEDIDO_ENTREGUE", entregaId:$id}')"
      if [[ "$API_LAST_STATUS" == "200" ]]; then
        delivered=$((delivered + 1))
      elif [[ "$API_LAST_STATUS" == "409" ]]; then
        conflicts=$((conflicts + 1))
      else
        errors=$((errors + 1))
      fi
    done <<< "$entrega_ids"
  done <<< "$ids"

  jq -cn \
    --argjson delivered "$delivered" \
    --argjson conflicts "$conflicts" \
    --argjson errors "$errors" \
    '{delivered:$delivered, conflicts:$conflicts, errors:$errors}'
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

for n in \
  "$NUM_ENTREGADORES_ATIVOS" \
  "$PEDIDOS_TOTAIS" \
  "$QUANTIDADE_GALOES" \
  "$HARD_RATIO_PERCENT" \
  "$MAX_CYCLES" \
  "$CYCLE_SLEEP_SECONDS" \
  "$MIN_ENTREGUES" \
  "$WAIT_ATTEMPTS" \
  "$WAIT_SECONDS" \
  "$WAIT_ROTAS_ATTEMPTS" \
  "$PROGRESS_GRACE_CYCLES" \
  "$STALL_THRESHOLD" \
  "$START_RETRY_ATTEMPTS" \
  "$START_RETRY_SLEEP_SECONDS"; do
  if ! is_positive_int "$n"; then
    echo "Parametro numerico invalido: $n" >&2
    exit 1
  fi
done

if [[ "$HARD_RATIO_PERCENT" -gt 100 ]]; then
  echo "HARD_RATIO_PERCENT invalido: $HARD_RATIO_PERCENT (max 100)." >&2
  exit 1
fi

require_cmd curl
require_cmd jq

if ! curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "API indisponivel em $API_BASE" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
seed_summary="$tmp_dir/seed-summary.json"
cycles_ndjson="$tmp_dir/cycles.ndjson"
: > "$cycles_ndjson"

set +e
(
  cd "$ROOT_DIR"
  API_BASE="$API_BASE" \
    DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" \
    DB_USER="$DB_USER" DB_NAME="$DB_NAME" DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_PASSWORD="$DB_PASSWORD" \
    DB_FORCE_CONTAINER="$DB_FORCE_CONTAINER" \
    NUM_ENTREGADORES_ATIVOS="$NUM_ENTREGADORES_ATIVOS" PEDIDOS_TOTAIS="$PEDIDOS_TOTAIS" \
    QUANTIDADE_GALOES="$QUANTIDADE_GALOES" METODO_PAGAMENTO="$METODO_PAGAMENTO" \
    JANELA_MODE="$JANELA_MODE" HARD_RATIO_PERCENT="$HARD_RATIO_PERCENT" \
    WAIT_ATTEMPTS="$WAIT_ATTEMPTS" WAIT_SECONDS="$WAIT_SECONDS" \
    SUMMARY_FILE="$seed_summary" \
    scripts/poc/seed-pedidos-simultaneos.sh
) > "$tmp_dir/seed.log" 2>&1
seed_exit="$?"
set -e

if [[ "$seed_exit" -ne 0 ]]; then
  echo "[check-frota-escala-4x50] FALHA: seed simultaneo falhou." >&2
  cat "$tmp_dir/seed.log" >&2
  exit 1
fi

if [[ ! -s "$seed_summary" ]]; then
  echo "[check-frota-escala-4x50] FALHA: seed nao gerou summary JSON." >&2
  exit 1
fi

hard_count="$(jq -r '.janelas.pedidosHard // 0' "$seed_summary")"
asap_count="$(jq -r '.janelas.pedidosFlexivel // 0' "$seed_summary")"

painel_atual="{}"
if painel_inicial="$(wait_rotas_disponiveis "$WAIT_ROTAS_ATTEMPTS" "$WAIT_SECONDS")"; then
  painel_atual="$painel_inicial"
fi

stalled_cycles=0
for cycle in $(seq 1 "$MAX_CYCLES"); do
  if painel_tmp="$(fetch_painel_json)"; then
    painel_atual="$painel_tmp"
  fi

  start_json="$(iniciar_rotas_prontas "$painel_atual")"
  start_errors="$(jq -r '.errors' <<< "$start_json")"
  if [[ "$start_errors" -gt 0 ]]; then
    echo "[check-frota-escala-4x50] FALHA: erro ao iniciar rotas prontas (cycle=${cycle})." >&2
    exit 1
  fi

  deliver_json="$(entregar_execucoes_ativas "$painel_atual")"
  deliver_errors="$(jq -r '.errors' <<< "$deliver_json")"
  if [[ "$deliver_errors" -gt 0 ]]; then
    echo "[check-frota-escala-4x50] FALHA: erro ao concluir entregas em execucao (cycle=${cycle})." >&2
    exit 1
  fi

  sleep "$CYCLE_SLEEP_SECONDS"
  if painel_tmp="$(fetch_painel_json)"; then
    painel_atual="$painel_tmp"
  fi

  pendentes="$(jq -r '.pedidosPorStatus.pendente // 0' <<< "$painel_atual")"
  confirmados="$(jq -r '.pedidosPorStatus.confirmado // 0' <<< "$painel_atual")"
  em_rota="$(jq -r '.pedidosPorStatus.emRota // 0' <<< "$painel_atual")"
  entregues_atual="$(jq -r '.pedidosPorStatus.entregue // 0' <<< "$painel_atual")"
  cancelados="$(jq -r '.pedidosPorStatus.cancelado // 0' <<< "$painel_atual")"
  pedidos_abertos="$((pendentes + confirmados + em_rota))"

  rotas_planejadas="$(jq -r '(.rotas.planejadas // []) | length' <<< "$painel_atual")"
  rotas_em_andamento="$(jq -r '(.rotas.emAndamento // []) | length' <<< "$painel_atual")"
  rotas_abertas="$((rotas_planejadas + rotas_em_andamento))"
  entregadores_com_rota_cycle="$(jq -r '[(.rotas.planejadas // [])[]?.entregadorId, (.rotas.emAndamento // [])[]?.entregadorId] | map(select(. != null)) | unique | length' <<< "$painel_atual")"

  started_count="$(jq -r '.started' <<< "$start_json")"
  delivered_count="$(jq -r '.delivered' <<< "$deliver_json")"

  if [[ "$started_count" -eq 0 && "$delivered_count" -eq 0 && "$rotas_abertas" -gt 0 ]]; then
    if [[ "$cycle" -gt "$PROGRESS_GRACE_CYCLES" ]]; then
      stalled_cycles=$((stalled_cycles + 1))
    else
      stalled_cycles=0
    fi
  else
    stalled_cycles=0
  fi

  jq -cn \
    --argjson cycle "$cycle" \
    --argjson started "$started_count" \
    --argjson startRetried "$(jq -r '.retriedStarted // 0' <<< "$start_json")" \
    --argjson startConflicts "$(jq -r '.conflicts' <<< "$start_json")" \
    --argjson delivered "$delivered_count" \
    --argjson deliveryConflicts "$(jq -r '.conflicts' <<< "$deliver_json")" \
    --argjson pedidosAbertos "$pedidos_abertos" \
    --argjson pedidosEntregues "$entregues_atual" \
    --argjson pedidosCancelados "$cancelados" \
    --argjson rotasPlanejadas "$rotas_planejadas" \
    --argjson rotasEmAndamento "$rotas_em_andamento" \
    --argjson rotasAbertas "$rotas_abertas" \
    --argjson entregadoresComRota "$entregadores_com_rota_cycle" \
    --argjson stalledCycles "$stalled_cycles" \
    '{
      cycle: $cycle,
      started: $started,
      startRetried: $startRetried,
      startConflicts: $startConflicts,
      delivered: $delivered,
      deliveryConflicts: $deliveryConflicts,
      pedidosAbertos: $pedidosAbertos,
      pedidosEntregues: $pedidosEntregues,
      pedidosCancelados: $pedidosCancelados,
      rotasPlanejadas: $rotasPlanejadas,
      rotasEmAndamento: $rotasEmAndamento,
      rotasAbertas: $rotasAbertas,
      entregadoresComRota: $entregadoresComRota,
      stalledCycles: $stalledCycles
    }' >> "$cycles_ndjson"

  if [[ "$pedidos_abertos" -eq 0 ]]; then
    break
  fi

  if [[ "$stalled_cycles" -ge "$STALL_THRESHOLD" ]]; then
    break
  fi
done

if painel_tmp="$(fetch_painel_json)"; then
  painel_atual="$painel_tmp"
fi

pendentes_final="$(jq -r '.pedidosPorStatus.pendente // 0' <<< "$painel_atual")"
confirmados_final="$(jq -r '.pedidosPorStatus.confirmado // 0' <<< "$painel_atual")"
em_rota_final="$(jq -r '.pedidosPorStatus.emRota // 0' <<< "$painel_atual")"
entregues_final="$(jq -r '.pedidosPorStatus.entregue // 0' <<< "$painel_atual")"
cancelados_final="$(jq -r '.pedidosPorStatus.cancelado // 0' <<< "$painel_atual")"
total_pedidos="$((pendentes_final + confirmados_final + em_rota_final + entregues_final + cancelados_final))"

rotas_planejadas_final="$(jq -r '(.rotas.planejadas // []) | length' <<< "$painel_atual")"
rotas_em_andamento_final="$(jq -r '(.rotas.emAndamento // []) | length' <<< "$painel_atual")"
entregadores_com_rota="$(jq -r '[(.rotas.planejadas // [])[]?.entregadorId, (.rotas.emAndamento // [])[]?.entregadorId] | map(select(. != null)) | unique | length' <<< "$painel_atual")"

cycles_json="$(jq -s '.' "$cycles_ndjson")"
cycles_total="$(jq -r 'length' <<< "$cycles_json")"
started_total="$(jq -r '[.[] | .started] | add // 0' <<< "$cycles_json")"
delivered_total_cycles="$(jq -r '[.[] | .delivered] | add // 0' <<< "$cycles_json")"
max_entregadores_com_rota="$(jq -r '[.[] | .entregadoresComRota // 0] | max // 0' <<< "$cycles_json")"

ok=true
violacoes='[]'

if [[ "$total_pedidos" -ne "$PEDIDOS_TOTAIS" ]]; then
  ok=false
  violacoes="$(jq -cn --argjson arr "$violacoes" --arg msg "total de pedidos diverge (${total_pedidos} != ${PEDIDOS_TOTAIS})" '$arr + [$msg]')"
fi

if [[ "$max_entregadores_com_rota" -lt "$NUM_ENTREGADORES_ATIVOS" ]]; then
  ok=false
  violacoes="$(jq -cn --argjson arr "$violacoes" --arg msg "nem todos os entregadores receberam rota (max=${max_entregadores_com_rota} < ${NUM_ENTREGADORES_ATIVOS})" '$arr + [$msg]')"
fi

if [[ "$JANELA_MODE" == "MIXED" ]]; then
  if [[ "$hard_count" -le 0 || "$asap_count" -le 0 ]]; then
    ok=false
    violacoes="$(jq -cn --argjson arr "$violacoes" --arg msg "modo MIXED sem distribuicao HARD/ASAP (hard=${hard_count}, asap=${asap_count})" '$arr + [$msg]')"
  fi
fi

if [[ "$entregues_final" -lt "$MIN_ENTREGUES" ]]; then
  ok=false
  violacoes="$(jq -cn --argjson arr "$violacoes" --arg msg "entregues abaixo do minimo (${entregues_final} < ${MIN_ENTREGUES})" '$arr + [$msg]')"
fi

mkdir -p "$(dirname "$SUMMARY_FILE")"

jq -n \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --argjson config "$(jq -cn \
    --argjson entregadores "$NUM_ENTREGADORES_ATIVOS" \
    --argjson pedidos "$PEDIDOS_TOTAIS" \
    --argjson quantidadeGaloes "$QUANTIDADE_GALOES" \
    --arg metodoPagamento "$METODO_PAGAMENTO" \
    --arg janelaMode "$JANELA_MODE" \
    --argjson hardRatioPercent "$HARD_RATIO_PERCENT" \
    --argjson maxCycles "$MAX_CYCLES" \
    --argjson progressGraceCycles "$PROGRESS_GRACE_CYCLES" \
    --argjson stallThreshold "$STALL_THRESHOLD" \
    --argjson minEntregues "$MIN_ENTREGUES" \
    '{
      entregadores: $entregadores,
      pedidos: $pedidos,
      quantidadeGaloes: $quantidadeGaloes,
      metodoPagamento: $metodoPagamento,
      janelaMode: $janelaMode,
      hardRatioPercent: $hardRatioPercent,
      maxCycles: $maxCycles,
      progressGraceCycles: $progressGraceCycles,
      stallThreshold: $stallThreshold,
      minEntregues: $minEntregues
    }')" \
  --argjson totals "$(jq -cn \
    --argjson totalPedidos "$total_pedidos" \
    --argjson pendentes "$pendentes_final" \
    --argjson confirmados "$confirmados_final" \
    --argjson emRota "$em_rota_final" \
    --argjson entregues "$entregues_final" \
    --argjson cancelados "$cancelados_final" \
    --argjson janelaHard "$hard_count" \
    --argjson janelaAsap "$asap_count" \
    --argjson rotasPlanejadas "$rotas_planejadas_final" \
    --argjson rotasEmAndamento "$rotas_em_andamento_final" \
    --argjson entregadoresComRota "$entregadores_com_rota" \
    --argjson maxEntregadoresComRota "$max_entregadores_com_rota" \
    --argjson cyclesTotal "$cycles_total" \
    --argjson startedTotal "$started_total" \
    --argjson deliveredTotalCycles "$delivered_total_cycles" \
    '{
      totalPedidos: $totalPedidos,
      pendentes: $pendentes,
      confirmados: $confirmados,
      emRota: $emRota,
      entregues: $entregues,
      cancelados: $cancelados,
      janelaHard: $janelaHard,
      janelaAsap: $janelaAsap,
      rotasPlanejadas: $rotasPlanejadas,
      rotasEmAndamento: $rotasEmAndamento,
      entregadoresComRota: $entregadoresComRota,
      maxEntregadoresComRota: $maxEntregadoresComRota,
      cyclesTotal: $cyclesTotal,
      startedTotal: $startedTotal,
      deliveredTotalCycles: $deliveredTotalCycles
    }')" \
  --argjson seed "$(cat "$seed_summary")" \
  --argjson cycles "$cycles_json" \
  --argjson violations "$violacoes" \
  --argjson ok "$ok" \
  '{
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    config: $config,
    totals: $totals,
    seed: $seed,
    cycles: $cycles,
    violations: $violations,
    ok: $ok
  }' > "$SUMMARY_FILE"

echo "[check-frota-escala-4x50] summary=$SUMMARY_FILE"
echo "[check-frota-escala-4x50] total=${total_pedidos} entregues=${entregues_final} hard=${hard_count} asap=${asap_count}"

if [[ "$ok" != "true" ]]; then
  echo "[check-frota-escala-4x50] FALHA: invariantes de escala nao atendidas." >&2
  jq -r '.violations[] | "- \(.)"' "$SUMMARY_FILE" >&2
  exit 1
fi

echo "[check-frota-escala-4x50] OK: escala 4x50 validada com giro operacional."
