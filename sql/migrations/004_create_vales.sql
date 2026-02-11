-- Migration: 004_create_vales
-- Descrição: Sistema de vale-água (crédito pré-pago)

-- UP

-- Saldo atual de cada cliente (desnormalizado para performance)
CREATE TABLE saldo_vales (
    cliente_id INTEGER PRIMARY KEY REFERENCES clientes(id) ON DELETE CASCADE,
    quantidade INTEGER NOT NULL DEFAULT 0,
    atualizado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_saldo_nao_negativo CHECK (quantidade >= 0)
);

COMMENT ON TABLE saldo_vales IS 'Saldo atual de vales - atualizado a cada movimentação';
COMMENT ON COLUMN saldo_vales.quantidade IS 'Número de galões disponíveis para o cliente';

-- Histórico de movimentações (auditoria completa)
CREATE TYPE vale_tipo_movimentacao AS ENUM ('CREDITO', 'DEBITO');

CREATE TABLE movimentacao_vales (
    id SERIAL PRIMARY KEY,
    cliente_id INTEGER NOT NULL REFERENCES clientes(id) ON DELETE CASCADE,
    tipo vale_tipo_movimentacao NOT NULL,
    quantidade INTEGER NOT NULL,
    pedido_id INTEGER,  -- FK adicionada depois (pedidos ainda não existe)
    registrado_por INTEGER NOT NULL REFERENCES users(id),
    observacao VARCHAR(255),  -- Ex: "Pagamento de 10 vales em dinheiro"
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_quantidade_positiva CHECK (quantidade > 0)
);

-- Índice para histórico do cliente
CREATE INDEX idx_movimentacao_cliente ON movimentacao_vales(cliente_id, criado_em DESC);

-- Índice para auditoria por usuário
CREATE INDEX idx_movimentacao_registrado_por ON movimentacao_vales(registrado_por);

COMMENT ON TABLE movimentacao_vales IS 'Auditoria completa de créditos e débitos de vales';
COMMENT ON COLUMN movimentacao_vales.tipo IS 'CREDITO = compra de vales, DEBITO = entrega realizada';
COMMENT ON COLUMN movimentacao_vales.registrado_por IS 'Entregador ou atendente que registrou';
