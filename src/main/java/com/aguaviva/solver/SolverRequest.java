package com.aguaviva.solver;

import java.util.List;
import java.util.Objects;

public record SolverRequest(
        String jobId,
        Long planVersion,
        Coordenada deposito,
        int capacidadeVeiculo,
        List<Integer> capacidadesEntregadores,
        String horarioInicio,
        String horarioFim,
        List<Integer> entregadores,
        List<PedidoSolver> pedidos) {
    public SolverRequest {
        Objects.requireNonNull(deposito, "Deposito nao pode ser nulo");
        Objects.requireNonNull(entregadores, "Entregadores nao pode ser nulo");
        Objects.requireNonNull(pedidos, "Pedidos nao pode ser nulo");
        if (capacidadesEntregadores != null && capacidadesEntregadores.size() != entregadores.size()) {
            throw new IllegalArgumentException("capacidadesEntregadores deve ter mesmo tamanho de entregadores");
        }
        if (capacidadesEntregadores != null
                && capacidadesEntregadores.stream().anyMatch(capacidade -> capacidade == null || capacidade < 0)) {
            throw new IllegalArgumentException("capacidadesEntregadores nao pode conter valores negativos");
        }
        jobId = jobId == null || jobId.isBlank() ? null : jobId.trim();
        capacidadesEntregadores = capacidadesEntregadores == null ? null : List.copyOf(capacidadesEntregadores);
        entregadores = List.copyOf(entregadores);
        pedidos = List.copyOf(pedidos);
    }

    public static SolverRequest of(
            String jobId,
            Long planVersion,
            Coordenada deposito,
            int capacidadeVeiculo,
            String horarioInicio,
            String horarioFim,
            List<Integer> entregadores,
            List<PedidoSolver> pedidos) {
        return new SolverRequest(
                jobId, planVersion, deposito, capacidadeVeiculo, null, horarioInicio, horarioFim, entregadores, pedidos);
    }

    public static SolverRequest of(
            Coordenada deposito,
            int capacidadeVeiculo,
            String horarioInicio,
            String horarioFim,
            List<Integer> entregadores,
            List<PedidoSolver> pedidos) {
        return new SolverRequest(null, null, deposito, capacidadeVeiculo, null, horarioInicio, horarioFim, entregadores, pedidos);
    }
}
