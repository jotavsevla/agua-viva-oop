-- Migration: 012_add_pedido_metodo_pagamento_and_vale_debito_idempotency
-- Descricao: Persistencia do metodo de pagamento e idempotencia no debito de vale

-- UP

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'pedido_metodo_pagamento') THEN
        CREATE TYPE pedido_metodo_pagamento AS ENUM ('NAO_INFORMADO', 'DINHEIRO', 'PIX', 'CARTAO', 'VALE');
    END IF;
END $$;

ALTER TABLE pedidos
    ADD COLUMN IF NOT EXISTS metodo_pagamento pedido_metodo_pagamento NOT NULL DEFAULT 'NAO_INFORMADO';

CREATE INDEX IF NOT EXISTS idx_pedidos_metodo_pagamento ON pedidos (metodo_pagamento);

CREATE UNIQUE INDEX IF NOT EXISTS uk_movimentacao_vales_debito_por_pedido
    ON movimentacao_vales (pedido_id)
    WHERE tipo = 'DEBITO' AND pedido_id IS NOT NULL;

COMMENT ON COLUMN pedidos.metodo_pagamento IS 'Metodo de pagamento informado no checkout';
COMMENT ON INDEX uk_movimentacao_vales_debito_por_pedido IS 'Impede mais de um debito de vale por pedido';
