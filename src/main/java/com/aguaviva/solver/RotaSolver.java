package com.aguaviva.solver;

import java.util.List;

public final class RotaSolver {

    private final int entregadorId;
    private final int numeroNoDia;
    private final List<Parada> paradas;

    public RotaSolver(int entregadorId, int numeroNoDia, List<Parada> paradas) {
        this.entregadorId = entregadorId;
        this.numeroNoDia = numeroNoDia;
        this.paradas = paradas;
    }

    public int getEntregadorId() {
        return entregadorId;
    }

    public int getNumeroNoDia() {
        return numeroNoDia;
    }

    public List<Parada> getParadas() {
        return paradas;
    }

    @Override
    public String toString() {
        return "RotaSolver{entregadorId=" + entregadorId
                + ", numeroNoDia=" + numeroNoDia
                + ", paradas=" + paradas.size() + "}";
    }
}
