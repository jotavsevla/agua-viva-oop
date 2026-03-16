package com.aguaviva.solver;

import java.util.List;

public record SolverResponse(List<RotaSolver> rotas, List<Integer> naoAtendidos) {
    public SolverResponse {
        rotas = rotas != null ? rotas : List.of();
        naoAtendidos = naoAtendidos != null ? naoAtendidos : List.of();
    }

    @Override
    public String toString() {
        return "SolverResponse{rotas=" + rotas.size() + ", naoAtendidos=" + naoAtendidos.size() + "}";
    }
}
