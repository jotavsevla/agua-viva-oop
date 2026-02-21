#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
DB_FORCE_CONTAINER="${DB_FORCE_CONTAINER:-0}"

EXPECTED_CLIENTES_MC="${EXPECTED_CLIENTES_MC:-12}"
EXPECTED_SALDOS_VALE_MC="${EXPECTED_SALDOS_VALE_MC:-4}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/check-montes-claros-seed.sh

Variaveis opcionais:
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres
  DB_FORCE_CONTAINER=0
  EXPECTED_CLIENTES_MC=12
  EXPECTED_SALDOS_VALE_MC=4

Comportamento:
  - Valida contagem de clientes seedados de Montes Claros.
  - Valida contagem de clientes com saldo VALE seedado.
  - Falha (exit 1) se os totais nao baterem com o esperado.
USAGE
}

query_sql() {
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

  command -v docker >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: docker" >&2
    exit 1
  }

  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" psql \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -q \
    -Atc "$sql"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

TOTAL_CLIENTES_MC="$(query_sql "SELECT COUNT(*) FROM clientes WHERE telefone::text LIKE '389910000%';" | tr -d '[:space:]')"
TOTAL_CLIENTES_MC_GEO="$(query_sql "SELECT COUNT(*) FROM clientes WHERE telefone::text LIKE '389910000%' AND latitude IS NOT NULL AND longitude IS NOT NULL;" | tr -d '[:space:]')"
TOTAL_SALDOS_MC="$(query_sql "SELECT COUNT(*) FROM saldo_vales sv JOIN clientes c ON c.id = sv.cliente_id WHERE c.telefone::text LIKE '389910000%';" | tr -d '[:space:]')"

echo "[check-montes-claros-seed] clientes_montes_claros=${TOTAL_CLIENTES_MC} (esperado=${EXPECTED_CLIENTES_MC})"
echo "[check-montes-claros-seed] clientes_montes_claros_com_geo=${TOTAL_CLIENTES_MC_GEO}"
echo "[check-montes-claros-seed] saldos_vale_montes_claros=${TOTAL_SALDOS_MC} (esperado=${EXPECTED_SALDOS_VALE_MC})"

echo "[check-montes-claros-seed] amostra de clientes:"
query_sql "SELECT telefone::text || ' | ' || nome || ' | ' || latitude || ',' || longitude
FROM clientes
WHERE telefone::text LIKE '389910000%'
ORDER BY telefone::text
LIMIT 5;"

if [[ "$TOTAL_CLIENTES_MC" != "$EXPECTED_CLIENTES_MC" ]]; then
  echo "[check-montes-claros-seed] FALHA: contagem de clientes seedados diverge." >&2
  exit 1
fi

if [[ "$TOTAL_CLIENTES_MC_GEO" != "$EXPECTED_CLIENTES_MC" ]]; then
  echo "[check-montes-claros-seed] FALHA: existem clientes seedados sem coordenadas." >&2
  exit 1
fi

if [[ "$TOTAL_SALDOS_MC" != "$EXPECTED_SALDOS_VALE_MC" ]]; then
  echo "[check-montes-claros-seed] FALHA: contagem de saldos VALE seedados diverge." >&2
  exit 1
fi

echo "[check-montes-claros-seed] OK: seed de Montes Claros consistente."
