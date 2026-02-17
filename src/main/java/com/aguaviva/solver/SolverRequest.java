package com.aguaviva.solver;

import java.util.List;
import java.util.Objects;

public final class SolverRequest {

    private final String jobId;
    private final Long planVersion;
    private final Coordenada deposito;
    private final int capacidadeVeiculo;
    private final List<Integer> capacidadesEntregadores;
    private final String horarioInicio;
    private final String horarioFim;
    private final List<Integer> entregadores;
    private final List<PedidoSolver> pedidos;

    public SolverRequest(
            String jobId,
            Long planVersion,
            Coordenada deposito,
            int capacidadeVeiculo,
            List<Integer> capacidadesEntregadores,
            String horarioInicio,
            String horarioFim,
            List<Integer> entregadores,
            List<PedidoSolver> pedidos) {
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
        this.jobId = normalizeOptional(jobId);
        this.planVersion = planVersion;
        this.deposito = deposito;
        this.capacidadeVeiculo = capacidadeVeiculo;
        this.capacidadesEntregadores = capacidadesEntregadores == null ? null : List.copyOf(capacidadesEntregadores);
        this.horarioInicio = horarioInicio;
        this.horarioFim = horarioFim;
        this.entregadores = List.copyOf(entregadores);
        this.pedidos = List.copyOf(pedidos);
    }

    public SolverRequest(
            String jobId,
            Long planVersion,
            Coordenada deposito,
            int capacidadeVeiculo,
            String horarioInicio,
            String horarioFim,
            List<Integer> entregadores,
            List<PedidoSolver> pedidos) {
        this(jobId, planVersion, deposito, capacidadeVeiculo, null, horarioInicio, horarioFim, entregadores, pedidos);
    }

    public SolverRequest(
            Coordenada deposito,
            int capacidadeVeiculo,
            String horarioInicio,
            String horarioFim,
            List<Integer> entregadores,
            List<PedidoSolver> pedidos) {
        this(null, null, deposito, capacidadeVeiculo, null, horarioInicio, horarioFim, entregadores, pedidos);
    }

    public String getJobId() {
        return jobId;
    }

    public Long getPlanVersion() {
        return planVersion;
    }

    public Coordenada getDeposito() {
        return deposito;
    }

    public int getCapacidadeVeiculo() {
        return capacidadeVeiculo;
    }

    public List<Integer> getCapacidadesEntregadores() {
        return capacidadesEntregadores;
    }

    public String getHorarioInicio() {
        return horarioInicio;
    }

    public String getHorarioFim() {
        return horarioFim;
    }

    public List<Integer> getEntregadores() {
        return entregadores;
    }

    public List<PedidoSolver> getPedidos() {
        return pedidos;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
