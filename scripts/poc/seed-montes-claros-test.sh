#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SEED_FILE="$ROOT_DIR/sql/seeds/001_seed_clientes_montes_claros_test.sql"

DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
DB_FORCE_CONTAINER="${DB_FORCE_CONTAINER:-0}"
CLIENTES_BBOX="${CLIENTES_BBOX:--43.9600,-16.8200,-43.7800,-16.6200}"
GEOFENCE_SUMMARY_FILE="${GEOFENCE_SUMMARY_FILE:-$ROOT_DIR/artifacts/poc/geofence-summary.json}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/seed-montes-claros-test.sh

Variaveis opcionais:
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres
  DB_FORCE_CONTAINER=0
  CLIENTES_BBOX=-43.9600,-16.8200,-43.7800,-16.6200
  GEOFENCE_SUMMARY_FILE=artifacts/poc/geofence-summary.json

Comportamento:
  - Se houver `psql` local, aplica seed via conexao TCP.
  - Se nao houver `psql`, usa fallback com `docker exec` no container do Postgres.
  - Defina `DB_FORCE_CONTAINER=1` para forcar caminho via container.
  - Ao final valida consistencia do seed e geofence operacional.
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ ! -f "$SEED_FILE" ]]; then
  echo "Arquivo de seed nao encontrado: $SEED_FILE" >&2
  exit 1
fi

echo "[seed-montes-claros-test] Aplicando seed em ${DB_NAME}"

if [[ "$DB_FORCE_CONTAINER" != "1" ]] && command -v psql >/dev/null 2>&1; then
  PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" \
    -p "$DB_PORT" \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -f "$SEED_FILE" >/dev/null
else
  require_cmd docker
  docker exec -i "$DB_CONTAINER" psql \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 < "$SEED_FILE" >/dev/null
fi

DB_CONTAINER="$DB_CONTAINER" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_PASSWORD="$DB_PASSWORD" DB_FORCE_CONTAINER="$DB_FORCE_CONTAINER" \
"$ROOT_DIR/scripts/poc/check-montes-claros-seed.sh"

DB_CONTAINER="$DB_CONTAINER" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_PASSWORD="$DB_PASSWORD" \
DB_FORCE_CONTAINER="$DB_FORCE_CONTAINER" CLIENTES_BBOX="$CLIENTES_BBOX" GEOFENCE_SUMMARY_FILE="$GEOFENCE_SUMMARY_FILE" \
"$ROOT_DIR/scripts/poc/check-clientes-geofence.sh"

echo "[seed-montes-claros-test] Seed aplicado e validado com sucesso (consistencia + geofence)."
