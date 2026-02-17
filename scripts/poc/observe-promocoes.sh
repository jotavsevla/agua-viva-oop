#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-http://localhost:8082}"
DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
WORK_DIR="${WORK_DIR:-/tmp/agua-viva-observe}"
SUMMARY_FILE="${SUMMARY_FILE:-$WORK_DIR/summary.json}"
REQUIRE_CONFIRMADO_EM_ROTA="${REQUIRE_CONFIRMADO_EM_ROTA:-0}"
REQUIRE_PENDENTE_CONFIRMADO="${REQUIRE_PENDENTE_CONFIRMADO:-0}"
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

TOTAL_PEDIDOS=$((NUM_ENTREGADORES_ATIVOS + 1))

echo "[observe-promocoes] Resetando estado"
"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/reset-test-state.sh" >/dev/null

docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<SQL >/dev/null
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

ATENDENTE_ID="$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -Atc "SELECT id FROM users WHERE email='promocao.atendente@aguaviva.local' LIMIT 1;" | tr -d '[:space:]')"
ENTREGADORES_ATIVOS_REAL="$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -Atc "SELECT COUNT(*) FROM users WHERE papel='entregador' AND ativo = true;" | tr -d '[:space:]')"

echo "[observe-promocoes] Entregadores ativos: ${ENTREGADORES_ATIVOS_REAL} (alvo: ${NUM_ENTREGADORES_ATIVOS})"
echo "[observe-promocoes] Pedidos no cenario: ${TOTAL_PEDIDOS}"

for i in $(seq 1 "$TOTAL_PEDIDOS"); do
  PHONE="38998769$(printf '%03d' $((200 + i)))"
  LAT="$(awk -v i="$i" 'BEGIN { printf "%.4f", -16.7300 - (i / 1000.0) }')"
  LON="$(awk -v i="$i" 'BEGIN { printf "%.4f", -43.8700 - (i / 1000.0) }')"
  docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<SQL >/dev/null
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

echo "[observe-promocoes] Replanejamento inicial"
api_post "/api/replanejamento/run" '{"debounceSegundos":0,"limiteEventos":100}' | jq .

echo "[observe-promocoes] Snapshot antes"
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -Atc "$(snapshot_sql)" > "$WORK_DIR/antes.txt"
cat "$WORK_DIR/antes.txt"

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

PEDIDO_MANTER_EM_ROTA="${PEDIDOS_CONFIRMADOS[0]}"

EXECUCAO_MANTER="$(curl -sS "$API_BASE/api/pedidos/$PEDIDO_MANTER_EM_ROTA/execucao")"
ROTA_MANTER="$(echo "$EXECUCAO_MANTER" | jq -r '.rotaId // .rotaPrimariaId // 0')"
if [[ "$ROTA_MANTER" == "0" ]]; then
  echo "Falha: pedido para manter em rota nao possui rota inicial" >&2
  exit 1
fi

api_post "/api/eventos" "$(jq -n --argjson rotaId "$ROTA_MANTER" '{eventType:"ROTA_INICIADA",rotaId:$rotaId}')" >/dev/null

echo "[observe-promocoes] Aumentando capacidade para permitir promocao de pendente"
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
UPDATE configuracoes SET valor = '2' WHERE chave = 'capacidade_veiculo';
SQL

echo "[observe-promocoes] Inserindo evento de disparo para forcar rodada de replanejamento"
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
INSERT INTO dispatch_events (event_type, aggregate_type, aggregate_id, payload, status, available_em)
VALUES ('PEDIDO_FALHOU', 'PEDIDO', NULL, '{}'::jsonb, 'PENDENTE', CURRENT_TIMESTAMP - INTERVAL '1 day');
SQL

echo "[observe-promocoes] Replanejamento apos evento"
api_post "/api/replanejamento/run" '{"debounceSegundos":0,"limiteEventos":100}' | jq .

echo "[observe-promocoes] Snapshot depois"
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -Atc "$(snapshot_sql)" > "$WORK_DIR/depois.txt"
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

CONFIRMADO_EM_ROTA_COUNT="$(grep -c 'CONFIRMADO->EM_ROTA' "$WORK_DIR/transicoes.txt" || true)"
PENDENTE_CONFIRMADO_COUNT="$(grep -c 'PENDENTE->CONFIRMADO' "$WORK_DIR/transicoes.txt" || true)"
ASSERT_FAILED=0

if [[ "$REQUIRE_CONFIRMADO_EM_ROTA" -eq 1 && "$CONFIRMADO_EM_ROTA_COUNT" -eq 0 ]]; then
  echo "[observe-promocoes] FALHA: nenhuma transicao CONFIRMADO->EM_ROTA encontrada." >&2
  ASSERT_FAILED=1
fi

if [[ "$REQUIRE_PENDENTE_CONFIRMADO" -eq 1 && "$PENDENTE_CONFIRMADO_COUNT" -eq 0 ]]; then
  echo "[observe-promocoes] FALHA: nenhuma transicao PENDENTE->CONFIRMADO encontrada." >&2
  ASSERT_FAILED=1
fi

jq -n \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg apiBase "$API_BASE" \
  --arg workDir "$WORK_DIR" \
  --argjson confirmadoEmRotaCount "$CONFIRMADO_EM_ROTA_COUNT" \
  --argjson pendenteConfirmadoCount "$PENDENTE_CONFIRMADO_COUNT" \
  --argjson requireConfirmadoEmRota "$REQUIRE_CONFIRMADO_EM_ROTA" \
  --argjson requirePendenteConfirmado "$REQUIRE_PENDENTE_CONFIRMADO" \
  --argjson ok "$(( ASSERT_FAILED == 0 ? 1 : 0 ))" \
  '{
    generatedAt: $generatedAt,
    apiBase: $apiBase,
    workDir: $workDir,
    transitions: {
      confirmadoParaEmRota: $confirmadoEmRotaCount,
      pendenteParaConfirmado: $pendenteConfirmadoCount
    },
    required: {
      confirmadoParaEmRota: ($requireConfirmadoEmRota == 1),
      pendenteParaConfirmado: ($requirePendenteConfirmado == 1)
    },
    ok: ($ok == 1)
  }' > "$SUMMARY_FILE"

echo
echo "[observe-promocoes] Artefatos em $WORK_DIR"
echo "[observe-promocoes] Summary em $SUMMARY_FILE"

if [[ "$ASSERT_FAILED" -ne 0 ]]; then
  exit 1
fi
