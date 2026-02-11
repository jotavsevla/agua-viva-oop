-- Migration: 005_create_pedidos
-- Descrição: Pedidos de entrega de água

-- UP
CREATE TYPE janela_tipo AS ENUM ('HARD', 'ASAP');
CREATE TYPE pedido_status AS ENUM ('PENDENTE', 'CONFIRMADO', 'EM_ROTA', 'ENTREGUE', 'CANCELADO');

CREATE TABLE pedidos (
    id SERIAL PRIMARY KEY,
    cliente_id INTEGER NOT NULL REFERENCES clientes(id),
    quantidade_galoes INTEGER NOT NULL DEFAULT 1,
    
    -- Janela de tempo
    janela_tipo janela_tipo NOT NULL DEFAULT 'ASAP',
    janela_inicio TIME,  -- NULL se ASAP
    janela_fim TIME,     -- NULL se ASAP
    
    -- Status do pedido
    status pedido_status NOT NULL DEFAULT 'PENDENTE',
    
    -- Rastreabilidade
    criado_por INTEGER NOT NULL REFERENCES users(id),
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Validações
    CONSTRAINT chk_quantidade_minima CHECK (quantidade_galoes > 0),
    CONSTRAINT chk_janela_hard_completa CHECK (
        janela_tipo = 'ASAP' 
        OR (janela_inicio IS NOT NULL AND janela_fim IS NOT NULL)
    ),
    CONSTRAINT chk_janela_ordem CHECK (
        janela_inicio IS NULL 
        OR janela_fim IS NULL 
        OR janela_inicio < janela_fim
    )
);

-- Índice crítico: pedidos pendentes para o solver
CREATE INDEX idx_pedidos_pendentes ON pedidos(status, criado_em) 
    WHERE status = 'PENDENTE';

-- Índice: pedidos por cliente (histórico)
CREATE INDEX idx_pedidos_cliente ON pedidos(cliente_id, criado_em DESC);

-- Índice: pedidos HARD (prioridade no solver)
CREATE INDEX idx_pedidos_hard ON pedidos(janela_tipo, janela_inicio) 
    WHERE status IN ('PENDENTE', 'CONFIRMADO') AND janela_tipo = 'HARD';

-- Adiciona FK na tabela movimentacao_vales (agora que pedidos existe)
ALTER TABLE movimentacao_vales 
    ADD CONSTRAINT fk_movimentacao_pedido 
    FOREIGN KEY (pedido_id) REFERENCES pedidos(id);

COMMENT ON TABLE pedidos IS 'Pedidos de entrega - cada pedido vira uma entrega';
COMMENT ON COLUMN pedidos.janela_tipo IS 'HARD = horário obrigatório, ASAP = quando puder';
COMMENT ON COLUMN pedidos.status IS 'PENDENTE → CONFIRMADO → EM_ROTA → ENTREGUE | CANCELADO';
