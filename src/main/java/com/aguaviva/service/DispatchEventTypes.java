package com.aguaviva.service;

public final class DispatchEventTypes {

    public static final String PEDIDO_CRIADO = "PEDIDO_CRIADO";
    public static final String ROTA_INICIADA = "ROTA_INICIADA";
    public static final String PEDIDO_ENTREGUE = "PEDIDO_ENTREGUE";
    public static final String PEDIDO_FALHOU = "PEDIDO_FALHOU";
    public static final String PEDIDO_CANCELADO = "PEDIDO_CANCELADO";

    private DispatchEventTypes() {}

    public static boolean exigeReplanejamento(String eventType) {
        return policyForEvent(eventType).replaneja();
    }

    public static boolean exigeReplanejamentoImediato(String eventType) {
        return policyForEvent(eventType).triggerKind() == ReplanejamentoTriggerKind.SECUNDARIO;
    }

    public static ReplanejamentoPolicyMatrix.ReplanejamentoEventPolicy policyForEvent(String eventType) {
        return ReplanejamentoPolicyMatrix.policyForEvent(eventType);
    }
}
