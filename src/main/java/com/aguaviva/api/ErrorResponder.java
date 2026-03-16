package com.aguaviva.api;

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

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", CORS_ALLOW_METHODS);
    }
}
