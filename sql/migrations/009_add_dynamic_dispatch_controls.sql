-- Migration: 009_add_dynamic_dispatch_controls
-- Descricao: Controles para operacao dinamica (D-CVRPTW online)

-- UP

-- ---------------------------------------------------------------------------
-- Entregas: estado operacional mais detalhado
-- ---------------------------------------------------------------------------
ALTER TYPE entrega_status ADD VALUE IF NOT EXISTS 'EM_EXECUCAO';
ALTER TYPE entrega_status ADD VALUE IF NOT EXISTS 'CANCELADA';

-- ---------------------------------------------------------------------------
-- Pedidos: rastreio de cancelamento e cobranca associada
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        WHERE t.typname = 'cobranca_cancelamento_status'
    ) THEN
        CREATE TYPE cobranca_cancelamento_status AS ENUM (
            'NAO_APLICAVEL',
            'PENDENTE',
            'QUITADA'
        );
    END IF;
END$$;

ALTER TABLE pedidos
    ADD COLUMN IF NOT EXISTS cancelado_em TIMESTAMP,
    ADD COLUMN IF NOT EXISTS motivo_cancelamento VARCHAR(255),
    ADD COLUMN IF NOT EXISTS cobranca_cancelamento_centavos INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cobranca_status cobranca_cancelamento_status NOT NULL DEFAULT 'NAO_APLICAVEL';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conname = 'chk_cobranca_cancelamento_nao_negativa'
    ) THEN
        ALTER TABLE pedidos
            ADD CONSTRAINT chk_cobranca_cancelamento_nao_negativa
                CHECK (cobranca_cancelamento_centavos >= 0);
    END IF;
END$$;

COMMENT ON COLUMN pedidos.cancelado_em IS 'Timestamp em que o pedido foi cancelado';
COMMENT ON COLUMN pedidos.motivo_cancelamento IS 'Motivo informado para cancelamento';
COMMENT ON COLUMN pedidos.cobranca_cancelamento_centavos IS 'Valor adicional de cobranca por cancelamento em rota';
COMMENT ON COLUMN pedidos.cobranca_status IS 'Status da cobranca de cancelamento';

-- ---------------------------------------------------------------------------
-- Versao de plano: evita aplicacao de resultado obsoleto
-- ---------------------------------------------------------------------------
ALTER TABLE rotas
    ADD COLUMN IF NOT EXISTS plan_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE entregas
    ADD COLUMN IF NOT EXISTS plan_version BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_rotas_plan_version ON rotas(plan_version);
CREATE INDEX IF NOT EXISTS idx_entregas_plan_version ON entregas(plan_version);

-- ---------------------------------------------------------------------------
-- Jobs do solver: job_id, cancelamento cooperativo e trilha de execucao
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        WHERE t.typname = 'solver_job_status'
    ) THEN
        CREATE TYPE solver_job_status AS ENUM (
            'PENDENTE',
            'EM_EXECUCAO',
            'CONCLUIDO',
            'CANCELADO',
            'FALHOU'
        );
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS solver_jobs (
    job_id VARCHAR(64) PRIMARY KEY,
    plan_version BIGINT NOT NULL,
    status solver_job_status NOT NULL DEFAULT 'PENDENTE',
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    solicitado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    iniciado_em TIMESTAMP,
    finalizado_em TIMESTAMP,
    erro TEXT,
    request_payload JSONB,
    response_payload JSONB
);

CREATE INDEX IF NOT EXISTS idx_solver_jobs_status ON solver_jobs(status, solicitado_em DESC);
CREATE INDEX IF NOT EXISTS idx_solver_jobs_plan_version ON solver_jobs(plan_version DESC);

COMMENT ON TABLE solver_jobs IS 'Controle de jobs de roteirizacao para operacao dinamica';
COMMENT ON COLUMN solver_jobs.cancel_requested IS 'Sinalizacao de cancelamento cooperativo do job';
