package com.aguaviva.api.dto.request;

public record EventoRequestDto(
        String externalEventId,
        String eventType,
        Integer rotaId,
        Integer entregaId,
        Integer actorEntregadorId,
        String motivo,
        Integer cobrancaCancelamentoCentavos) {}
