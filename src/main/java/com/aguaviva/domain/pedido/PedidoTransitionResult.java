package com.aguaviva.domain.pedido;

public record PedidoTransitionResult(PedidoStatus novoStatus, boolean geraCobrancaCancelamento) {}
