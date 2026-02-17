#!/usr/bin/env bash
set -euo pipefail

DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-1}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

require_cmd docker

if ! [[ "$NUM_ENTREGADORES_ATIVOS" =~ ^[0-9]+$ ]] || [[ "$NUM_ENTREGADORES_ATIVOS" -le 0 ]]; then
  echo "NUM_ENTREGADORES_ATIVOS invalido: $NUM_ENTREGADORES_ATIVOS (use inteiro > 0)" >&2
  exit 1
fi

echo "[reset-test-state] Limpando estado operacional no banco de teste"

docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<SQL
TRUNCATE TABLE
  eventos_operacionais_idempotencia,
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

echo "[reset-test-state] Estado resetado com base minima (entregadores ativos: ${NUM_ENTREGADORES_ATIVOS})"
