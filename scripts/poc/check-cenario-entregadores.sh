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

ENTREGADORES_VARIANTES="${ENTREGADORES_VARIANTES:-1,2,4}"
CAPACIDADE_IGUAL="${CAPACIDADE_IGUAL:-1}"
CAPACIDADE_DIFERENTE="${CAPACIDADE_DIFERENTE:-2}"
PEDIDOS_EXTRA="${PEDIDOS_EXTRA:-1}"
WAIT_ATTEMPTS="${WAIT_ATTEMPTS:-40}"
WAIT_SECONDS="${WAIT_SECONDS:-1}"
SUMMARY_FILE="${SUMMARY_FILE:-$ROOT_DIR/artifacts/poc/entregadores-summary.json}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/check-cenario-entregadores.sh

Variaveis opcionais:
  API_BASE=http://localhost:8082
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres
  ENTREGADORES_VARIANTES=1,2,4
  CAPACIDADE_IGUAL=1
  CAPACIDADE_DIFERENTE=2
  PEDIDOS_EXTRA=1
  WAIT_ATTEMPTS=40
  WAIT_SECONDS=1
  SUMMARY_FILE=artifacts/poc/entregadores-summary.json
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

psql_exec() {
  local sql="$1"
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASSWORD" psql \
      -h "$DB_HOST" \
      -p "$DB_PORT" \
      -U "$DB_USER" \
      -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 \
      -q \
      -c "$sql" >/dev/null
    return
  fi

  require_cmd docker
  docker exec -i "$DB_CONTAINER" \
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

parse_variantes() {
  local raw="$1"
  IFS=',' read -r -a VARIANTES_RAW <<< "$raw"
  VARIANTES=()
  local v
  for v in "${VARIANTES_RAW[@]}"; do
    v="$(echo "$v" | tr -d '[:space:]')"
    if [[ -z "$v" ]]; then
      continue
    fi
    if ! is_positive_int "$v"; then
      echo "ENTREGADORES_VARIANTES invalido: '$v'" >&2
      exit 1
    fi
    VARIANTES+=("$v")
  done

  if [[ "${#VARIANTES[@]}" -lt 3 ]]; then
    echo "ENTREGADORES_VARIANTES deve conter pelo menos 3 valores (ex.: 1,2,4)." >&2
    exit 1
  fi

  IFS=$'\n' VARIANTES=( $(printf '%s\n' "${VARIANTES[@]}" | awk 'NF > 0' | sort -n | uniq) )
  unset IFS

  local has_1=0
  local has_2=0
  local maior=0
  for v in "${VARIANTES[@]}"; do
    if [[ "$v" == "1" ]]; then
      has_1=1
    fi
    if [[ "$v" == "2" ]]; then
      has_2=1
    fi
    if [[ "$v" -gt "$maior" ]]; then
      maior="$v"
    fi
  done

  if [[ "$has_1" -ne 1 || "$has_2" -ne 1 || "$maior" -le 2 ]]; then
    echo "ENTREGADORES_VARIANTES deve conter 1, 2 e N (N > 2). Atual: ${VARIANTES[*]}" >&2
    exit 1
  fi
}

create_order() {
  local atendente_id="$1"
  local telefone="$2"
  local case_id="$3"
  local idx="$4"

  local payload
  payload="$(jq -n \
    --arg externalCallId "entregadores-${case_id}-${idx}-$(date +%s)-${RANDOM}" \
    --arg telefone "$telefone" \
    --argjson quantidadeGaloes 1 \
    --argjson atendenteId "$atendente_id" \
    '{externalCallId:$externalCallId, telefone:$telefone, quantidadeGaloes:$quantidadeGaloes, atendenteId:$atendenteId, metodoPagamento:"PIX"}')"

  api_post_capture "/api/atendimento/pedidos" "$payload"
  if [[ "$API_LAST_STATUS" != "200" ]]; then
    echo "Falha ao criar pedido ($case_id idx=$idx telefone=$telefone): HTTP $API_LAST_STATUS" >&2
    echo "$API_LAST_BODY" | jq . >&2 || echo "$API_LAST_BODY" >&2
    return 1
  fi
}

wait_case_settle() {
  local expected_total="$1"
  local attempt
  local pedidos_total="0"
  local dispatch_due="1"

  for attempt in $(seq 1 "$WAIT_ATTEMPTS"); do
    pedidos_total="$(psql_query "SELECT COUNT(*) FROM pedidos;" | extract_single_value)"
    dispatch_due="$(psql_query "SELECT COUNT(*) FROM dispatch_events WHERE status::text='PENDENTE' AND available_em <= CURRENT_TIMESTAMP;" | extract_single_value)"

    if [[ "$pedidos_total" == "$expected_total" && "$dispatch_due" == "0" ]]; then
      return 0
    fi

    if [[ "$attempt" -lt "$WAIT_ATTEMPTS" ]]; then
      sleep "$WAIT_SECONDS"
    fi
  done

  echo "Timeout aguardando estabilizacao (pedidos_total=$pedidos_total expected=$expected_total dispatch_due=$dispatch_due)." >&2
  return 1
}

collect_metrics_json() {
  local case_id="$1"
  local entregadores="$2"
  local capacidade="$3"
  local expected_total="$4"

  local capacidade_cfg pedidos_total pendentes confirmados rotas_planejadas entregadores_com_rota max_carga entregadores_ativos

  capacidade_cfg="$(psql_query "SELECT COALESCE(MAX(valor::int), 0) FROM configuracoes WHERE chave='capacidade_veiculo';" | extract_single_value)"
  pedidos_total="$(psql_query "SELECT COUNT(*) FROM pedidos;" | extract_single_value)"
  pendentes="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='PENDENTE';" | extract_single_value)"
  confirmados="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='CONFIRMADO';" | extract_single_value)"
  rotas_planejadas="$(psql_query "SELECT COUNT(*) FROM rotas WHERE status::text='PLANEJADA';" | extract_single_value)"
  entregadores_com_rota="$(psql_query "SELECT COUNT(DISTINCT entregador_id) FROM rotas WHERE status::text='PLANEJADA';" | extract_single_value)"
  max_carga="$(psql_query "WITH cargas AS (
    SELECT r.id, COUNT(*) AS carga
    FROM entregas e
    JOIN rotas r ON r.id = e.rota_id
    WHERE e.status::text IN ('PENDENTE','EM_EXECUCAO')
    GROUP BY r.id
  )
  SELECT COALESCE(MAX(carga), 0) FROM cargas;" | extract_single_value)"
  entregadores_ativos="$(psql_query "SELECT COUNT(*) FROM users WHERE papel='entregador' AND ativo = true;" | extract_single_value)"

  jq -n \
    --arg caseId "$case_id" \
    --argjson entregadores "$entregadores" \
    --argjson capacidade "$capacidade" \
    --argjson expectedTotal "$expected_total" \
    --argjson capacidadeConfigurada "$capacidade_cfg" \
    --argjson pedidosTotal "$pedidos_total" \
    --argjson pendentes "$pendentes" \
    --argjson confirmados "$confirmados" \
    --argjson rotasPlanejadas "$rotas_planejadas" \
    --argjson entregadoresComRota "$entregadores_com_rota" \
    --argjson maxCargaAtiva "$max_carga" \
    --argjson entregadoresAtivos "$entregadores_ativos" \
    '{
      caseId: $caseId,
      entregadores: $entregadores,
      capacidade: $capacidade,
      expectedTotal: $expectedTotal,
      capacidadeConfigurada: $capacidadeConfigurada,
      pedidosTotal: $pedidosTotal,
      pendentes: $pendentes,
      confirmados: $confirmados,
      rotasPlanejadas: $rotasPlanejadas,
      entregadoresComRota: $entregadoresComRota,
      maxCargaAtiva: $maxCargaAtiva,
      entregadoresAtivos: $entregadoresAtivos
    }'
}

run_case() {
  local entregadores="$1"
  local capacidade="$2"
  local case_id="$3"
  local expected_total
  local atendente_id
  local phones

  expected_total=$((entregadores + PEDIDOS_EXTRA))

  DB_CONTAINER="$DB_CONTAINER" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
    NUM_ENTREGADORES_ATIVOS="$entregadores" SEED_MONTES_CLAROS=1 \
    "$ROOT_DIR/scripts/poc/reset-test-state.sh" >/dev/null

  psql_exec "UPDATE configuracoes SET valor='PADRAO' WHERE chave='frota_perfil_ativo';"
  psql_exec "UPDATE configuracoes SET valor='${capacidade}' WHERE chave='capacidade_veiculo';"

  atendente_id="$(psql_query "SELECT id FROM users WHERE email='base.atendente@aguaviva.local' LIMIT 1;" | extract_single_value)"
  if [[ -z "$atendente_id" ]]; then
    echo "Atendente base nao encontrado no cenario $case_id" >&2
    return 1
  fi

  phones="$(psql_query "SELECT telefone FROM clientes ORDER BY id LIMIT ${expected_total};")"
  if [[ "$(echo "$phones" | awk 'NF > 0' | wc -l | tr -d '[:space:]')" -lt "$expected_total" ]]; then
    echo "Clientes insuficientes para montar cenario $case_id (esperado=${expected_total})." >&2
    return 1
  fi

  local idx=0
  local phone
  while IFS= read -r phone; do
    if [[ -z "$phone" ]]; then
      continue
    fi
    idx=$((idx + 1))
    create_order "$atendente_id" "$phone" "$case_id" "$idx"
  done <<< "$phones"

  wait_case_settle "$expected_total"
  collect_metrics_json "$case_id" "$entregadores" "$capacidade" "$expected_total"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd curl
require_cmd jq
require_cmd awk
parse_variantes "$ENTREGADORES_VARIANTES"

if ! is_positive_int "$CAPACIDADE_IGUAL"; then
  echo "CAPACIDADE_IGUAL invalida: $CAPACIDADE_IGUAL" >&2
  exit 1
fi
if ! is_positive_int "$CAPACIDADE_DIFERENTE"; then
  echo "CAPACIDADE_DIFERENTE invalida: $CAPACIDADE_DIFERENTE" >&2
  exit 1
fi
if ! is_positive_int "$PEDIDOS_EXTRA"; then
  echo "PEDIDOS_EXTRA invalido: $PEDIDOS_EXTRA" >&2
  exit 1
fi
if ! is_positive_int "$WAIT_ATTEMPTS"; then
  echo "WAIT_ATTEMPTS invalido: $WAIT_ATTEMPTS" >&2
  exit 1
fi
if ! is_positive_int "$WAIT_SECONDS"; then
  echo "WAIT_SECONDS invalido: $WAIT_SECONDS" >&2
  exit 1
fi

if ! curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "API offline em $API_BASE" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

cases_ndjson="$tmp_dir/cases.ndjson"
comparativos_ndjson="$tmp_dir/comparativos.ndjson"
: > "$cases_ndjson"
: > "$comparativos_ndjson"

prev_low_confirmados=""
prev_low_pendentes=""
prev_entregadores=""

for entregadores in "${VARIANTES[@]}"; do
  echo "[check-cenario-entregadores] executando entregadores=${entregadores} capacidade=${CAPACIDADE_IGUAL}"
  low_json="$(run_case "$entregadores" "$CAPACIDADE_IGUAL" "n${entregadores}-cap${CAPACIDADE_IGUAL}")"

  echo "[check-cenario-entregadores] executando entregadores=${entregadores} capacidade=${CAPACIDADE_DIFERENTE}"
  high_json="$(run_case "$entregadores" "$CAPACIDADE_DIFERENTE" "n${entregadores}-cap${CAPACIDADE_DIFERENTE}")"

  low_ok=true
  high_ok=true
  cmp_ok=true

  low_viol='[]'
  high_viol='[]'
  cmp_viol='[]'

  low_total="$(jq -r '.pedidosTotal' <<< "$low_json")"
  low_expected="$(jq -r '.expectedTotal' <<< "$low_json")"
  low_pend="$(jq -r '.pendentes' <<< "$low_json")"
  low_conf="$(jq -r '.confirmados' <<< "$low_json")"
  low_max_carga="$(jq -r '.maxCargaAtiva' <<< "$low_json")"
  low_cap_cfg="$(jq -r '.capacidadeConfigurada' <<< "$low_json")"
  low_entregadores_ativos="$(jq -r '.entregadoresAtivos' <<< "$low_json")"

  high_total="$(jq -r '.pedidosTotal' <<< "$high_json")"
  high_expected="$(jq -r '.expectedTotal' <<< "$high_json")"
  high_pend="$(jq -r '.pendentes' <<< "$high_json")"
  high_conf="$(jq -r '.confirmados' <<< "$high_json")"
  high_max_carga="$(jq -r '.maxCargaAtiva' <<< "$high_json")"
  high_cap_cfg="$(jq -r '.capacidadeConfigurada' <<< "$high_json")"
  high_entregadores_ativos="$(jq -r '.entregadoresAtivos' <<< "$high_json")"

  if [[ "$low_total" -ne "$low_expected" ]]; then
    low_ok=false
    low_viol="$(jq -cn --argjson arr "$low_viol" --arg msg "pedidosTotal baixo diverge do esperado (${low_total} != ${low_expected})" '$arr + [$msg]')"
  fi
  if [[ $((low_conf + low_pend)) -ne "$low_expected" ]]; then
    low_ok=false
    low_viol="$(jq -cn --argjson arr "$low_viol" --arg msg "confirmados+pendentes baixo diverge do esperado" '$arr + [$msg]')"
  fi
  if [[ "$low_max_carga" -gt "$CAPACIDADE_IGUAL" ]]; then
    low_ok=false
    low_viol="$(jq -cn --argjson arr "$low_viol" --arg msg "maxCargaAtiva baixo excede capacidade (${low_max_carga} > ${CAPACIDADE_IGUAL})" '$arr + [$msg]')"
  fi
  if [[ "$low_conf" -gt $((entregadores * CAPACIDADE_IGUAL)) ]]; then
    low_ok=false
    low_viol="$(jq -cn --argjson arr "$low_viol" --arg msg "confirmados baixo acima da capacidade teorica" '$arr + [$msg]')"
  fi
  if [[ "$low_cap_cfg" -ne "$CAPACIDADE_IGUAL" ]]; then
    low_ok=false
    low_viol="$(jq -cn --argjson arr "$low_viol" --arg msg "configuracao de capacidade baixa nao aplicada" '$arr + [$msg]')"
  fi
  if [[ "$low_entregadores_ativos" -ne "$entregadores" ]]; then
    low_ok=false
    low_viol="$(jq -cn --argjson arr "$low_viol" --arg msg "entregadores ativos baixo divergem (${low_entregadores_ativos} != ${entregadores})" '$arr + [$msg]')"
  fi

  if [[ "$high_total" -ne "$high_expected" ]]; then
    high_ok=false
    high_viol="$(jq -cn --argjson arr "$high_viol" --arg msg "pedidosTotal alto diverge do esperado (${high_total} != ${high_expected})" '$arr + [$msg]')"
  fi
  if [[ $((high_conf + high_pend)) -ne "$high_expected" ]]; then
    high_ok=false
    high_viol="$(jq -cn --argjson arr "$high_viol" --arg msg "confirmados+pendentes alto diverge do esperado" '$arr + [$msg]')"
  fi
  if [[ "$high_max_carga" -gt "$CAPACIDADE_DIFERENTE" ]]; then
    high_ok=false
    high_viol="$(jq -cn --argjson arr "$high_viol" --arg msg "maxCargaAtiva alto excede capacidade (${high_max_carga} > ${CAPACIDADE_DIFERENTE})" '$arr + [$msg]')"
  fi
  if [[ "$high_conf" -gt $((entregadores * CAPACIDADE_DIFERENTE)) ]]; then
    high_ok=false
    high_viol="$(jq -cn --argjson arr "$high_viol" --arg msg "confirmados alto acima da capacidade teorica" '$arr + [$msg]')"
  fi
  if [[ "$high_cap_cfg" -ne "$CAPACIDADE_DIFERENTE" ]]; then
    high_ok=false
    high_viol="$(jq -cn --argjson arr "$high_viol" --arg msg "configuracao de capacidade alta nao aplicada" '$arr + [$msg]')"
  fi
  if [[ "$high_entregadores_ativos" -ne "$entregadores" ]]; then
    high_ok=false
    high_viol="$(jq -cn --argjson arr "$high_viol" --arg msg "entregadores ativos alto divergem (${high_entregadores_ativos} != ${entregadores})" '$arr + [$msg]')"
  fi

  if [[ "$high_conf" -lt "$low_conf" ]]; then
    cmp_ok=false
    cmp_viol="$(jq -cn --argjson arr "$cmp_viol" --arg msg "capacidade maior reduziu confirmados (${high_conf} < ${low_conf})" '$arr + [$msg]')"
  fi
  if [[ "$high_pend" -gt "$low_pend" ]]; then
    cmp_ok=false
    cmp_viol="$(jq -cn --argjson arr "$cmp_viol" --arg msg "capacidade maior aumentou pendentes (${high_pend} > ${low_pend})" '$arr + [$msg]')"
  fi

  if [[ -n "$prev_entregadores" ]]; then
    if [[ "$low_conf" -lt "$prev_low_confirmados" ]]; then
      cmp_ok=false
      cmp_viol="$(jq -cn --argjson arr "$cmp_viol" --arg msg "escalabilidade regressiva em confirmados: n${entregadores} (${low_conf}) < n${prev_entregadores} (${prev_low_confirmados})" '$arr + [$msg]')"
    fi
    if [[ "$low_pend" -gt "$prev_low_pendentes" ]]; then
      cmp_ok=false
      cmp_viol="$(jq -cn --argjson arr "$cmp_viol" --arg msg "escalabilidade regressiva em pendentes: n${entregadores} (${low_pend}) > n${prev_entregadores} (${prev_low_pendentes})" '$arr + [$msg]')"
    fi
  fi

  prev_entregadores="$entregadores"
  prev_low_confirmados="$low_conf"
  prev_low_pendentes="$low_pend"

  low_case="$(jq -cn --argjson base "$low_json" --argjson ok "$low_ok" --argjson viol "$low_viol" --arg tipo "capacidade_igual" '$base + {ok:$ok, violacoes:$viol, tipo:$tipo}')"
  high_case="$(jq -cn --argjson base "$high_json" --argjson ok "$high_ok" --argjson viol "$high_viol" --arg tipo "capacidade_diferente" '$base + {ok:$ok, violacoes:$viol, tipo:$tipo}')"
  cmp_case="$(jq -cn --arg id "n${entregadores}-comparativo" --argjson entregadores "$entregadores" --argjson ok "$cmp_ok" --argjson violacoes "$cmp_viol" '{id:$id, entregadores:$entregadores, ok:$ok, violacoes:$violacoes}')"

  echo "$low_case" >> "$cases_ndjson"
  echo "$high_case" >> "$cases_ndjson"
  echo "$cmp_case" >> "$comparativos_ndjson"
done

cases_json="$(jq -s '.' "$cases_ndjson")"
comparativos_json="$(jq -s '.' "$comparativos_ndjson")"

cases_total="$(jq -r 'length' <<< "$cases_json")"
cases_ok="$(jq -r '[.[] | select(.ok == true)] | length' <<< "$cases_json")"
comparativos_total="$(jq -r 'length' <<< "$comparativos_json")"
comparativos_ok="$(jq -r '[.[] | select(.ok == true)] | length' <<< "$comparativos_json")"
violacoes_cases="$(jq -r '[.[] | (.violacoes | length)] | add // 0' <<< "$cases_json")"
violacoes_comparativos="$(jq -r '[.[] | (.violacoes | length)] | add // 0' <<< "$comparativos_json")"

ok=true
if [[ "$cases_ok" -ne "$cases_total" || "$comparativos_ok" -ne "$comparativos_total" ]]; then
  ok=false
fi

mkdir -p "$(dirname "$SUMMARY_FILE")"

jq -n \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --argjson variantes "$(printf '%s\n' "${VARIANTES[@]}" | jq -R . | jq -s 'map(tonumber)')" \
  --argjson capacidadeIgual "$CAPACIDADE_IGUAL" \
  --argjson capacidadeDiferente "$CAPACIDADE_DIFERENTE" \
  --argjson pedidosExtra "$PEDIDOS_EXTRA" \
  --argjson cases "$cases_json" \
  --argjson comparativos "$comparativos_json" \
  --argjson casesTotal "$cases_total" \
  --argjson casesOk "$cases_ok" \
  --argjson comparativosTotal "$comparativos_total" \
  --argjson comparativosOk "$comparativos_ok" \
  --argjson violacoesCases "$violacoes_cases" \
  --argjson violacoesComparativos "$violacoes_comparativos" \
  --argjson ok "$ok" \
  '{
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    configuracao: {
      entregadoresVariantes: $variantes,
      capacidadeIgual: $capacidadeIgual,
      capacidadeDiferente: $capacidadeDiferente,
      pedidosExtra: $pedidosExtra
    },
    totals: {
      casesTotal: $casesTotal,
      casesOk: $casesOk,
      comparativosTotal: $comparativosTotal,
      comparativosOk: $comparativosOk,
      violacoesCases: $violacoesCases,
      violacoesComparativos: $violacoesComparativos
    },
    cases: $cases,
    comparativos: $comparativos,
    ok: $ok
  }' > "$SUMMARY_FILE"

echo "[check-cenario-entregadores] summary=$SUMMARY_FILE"
echo "[check-cenario-entregadores] cases_ok=${cases_ok}/${cases_total} comparativos_ok=${comparativos_ok}/${comparativos_total}"

if [[ "$ok" != "true" ]]; then
  echo "[check-cenario-entregadores] FALHA: violacoes encontradas." >&2
  jq -r '.cases[] | select(.ok == false) | "- [case] \(.caseId): \(.violacoes | join("; "))"' "$SUMMARY_FILE" >&2
  jq -r '.comparativos[] | select(.ok == false) | "- [comparativo] \(.id): \(.violacoes | join("; "))"' "$SUMMARY_FILE" >&2
  exit 1
fi

echo "[check-cenario-entregadores] OK: cenario oficial 1/2/N validado."
