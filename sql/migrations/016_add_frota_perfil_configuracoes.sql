-- Migration: 016_add_frota_perfil_configuracoes
-- Descricao: Parametrizacao simples de perfil de frota (moto/carro) mantendo solver unico

-- UP
INSERT INTO configuracoes (chave, valor, descricao) VALUES
    ('frota_perfil_ativo', 'PADRAO', 'Perfil de frota ativo: PADRAO|MOTO|CARRO'),
    ('capacidade_frota_moto', '2', 'Capacidade por entregador para perfil MOTO'),
    ('capacidade_frota_carro', '5', 'Capacidade por entregador para perfil CARRO')
ON CONFLICT (chave) DO NOTHING;
