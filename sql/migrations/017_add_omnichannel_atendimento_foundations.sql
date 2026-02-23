-- Migration: 017_add_omnichannel_atendimento_foundations
-- Descricao: Fundacoes para atendimento omnichannel (telefone canonico, idempotencia por canal e cobertura)

-- UP

-- Enforce canonicidade por telefone normalizado (somente digitos).
-- Obs: em bases legadas com duplicidade de numero normalizado, este indice falhara e exige saneamento previo.
CREATE UNIQUE INDEX IF NOT EXISTS uk_clientes_telefone_normalizado
    ON clientes ((regexp_replace(telefone, '[^0-9]', '', 'g')));

-- Registro deterministico de idempotencia por canal + evento de origem.
CREATE TABLE IF NOT EXISTS atendimentos_idempotencia (
    origem_canal VARCHAR(32) NOT NULL,
    source_event_id VARCHAR(128) NOT NULL,
    pedido_id INTEGER NOT NULL REFERENCES pedidos(id),
    cliente_id INTEGER NOT NULL REFERENCES clientes(id),
    telefone_normalizado VARCHAR(15) NOT NULL,
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (origem_canal, source_event_id)
);

CREATE INDEX IF NOT EXISTS idx_atendimentos_idempotencia_pedido
    ON atendimentos_idempotencia (pedido_id);

CREATE INDEX IF NOT EXISTS idx_atendimentos_idempotencia_cliente
    ON atendimentos_idempotencia (cliente_id);

-- Cobertura operacional padrao (min_lon,min_lat,max_lon,max_lat).
INSERT INTO configuracoes (chave, valor, descricao)
VALUES (
    'cobertura_bbox',
    '-43.9600,-16.8200,-43.7800,-16.6200',
    'Cobertura operacional de atendimento (MOC) em bbox min_lon,min_lat,max_lon,max_lat'
)
ON CONFLICT (chave) DO NOTHING;

COMMENT ON TABLE atendimentos_idempotencia IS 'Idempotencia de atendimento por origem de canal + source_event_id';
COMMENT ON COLUMN atendimentos_idempotencia.source_event_id IS 'Chave de idempotencia do canal de origem';
COMMENT ON COLUMN atendimentos_idempotencia.telefone_normalizado IS 'Telefone normalizado (somente digitos) usado no processamento';
