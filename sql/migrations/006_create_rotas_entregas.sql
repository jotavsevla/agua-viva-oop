-- Migration: 006_create_rotas_entregas
-- Descrição: Rotas (viagens) e entregas individuais

-- UP
CREATE TYPE rota_status AS ENUM ('PLANEJADA', 'EM_ANDAMENTO', 'CONCLUIDA');
CREATE TYPE entrega_status AS ENUM ('PENDENTE', 'ENTREGUE', 'FALHOU');

-- Rota = uma saída do depósito até retornar
CREATE TABLE rotas (
    id SERIAL PRIMARY KEY,
    entregador_id INTEGER NOT NULL REFERENCES users(id),
    data DATE NOT NULL DEFAULT CURRENT_DATE,
    numero_no_dia INTEGER NOT NULL DEFAULT 1,  -- 1ª, 2ª, 3ª rota do dia
    status rota_status NOT NULL DEFAULT 'PLANEJADA',

    -- Timestamps de execução
    inicio TIMESTAMP,      -- Quando saiu do depósito
    fim TIMESTAMP,         -- Quando retornou

    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Não pode haver duas rotas com mesmo número no mesmo dia para o mesmo entregador
    CONSTRAINT uk_rota_entregador_dia UNIQUE (entregador_id, data, numero_no_dia)
);

-- Índice: rotas do dia para um entregador (tela mobile)
CREATE INDEX idx_rotas_entregador_hoje ON rotas(entregador_id, data, numero_no_dia)
    WHERE status != 'CONCLUIDA';

-- Índice: rotas planejadas (para o solver atualizar)
CREATE INDEX idx_rotas_planejadas ON rotas(status, data)
    WHERE status = 'PLANEJADA';

COMMENT ON TABLE rotas IS 'Cada rota = uma viagem do depósito até retornar';
COMMENT ON COLUMN rotas.numero_no_dia IS 'Sequência de rotas: 1, 2, 3... no mesmo dia';

-- Entrega = um pedido atribuído a uma rota
CREATE TABLE entregas (
    id SERIAL PRIMARY KEY,
    pedido_id INTEGER NOT NULL REFERENCES pedidos(id),
    rota_id INTEGER NOT NULL REFERENCES rotas(id) ON DELETE CASCADE,
    ordem_na_rota INTEGER NOT NULL,  -- 1, 2, 3... sequência de visitas

    -- Previsão do solver
    hora_prevista TIMESTAMP,

    -- Execução real
    hora_real TIMESTAMP,
    status entrega_status NOT NULL DEFAULT 'PENDENTE',

    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Um pedido só pode estar em uma entrega
    CONSTRAINT uk_pedido_entrega UNIQUE (pedido_id),
    -- Ordem deve ser positiva
    CONSTRAINT chk_ordem_valida CHECK (ordem_na_rota > 0)
);

-- Índice: entregas de uma rota em ordem (tela do entregador)
CREATE INDEX idx_entregas_rota_ordem ON entregas(rota_id, ordem_na_rota);

-- Índice: entregas pendentes
CREATE INDEX idx_entregas_pendentes ON entregas(status)
    WHERE status = 'PENDENTE';

COMMENT ON TABLE entregas IS 'Atribuição de pedido a uma rota específica';
COMMENT ON COLUMN entregas.ordem_na_rota IS 'Sequência otimizada pelo solver';
COMMENT ON COLUMN entregas.hora_prevista IS 'Estimativa do solver para chegada';
