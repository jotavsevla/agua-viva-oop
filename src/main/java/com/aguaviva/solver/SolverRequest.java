package com.aguaviva.solver;

import java.util.List;
import java.util.Objects;

public final class SolverRequest {

    private final Coordenada deposito;
    private final int capacidadeVeiculo;
    private final String horarioInicio;
    private final String horarioFim;
    private final List<Integer> entregadores;
    private final List<PedidoSolver> pedidos;

    public SolverRequest(Coordenada deposito, int capacidadeVeiculo,
                         String horarioInicio, String horarioFim,
                         List<Integer> entregadores, List<PedidoSolver> pedidos) {
        Objects.requireNonNull(deposito, "Deposito nao pode ser nulo");
        Objects.requireNonNull(entregadores, "Entregadores nao pode ser nulo");
        Objects.requireNonNull(pedidos, "Pedidos nao pode ser nulo");
        this.deposito = deposito;
        this.capacidadeVeiculo = capacidadeVeiculo;
        this.horarioInicio = horarioInicio;
        this.horarioFim = horarioFim;
        this.entregadores = List.copyOf(entregadores);
        this.pedidos = List.copyOf(pedidos);
    }

    public Coordenada getDeposito() { return deposito; }
    public int getCapacidadeVeiculo() { return capacidadeVeiculo; }
    public String getHorarioInicio() { return horarioInicio; }
    public String getHorarioFim() { return horarioFim; }
    public List<Integer> getEntregadores() { return entregadores; }
    public List<PedidoSolver> getPedidos() { return pedidos; }
}
