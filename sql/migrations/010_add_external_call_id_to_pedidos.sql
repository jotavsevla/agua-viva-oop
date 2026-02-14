-- Migration: 010_add_external_call_id_to_pedidos
-- Descricao: Idempotencia de entrada telefonica por chamada externa

-- UP
ALTER TABLE pedidos
    ADD COLUMN IF NOT EXISTS external_call_id VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_pedidos_external_call_id'
          AND contype = 'u'
    ) THEN
        IF EXISTS (
            SELECT 1
            FROM pg_indexes
            WHERE indexname = 'uk_pedidos_external_call_id'
        ) THEN
            EXECUTE 'DROP INDEX uk_pedidos_external_call_id';
        END IF;

        ALTER TABLE pedidos
            ADD CONSTRAINT uk_pedidos_external_call_id UNIQUE (external_call_id);
    END IF;
END$$;

COMMENT ON COLUMN pedidos.external_call_id IS 'Identificador externo da chamada para idempotencia de atendimento telefonico';
