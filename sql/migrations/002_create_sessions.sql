-- Migration: 002_create_sessions
-- Descrição: Sessões de autenticação dos usuários

-- UP
CREATE TABLE sessions (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expira_em TIMESTAMP NOT NULL,
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Índice para limpeza de sessões expiradas e validação de expiração
CREATE INDEX idx_sessions_expira_em ON sessions(expira_em);

COMMENT ON TABLE sessions IS 'Controle de sessões ativas - token-based auth';
COMMENT ON COLUMN sessions.token IS 'Token único gerado no login, enviado no header Authorization';
