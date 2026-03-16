package com.aguaviva.solver;

import java.util.List;

public record RotaSolver(int entregadorId, int numeroNoDia, List<Parada> paradas) {

    @Override
    public String toString() {
        return "RotaSolver{entregadorId=" + entregadorId
                + ", numeroNoDia=" + numeroNoDia
                + ", paradas=" + paradas.size() + "}";
    }
}
