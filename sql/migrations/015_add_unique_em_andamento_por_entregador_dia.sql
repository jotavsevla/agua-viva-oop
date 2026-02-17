-- Migration: 015_add_unique_em_andamento_por_entregador_dia
-- Descricao: Garante no maximo uma rota EM_ANDAMENTO simultanea por entregador/dia

-- UP
CREATE UNIQUE INDEX IF NOT EXISTS uk_rotas_andamento_entregador_data
    ON rotas (entregador_id, data)
    WHERE status = 'EM_ANDAMENTO';
