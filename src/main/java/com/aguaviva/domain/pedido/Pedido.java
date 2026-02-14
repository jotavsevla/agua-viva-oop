package com.aguaviva.domain.pedido;

import java.time.LocalTime;
import java.util.Objects;

public final class Pedido {

    private final int id;
    private final int clienteId;
    private final int quantidadeGaloes;
    private final JanelaTipo janelaTipo;
    private final LocalTime janelaInicio;
    private final LocalTime janelaFim;
    private final PedidoStatus status;
    private final int criadoPorUserId;

    public Pedido(
            int id,
            int clienteId,
            int quantidadeGaloes,
            JanelaTipo janelaTipo,
            LocalTime janelaInicio,
            LocalTime janelaFim,
            PedidoStatus status,
            int criadoPorUserId) {
        validarId(id);
        validarClienteId(clienteId);
        validarQuantidadeGaloes(quantidadeGaloes);
        Objects.requireNonNull(janelaTipo, "JanelaTipo nao pode ser nulo");
        validarJanela(janelaTipo, janelaInicio, janelaFim);
        Objects.requireNonNull(status, "Status nao pode ser nulo");
        validarCriadoPorUserId(criadoPorUserId);

        this.id = id;
        this.clienteId = clienteId;
        this.quantidadeGaloes = quantidadeGaloes;
        this.janelaTipo = janelaTipo;
        this.janelaInicio = janelaInicio;
        this.janelaFim = janelaFim;
        this.status = status;
        this.criadoPorUserId = criadoPorUserId;
    }

    public Pedido(
            int clienteId,
            int quantidadeGaloes,
            JanelaTipo janelaTipo,
            LocalTime janelaInicio,
            LocalTime janelaFim,
            int criadoPorUserId) {
        this(
                0,
                clienteId,
                quantidadeGaloes,
                janelaTipo,
                janelaInicio,
                janelaFim,
                PedidoStatus.PENDENTE,
                criadoPorUserId);
    }

    public int getId() {
        return id;
    }

    public int getClienteId() {
        return clienteId;
    }

    public int getQuantidadeGaloes() {
        return quantidadeGaloes;
    }

    public JanelaTipo getJanelaTipo() {
        return janelaTipo;
    }

    public LocalTime getJanelaInicio() {
        return janelaInicio;
    }

    public LocalTime getJanelaFim() {
        return janelaFim;
    }

    public PedidoStatus getStatus() {
        return status;
    }

    public int getCriadoPorUserId() {
        return criadoPorUserId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Pedido pedido = (Pedido) o;
        if (this.id == 0 || pedido.id == 0) return false;
        return this.id == pedido.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Pedido{id=" + id
                + ", clienteId=" + clienteId
                + ", quantidadeGaloes=" + quantidadeGaloes
                + ", janelaTipo=" + janelaTipo
                + ", status=" + status + "}";
    }

    private static void validarId(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("Id nao pode ser negativo");
        }
    }

    private static void validarClienteId(int clienteId) {
        if (clienteId <= 0) {
            throw new IllegalArgumentException("ClienteId deve ser maior que zero");
        }
    }

    private static void validarQuantidadeGaloes(int quantidadeGaloes) {
        if (quantidadeGaloes <= 0) {
            throw new IllegalArgumentException("Quantidade de galoes deve ser maior que zero");
        }
    }

    private static void validarCriadoPorUserId(int criadoPorUserId) {
        if (criadoPorUserId <= 0) {
            throw new IllegalArgumentException("CriadoPorUserId deve ser maior que zero");
        }
    }

    private static void validarJanela(JanelaTipo janelaTipo, LocalTime janelaInicio, LocalTime janelaFim) {
        if (janelaTipo == JanelaTipo.ASAP) {
            if (janelaInicio != null || janelaFim != null) {
                throw new IllegalArgumentException("Pedido ASAP nao deve ter janela de horario");
            }
            return;
        }

        if (janelaInicio == null || janelaFim == null) {
            throw new IllegalArgumentException("Pedido HARD deve informar janelaInicio e janelaFim");
        }
        if (!janelaInicio.isBefore(janelaFim)) {
            throw new IllegalArgumentException("janelaInicio deve ser anterior a janelaFim");
        }
    }
}
