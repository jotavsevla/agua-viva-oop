-- Migration: 011_create_dispatch_events
-- Descricao: Outbox/eventos de despacho para replanejamento quase em tempo real

-- UP
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_type WHERE typname = 'dispatch_event_status'
    ) THEN
        CREATE TYPE dispatch_event_status AS ENUM ('PENDENTE', 'PROCESSADO');
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS dispatch_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status dispatch_event_status NOT NULL DEFAULT 'PENDENTE',
    created_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_em TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dispatch_events_pendentes
    ON dispatch_events(status, available_em, created_em)
    WHERE status = 'PENDENTE';

COMMENT ON TABLE dispatch_events IS 'Outbox de eventos operacionais para acionar replanejamento em lote';
COMMENT ON COLUMN dispatch_events.available_em IS 'Timestamp minimo para processamento (debounce/coalescencia)';
