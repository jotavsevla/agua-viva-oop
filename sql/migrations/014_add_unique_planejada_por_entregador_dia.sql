-- Migration: 014_add_unique_planejada_por_entregador_dia
-- Descrição: Formaliza modelo de 2 camadas com no maximo 1 rota PLANEJADA por entregador/dia

-- UP
CREATE UNIQUE INDEX IF NOT EXISTS uk_rotas_planejada_entregador_data
    ON rotas (entregador_id, data)
    WHERE status = 'PLANEJADA';
