#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_BASE="${API_BASE:-http://localhost:8082}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
WORK_DIR="${WORK_DIR:-/tmp/agua-viva-observe}"
SUMMARY_FILE="${SUMMARY_FILE:-$WORK_DIR/summary.json}"
REQUIRE_CONFIRMADO_EM_ROTA="${REQUIRE_CONFIRMADO_EM_ROTA:-0}"
REQUIRE_PENDENTE_CONFIRMADO="${REQUIRE_PENDENTE_CONFIRMADO:-0}"
REQUIRE_NO_EM_ROTA_CONFIRMADO="${REQUIRE_NO_EM_ROTA_CONFIRMADO:-1}"
PROMO_DEBOUNCE_SEGUNDOS="${PROMO_DEBOUNCE_SEGUNDOS:-${DEBOUNCE_SEGUNDOS:-0}}"
NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-2}"
mkdir -p "$WORK_DIR"

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

psql_query() {
  local sql="$1"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$DB_USER" -d "$DB_NAME" -q -Atc "$sql"
}

psql_exec() {
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

wait_for_sql_truth() {
  local sql="$1"
  local expected="${2:-1}"
  local attempts="${3:-20}"
  local pause="${4:-1}"
  local value

  for _ in $(seq 1 "$attempts"); do
    value="$(psql_query "$sql" | tr -d '[:space:]')"
    if [[ "$value" == "$expected" ]]; then
      return 0
    fi
    sleep "$pause"
  done
  return 1
}

debounce_if_needed() {
  local reason="$1"
  if [[ "$PROMO_DEBOUNCE_SEGUNDOS" -le 0 ]]; then
    return 0
  fi
  echo "[observe-promocoes] pausa ${PROMO_DEBOUNCE_SEGUNDOS}s: ${reason}"
  sleep "$PROMO_DEBOUNCE_SEGUNDOS"
}

snapshot_sql() {
  cat <<'SQL'
SELECT p.id,p.status::text,COALESCE(r.id::text,'-'),COALESCE(r.status::text,'-'),COALESCE(e.status::text,'-')
FROM pedidos p
LEFT JOIN entregas e ON e.pedido_id=p.id AND e.status::text IN ('PENDENTE','EM_EXECUCAO')
LEFT JOIN rotas r ON r.id=e.rota_id
WHERE p.status::text IN ('PENDENTE','CONFIRMADO','EM_ROTA')
ORDER BY p.criado_em,p.id;
SQL
}

if ! curl -fsS "$API_BASE/health" >/dev/null 2>&1; then
  echo "API offline em $API_BASE" >&2
  exit 1
fi

if ! [[ "$NUM_ENTREGADORES_ATIVOS" =~ ^[0-9]+$ ]] || [[ "$NUM_ENTREGADORES_ATIVOS" -le 0 ]]; then
  echo "NUM_ENTREGADORES_ATIVOS invalido: $NUM_ENTREGADORES_ATIVOS (use inteiro > 0)" >&2
  exit 1
fi
if ! [[ "$PROMO_DEBOUNCE_SEGUNDOS" =~ ^[0-9]+$ ]]; then
  echo "PROMO_DEBOUNCE_SEGUNDOS invalido: $PROMO_DEBOUNCE_SEGUNDOS (use inteiro >= 0)" >&2
  exit 1
fi

TOTAL_PEDIDOS=$((NUM_ENTREGADORES_ATIVOS + 1))

echo "[observe-promocoes] Resetando estado"
"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/reset-test-state.sh" >/dev/null

psql_exec <<SQL >/dev/null
UPDATE configuracoes SET valor = '1' WHERE chave = 'capacidade_veiculo';

INSERT INTO users (nome,email,senha_hash,papel,ativo)
VALUES
  ('Atendente Promocao','promocao.atendente@aguaviva.local','hash_nao_usado','atendente',true)
ON CONFLICT (email) DO UPDATE SET ativo=true;

UPDATE users
SET ativo = false
WHERE papel = 'entregador'
  AND email <> 'base.entregador@aguaviva.local';

UPDATE users
SET ativo = true
WHERE email = 'base.entregador@aguaviva.local';

INSERT INTO users (nome,email,senha_hash,papel,ativo)
SELECT
  'Entregador Promocao ' || n,
  'promocao.entregador.' || n || '@aguaviva.local',
  'hash_nao_usado',
  'entregador',
  true
FROM generate_series(1, GREATEST(${NUM_ENTREGADORES_ATIVOS} - 1, 0)) AS n
ON CONFLICT (email) DO UPDATE SET ativo = true;
SQL

ATENDENTE_ID="$(psql_query "SELECT id FROM users WHERE email='promocao.atendente@aguaviva.local' LIMIT 1;" | tr -d '[:space:]')"
ENTREGADORES_ATIVOS_REAL="$(psql_query "SELECT COUNT(*) FROM users WHERE papel='entregador' AND ativo = true;" | tr -d '[:space:]')"

echo "[observe-promocoes] Entregadores ativos: ${ENTREGADORES_ATIVOS_REAL} (alvo: ${NUM_ENTREGADORES_ATIVOS})"
echo "[observe-promocoes] Pedidos no cenario: ${TOTAL_PEDIDOS}"

for i in $(seq 1 "$TOTAL_PEDIDOS"); do
  PHONE="38998769$(printf '%03d' $((200 + i)))"
  LAT="$(awk -v i="$i" 'BEGIN { printf "%.4f", -16.7300 - (i / 1000.0) }')"
  LON="$(awk -v i="$i" 'BEGIN { printf "%.4f", -43.8700 - (i / 1000.0) }')"
  psql_exec <<SQL >/dev/null
INSERT INTO clientes (nome,telefone,tipo,endereco,latitude,longitude)
VALUES ('Cliente Promocao ${i}','${PHONE}','PF','Rua ${i}',${LAT},${LON})
ON CONFLICT (telefone) DO UPDATE
SET atualizado_em = CURRENT_TIMESTAMP,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude;
SQL
  CALL_ID="promocao-${PHONE}-$(date +%s)-$RANDOM"
  api_post "/api/atendimento/pedidos" "$(jq -n \
    --arg externalCallId "$CALL_ID" \
    --arg telefone "$PHONE" \
    --argjson quantidadeGaloes 1 \
    --argjson atendenteId "$ATENDENTE_ID" \
    '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')" >/dev/null
  sleep 0.2
done

echo "[observe-promocoes] Aguardando roteirizacao automatica por PEDIDO_CRIADO"
if ! wait_for_sql_truth "SELECT CASE WHEN EXISTS (
  SELECT 1
  FROM pedidos p
  JOIN entregas e ON e.pedido_id = p.id
  JOIN rotas r ON r.id = e.rota_id
  WHERE p.status::text = 'CONFIRMADO'
    AND r.status::text = 'PLANEJADA'
) THEN 1 ELSE 0 END;" "1" 30 1; then
  echo "Falha: nenhuma rota PLANEJADA encontrada apos atendimento." >&2
  exit 1
fi

echo "[observe-promocoes] Snapshot antes"
psql_query "$(snapshot_sql)" > "$WORK_DIR/antes.txt"
cat "$WORK_DIR/antes.txt"
debounce_if_needed "apos snapshot antes"

PEDIDOS_CONFIRMADOS=()
while IFS= read -r pedido_id; do
  if [[ -n "$pedido_id" ]]; then
    PEDIDOS_CONFIRMADOS+=("$pedido_id")
  fi
done < <(awk -F'|' '$2 == "CONFIRMADO" {print $1}' "$WORK_DIR/antes.txt")
if [[ "${#PEDIDOS_CONFIRMADOS[@]}" -lt 1 ]]; then
  echo "Falha: cenario sem pedido CONFIRMADO para observar promocoes." >&2
  exit 1
fi

PEDIDOS_PENDENTES_ANTES=()
while IFS= read -r pedido_id; do
  if [[ -n "$pedido_id" ]]; then
    PEDIDOS_PENDENTES_ANTES+=("$pedido_id")
  fi
done < <(awk -F'|' '$2 == "PENDENTE" {print $1}' "$WORK_DIR/antes.txt")
if [[ "${#PEDIDOS_PENDENTES_ANTES[@]}" -lt 1 ]]; then
  echo "Falha: cenario sem pedido PENDENTE para observar promocao." >&2
  exit 1
fi
PENDENTES_ANTES_CSV="$(IFS=,; echo "${PEDIDOS_PENDENTES_ANTES[*]}")"

PEDIDO_MANTER_EM_ROTA="${PEDIDOS_CONFIRMADOS[0]}"

EXECUCAO_MANTER="$(curl -sS "$API_BASE/api/pedidos/$PEDIDO_MANTER_EM_ROTA/execucao")"
ROTA_MANTER="$(echo "$EXECUCAO_MANTER" | jq -r '.rotaId // .rotaPrimariaId // 0')"
if [[ "$ROTA_MANTER" == "0" ]]; then
  echo "Falha: pedido para manter em rota nao possui rota inicial" >&2
  exit 1
fi

debounce_if_needed "antes de ROTA_INICIADA"
api_post "/api/eventos" "$(jq -n --argjson rotaId "$ROTA_MANTER" '{eventType:"ROTA_INICIADA",rotaId:$rotaId}')" >/dev/null
debounce_if_needed "apos ROTA_INICIADA"

echo "[observe-promocoes] Aumentando capacidade para permitir promocao de pendente"
psql_exec <<'SQL' >/dev/null
UPDATE configuracoes SET valor = '3' WHERE chave = 'capacidade_veiculo';
SQL
debounce_if_needed "apos ajuste de capacidade"

echo "[observe-promocoes] Disparando rodada por evento real (PEDIDO_CRIADO) ate promover pendente conhecido"
PROMOCAO_OK=0
for tentativa in $(seq 1 5); do
  debounce_if_needed "antes de gatilho PEDIDO_CRIADO tentativa ${tentativa}"
  CALL_ID_GATILHO="promocao-gatilho-${tentativa}-$(date +%s)-$RANDOM"
  api_post "/api/atendimento/pedidos" "$(jq -n \
    --arg externalCallId "$CALL_ID_GATILHO" \
    --arg telefone "38998769201" \
    --argjson quantidadeGaloes 1 \
    --argjson atendenteId "$ATENDENTE_ID" \
    '{externalCallId:$externalCallId,telefone:$telefone,quantidadeGaloes:$quantidadeGaloes,atendenteId:$atendenteId,metodoPagamento:"PIX"}')" >/dev/null
  debounce_if_needed "apos gatilho PEDIDO_CRIADO tentativa ${tentativa}"

  if wait_for_sql_truth "SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pedidos p
    JOIN entregas e ON e.pedido_id = p.id
    JOIN rotas r ON r.id = e.rota_id
    WHERE p.id IN (${PENDENTES_ANTES_CSV})
      AND p.status::text = 'CONFIRMADO'
      AND r.status::text = 'PLANEJADA'
      AND e.status::text = 'PENDENTE'
  ) THEN 1 ELSE 0 END;" "1" 8 1; then
    PROMOCAO_OK=1
    break
  fi
done

if [[ "$PROMOCAO_OK" -ne 1 ]]; then
  echo "Falha: nenhum pedido pendente conhecido foi promovido para CONFIRMADO/PLANEJADA apos gatilhos de PEDIDO_CRIADO." >&2
  exit 1
fi

echo "[observe-promocoes] Snapshot depois"
debounce_if_needed "antes do snapshot depois"
psql_query "$(snapshot_sql)" > "$WORK_DIR/depois.txt"
cat "$WORK_DIR/depois.txt"

echo
echo "[observe-promocoes] Diff bruto"
diff -u "$WORK_DIR/antes.txt" "$WORK_DIR/depois.txt" || true

echo
echo "[observe-promocoes] Transicoes por pedido"
awk -F'|' '
NR==FNR { antigo[$1]=$2; next }
{
  novo=$2;
  old=(($1 in antigo) ? antigo[$1] : "-");
  if (old != novo) {
    print $1 "|" old "->" novo;
  }
}
' "$WORK_DIR/antes.txt" "$WORK_DIR/depois.txt" | tee "$WORK_DIR/transicoes.txt"

echo
echo "[observe-promocoes] Confirmado -> EM_ROTA"
grep 'CONFIRMADO->EM_ROTA' "$WORK_DIR/transicoes.txt" || true

echo
echo "[observe-promocoes] PENDENTE -> CONFIRMADO"
grep 'PENDENTE->CONFIRMADO' "$WORK_DIR/transicoes.txt" || true

echo
echo "[observe-promocoes] EM_ROTA -> CONFIRMADO (nao permitido)"
grep 'EM_ROTA->CONFIRMADO' "$WORK_DIR/transicoes.txt" || true

CONFIRMADO_EM_ROTA_COUNT="$(grep -c 'CONFIRMADO->EM_ROTA' "$WORK_DIR/transicoes.txt" || true)"
PENDENTE_CONFIRMADO_COUNT="$(grep -c 'PENDENTE->CONFIRMADO' "$WORK_DIR/transicoes.txt" || true)"
EM_ROTA_CONFIRMADO_COUNT="$(grep -c 'EM_ROTA->CONFIRMADO' "$WORK_DIR/transicoes.txt" || true)"
ASSERT_FAILED=0

if [[ "$REQUIRE_CONFIRMADO_EM_ROTA" -eq 1 && "$CONFIRMADO_EM_ROTA_COUNT" -eq 0 ]]; then
  echo "[observe-promocoes] FALHA: nenhuma transicao CONFIRMADO->EM_ROTA encontrada." >&2
  ASSERT_FAILED=1
fi

if [[ "$REQUIRE_PENDENTE_CONFIRMADO" -eq 1 && "$PENDENTE_CONFIRMADO_COUNT" -eq 0 ]]; then
  echo "[observe-promocoes] FALHA: nenhuma transicao PENDENTE->CONFIRMADO encontrada." >&2
  ASSERT_FAILED=1
fi

if [[ "$REQUIRE_NO_EM_ROTA_CONFIRMADO" -eq 1 && "$EM_ROTA_CONFIRMADO_COUNT" -gt 0 ]]; then
  echo "[observe-promocoes] FALHA: detectada transicao proibida EM_ROTA->CONFIRMADO." >&2
  ASSERT_FAILED=1
fi

jq -n \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --arg workDir "$WORK_DIR" \
  --argjson confirmadoEmRotaCount "$CONFIRMADO_EM_ROTA_COUNT" \
  --argjson pendenteConfirmadoCount "$PENDENTE_CONFIRMADO_COUNT" \
  --argjson emRotaConfirmadoCount "$EM_ROTA_CONFIRMADO_COUNT" \
  --argjson requireConfirmadoEmRota "$REQUIRE_CONFIRMADO_EM_ROTA" \
  --argjson requirePendenteConfirmado "$REQUIRE_PENDENTE_CONFIRMADO" \
  --argjson requireNoEmRotaConfirmado "$REQUIRE_NO_EM_ROTA_CONFIRMADO" \
  --argjson promoDebounceSegundos "$PROMO_DEBOUNCE_SEGUNDOS" \
  --argjson ok "$(( ASSERT_FAILED == 0 ? 1 : 0 ))" \
  '{
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    workDir: $workDir,
    transitions: {
      confirmadoParaEmRota: $confirmadoEmRotaCount,
      pendenteParaConfirmado: $pendenteConfirmadoCount,
      emRotaParaConfirmado: $emRotaConfirmadoCount
    },
    required: {
      confirmadoParaEmRota: ($requireConfirmadoEmRota == 1),
      pendenteParaConfirmado: ($requirePendenteConfirmado == 1),
      noEmRotaParaConfirmado: ($requireNoEmRotaConfirmado == 1)
    },
    timing: {
      promoDebounceSegundos: $promoDebounceSegundos
    },
    ok: ($ok == 1)
  }' > "$SUMMARY_FILE"

echo
echo "[observe-promocoes] Artefatos em $WORK_DIR"
echo "[observe-promocoes] Summary em $SUMMARY_FILE"

if [[ "$ASSERT_FAILED" -ne 0 ]]; then
  exit 1
fi
