-- Migration: 007_create_configuracoes
-- Descrição: Configurações do sistema e seed inicial

-- UP
CREATE TABLE configuracoes (
    chave VARCHAR(50) PRIMARY KEY,
    valor VARCHAR(255) NOT NULL,
    descricao TEXT,
    atualizado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Configurações iniciais do sistema
INSERT INTO configuracoes (chave, valor, descricao) VALUES
    ('capacidade_veiculo', '5', 'Quantidade máxima de galões por entregador'),
    ('horario_inicio_expediente', '08:00', 'Início do expediente de entregas'),
    ('horario_fim_expediente', '18:00', 'Fim do expediente de entregas'),
    ('intervalo_reotimizacao_min', '30', 'Minutos entre recálculos automáticos de rotas'),
    ('deposito_latitude', '-16.7244', 'Latitude do depósito/armazém'),
    ('deposito_longitude', '-43.8636', 'Longitude do depósito/armazém');

COMMENT ON TABLE configuracoes IS 'Parâmetros configuráveis do sistema';
