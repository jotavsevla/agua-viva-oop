package com.aguaviva.api;

import com.aguaviva.domain.exception.BusinessRuleException;
import com.aguaviva.domain.exception.DatabaseException;
import com.aguaviva.domain.exception.DuplicateEntityException;
import com.aguaviva.domain.exception.EntityNotFoundException;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ErrorResponder {

    private static final Gson GSON = new Gson();

    private static final String CORS_ALLOW_ORIGIN = "*";
    private static final String CORS_ALLOW_HEADERS = "Content-Type,Idempotency-Key,X-Idempotency-Key";
    private static final String CORS_ALLOW_METHODS = "GET,POST,OPTIONS";

    private ErrorResponder() {}

    public static void send(HttpExchange exchange, int statusCode, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        send(exchange, statusCode, GSON.toJson(Map.of("erro", message)));
    }

    public static void sendInternalError(HttpExchange exchange) throws IOException {
        sendError(exchange, 500, "Erro interno do servidor");
    }

    public static void sendMappedError(HttpExchange exchange, Exception exception) throws IOException {
        int statusCode = mapStatusCode(exception);
        if (statusCode == 500) {
            sendInternalError(exchange);
            return;
        }
        sendError(exchange, statusCode, resolvePublicMessage(exception));
    }

    private static int mapStatusCode(Exception exception) {
        if (exception instanceof DatabaseException) {
            return 500;
        }
        if (exception instanceof EntityNotFoundException) {
            return 404;
        }
        if (exception instanceof DuplicateEntityException) {
            return 409;
        }
        if (exception instanceof BusinessRuleException) {
            return 400;
        }
        if (exception instanceof IllegalStateException) {
            return 409;
        }
        if (exception instanceof IllegalArgumentException) {
            String message = exception.getMessage();
            if (message != null && message.startsWith("Pedido nao encontrado com id:")) {
                return 404;
            }
            return 400;
        }
        return 500;
    }

    private static String resolvePublicMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Erro na requisicao";
        }
        return message;
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", CORS_ALLOW_METHODS);
    }
}
