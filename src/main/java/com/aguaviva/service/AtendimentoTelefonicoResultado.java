package com.aguaviva.service;

public record AtendimentoTelefonicoResultado(
        int pedidoId, int clienteId, String telefoneNormalizado, boolean clienteCriado, boolean idempotente) {}
