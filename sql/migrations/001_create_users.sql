-- Migration: 001_create_users
-- Descrição: Tabela de usuários do sistema (supervisor, admin, atendente, entregador)

-- UP
CREATE TYPE user_papel AS ENUM ('supervisor', 'admin', 'atendente', 'entregador');

CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    senha_hash VARCHAR(255) NOT NULL,
    papel user_papel NOT NULL,
    telefone VARCHAR(20),
    ativo BOOLEAN NOT NULL DEFAULT true,
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Índice para busca por email (login)
CREATE INDEX idx_users_email ON users(email);

-- Índice para listar entregadores ativos
CREATE INDEX idx_users_entregadores_ativos ON users(papel, ativo) 
    WHERE papel = 'entregador' AND ativo = true;

COMMENT ON TABLE users IS 'Usuários do sistema com diferentes níveis de acesso';
COMMENT ON COLUMN users.papel IS 'Hierarquia: supervisor > admin > atendente > entregador';
COMMENT ON COLUMN users.ativo IS 'Admin controla ativação de entregadores';
