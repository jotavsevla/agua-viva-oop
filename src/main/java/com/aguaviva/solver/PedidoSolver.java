package com.aguaviva.solver;

public record PedidoSolver(
        int pedidoId,
        double lat,
        double lon,
        int galoes,
        String janelaTipo,
        String janelaInicio,
        String janelaFim,
        int prioridade) {
    public PedidoSolver {
        if (galoes < 1) {
            throw new IllegalArgumentException("Galoes deve ser >= 1");
        }
    }
}
