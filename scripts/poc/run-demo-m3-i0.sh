#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/run-demo-m3-i0.sh

Variaveis opcionais:
  API_BASE=http://localhost:8082
  OUT_DIR=artifacts/poc/demo-m3-i0-<timestamp>
  KEEP_RUNNING=0|1
  UI_BASE=http://localhost:4174
  SOLVER_REBUILD=1
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres

Comportamento:
  1) sobe ambiente de teste (`start-test-env.sh`)
  2) reseta estado + seed de Montes Claros
  3) valida seed com `check-montes-claros-seed.sh`
  4) executa cenarios `feliz`, `falha`, `cancelamento`
  5) coleta evidencias por cenario:
     - timeline final
     - execucao final
     - feed de jobs de replanejamento
     - detalhe dos novos jobs capturados no cenario
  6) gera:
     - demo-summary.json
     - demo-summary.txt

Saida:
  - exit 0 apenas quando todos os cenarios aprovarem.
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
START_ENV_SCRIPT="$ROOT_DIR/scripts/poc/start-test-env.sh"
RESET_SCRIPT="$ROOT_DIR/scripts/poc/reset-test-state.sh"
CHECK_SEED_SCRIPT="$ROOT_DIR/scripts/poc/check-montes-claros-seed.sh"
RUN_CENARIO_SCRIPT="$ROOT_DIR/scripts/poc/run-cenario.sh"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

extract_port_from_url() {
  local url="$1"
  local default_port="$2"
  local parsed
  parsed="$(printf '%s' "$url" | sed -nE 's#^https?://[^:/]+:([0-9]+).*$#\1#p')"
  if [[ -n "$parsed" ]]; then
    echo "$parsed"
  else
    echo "$default_port"
  fi
}

expected_status_for() {
  case "$1" in
    feliz) echo "ENTREGUE" ;;
    falha|cancelamento) echo "CANCELADO" ;;
    *) echo "INDEFINIDO" ;;
  esac
}

extract_line_value() {
  local key="$1"
  local file="$2"
  awk -F= -v k="$key" '$1 == k { print $2 }' "$file" | tail -n1 | tr -d '[:space:]'
}

to_json_number_or_null() {
  local value="$1"
  if [[ -n "$value" && "$value" =~ ^[0-9]+$ ]]; then
    echo "$value"
  else
    echo "null"
  fi
}

to_json_bool() {
  local value="$1"
  if [[ "$value" == "1" ]]; then
    echo "true"
  else
    echo "false"
  fi
}

api_get_capture() {
  local path="$1"
  local raw
  raw="$(curl -sS -w $'\n%{http_code}' "$API_BASE$path")"
  API_LAST_STATUS="${raw##*$'\n'}"
  API_LAST_BODY="${raw%$'\n'*}"
}

api_get_to_file() {
  local path="$1"
  local file="$2"
  api_get_capture "$path"
  if [[ "$API_LAST_STATUS" -lt 200 || "$API_LAST_STATUS" -ge 300 ]]; then
    {
      echo "status=$API_LAST_STATUS"
      echo "$API_LAST_BODY"
    } > "$file"
    return 1
  fi

  if echo "$API_LAST_BODY" | jq . > "$file" 2>/dev/null; then
    return 0
  fi

  echo "$API_LAST_BODY" > "$file"
  return 0
}

capture_jobs_feed() {
  local file="$1"
  api_get_to_file "/api/operacao/replanejamento/jobs?limite=200" "$file"
}

list_job_ids_from_feed() {
  local file="$1"
  jq -r '.jobs[]?.jobId // empty' "$file" | sort -u
}

sanitize_file_component() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'
}

api_was_online=0
ui_was_online=0
cleanup_ran=0

cleanup() {
  if [[ "$cleanup_ran" == "1" ]]; then
    return 0
  fi
  cleanup_ran=1

  if [[ "${KEEP_RUNNING:-0}" == "1" ]]; then
    return 0
  fi

  if [[ "$api_was_online" == "0" ]]; then
    pkill -f "com.aguaviva.App -Dexec.args=api" >/dev/null 2>&1 || true
  fi

  if [[ "$ui_was_online" == "0" ]]; then
    pkill -f "python3 -m http.server ${UI_PORT:-4174}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd curl
require_cmd jq
require_cmd awk
require_cmd sort
require_cmd comm

API_BASE="${API_BASE:-http://localhost:8082}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/artifacts/poc/demo-m3-i0-$(date +%Y%m%d-%H%M%S)}"
KEEP_RUNNING="${KEEP_RUNNING:-0}"
UI_BASE="${UI_BASE:-http://localhost:4174}"
SOLVER_REBUILD="${SOLVER_REBUILD:-1}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
SEED_MONTES_CLAROS="${SEED_MONTES_CLAROS:-1}"

if [[ "$KEEP_RUNNING" != "0" && "$KEEP_RUNNING" != "1" ]]; then
  echo "KEEP_RUNNING invalido: $KEEP_RUNNING (use 0 ou 1)" >&2
  exit 1
fi

if [[ "$SEED_MONTES_CLAROS" != "0" && "$SEED_MONTES_CLAROS" != "1" ]]; then
  echo "SEED_MONTES_CLAROS invalido: $SEED_MONTES_CLAROS (use 0 ou 1)" >&2
  exit 1
fi

if [[ "$OUT_DIR" != /* ]]; then
  OUT_DIR="$ROOT_DIR/$OUT_DIR"
fi
mkdir -p "$OUT_DIR/scenarios"

SUMMARY_NDJSON="$OUT_DIR/summary.ndjson"
SUMMARY_JSON="$OUT_DIR/demo-summary.json"
SUMMARY_TXT="$OUT_DIR/demo-summary.txt"
: > "$SUMMARY_NDJSON"

API_PORT="$(extract_port_from_url "$API_BASE" "8082")"
UI_PORT="$(extract_port_from_url "$UI_BASE" "4174")"

if curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  api_was_online=1
fi
if curl -fsS "$UI_BASE" >/dev/null 2>&1; then
  ui_was_online=1
fi

echo "[demo-m3-i0] OUT_DIR=$OUT_DIR"
echo "[demo-m3-i0] API_BASE=$API_BASE"
echo "[demo-m3-i0] UI_BASE=$UI_BASE"

echo "[demo-m3-i0] Subindo ambiente de teste"
(
  cd "$ROOT_DIR"
  API_BASE="$API_BASE" \
  API_PORT="$API_PORT" \
  UI_BASE="$UI_BASE" \
  UI_PORT="$UI_PORT" \
  SOLVER_REBUILD="$SOLVER_REBUILD" \
  DB_CONTAINER="$DB_CONTAINER" \
  POSTGRES_DB="$DB_NAME" \
  POSTGRES_USER="$DB_USER" \
  POSTGRES_HOST="$DB_HOST" \
  POSTGRES_PORT="$DB_PORT" \
  POSTGRES_PASSWORD="$DB_PASSWORD" \
  "$START_ENV_SCRIPT"
) > "$OUT_DIR/start-test-env.log" 2>&1

echo "[demo-m3-i0] Resetando estado operacional"
(
  cd "$ROOT_DIR"
  DB_CONTAINER="$DB_CONTAINER" \
  DB_USER="$DB_USER" \
  DB_NAME="$DB_NAME" \
  SEED_MONTES_CLAROS="$SEED_MONTES_CLAROS" \
  "$RESET_SCRIPT"
) > "$OUT_DIR/reset-test-state.log" 2>&1

echo "[demo-m3-i0] Validando seed de Montes Claros"
(
  cd "$ROOT_DIR"
  DB_CONTAINER="$DB_CONTAINER" \
  DB_USER="$DB_USER" \
  DB_NAME="$DB_NAME" \
  DB_HOST="$DB_HOST" \
  DB_PORT="$DB_PORT" \
  DB_PASSWORD="$DB_PASSWORD" \
  "$CHECK_SEED_SCRIPT"
) > "$OUT_DIR/check-montes-claros-seed.log" 2>&1

echo "[demo-m3-i0] Capturando baseline de jobs"
capture_jobs_feed "$OUT_DIR/jobs-baseline.json"

overall_failed=0
SCENARIOS=(feliz falha cancelamento)

for scenario in "${SCENARIOS[@]}"; do
  scenario_dir="$OUT_DIR/scenarios/$scenario"
  mkdir -p "$scenario_dir"

  expected_status="$(expected_status_for "$scenario")"
  require_job_evidence=0
  if [[ "$scenario" == "falha" || "$scenario" == "cancelamento" ]]; then
    require_job_evidence=1
  fi

  echo "[demo-m3-i0] Executando cenario: $scenario"

  capture_jobs_feed "$scenario_dir/jobs-before.json"

  set +e
  (
    cd "$ROOT_DIR"
    API_BASE="$API_BASE" \
    DB_CONTAINER="$DB_CONTAINER" \
    DB_USER="$DB_USER" \
    DB_NAME="$DB_NAME" \
    DB_HOST="$DB_HOST" \
    DB_PORT="$DB_PORT" \
    DB_PASSWORD="$DB_PASSWORD" \
    "$RUN_CENARIO_SCRIPT" "$scenario"
  ) > "$scenario_dir/run-cenario.log" 2>&1
  run_exit="$?"
  set -e

  pedido_id="$(extract_line_value "pedido_id" "$scenario_dir/run-cenario.log")"
  rota_id="$(extract_line_value "rota_id" "$scenario_dir/run-cenario.log")"
  entrega_id="$(extract_line_value "entrega_id" "$scenario_dir/run-cenario.log")"

  timeline_status="INDEFINIDO"
  timeline_capture_ok=0
  execucao_capture_ok=0

  if [[ -n "$pedido_id" ]]; then
    if api_get_to_file "/api/pedidos/${pedido_id}/timeline" "$scenario_dir/timeline-final.json"; then
      timeline_capture_ok=1
      timeline_status="$(jq -r '.statusAtual // "INDEFINIDO"' "$scenario_dir/timeline-final.json")"
    fi
    if api_get_to_file "/api/pedidos/${pedido_id}/execucao" "$scenario_dir/execucao-final.json"; then
      execucao_capture_ok=1
    fi
  fi

  jobs_after_ok=0
  if capture_jobs_feed "$scenario_dir/jobs-after.json"; then
    jobs_after_ok=1
  fi

  new_jobs_file="$scenario_dir/new-job-ids.txt"
  if [[ "$jobs_after_ok" == "1" ]]; then
    comm -13 \
      <(list_job_ids_from_feed "$scenario_dir/jobs-before.json") \
      <(list_job_ids_from_feed "$scenario_dir/jobs-after.json") \
      > "$new_jobs_file"
  else
    : > "$new_jobs_file"
  fi

  new_job_count=0
  job_details_count=0
  if [[ -s "$new_jobs_file" ]]; then
    new_job_count="$(wc -l < "$new_jobs_file" | tr -d '[:space:]')"
    while IFS= read -r job_id; do
      [[ -z "$job_id" ]] && continue
      safe_job_id="$(sanitize_file_component "$job_id")"
      detail_file="$scenario_dir/job-detail-${safe_job_id}.json"
      if api_get_to_file "/api/operacao/replanejamento/jobs/${job_id}" "$detail_file"; then
        job_details_count=$((job_details_count + 1))
      fi
    done < "$new_jobs_file"
  fi

  status_ok=0
  if [[ "$run_exit" -eq 0 && "$timeline_capture_ok" == "1" && "$timeline_status" == "$expected_status" ]]; then
    status_ok=1
  fi

  job_evidence_ok=1
  if [[ "$require_job_evidence" == "1" && "$job_details_count" -eq 0 ]]; then
    job_evidence_ok=0
  fi

  scenario_pass=1
  fail_reason="ok"
  if [[ "$status_ok" != "1" ]]; then
    scenario_pass=0
    fail_reason="status_or_execution_failed"
  elif [[ "$job_evidence_ok" != "1" ]]; then
    scenario_pass=0
    fail_reason="missing_replanejamento_job_evidence"
  fi

  if [[ "$scenario_pass" == "0" ]]; then
    overall_failed=1
  fi

  jq -cn \
    --arg scenario "$scenario" \
    --arg expectedStatus "$expected_status" \
    --arg actualStatus "$timeline_status" \
    --arg evidenceDir "$scenario_dir" \
    --arg failReason "$fail_reason" \
    --argjson passed "$(to_json_bool "$scenario_pass")" \
    --argjson runExit "$run_exit" \
    --argjson pedidoId "$(to_json_number_or_null "$pedido_id")" \
    --argjson rotaId "$(to_json_number_or_null "$rota_id")" \
    --argjson entregaId "$(to_json_number_or_null "$entrega_id")" \
    --argjson timelineCaptured "$(to_json_bool "$timeline_capture_ok")" \
    --argjson execucaoCaptured "$(to_json_bool "$execucao_capture_ok")" \
    --argjson jobsAfterCaptured "$(to_json_bool "$jobs_after_ok")" \
    --argjson requiredJobEvidence "$(to_json_bool "$require_job_evidence")" \
    --argjson newJobCount "${new_job_count:-0}" \
    --argjson jobDetailsCount "${job_details_count:-0}" \
    '{
      scenario: $scenario,
      passed: $passed,
      expectedStatus: $expectedStatus,
      actualStatus: $actualStatus,
      runExit: $runExit,
      pedidoId: $pedidoId,
      rotaId: $rotaId,
      entregaId: $entregaId,
      timelineCaptured: $timelineCaptured,
      execucaoCaptured: $execucaoCaptured,
      jobsAfterCaptured: $jobsAfterCaptured,
      requiredJobEvidence: $requiredJobEvidence,
      newJobCount: $newJobCount,
      jobDetailsCount: $jobDetailsCount,
      failReason: $failReason,
      evidenceDir: $evidenceDir
    }' >> "$SUMMARY_NDJSON"
done

jq -s \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --arg outDir "$OUT_DIR" \
  --argjson keepRunning "$(to_json_bool "$KEEP_RUNNING")" \
  '{
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    outDir: $outDir,
    keepRunning: $keepRunning,
    approved: (all(.[]; .passed == true)),
    scenarios: .
  }' "$SUMMARY_NDJSON" > "$SUMMARY_JSON"

{
  echo "Demo M3-I0 Summary"
  echo "generated_at=$(jq -r '.generatedAt' "$SUMMARY_JSON")"
  echo "api_base=$(jq -r '.apiBase' "$SUMMARY_JSON")"
  echo "out_dir=$(jq -r '.outDir' "$SUMMARY_JSON")"
  echo "approved=$(jq -r '.approved' "$SUMMARY_JSON")"
  echo
  jq -r '.scenarios[] | "- \(.scenario): passed=\(.passed) expected=\(.expectedStatus) actual=\(.actualStatus) runExit=\(.runExit) pedidoId=\(.pedidoId // "null") newJobs=\(.newJobCount) jobDetails=\(.jobDetailsCount) failReason=\(.failReason)"' "$SUMMARY_JSON"
} > "$SUMMARY_TXT"

echo "[demo-m3-i0] Summary JSON: $SUMMARY_JSON"
echo "[demo-m3-i0] Summary TXT:  $SUMMARY_TXT"

if [[ "$overall_failed" -eq 1 ]]; then
  echo "[demo-m3-i0] FALHA: ao menos um cenario nao aprovou." >&2
  exit 1
fi

echo "[demo-m3-i0] OK: todos os cenarios aprovados."
