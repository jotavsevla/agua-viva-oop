package com.aguaviva.api;

import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.service.AtendimentoTelefonicoResultado;
import com.aguaviva.service.AtendimentoTelefonicoService;
import com.aguaviva.service.DispatchEventTypes;
import com.aguaviva.service.ExecucaoEntregaResultado;
import com.aguaviva.service.ExecucaoEntregaService;
import com.aguaviva.service.ReplanejamentoWorkerResultado;
import com.aguaviva.service.ReplanejamentoWorkerService;
import com.aguaviva.service.RotaService;
import com.aguaviva.solver.SolverClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ApiServer {

    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final AtendimentoTelefonicoService atendimentoTelefonicoService;
    private final ExecucaoEntregaService execucaoEntregaService;
    private final ReplanejamentoWorkerService replanejamentoWorkerService;

    private ApiServer(
            AtendimentoTelefonicoService atendimentoTelefonicoService,
            ExecucaoEntregaService execucaoEntregaService,
            ReplanejamentoWorkerService replanejamentoWorkerService
    ) {
        this.atendimentoTelefonicoService = Objects.requireNonNull(atendimentoTelefonicoService);
        this.execucaoEntregaService = Objects.requireNonNull(execucaoEntregaService);
        this.replanejamentoWorkerService = Objects.requireNonNull(replanejamentoWorkerService);
    }

    public static void startFromEnv() throws IOException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        String solverUrl = env("SOLVER_URL", "http://localhost:8080");
        int port = Integer.parseInt(env("API_PORT", "8081"));

        SolverClient solverClient = new SolverClient(solverUrl);
        RotaService rotaService = new RotaService(solverClient, connectionFactory);

        AtendimentoTelefonicoService atendimentoTelefonicoService = new AtendimentoTelefonicoService(connectionFactory);
        ExecucaoEntregaService execucaoEntregaService = new ExecucaoEntregaService(connectionFactory);
        ReplanejamentoWorkerService workerService = new ReplanejamentoWorkerService(
                connectionFactory,
                rotaService::planejarRotasPendentes
        );

        ApiServer app = new ApiServer(atendimentoTelefonicoService, execucaoEntregaService, workerService);
        app.start(port);
    }

    private void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/api/atendimento/pedidos", new AtendimentoHandler());
        server.createContext("/api/eventos", new EventoOperacionalHandler());
        server.createContext("/api/replanejamento/run", new ReplanejamentoHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("API online na porta " + port);
        System.out.println("Endpoints: /health, /api/atendimento/pedidos, /api/eventos, /api/replanejamento/run");
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }
            writeJson(exchange, 200, Map.of("status", "ok"));
        }
    }

    private final class AtendimentoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            try {
                AtendimentoRequest req = parseBody(exchange, AtendimentoRequest.class);
                AtendimentoTelefonicoResultado resultado = atendimentoTelefonicoService.registrarPedido(
                        requireText(req.externalCallId(), "external_call_id"),
                        requireText(req.telefone(), "telefone"),
                        requireInt(req.quantidadeGaloes(), "quantidade_galoes"),
                        requireInt(req.atendenteId(), "atendente_id")
                );
                writeJson(exchange, 200, resultado);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(exchange, 500, Map.of("erro", "Falha no atendimento telefonico", "detalhe", e.getMessage()));
            }
        }
    }

    private final class EventoOperacionalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            try {
                EventoRequest req = parseBody(exchange, EventoRequest.class);
                String eventType = requireText(req.eventType(), "event_type").toUpperCase(Locale.ROOT);
                ExecucaoEntregaResultado resultado;

                switch (eventType) {
                    case DispatchEventTypes.ROTA_INICIADA ->
                            resultado = execucaoEntregaService.registrarRotaIniciada(requireInt(req.rotaId(), "rota_id"));
                    case DispatchEventTypes.PEDIDO_ENTREGUE ->
                            resultado = execucaoEntregaService.registrarPedidoEntregue(requireInt(req.entregaId(), "entrega_id"));
                    case DispatchEventTypes.PEDIDO_FALHOU ->
                            resultado = execucaoEntregaService.registrarPedidoFalhou(requireInt(req.entregaId(), "entrega_id"), req.motivo());
                    case DispatchEventTypes.PEDIDO_CANCELADO ->
                            resultado = execucaoEntregaService.registrarPedidoCancelado(
                                    requireInt(req.entregaId(), "entrega_id"),
                                    req.motivo(),
                                    req.cobrancaCancelamentoCentavos()
                            );
                    default -> throw new IllegalArgumentException("event_type invalido: " + eventType);
                }

                writeJson(exchange, 200, resultado);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (IllegalStateException e) {
                writeJson(exchange, 409, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(exchange, 500, Map.of("erro", "Falha ao processar evento operacional", "detalhe", e.getMessage()));
            }
        }
    }

    private final class ReplanejamentoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            try {
                ReplanejamentoRequest req = parseBody(exchange, ReplanejamentoRequest.class);
                int debounce = req.debounceSegundos() == null ? 20 : req.debounceSegundos();
                int limite = req.limiteEventos() == null ? 100 : req.limiteEventos();

                ReplanejamentoWorkerResultado resultado = replanejamentoWorkerService.processarPendentes(debounce, limite);
                writeJson(exchange, 200, resultado);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(exchange, 500, Map.of("erro", "Falha ao executar worker de replanejamento", "detalhe", e.getMessage()));
            }
        }
    }

    private <T> T parseBody(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                throw new IllegalArgumentException("Body JSON obrigatorio");
            }
            T parsed = gson.fromJson(body, type);
            if (parsed == null) {
                throw new IllegalArgumentException("Body JSON invalido");
            }
            return parsed;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("JSON invalido", e);
        }
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        String json = gson.toJson(payload);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static int requireInt(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " deve ser maior que zero");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " obrigatorio");
        }
        return value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record AtendimentoRequest(
            String externalCallId,
            String telefone,
            Integer quantidadeGaloes,
            Integer atendenteId
    ) {
    }

    private record EventoRequest(
            String eventType,
            Integer rotaId,
            Integer entregaId,
            String motivo,
            Integer cobrancaCancelamentoCentavos
    ) {
    }

    private record ReplanejamentoRequest(Integer debounceSegundos, Integer limiteEventos) {
    }
}
