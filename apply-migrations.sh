#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_SERVICE="${DB_SERVICE:-${CONTAINER_NAME:-postgres-oop-dev}}"
DB_USER="${POSTGRES_USER:-postgres}"
DB_NAME="${POSTGRES_DB:-agua_viva_oop_dev}"

echo "Aplicando migrations em service=$DB_SERVICE ($DB_NAME) como $DB_USER"

shopt -s nullglob
migrations=(sql/migrations/*.sql)
if [[ "${#migrations[@]}" -eq 0 ]]; then
  echo "Nenhuma migration encontrada em sql/migrations/" >&2
  exit 1
fi

psql_exec_sql() {
  local sql="$1"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -v ON_ERROR_STOP=1 -X -U "$DB_USER" -d "$DB_NAME" -Atqc "$sql"
}

migration_already_applied() {
  local filename="$1"
  local filename_escaped="${filename//\'/\'\'}"
  local count
  count="$(psql_exec_sql "SELECT COUNT(*) FROM schema_migrations WHERE filename = '${filename_escaped}';")"
  [[ "${count:-0}" -gt 0 ]]
}

record_migration_applied() {
  local filename="$1"
  local filename_escaped="${filename//\'/\'\'}"
  psql_exec_sql \
    "INSERT INTO schema_migrations(filename) VALUES ('${filename_escaped}') ON CONFLICT (filename) DO NOTHING;"
}

psql_exec_sql \
  "CREATE TABLE IF NOT EXISTS schema_migrations (
     filename VARCHAR(255) PRIMARY KEY,
     applied_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
   );"

for f in "${migrations[@]}"; do
  migration_name="$(basename "$f")"
  if migration_already_applied "$migration_name"; then
    echo "Ignorando (ja aplicada): $migration_name"
    continue
  fi

  echo "Aplicando: $migration_name"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
    psql -v ON_ERROR_STOP=1 -X -U "$DB_USER" -d "$DB_NAME" < "$f"
  record_migration_applied "$migration_name"
done

echo "Migrations aplicadas com sucesso."
