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

NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-1}"
CAPACIDADE_MOTO="${CAPACIDADE_MOTO:-2}"
CAPACIDADE_CARRO="${CAPACIDADE_CARRO:-5}"
CAPACIDADE_PADRAO_FALLBACK="${CAPACIDADE_PADRAO_FALLBACK:-99}"
PEDIDOS_TOTAIS="${PEDIDOS_TOTAIS:-6}"
WAIT_ATTEMPTS="${WAIT_ATTEMPTS:-40}"
WAIT_SECONDS="${WAIT_SECONDS:-1}"
SUMMARY_FILE="${SUMMARY_FILE:-$ROOT_DIR/artifacts/poc/frota-perfil-summary.json}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/check-frota-perfil.sh

Variaveis opcionais:
  API_BASE=http://localhost:8082
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres
  NUM_ENTREGADORES_ATIVOS=1
  CAPACIDADE_MOTO=2
  CAPACIDADE_CARRO=5
  CAPACIDADE_PADRAO_FALLBACK=99
  PEDIDOS_TOTAIS=6
  WAIT_ATTEMPTS=40
  WAIT_SECONDS=1
  SUMMARY_FILE=artifacts/poc/frota-perfil-summary.json
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
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
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

create_order() {
  local atendente_id="$1"
  local telefone="$2"
  local case_id="$3"
  local idx="$4"

  local payload
  payload="$(jq -n \
    --arg externalCallId "frota-${case_id}-${idx}-$(date +%s)-${RANDOM}" \
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

run_profile_case() {
  local perfil="$1"
  local capacidade_alvo="$2"
  local case_id="$3"
  local atendente_id
  local phones

  DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
    NUM_ENTREGADORES_ATIVOS="$NUM_ENTREGADORES_ATIVOS" SEED_MONTES_CLAROS=1 \
    "$ROOT_DIR/scripts/poc/reset-test-state.sh" >/dev/null

  psql_exec "UPDATE configuracoes SET valor='${CAPACIDADE_PADRAO_FALLBACK}' WHERE chave='capacidade_veiculo';"
  psql_exec "UPDATE configuracoes SET valor='${CAPACIDADE_MOTO}' WHERE chave='capacidade_frota_moto';"
  psql_exec "UPDATE configuracoes SET valor='${CAPACIDADE_CARRO}' WHERE chave='capacidade_frota_carro';"
  psql_exec "UPDATE configuracoes SET valor='${perfil}' WHERE chave='frota_perfil_ativo';"

  atendente_id="$(psql_query "SELECT id FROM users WHERE email='base.atendente@aguaviva.local' LIMIT 1;" | extract_single_value)"
  if [[ -z "$atendente_id" ]]; then
    echo "Atendente base nao encontrado para perfil ${perfil}" >&2
    return 1
  fi

  phones="$(psql_query "SELECT telefone FROM clientes ORDER BY id LIMIT ${PEDIDOS_TOTAIS};")"
  if [[ "$(echo "$phones" | awk 'NF > 0' | wc -l | tr -d '[:space:]')" -lt "$PEDIDOS_TOTAIS" ]]; then
    echo "Clientes insuficientes para o caso ${case_id}" >&2
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

  wait_case_settle "$PEDIDOS_TOTAIS"

  local pedidos_total pendentes confirmados max_carga capacidade_cfg perfil_cfg cap_moto_cfg cap_carro_cfg entregadores_ativos
  pedidos_total="$(psql_query "SELECT COUNT(*) FROM pedidos;" | extract_single_value)"
  pendentes="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='PENDENTE';" | extract_single_value)"
  confirmados="$(psql_query "SELECT COUNT(*) FROM pedidos WHERE status::text='CONFIRMADO';" | extract_single_value)"
  max_carga="$(psql_query "WITH cargas AS (
    SELECT r.id, COUNT(*) AS carga
    FROM entregas e
    JOIN rotas r ON r.id = e.rota_id
    WHERE e.status::text IN ('PENDENTE','EM_EXECUCAO')
    GROUP BY r.id
  )
  SELECT COALESCE(MAX(carga), 0) FROM cargas;" | extract_single_value)"

  capacidade_cfg="$(psql_query "SELECT valor FROM configuracoes WHERE chave='capacidade_veiculo';" | extract_single_value)"
  perfil_cfg="$(psql_query "SELECT valor FROM configuracoes WHERE chave='frota_perfil_ativo';" | extract_single_value)"
  cap_moto_cfg="$(psql_query "SELECT valor FROM configuracoes WHERE chave='capacidade_frota_moto';" | extract_single_value)"
  cap_carro_cfg="$(psql_query "SELECT valor FROM configuracoes WHERE chave='capacidade_frota_carro';" | extract_single_value)"
  entregadores_ativos="$(psql_query "SELECT COUNT(*) FROM users WHERE papel='entregador' AND ativo=true;" | extract_single_value)"

  local ok=true
  local viol='[]'

  if [[ "$pedidos_total" -ne "$PEDIDOS_TOTAIS" ]]; then
    ok=false
    viol="$(jq -cn --argjson arr "$viol" --arg msg "pedidosTotal diverge do esperado (${pedidos_total} != ${PEDIDOS_TOTAIS})" '$arr + [$msg]')"
  fi
  if [[ $((confirmados + pendentes)) -ne "$PEDIDOS_TOTAIS" ]]; then
    ok=false
    viol="$(jq -cn --argjson arr "$viol" --arg msg "confirmados+pendentes diverge do esperado" '$arr + [$msg]')"
  fi
  if [[ "$confirmados" -gt $((NUM_ENTREGADORES_ATIVOS * capacidade_alvo)) ]]; then
    ok=false
    viol="$(jq -cn --argjson arr "$viol" --arg msg "confirmados acima do limite teorico (${confirmados} > $((NUM_ENTREGADORES_ATIVOS * capacidade_alvo)))" '$arr + [$msg]')"
  fi
  if [[ "$max_carga" -gt "$capacidade_alvo" ]]; then
    ok=false
    viol="$(jq -cn --argjson arr "$viol" --arg msg "maxCargaAtiva excede capacidade alvo (${max_carga} > ${capacidade_alvo})" '$arr + [$msg]')"
  fi
  if [[ "$entregadores_ativos" -ne "$NUM_ENTREGADORES_ATIVOS" ]]; then
    ok=false
    viol="$(jq -cn --argjson arr "$viol" --arg msg "entregadores ativos divergentes (${entregadores_ativos} != ${NUM_ENTREGADORES_ATIVOS})" '$arr + [$msg]')"
  fi

  if [[ "$PEDIDOS_TOTAIS" -gt $((NUM_ENTREGADORES_ATIVOS * capacidade_alvo)) ]]; then
    if [[ "$pendentes" -lt 1 ]]; then
      ok=false
      viol="$(jq -cn --argjson arr "$viol" --arg msg "esperado backlog pendente para capacidade alvo" '$arr + [$msg]')"
    fi
  else
    if [[ "$pendentes" -ne 0 ]]; then
      ok=false
      viol="$(jq -cn --argjson arr "$viol" --arg msg "nao era esperado backlog pendente" '$arr + [$msg]')"
    fi
  fi

  jq -n \
    --arg caseId "$case_id" \
    --arg perfil "$perfil" \
    --argjson capacidadeAlvo "$capacidade_alvo" \
    --argjson pedidosTotal "$pedidos_total" \
    --argjson pendentes "$pendentes" \
    --argjson confirmados "$confirmados" \
    --argjson maxCargaAtiva "$max_carga" \
    --arg capacidadeConfigurada "$capacidade_cfg" \
    --arg perfilConfigurado "$perfil_cfg" \
    --arg capacidadeMotoConfigurada "$cap_moto_cfg" \
    --arg capacidadeCarroConfigurada "$cap_carro_cfg" \
    --argjson entregadoresAtivos "$entregadores_ativos" \
    --argjson ok "$ok" \
    --argjson violacoes "$viol" \
    '{
      caseId: $caseId,
      perfil: $perfil,
      capacidadeAlvo: $capacidadeAlvo,
      pedidosTotal: $pedidosTotal,
      pendentes: $pendentes,
      confirmados: $confirmados,
      maxCargaAtiva: $maxCargaAtiva,
      configuracao: {
        capacidadeVeiculo: $capacidadeConfigurada,
        frotaPerfilAtivo: $perfilConfigurado,
        capacidadeMoto: $capacidadeMotoConfigurada,
        capacidadeCarro: $capacidadeCarroConfigurada
      },
      entregadoresAtivos: $entregadoresAtivos,
      ok: $ok,
      violacoes: $violacoes
    }'
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd curl
require_cmd jq
require_cmd awk

for n in "$NUM_ENTREGADORES_ATIVOS" "$CAPACIDADE_MOTO" "$CAPACIDADE_CARRO" "$CAPACIDADE_PADRAO_FALLBACK" "$PEDIDOS_TOTAIS" "$WAIT_ATTEMPTS" "$WAIT_SECONDS"; do
  if ! is_positive_int "$n"; then
    echo "Parametro numerico invalido: $n" >&2
    exit 1
  fi
done

if [[ "$CAPACIDADE_CARRO" -lt "$CAPACIDADE_MOTO" ]]; then
  echo "CAPACIDADE_CARRO deve ser >= CAPACIDADE_MOTO para o comparativo." >&2
  exit 1
fi
if [[ "$PEDIDOS_TOTAIS" -le "$CAPACIDADE_MOTO" ]]; then
  echo "PEDIDOS_TOTAIS deve ser > CAPACIDADE_MOTO para exercitar backlog no perfil MOTO." >&2
  exit 1
fi

if ! curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "API offline em $API_BASE" >&2
  exit 1
fi

echo "[check-frota-perfil] executando perfil=MOTO"
moto_case="$(run_profile_case "MOTO" "$CAPACIDADE_MOTO" "perfil-moto")"
echo "[check-frota-perfil] executando perfil=CARRO"
carro_case="$(run_profile_case "CARRO" "$CAPACIDADE_CARRO" "perfil-carro")"

cmp_ok=true
cmp_viol='[]'

moto_conf="$(jq -r '.confirmados' <<< "$moto_case")"
carro_conf="$(jq -r '.confirmados' <<< "$carro_case")"
moto_pend="$(jq -r '.pendentes' <<< "$moto_case")"
carro_pend="$(jq -r '.pendentes' <<< "$carro_case")"

if [[ "$carro_conf" -lt "$moto_conf" ]]; then
  cmp_ok=false
  cmp_viol="$(jq -cn --argjson arr "$cmp_viol" --arg msg "perfil CARRO reduziu confirmados (${carro_conf} < ${moto_conf})" '$arr + [$msg]')"
fi
if [[ "$carro_pend" -gt "$moto_pend" ]]; then
  cmp_ok=false
  cmp_viol="$(jq -cn --argjson arr "$cmp_viol" --arg msg "perfil CARRO aumentou pendentes (${carro_pend} > ${moto_pend})" '$arr + [$msg]')"
fi
if [[ "$CAPACIDADE_CARRO" -gt "$CAPACIDADE_MOTO" && "$carro_conf" -le "$moto_conf" ]]; then
  cmp_ok=false
  cmp_viol="$(jq -cn --argjson arr "$cmp_viol" --arg msg "perfil CARRO nao melhorou throughput apesar de capacidade maior" '$arr + [$msg]')"
fi

ok=true
if [[ "$(jq -r '.ok' <<< "$moto_case")" != "true" || "$(jq -r '.ok' <<< "$carro_case")" != "true" || "$cmp_ok" != "true" ]]; then
  ok=false
fi

mkdir -p "$(dirname "$SUMMARY_FILE")"

jq -n \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --argjson numEntregadores "$NUM_ENTREGADORES_ATIVOS" \
  --argjson capacidadeMoto "$CAPACIDADE_MOTO" \
  --argjson capacidadeCarro "$CAPACIDADE_CARRO" \
  --argjson capacidadePadraoFallback "$CAPACIDADE_PADRAO_FALLBACK" \
  --argjson pedidosTotais "$PEDIDOS_TOTAIS" \
  --argjson moto "$moto_case" \
  --argjson carro "$carro_case" \
  --argjson comparativoOk "$cmp_ok" \
  --argjson comparativoViolacoes "$cmp_viol" \
  --argjson ok "$ok" \
  '{
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    configuracao: {
      numEntregadoresAtivos: $numEntregadores,
      capacidadeMoto: $capacidadeMoto,
      capacidadeCarro: $capacidadeCarro,
      capacidadePadraoFallback: $capacidadePadraoFallback,
      pedidosTotais: $pedidosTotais
    },
    cases: [$moto, $carro],
    comparativo: {
      ok: $comparativoOk,
      violacoes: $comparativoViolacoes
    },
    ok: $ok
  }' > "$SUMMARY_FILE"

echo "[check-frota-perfil] summary=$SUMMARY_FILE"

if [[ "$ok" != "true" ]]; then
  echo "[check-frota-perfil] FALHA: violacoes encontradas." >&2
  jq -r '.cases[] | select(.ok == false) | "- [case] \(.caseId): \(.violacoes | join("; "))"' "$SUMMARY_FILE" >&2
  jq -r '.comparativo | select(.ok == false) | "- [comparativo]: \(.violacoes | join("; "))"' "$SUMMARY_FILE" >&2
  exit 1
fi

echo "[check-frota-perfil] OK: perfil MOTO/CARRO validado sem alterar regra core."
