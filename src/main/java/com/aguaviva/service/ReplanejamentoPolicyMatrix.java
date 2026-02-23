package com.aguaviva.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReplanejamentoPolicyMatrix {

    public static final ReplanejamentoEventPolicy NONE =
            new ReplanejamentoEventPolicy(ReplanejamentoTriggerKind.NONE, CapacidadePolicy.REMANESCENTE);

    private static final Map<String, ReplanejamentoEventPolicy> POLICIES = Map.of(
            DispatchEventTypes.PEDIDO_CRIADO,
            new ReplanejamentoEventPolicy(ReplanejamentoTriggerKind.PRIMARIO, CapacidadePolicy.CHEIA),
            DispatchEventTypes.PEDIDO_FALHOU,
            new ReplanejamentoEventPolicy(ReplanejamentoTriggerKind.SECUNDARIO, CapacidadePolicy.REMANESCENTE),
            DispatchEventTypes.PEDIDO_CANCELADO,
            new ReplanejamentoEventPolicy(ReplanejamentoTriggerKind.SECUNDARIO, CapacidadePolicy.REMANESCENTE),
            DispatchEventTypes.PEDIDO_ENTREGUE,
            NONE,
            DispatchEventTypes.ROTA_INICIADA,
            NONE,
            DispatchEventTypes.ROTA_CONCLUIDA,
            new ReplanejamentoEventPolicy(ReplanejamentoTriggerKind.SECUNDARIO, CapacidadePolicy.REMANESCENTE));

    private ReplanejamentoPolicyMatrix() {}

    public static ReplanejamentoEventPolicy policyForEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return NONE;
        }
        return POLICIES.getOrDefault(eventType, NONE);
    }

    public static ReplanejamentoEventPolicy consolidate(List<String> eventTypes) {
        Objects.requireNonNull(eventTypes, "eventTypes nao pode ser nulo");

        ReplanejamentoTriggerKind kind = ReplanejamentoTriggerKind.NONE;
        for (String eventType : eventTypes) {
            ReplanejamentoTriggerKind candidate = policyForEvent(eventType).triggerKind();
            if (candidate == ReplanejamentoTriggerKind.SECUNDARIO) {
                kind = ReplanejamentoTriggerKind.SECUNDARIO;
                break;
            }
            if (candidate == ReplanejamentoTriggerKind.PRIMARIO) {
                kind = ReplanejamentoTriggerKind.PRIMARIO;
            }
        }

        return policyForTrigger(kind);
    }

    public static ReplanejamentoEventPolicy policyForTrigger(ReplanejamentoTriggerKind triggerKind) {
        return switch (Objects.requireNonNull(triggerKind, "triggerKind nao pode ser nulo")) {
            case PRIMARIO -> new ReplanejamentoEventPolicy(ReplanejamentoTriggerKind.PRIMARIO, CapacidadePolicy.CHEIA);
            case SECUNDARIO ->
                new ReplanejamentoEventPolicy(ReplanejamentoTriggerKind.SECUNDARIO, CapacidadePolicy.REMANESCENTE);
            case NONE -> NONE;
        };
    }

    public record ReplanejamentoEventPolicy(ReplanejamentoTriggerKind triggerKind, CapacidadePolicy capacidadePolicy) {
        public ReplanejamentoEventPolicy {
            Objects.requireNonNull(triggerKind, "triggerKind nao pode ser nulo");
            Objects.requireNonNull(capacidadePolicy, "capacidadePolicy nao pode ser nulo");
        }

        public boolean replaneja() {
            return triggerKind.replaneja();
        }
    }
}
