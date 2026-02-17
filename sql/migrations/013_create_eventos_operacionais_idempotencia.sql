-- Migration: 013_create_eventos_operacionais_idempotencia
-- Descricao: Idempotencia forte para integracao de eventos operacionais

-- UP

CREATE TABLE IF NOT EXISTS eventos_operacionais_idempotencia (
    external_event_id VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_id BIGINT NOT NULL,
    response_json JSONB NOT NULL,
    status_code INTEGER NOT NULL,
    created_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_eventos_operacionais_idempotencia_created_em
    ON eventos_operacionais_idempotencia (created_em DESC);

COMMENT ON TABLE eventos_operacionais_idempotencia IS 'Registro deterministico de deduplicacao por externalEventId';
COMMENT ON COLUMN eventos_operacionais_idempotencia.request_hash IS 'Hash canonical do payload para detectar reuse divergente';
COMMENT ON COLUMN eventos_operacionais_idempotencia.response_json IS 'Resposta JSON armazenada para replay idempotente';
