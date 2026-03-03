package com.aguaviva.api.dto.request;

public record AtendimentoRequestDto(
        String externalCallId,
        String sourceEventId,
        String manualRequestId,
        String origemCanal,
        String telefone,
        Integer quantidadeGaloes,
        Integer atendenteId,
        String metodoPagamento,
        String janelaTipo,
        String janelaInicio,
        String janelaFim,
        String nomeCliente,
        String endereco,
        Double latitude,
        Double longitude) {}
