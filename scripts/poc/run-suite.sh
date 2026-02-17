#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/run-suite.sh [<feliz|falha|cancelamento>...]

Sem argumentos, executa os 3 cenarios canonicos da PoC.

Variaveis opcionais:
  API_BASE=http://localhost:8082
  OUT_DIR=artifacts/poc/<timestamp>
  VALE_POSITIVO_SALDO=10
  SUITE_DEBOUNCE_SEGUNDOS=0

Todas as variaveis de `scripts/poc/run-cenario.sh` tambem sao aceitas.
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

is_valid_scenario() {
  case "$1" in
    feliz|falha|cancelamento) return 0 ;;
    *) return 1 ;;
  esac
}

expected_status_for() {
  case "$1" in
    feliz) echo "ENTREGUE" ;;
    falha) echo "CANCELADO" ;;
    cancelamento) echo "CANCELADO" ;;
    *)
      echo "INDEFINIDO"
      ;;
  esac
}

extract_pedido_id() {
  local log_file="$1"
  awk -F= '/^pedido_id=/{print $2}' "$log_file" | tail -n1 | tr -d '[:space:]'
}

extract_rota_id() {
  local log_file="$1"
  awk -F= '/^rota_id=/{print $2}' "$log_file" | tail -n1 | tr -d '[:space:]'
}

extract_entrega_id() {
  local log_file="$1"
  awk -F= '/^entrega_id=/{print $2}' "$log_file" | tail -n1 | tr -d '[:space:]'
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_CENARIO_SCRIPT="$ROOT_DIR/scripts/poc/run-cenario.sh"

if [[ ! -x "$RUN_CENARIO_SCRIPT" ]]; then
  echo "Script nao executavel: $RUN_CENARIO_SCRIPT" >&2
  echo "Execute: chmod +x scripts/poc/run-cenario.sh" >&2
  exit 1
fi

require_cmd curl
require_cmd jq

API_BASE="${API_BASE:-http://localhost:8082}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/artifacts/poc/$(date +%Y%m%d-%H%M%S)}"
SUITE_DEBOUNCE_SEGUNDOS="${SUITE_DEBOUNCE_SEGUNDOS:-0}"

declare -a SCENARIOS
if [[ "$#" -eq 0 ]]; then
  SCENARIOS=(feliz falha cancelamento)
else
  for cenario in "$@"; do
    if ! is_valid_scenario "$cenario"; then
      echo "Cenario invalido: $cenario" >&2
      usage
      exit 1
    fi
    SCENARIOS+=("$cenario")
  done
fi

mkdir -p "$OUT_DIR"
SUMMARY_FILE="$OUT_DIR/summary.json"

echo "Iniciando suite PoC em $OUT_DIR"

first_entry=true
overall_failed=0

{
  echo "{"
  echo "  \"generatedAt\": \"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\","
  echo "  \"apiBase\": \"${API_BASE}\","
  echo "  \"scenarios\": ["
} > "$SUMMARY_FILE"

for cenario in "${SCENARIOS[@]}"; do
  log_file="$OUT_DIR/${cenario}.log"
  timeline_file="$OUT_DIR/${cenario}.timeline.json"
  expected_status="$(expected_status_for "$cenario")"

  echo
  echo "==> Executando cenario: $cenario"
  echo "Log: $log_file"

  set +e
  DEBOUNCE_SEGUNDOS="$SUITE_DEBOUNCE_SEGUNDOS" "$RUN_CENARIO_SCRIPT" "$cenario" 2>&1 | tee "$log_file"
  cenario_exit="${PIPESTATUS[0]}"
  set -e

  pedido_id="$(extract_pedido_id "$log_file")"
  rota_id="$(extract_rota_id "$log_file")"
  entrega_id="$(extract_entrega_id "$log_file")"

  timeline_status="INDEFINIDO"
  validacao_status="FALHA"
  erro_validacao=""

  if [[ "$cenario_exit" -eq 0 && -n "$pedido_id" ]]; then
    set +e
    timeline_body="$(curl -sS "$API_BASE/api/pedidos/${pedido_id}/timeline")"
    timeline_exit="$?"
    set -e

    if [[ "$timeline_exit" -eq 0 ]]; then
      echo "$timeline_body" | jq . > "$timeline_file"
      timeline_status="$(echo "$timeline_body" | jq -r '.statusAtual // "INDEFINIDO"')"
      if [[ "$timeline_status" == "$expected_status" ]]; then
        validacao_status="OK"
      else
        erro_validacao="statusAtual esperado=${expected_status} obtido=${timeline_status}"
      fi
    else
      erro_validacao="falha ao consultar timeline final de pedido_id=${pedido_id}"
    fi
  else
    if [[ "$cenario_exit" -ne 0 ]]; then
      erro_validacao="run-cenario retornou codigo ${cenario_exit}"
    else
      erro_validacao="pedido_id nao encontrado no log"
    fi
  fi

  if [[ "$validacao_status" != "OK" ]]; then
    overall_failed=1
  fi

  echo "resultado cenario=${cenario} validacao=${validacao_status} esperado=${expected_status} timeline=${timeline_status}"
  if [[ -n "$erro_validacao" ]]; then
    echo "detalhe: $erro_validacao"
  fi

  if [[ "$first_entry" == false ]]; then
    echo "," >> "$SUMMARY_FILE"
  fi
  first_entry=false

  {
    echo "    {"
    echo "      \"cenario\": \"${cenario}\","
    echo "      \"validacao\": \"${validacao_status}\","
    echo "      \"expectedStatus\": \"${expected_status}\","
    echo "      \"timelineStatus\": \"${timeline_status}\","
    if [[ -n "$pedido_id" ]]; then
      echo "      \"pedidoId\": ${pedido_id},"
    else
      echo "      \"pedidoId\": null,"
    fi
    if [[ -n "$rota_id" ]]; then
      echo "      \"rotaId\": ${rota_id},"
    else
      echo "      \"rotaId\": null,"
    fi
    if [[ -n "$entrega_id" ]]; then
      echo "      \"entregaId\": ${entrega_id},"
    else
      echo "      \"entregaId\": null,"
    fi
    echo "      \"logFile\": \"${log_file}\","
    if [[ -f "$timeline_file" ]]; then
      echo "      \"timelineFile\": \"${timeline_file}\","
    else
      echo "      \"timelineFile\": null,"
    fi
    if [[ -n "$erro_validacao" ]]; then
      erro_json="$(printf '%s' "$erro_validacao" | jq -R .)"
      echo "      \"erro\": ${erro_json}"
    else
      echo "      \"erro\": null"
    fi
    echo -n "    }"
  } >> "$SUMMARY_FILE"
done

echo
echo "==> Validando checkout VALE (saldo suficiente)"
vale_ok_log="$OUT_DIR/vale-positivo.log"
set +e
DEBOUNCE_SEGUNDOS="$SUITE_DEBOUNCE_SEGUNDOS" METODO_PAGAMENTO=VALE VALE_SALDO="${VALE_POSITIVO_SALDO:-10}" "$RUN_CENARIO_SCRIPT" feliz 2>&1 | tee "$vale_ok_log"
vale_ok_exit="${PIPESTATUS[0]}"
set -e
if [[ "$vale_ok_exit" -ne 0 ]]; then
  echo "validacao VALE positivo: FALHA (run-cenario retornou ${vale_ok_exit})"
  overall_failed=1
else
  echo "validacao VALE positivo: OK"
fi

echo
echo "==> Validando bloqueio VALE sem saldo"
vale_bloqueado_log="$OUT_DIR/vale-bloqueado.log"
set +e
DEBOUNCE_SEGUNDOS="$SUITE_DEBOUNCE_SEGUNDOS" METODO_PAGAMENTO=VALE VALE_SALDO=0 "$RUN_CENARIO_SCRIPT" feliz 2>&1 | tee "$vale_bloqueado_log"
vale_bloqueado_exit="${PIPESTATUS[0]}"
set -e
if [[ "$vale_bloqueado_exit" -eq 0 ]]; then
  echo "validacao VALE bloqueado: FALHA (run-cenario deveria falhar)"
  overall_failed=1
elif grep -qi "cliente nao possui vale" "$vale_bloqueado_log"; then
  echo "validacao VALE bloqueado: OK"
else
  echo "validacao VALE bloqueado: FALHA (motivo esperado nao encontrado no log)"
  overall_failed=1
fi

{
  echo
  echo "  ]"
  echo "}"
} >> "$SUMMARY_FILE"

echo
echo "Summary: $SUMMARY_FILE"
if [[ "$overall_failed" -eq 1 ]]; then
  echo "Suite PoC concluida com falhas."
  exit 1
fi

echo "Suite PoC concluida com sucesso."
