#!/bin/bash
set -e

CONTAINER=${CONTAINER_NAME:-postgres-oop-dev}
DB_USER=${POSTGRES_USER:-postgres}
DB_NAME=${POSTGRES_DB:-agua_viva_oop_dev}

echo "Aplicando migrations em $CONTAINER ($DB_NAME) como $DB_USER"

for f in sql/migrations/*.sql; do
  echo "Aplicando: $f"
  docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" < "$f"
done

echo "Migrations aplicadas com sucesso."
