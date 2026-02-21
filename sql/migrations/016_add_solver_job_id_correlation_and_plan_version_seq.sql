-- Migration: 016_add_solver_job_id_correlation_and_plan_version_seq
-- Descricao:
-- 1) Cria sequence global para plan_version (evita colisao entre processos/instancias).
-- 2) Adiciona correlacao por job_id em rotas/entregas para detalhamento confiavel por job.

-- ---------------------------------------------------------------------------
-- Sequence global de versao de plano
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS solver_plan_version_seq START WITH 1 INCREMENT BY 1;

DO $$
DECLARE
    next_plan_version BIGINT;
BEGIN
    SELECT GREATEST(
        COALESCE((SELECT MAX(plan_version) FROM solver_jobs), 0),
        COALESCE((SELECT MAX(plan_version) FROM rotas), 0),
        COALESCE((SELECT MAX(plan_version) FROM entregas), 0)
    ) + 1
    INTO next_plan_version;

    PERFORM setval('solver_plan_version_seq', next_plan_version, false);
END$$;

-- ---------------------------------------------------------------------------
-- Correlacao de impacto por job_id
-- ---------------------------------------------------------------------------
ALTER TABLE rotas
    ADD COLUMN IF NOT EXISTS job_id VARCHAR(64);

ALTER TABLE entregas
    ADD COLUMN IF NOT EXISTS job_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_rotas_job_id ON rotas(job_id) WHERE job_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_entregas_job_id ON entregas(job_id) WHERE job_id IS NOT NULL;

COMMENT ON COLUMN rotas.job_id IS 'job_id do solver responsavel pela criacao/atualizacao da rota';
COMMENT ON COLUMN entregas.job_id IS 'job_id do solver responsavel pela criacao/atualizacao da entrega';
