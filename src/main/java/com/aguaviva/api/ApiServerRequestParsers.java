package com.aguaviva.api;

import com.sun.net.httpserver.HttpExchange;

final class ApiServerRequestParsers {

    private ApiServerRequestParsers() {}

    static int requireInt(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " deve ser maior que zero");
        }
        return value;
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " obrigatorio");
        }
        return value;
    }

    static int parsePedidoIdTimeline(String path) {
        return parsePedidoIdWithSuffix(path, "/timeline", "timeline");
    }

    static int parsePedidoIdExecucao(String path) {
        return parsePedidoIdWithSuffix(path, "/execucao", "execucao");
    }

    static int parseEntregadorIdRoteiro(String path) {
        String prefix = "/api/entregadores/";
        String suffix = "/roteiro";
        if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) {
            throw new IllegalArgumentException("Path invalido para roteiro");
        }
        String entregadorIdRaw = path.substring(prefix.length(), path.length() - suffix.length());
        if (entregadorIdRaw.isBlank() || entregadorIdRaw.contains("/")) {
            throw new IllegalArgumentException("entregadorId invalido");
        }
        try {
            return requireInt(Integer.parseInt(entregadorIdRaw), "entregadorId");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("entregadorId invalido", e);
        }
    }

    static Integer parseLimiteQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            if (!"limite".equals(parts[0])) {
                continue;
            }
            if (parts.length < 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException("limite invalido");
            }
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("limite invalido", e);
            }
        }
        return null;
    }

    static String parseJobIdReplanejamento(String path) {
        String prefix = "/api/operacao/replanejamento/jobs/";
        if (path == null || !path.startsWith(prefix)) {
            throw new IllegalArgumentException("Path invalido para detalhe de replanejamento");
        }
        String jobId = path.substring(prefix.length());
        if (jobId.isBlank() || jobId.contains("/")) {
            throw new IllegalArgumentException("jobId invalido");
        }
        return jobId;
    }

    static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static boolean isOrigemCanalManual(String origemCanal) {
        if (origemCanal == null) {
            return false;
        }
        return "MANUAL".equalsIgnoreCase(origemCanal.trim());
    }

    static String resolveIdempotencyKeyHeader(HttpExchange exchange) {
        String idempotencyKey = normalizeOptionalText(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        String idempotencyKeyAlias = normalizeOptionalText(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
        if (idempotencyKey != null && idempotencyKeyAlias != null && !idempotencyKey.equals(idempotencyKeyAlias)) {
            throw new IllegalArgumentException("Idempotency-Key e X-Idempotency-Key devem ter o mesmo valor");
        }
        return idempotencyKey != null ? idempotencyKey : idempotencyKeyAlias;
    }

    private static int parsePedidoIdWithSuffix(String path, String suffix, String endpoint) {
        String prefix = "/api/pedidos/";
        if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) {
            throw new IllegalArgumentException("Path invalido para " + endpoint);
        }
        String pedidoIdRaw = path.substring(prefix.length(), path.length() - suffix.length());
        if (pedidoIdRaw.isBlank() || pedidoIdRaw.contains("/")) {
            throw new IllegalArgumentException("pedidoId invalido");
        }
        try {
            return requireInt(Integer.parseInt(pedidoIdRaw), "pedidoId");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("pedidoId invalido", e);
        }
    }
}
