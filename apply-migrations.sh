#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE=${COMPOSE_FILE:-compose.yml}
DB_SERVICE=${DB_SERVICE:-${CONTAINER_NAME:-postgres-oop-dev}}
DB_USER=${POSTGRES_USER:-postgres}
DB_NAME=${POSTGRES_DB:-agua_viva_oop_dev}

echo "Aplicando migrations em service=$DB_SERVICE ($DB_NAME) como $DB_USER"

for f in sql/migrations/*.sql; do
  echo "Aplicando: $f"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" < "$f"
done

echo "Migrations aplicadas com sucesso."
