package com.aguaviva.service;

public record ExecucaoEntregaResultado(
        String evento,
        int rotaId,
        int entregaId,
        int pedidoId,
        boolean idempotente
) {
}
