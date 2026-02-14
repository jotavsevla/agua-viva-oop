package com.aguaviva.solver;

import java.util.List;

public final class SolverResponse {

    private final List<RotaSolver> rotas;
    private final List<Integer> naoAtendidos;

    public SolverResponse(List<RotaSolver> rotas, List<Integer> naoAtendidos) {
        this.rotas = rotas != null ? rotas : List.of();
        this.naoAtendidos = naoAtendidos != null ? naoAtendidos : List.of();
    }

    public List<RotaSolver> getRotas() {
        return rotas;
    }

    public List<Integer> getNaoAtendidos() {
        return naoAtendidos;
    }

    @Override
    public String toString() {
        return "SolverResponse{rotas=" + rotas.size() + ", naoAtendidos=" + naoAtendidos.size() + "}";
    }
}
