-- Migration: 008_create_views_cte
-- Descrição: Views com CTEs para consultas complexas (documentação de portfólio)

-- ============================================================================
-- CTE 1: Dashboard do Entregador
-- Retorna a rota atual com todas as entregas ordenadas
-- ============================================================================

/*
Esta query demonstra:
- CTE para isolar lógica de "rota ativa"
- JOIN múltiplo (entregas → pedidos → clientes)
- Ordenação por prioridade (HARD primeiro) e sequência
*/

CREATE OR REPLACE VIEW vw_rota_atual_entregador AS
WITH rota_ativa AS (
    -- Seleciona a rota em andamento OU a próxima planejada
    SELECT id, entregador_id, numero_no_dia, status
    FROM rotas
    WHERE data = CURRENT_DATE
      AND status IN ('EM_ANDAMENTO', 'PLANEJADA')
    ORDER BY
        CASE status
            WHEN 'EM_ANDAMENTO' THEN 1
            WHEN 'PLANEJADA' THEN 2
        END,
        numero_no_dia
    LIMIT 1
)
SELECT
    r.entregador_id,
    r.numero_no_dia,
    r.status AS rota_status,
    e.ordem_na_rota,
    e.status AS entrega_status,
    e.hora_prevista,
    p.id AS pedido_id,
    p.quantidade_galoes,
    p.janela_tipo,
    p.janela_inicio,
    p.janela_fim,
    c.nome AS cliente_nome,
    c.telefone AS cliente_telefone,
    c.endereco AS cliente_endereco,
    c.latitude,
    c.longitude,
    c.notas AS cliente_notas
FROM rota_ativa r
JOIN entregas e ON e.rota_id = r.id
JOIN pedidos p ON p.id = e.pedido_id
JOIN clientes c ON c.id = p.cliente_id
WHERE e.status = 'PENDENTE'
ORDER BY e.ordem_na_rota;

COMMENT ON VIEW vw_rota_atual_entregador IS
'Lista entregas pendentes da rota atual - usado na tela mobile do entregador';


-- ============================================================================
-- CTE 2: Pedidos Pendentes para o Solver
-- Agrupa dados necessários para otimização de rotas
-- ============================================================================

/*
Esta query demonstra:
- CTE para cálculo de saldo (valida se cliente pode pedir)
- Dados desnormalizados para envio ao solver Python
- Priorização: HARD antes de ASAP
*/

CREATE OR REPLACE VIEW vw_pedidos_para_solver AS
WITH clientes_com_saldo AS (
    -- Apenas clientes com saldo suficiente
    SELECT
        c.id,
        c.latitude,
        c.longitude,
        sv.quantidade AS saldo
    FROM clientes c
    JOIN saldo_vales sv ON sv.cliente_id = c.id
    WHERE sv.quantidade > 0
)
SELECT
    p.id AS pedido_id,
    p.quantidade_galoes,
    p.janela_tipo,
    p.janela_inicio,
    p.janela_fim,
    cs.latitude,
    cs.longitude,
    -- Prioridade numérica para o solver
    CASE p.janela_tipo
        WHEN 'HARD' THEN 1
        WHEN 'ASAP' THEN 2
    END AS prioridade
FROM pedidos p
JOIN clientes_com_saldo cs ON cs.id = p.cliente_id
WHERE p.status = 'PENDENTE'
  AND p.quantidade_galoes <= cs.saldo  -- Cliente tem saldo suficiente
ORDER BY prioridade, p.criado_em;

COMMENT ON VIEW vw_pedidos_para_solver IS
'Pedidos prontos para entrar no cálculo de rotas - enviados ao microserviço Python';


-- ============================================================================
-- CTE 3: Relatório de Performance do Entregador
-- Calcula métricas de entregas por período
-- ============================================================================

/*
Esta query demonstra:
- Múltiplas CTEs encadeadas
- Agregações com FILTER
- Cálculo de percentuais e médias
*/

CREATE OR REPLACE VIEW vw_relatorio_entregador AS
WITH entregas_periodo AS (
    -- Base: entregas finalizadas nos últimos 30 dias
    SELECT
        r.entregador_id,
        e.id AS entrega_id,
        e.status,
        e.hora_prevista,
        e.hora_real,
        p.janela_tipo,
        p.janela_fim
    FROM entregas e
    JOIN rotas r ON r.id = e.rota_id
    JOIN pedidos p ON p.id = e.pedido_id
    WHERE r.data >= CURRENT_DATE - INTERVAL '30 days'
      AND e.status IN ('ENTREGUE', 'FALHOU')
),
metricas_brutas AS (
    -- Cálculos intermediários
    SELECT
        entregador_id,
        COUNT(*) AS total_entregas,
        COUNT(*) FILTER (WHERE status = 'ENTREGUE') AS entregas_sucesso,
        COUNT(*) FILTER (WHERE status = 'FALHOU') AS entregas_falha,
        COUNT(*) FILTER (
            WHERE status = 'ENTREGUE'
            AND janela_tipo = 'HARD'
            AND hora_real::time <= janela_fim
        ) AS hard_no_prazo,
        COUNT(*) FILTER (WHERE janela_tipo = 'HARD') AS total_hard,
        AVG(EXTRACT(EPOCH FROM (hora_real - hora_prevista)) / 60)
            FILTER (WHERE hora_real IS NOT NULL) AS desvio_medio_min
    FROM entregas_periodo
    GROUP BY entregador_id
)
SELECT
    u.id AS entregador_id,
    u.nome AS entregador_nome,
    mb.total_entregas,
    mb.entregas_sucesso,
    mb.entregas_falha,
    ROUND(100.0 * mb.entregas_sucesso / NULLIF(mb.total_entregas, 0), 1) AS taxa_sucesso_pct,
    mb.hard_no_prazo,
    mb.total_hard,
    ROUND(100.0 * mb.hard_no_prazo / NULLIF(mb.total_hard, 0), 1) AS taxa_hard_no_prazo_pct,
    ROUND(mb.desvio_medio_min::numeric, 1) AS desvio_medio_minutos
FROM metricas_brutas mb
JOIN users u ON u.id = mb.entregador_id
ORDER BY taxa_sucesso_pct DESC;

COMMENT ON VIEW vw_relatorio_entregador IS
'Métricas de performance dos últimos 30 dias - usado pelo supervisor';


-- ============================================================================
-- CTE 4: Histórico de Movimentação de Vales do Cliente
-- Extrato completo com saldo acumulado
-- ============================================================================

/*
Esta query demonstra:
- Window function com SUM() OVER
- CASE para valores positivos/negativos
- Ordenação temporal com saldo running
*/

-- Esta é uma função que recebe cliente_id como parâmetro
-- Usada na tela de detalhes do cliente

CREATE OR REPLACE FUNCTION fn_extrato_vales(p_cliente_id INTEGER)
RETURNS TABLE (
    data TIMESTAMP,
    tipo VARCHAR,
    quantidade INTEGER,
    saldo_apos INTEGER,
    registrado_por VARCHAR,
    pedido_id INTEGER,
    observacao VARCHAR
) AS $$
WITH movimentacoes AS (
    SELECT
        mv.criado_em,
        mv.tipo::VARCHAR,
        mv.quantidade,
        CASE mv.tipo
            WHEN 'CREDITO' THEN mv.quantidade
            WHEN 'DEBITO' THEN -mv.quantidade
        END AS valor_com_sinal,
        u.nome AS registrado_por,
        mv.pedido_id,
        mv.observacao
    FROM movimentacao_vales mv
    JOIN users u ON u.id = mv.registrado_por
    WHERE mv.cliente_id = p_cliente_id
)
SELECT
    criado_em AS data,
    tipo,
    quantidade,
    SUM(valor_com_sinal) OVER (ORDER BY criado_em) AS saldo_apos,
    registrado_por,
    pedido_id,
    observacao
FROM movimentacoes
ORDER BY criado_em DESC;
$$ LANGUAGE SQL STABLE;

COMMENT ON FUNCTION fn_extrato_vales IS
'Extrato de vales com saldo running - tela de detalhes do cliente';
