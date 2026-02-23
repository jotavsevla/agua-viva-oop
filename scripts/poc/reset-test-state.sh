#!/usr/bin/env bash
set -euo pipefail

DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_SERVICE="${DB_SERVICE:-$DB_CONTAINER}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
DB_FORCE_CONTAINER="${DB_FORCE_CONTAINER:-0}"
NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-1}"
SEED_MONTES_CLAROS="${SEED_MONTES_CLAROS:-1}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

if ! [[ "$NUM_ENTREGADORES_ATIVOS" =~ ^[0-9]+$ ]] || [[ "$NUM_ENTREGADORES_ATIVOS" -le 0 ]]; then
  echo "NUM_ENTREGADORES_ATIVOS invalido: $NUM_ENTREGADORES_ATIVOS (use inteiro > 0)" >&2
  exit 1
fi

if [[ "$SEED_MONTES_CLAROS" != "0" && "$SEED_MONTES_CLAROS" != "1" ]]; then
  echo "SEED_MONTES_CLAROS invalido: $SEED_MONTES_CLAROS (use 0 ou 1)" >&2
  exit 1
fi

if [[ "$DB_FORCE_CONTAINER" != "0" && "$DB_FORCE_CONTAINER" != "1" ]]; then
  echo "DB_FORCE_CONTAINER invalido: $DB_FORCE_CONTAINER (use 0 ou 1)" >&2
  exit 1
fi

USE_LOCAL_PSQL=0
if [[ "$DB_FORCE_CONTAINER" != "1" ]] && command -v psql >/dev/null 2>&1; then
  if PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" \
    -p "$DB_PORT" \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -q \
    -c "SELECT 1;" >/dev/null 2>&1; then
    USE_LOCAL_PSQL=1
  fi
fi

if [[ "$USE_LOCAL_PSQL" -ne 1 ]]; then
  require_cmd docker
fi

run_sql() {
  if [[ "$USE_LOCAL_PSQL" -eq 1 ]]; then
    PGPASSWORD="$DB_PASSWORD" psql \
      -h "$DB_HOST" \
      -p "$DB_PORT" \
      -U "$DB_USER" \
      -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 \
      -q
    return
  fi

  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T "$DB_SERVICE" psql \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -q
}

echo "[reset-test-state] Limpando estado operacional no banco de teste"

run_sql <<SQL
TRUNCATE TABLE
  eventos_operacionais_idempotencia,
  atendimentos_idempotencia,
  dispatch_events,
  sessions,
  entregas,
  rotas,
  movimentacao_vales,
  saldo_vales,
  pedidos,
  clientes,
  users
RESTART IDENTITY CASCADE;

UPDATE configuracoes SET valor = '5' WHERE chave = 'capacidade_veiculo';
INSERT INTO configuracoes (chave, valor, descricao)
VALUES
  ('frota_perfil_ativo', 'PADRAO', 'Perfil de frota ativo: PADRAO|MOTO|CARRO'),
  ('capacidade_frota_moto', '2', 'Capacidade por entregador para perfil MOTO'),
  ('capacidade_frota_carro', '5', 'Capacidade por entregador para perfil CARRO'),
  ('cobertura_bbox', '-43.9600,-16.8200,-43.7800,-16.6200', 'Cobertura operacional de atendimento (MOC) em bbox min_lon,min_lat,max_lon,max_lat')
ON CONFLICT (chave) DO NOTHING;
UPDATE configuracoes SET valor = 'PADRAO' WHERE chave = 'frota_perfil_ativo';
UPDATE configuracoes SET valor = '2' WHERE chave = 'capacidade_frota_moto';
UPDATE configuracoes SET valor = '5' WHERE chave = 'capacidade_frota_carro';
UPDATE configuracoes SET valor = '-43.9600,-16.8200,-43.7800,-16.6200' WHERE chave = 'cobertura_bbox';

INSERT INTO users (nome, email, senha_hash, papel, ativo)
VALUES
  ('Atendente Base Test', 'base.atendente@aguaviva.local', 'hash_nao_usado', 'atendente', true),
  ('Entregador Base Test', 'base.entregador@aguaviva.local', 'hash_nao_usado', 'entregador', true)
ON CONFLICT (email) DO UPDATE SET ativo = true;

INSERT INTO users (nome, email, senha_hash, papel, ativo)
SELECT
  'Entregador Base Test ' || n,
  'base.entregador.' || n || '@aguaviva.local',
  'hash_nao_usado',
  'entregador',
  true
FROM generate_series(1, GREATEST(${NUM_ENTREGADORES_ATIVOS} - 1, 0)) AS n
ON CONFLICT (email) DO UPDATE SET ativo = true;
SQL

if [[ "$SEED_MONTES_CLAROS" == "1" ]]; then
  DB_CONTAINER="$DB_CONTAINER" DB_SERVICE="$DB_SERVICE" COMPOSE_FILE="$COMPOSE_FILE" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
    DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_PASSWORD="$DB_PASSWORD" \
    DB_FORCE_CONTAINER="$DB_FORCE_CONTAINER" \
    "$ROOT_DIR/scripts/poc/seed-montes-claros-test.sh"
  echo "[reset-test-state] Estado resetado com base operacional + seed geografico de Montes Claros (entregadores ativos: ${NUM_ENTREGADORES_ATIVOS})"
else
  echo "[reset-test-state] Estado resetado com base minima (entregadores ativos: ${NUM_ENTREGADORES_ATIVOS})"
fi
