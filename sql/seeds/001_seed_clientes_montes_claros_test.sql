-- Seed: clientes reais de Montes Claros (MG) para ambiente de teste
-- Objetivo: disponibilizar base geografica plausivel para cenarios operacionais.
-- Regras:
-- 1) Nao cria pedidos automaticamente (evita ruido em gates de status).
-- 2) Usa ON CONFLICT por telefone para permitir reexecucao idempotente.

INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude, notas)
VALUES
    (
        'Cliente Centro',
        '38991000001',
        'PF',
        'Praca Doutor Carlos, Centro, Montes Claros - MG',
        -16.7210,
        -43.8610,
        'Regiao central'
    ),
    (
        'Cliente Major Prates',
        '38991000002',
        'PF',
        'Avenida Deputado Esteves Rodrigues, Major Prates, Montes Claros - MG',
        -16.7050,
        -43.8600,
        'Bairro de alta demanda'
    ),
    (
        'Cliente Major Prates Norte',
        '38991000003',
        'PF',
        'Rua Quinze, Major Prates, Montes Claros - MG',
        -16.7080,
        -43.8550,
        'Ponto auxiliar da regiao norte'
    ),
    (
        'Cliente Todos os Santos',
        '38991000004',
        'PF',
        'Rua Santa Maria, Todos os Santos, Montes Claros - MG',
        -16.7400,
        -43.8700,
        'Faixa sul da cidade'
    ),
    (
        'Cliente Todos os Santos Sul',
        '38991000005',
        'PF',
        'Rua Joao XXIII, Todos os Santos, Montes Claros - MG',
        -16.7380,
        -43.8650,
        'Entrega recorrente residencial'
    ),
    (
        'Cliente Sao Jose',
        '38991000006',
        'PF',
        'Rua Sao Judas Tadeu, Sao Jose, Montes Claros - MG',
        -16.7150,
        -43.8450,
        'Faixa leste'
    ),
    (
        'Cliente Sao Jose Leste',
        '38991000007',
        'PF',
        'Rua Padre Augusto, Sao Jose, Montes Claros - MG',
        -16.7180,
        -43.8480,
        'Complemento de cobertura leste'
    ),
    (
        'Cliente Ibituruna',
        '38991000008',
        'PF',
        'Avenida Mestra Fininha, Ibituruna, Montes Claros - MG',
        -16.7200,
        -43.8800,
        'Area com aclive'
    ),
    (
        'Cliente Ibituruna Oeste',
        '38991000009',
        'PF',
        'Avenida Jose Correa Machado, Ibituruna, Montes Claros - MG',
        -16.7230,
        -43.8750,
        'Ponto oeste'
    ),
    (
        'Cliente Jardim Panorama',
        '38991000010',
        'PF',
        'Avenida Donato Quintino, Jardim Panorama, Montes Claros - MG',
        -16.6950,
        -43.8550,
        'Faixa norte'
    ),
    (
        'Cliente Jardim Panorama Norte',
        '38991000011',
        'PF',
        'Rua Sao Judas, Jardim Panorama, Montes Claros - MG',
        -16.6980,
        -43.8520,
        'Borda norte urbana'
    ),
    (
        'Cliente Empresarial Montes Claros',
        '38991000012',
        'PJ',
        'Avenida Dulce Sarmento, Interlagos, Montes Claros - MG',
        -16.7344,
        -43.8772,
        'Ponto de referencia proximo ao deposito operacional'
    )
ON CONFLICT (telefone) DO UPDATE
SET
    nome = EXCLUDED.nome,
    tipo = EXCLUDED.tipo,
    endereco = EXCLUDED.endereco,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    notas = EXCLUDED.notas,
    atualizado_em = CURRENT_TIMESTAMP;

-- Seed de saldo para cenarios de checkout em VALE.
INSERT INTO saldo_vales (cliente_id, quantidade)
SELECT c.id, 8
FROM clientes c
WHERE c.telefone IN ('38991000001', '38991000004', '38991000008', '38991000012')
ON CONFLICT (cliente_id) DO UPDATE
SET
    quantidade = EXCLUDED.quantidade,
    atualizado_em = CURRENT_TIMESTAMP;
