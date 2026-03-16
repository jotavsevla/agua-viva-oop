package com.aguaviva.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SolverStubServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> lastSolveRequestBody = new AtomicReference<>("");

    private volatile int solveStatus = 200;
    private volatile String solveBody = """
            {"rotas":[],"nao_atendidos":[]}
            """;

    private volatile int resultStatus = 200;
    private volatile String resultBody = """
            {"job_id":"job-1","status":"CONCLUIDO","response":{"rotas":[],"nao_atendidos":[]},"erro":null}
            """;

    private volatile int cancelStatus = 202;
    private volatile String cancelBody = """
            {"status":"cancelamento_solicitado"}
            """;

    public SolverStubServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(0), 0);
        this.server.createContext("/solve", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            lastSolveRequestBody.set(new String(requestBytes, StandardCharsets.UTF_8));
            writeResponse(exchange, solveStatus, solveBody);
        });
        this.server.createContext("/result", exchange -> writeResponse(exchange, resultStatus, resultBody));
        this.server.createContext("/cancel", exchange -> writeResponse(exchange, cancelStatus, cancelBody));
        this.server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String lastSolveRequestBody() {
        return lastSolveRequestBody.get();
    }

    public void configureSolveResponse(int status, String jsonBody) {
        this.solveStatus = status;
        this.solveBody = Objects.requireNonNull(jsonBody);
    }

    public void configureResultResponse(int status, String jsonBody) {
        this.resultStatus = status;
        this.resultBody = Objects.requireNonNull(jsonBody);
    }

    public void configureCancelResponse(int status, String jsonBody) {
        this.cancelStatus = status;
        this.cancelBody = Objects.requireNonNull(jsonBody);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
