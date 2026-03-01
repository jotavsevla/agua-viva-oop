#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_SERVICE="${DB_SERVICE:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
APP_DB_USER="${APP_DB_USER:-agua_viva_app}"
APP_DB_PASSWORD="${APP_DB_PASSWORD:-}"

usage() {
  cat <<'USAGE'
Uso:
  scripts/db/ensure-app-db-role.sh

Provisiona/atualiza role de aplicacao com privilegios minimos no schema public:
  - CREATE/ALTER ROLE LOGIN com senha
  - GRANT CONNECT no database alvo
  - GRANT USAGE no schema public
  - GRANT DML em todas as tabelas
  - GRANT USAGE/SELECT/UPDATE em todas as sequencias
  - ALTER DEFAULT PRIVILEGES para objetos futuros criados pelo owner atual

Variaveis:
  COMPOSE_FILE      (default: compose.yml)
  DB_SERVICE        (default: postgres-oop-test)
  DB_USER           (default: postgres)
  DB_NAME           (default: agua_viva_oop_test)
  APP_DB_USER       (default: agua_viva_app)
  APP_DB_PASSWORD   (obrigatoria)
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

normalize() {
  local raw="$1"
  echo "$raw" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

require_cmd docker

APP_DB_USER="$(normalize "$APP_DB_USER")"
APP_DB_PASSWORD="$(normalize "$APP_DB_PASSWORD")"

if [[ -z "$APP_DB_USER" ]]; then
  echo "APP_DB_USER nao pode ser vazio." >&2
  exit 1
fi
if [[ "$APP_DB_USER" == "postgres" ]]; then
  echo "APP_DB_USER nao pode ser postgres (least-privilege violado)." >&2
  exit 1
fi
if [[ -z "$APP_DB_PASSWORD" ]]; then
  echo "APP_DB_PASSWORD obrigatoria para provisionar role de aplicacao." >&2
  exit 1
fi

docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" \
  psql -v ON_ERROR_STOP=1 -X -U "$DB_USER" -d "$DB_NAME" \
  -v app_db_user="$APP_DB_USER" \
  -v app_db_password="$APP_DB_PASSWORD" \
  -v app_db_name="$DB_NAME" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_db_user') THEN
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_db_user', :'app_db_password');
  ELSE
    EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', :'app_db_user', :'app_db_password');
  END IF;
END $$;

DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', :'app_db_name', :'app_db_user');
  EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', :'app_db_user');
  EXECUTE format(
    'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I',
    :'app_db_user'
  );
  EXECUTE format(
    'GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I',
    :'app_db_user'
  );
  EXECUTE format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
    :'app_db_user'
  );
  EXECUTE format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I',
    :'app_db_user'
  );
END $$;
SQL

echo "Role de aplicacao '$APP_DB_USER' provisionada com privilegios minimos."
