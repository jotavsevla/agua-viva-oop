-- Migration: 003_create_clientes
-- Descrição: Cadastro de clientes (PF e PJ)

-- UP
CREATE TYPE cliente_tipo AS ENUM ('PF', 'PJ');

CREATE TABLE clientes (
    id SERIAL PRIMARY KEY,
    nome VARCHAR(150) NOT NULL,
    telefone VARCHAR(20) NOT NULL UNIQUE,  -- Chave de identificação principal
    tipo cliente_tipo NOT NULL DEFAULT 'PF',
    endereco VARCHAR(255) NOT NULL,
    latitude DECIMAL(10, 8),   -- Precisão de ~1mm
    longitude DECIMAL(11, 8),
    notas TEXT,                -- Observações livres sobre o cliente
    data_aniversario DATE,     -- Para campanhas futuras
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Índice principal: busca por telefone (usado no atendimento)
CREATE UNIQUE INDEX idx_clientes_telefone ON clientes(telefone);

-- Índice para busca por nome (autocompletar)
CREATE INDEX idx_clientes_nome ON clientes(nome varchar_pattern_ops);

COMMENT ON TABLE clientes IS 'Clientes da distribuidora - 1 telefone = 1 endereço';
COMMENT ON COLUMN clientes.telefone IS 'Identificador único - cliente informa no pedido';
COMMENT ON COLUMN clientes.latitude IS 'Para cálculo de rotas - geocodificado do endereço';
COMMENT ON COLUMN clientes.notas IS 'Ex: "Portão azul", "Cachorro bravo", "Ligar antes"';
