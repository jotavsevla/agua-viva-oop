#!/usr/bin/env bash
set -euo pipefail

DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
NUM_ENTREGADORES_ATIVOS="${NUM_ENTREGADORES_ATIVOS:-1}"
SEED_MONTES_CLAROS="${SEED_MONTES_CLAROS:-1}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

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

if [[ "$SEED_MONTES_CLAROS" != "0" && "$SEED_MONTES_CLAROS" != "1" ]]; then
  echo "SEED_MONTES_CLAROS invalido: $SEED_MONTES_CLAROS (use 0 ou 1)" >&2
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
INSERT INTO configuracoes (chave, valor, descricao)
VALUES
  ('frota_perfil_ativo', 'PADRAO', 'Perfil de frota ativo: PADRAO|MOTO|CARRO'),
  ('capacidade_frota_moto', '2', 'Capacidade por entregador para perfil MOTO'),
  ('capacidade_frota_carro', '5', 'Capacidade por entregador para perfil CARRO')
ON CONFLICT (chave) DO NOTHING;
UPDATE configuracoes SET valor = 'PADRAO' WHERE chave = 'frota_perfil_ativo';
UPDATE configuracoes SET valor = '2' WHERE chave = 'capacidade_frota_moto';
UPDATE configuracoes SET valor = '5' WHERE chave = 'capacidade_frota_carro';

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
  "$ROOT_DIR/scripts/poc/seed-montes-claros-test.sh"
  echo "[reset-test-state] Estado resetado com base operacional + seed geografico de Montes Claros (entregadores ativos: ${NUM_ENTREGADORES_ATIVOS})"
else
  echo "[reset-test-state] Estado resetado com base minima (entregadores ativos: ${NUM_ENTREGADORES_ATIVOS})"
fi
