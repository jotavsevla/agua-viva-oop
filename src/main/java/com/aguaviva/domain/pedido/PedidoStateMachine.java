package com.aguaviva.domain.pedido;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public final class PedidoStateMachine {

    private static final Map<PedidoStatus, EnumSet<PedidoStatus>> TRANSICOES_VALIDAS = Map.of(
            PedidoStatus.PENDENTE, EnumSet.of(PedidoStatus.CONFIRMADO, PedidoStatus.CANCELADO),
            PedidoStatus.CONFIRMADO, EnumSet.of(PedidoStatus.EM_ROTA, PedidoStatus.CANCELADO),
            PedidoStatus.EM_ROTA, EnumSet.of(PedidoStatus.ENTREGUE, PedidoStatus.CANCELADO),
            PedidoStatus.ENTREGUE, EnumSet.noneOf(PedidoStatus.class),
            PedidoStatus.CANCELADO, EnumSet.noneOf(PedidoStatus.class));

    private PedidoStateMachine() {}

    public static PedidoTransitionResult transicionar(PedidoStatus statusAtual, PedidoStatus statusDestino) {
        Objects.requireNonNull(statusAtual, "Status atual nao pode ser nulo");
        Objects.requireNonNull(statusDestino, "Status destino nao pode ser nulo");

        EnumSet<PedidoStatus> permitidas = TRANSICOES_VALIDAS.get(statusAtual);
        if (permitidas == null || !permitidas.contains(statusDestino)) {
            throw new IllegalStateException("Transicao de status invalida: " + statusAtual + " -> " + statusDestino);
        }

        boolean geraCobranca = statusAtual == PedidoStatus.EM_ROTA && statusDestino == PedidoStatus.CANCELADO;
        return new PedidoTransitionResult(statusDestino, geraCobranca);
    }
}
