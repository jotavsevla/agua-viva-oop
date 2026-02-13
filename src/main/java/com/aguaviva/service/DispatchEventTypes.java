package com.aguaviva.service;

import java.util.Set;

public final class DispatchEventTypes {

    public static final String PEDIDO_CRIADO = "PEDIDO_CRIADO";
    public static final String ROTA_INICIADA = "ROTA_INICIADA";
    public static final String PEDIDO_ENTREGUE = "PEDIDO_ENTREGUE";
    public static final String PEDIDO_FALHOU = "PEDIDO_FALHOU";
    public static final String PEDIDO_CANCELADO = "PEDIDO_CANCELADO";

    private static final Set<String> REPLANEJAMENTO_TRIGGER = Set.of(
            PEDIDO_CRIADO,
            PEDIDO_FALHOU,
            PEDIDO_CANCELADO
    );

    private DispatchEventTypes() {
    }

    public static boolean exigeReplanejamento(String eventType) {
        return REPLANEJAMENTO_TRIGGER.contains(eventType);
    }
}
