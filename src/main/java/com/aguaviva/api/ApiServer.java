package com.aguaviva.api;

import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.service.AtendimentoTelefonicoResultado;
import com.aguaviva.service.AtendimentoTelefonicoService;
import com.aguaviva.service.DispatchEventTypes;
import com.aguaviva.service.EventoOperacionalIdempotenciaService;
import com.aguaviva.service.ExecucaoEntregaResultado;
import com.aguaviva.service.ExecucaoEntregaService;
import com.aguaviva.service.OperacaoEventosService;
import com.aguaviva.service.OperacaoMapaService;
import com.aguaviva.service.OperacaoPainelService;
import com.aguaviva.service.OperacaoReplanejamentoService;
import com.aguaviva.service.PedidoExecucaoService;
import com.aguaviva.service.PedidoTimelineService;
import com.aguaviva.service.ReplanejamentoWorkerService;
import com.aguaviva.service.RotaService;
import com.aguaviva.service.RoteiroEntregadorService;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ApiServer {

    private static final String CORS_ALLOW_ORIGIN = "*";
    private static final String CORS_ALLOW_HEADERS = "Content-Type,Idempotency-Key,X-Idempotency-Key";
    private static final String CORS_ALLOW_METHODS = "GET,POST,OPTIONS";
    private static final String TEST_VERBOSE_PROPERTY = "aguaviva.test.verbose";

    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final AtendimentoTelefonicoService atendimentoTelefonicoService;
    private final ExecucaoEntregaService execucaoEntregaService;
    private final EventoOperacionalIdempotenciaService eventoOperacionalIdempotenciaService;
    private final ReplanejamentoWorkerService replanejamentoWorkerService;
    private final PedidoTimelineService pedidoTimelineService;
    private final PedidoExecucaoService pedidoExecucaoService;
    private final RoteiroEntregadorService roteiroEntregadorService;
    private final OperacaoPainelService operacaoPainelService;
    private final OperacaoEventosService operacaoEventosService;
    private final OperacaoMapaService operacaoMapaService;
    private final OperacaoReplanejamentoService operacaoReplanejamentoService;
    private final boolean startupLogsEnabled;

    private ApiServer(
            AtendimentoTelefonicoService atendimentoTelefonicoService,
            ExecucaoEntregaService execucaoEntregaService,
            EventoOperacionalIdempotenciaService eventoOperacionalIdempotenciaService,
            ReplanejamentoWorkerService replanejamentoWorkerService,
            PedidoTimelineService pedidoTimelineService,
            PedidoExecucaoService pedidoExecucaoService,
            RoteiroEntregadorService roteiroEntregadorService,
            OperacaoPainelService operacaoPainelService,
            OperacaoEventosService operacaoEventosService,
            OperacaoMapaService operacaoMapaService,
            OperacaoReplanejamentoService operacaoReplanejamentoService,
            boolean startupLogsEnabled) {
        this.atendimentoTelefonicoService = Objects.requireNonNull(atendimentoTelefonicoService);
        this.execucaoEntregaService = Objects.requireNonNull(execucaoEntregaService);
        this.eventoOperacionalIdempotenciaService = Objects.requireNonNull(eventoOperacionalIdempotenciaService);
        this.replanejamentoWorkerService = Objects.requireNonNull(replanejamentoWorkerService);
        this.pedidoTimelineService = Objects.requireNonNull(pedidoTimelineService);
        this.pedidoExecucaoService = Objects.requireNonNull(pedidoExecucaoService);
        this.roteiroEntregadorService = Objects.requireNonNull(roteiroEntregadorService);
        this.operacaoPainelService = Objects.requireNonNull(operacaoPainelService);
        this.operacaoEventosService = Objects.requireNonNull(operacaoEventosService);
        this.operacaoMapaService = Objects.requireNonNull(operacaoMapaService);
        this.operacaoReplanejamentoService = Objects.requireNonNull(operacaoReplanejamentoService);
        this.startupLogsEnabled = startupLogsEnabled;
    }

    public static void startFromEnv() throws IOException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        String solverUrl = env("SOLVER_URL", "http://localhost:8080");
        int port = Integer.parseInt(env("API_PORT", "8081"));

        SolverClient solverClient = new SolverClient(solverUrl);
        RotaService rotaService = new RotaService(solverClient, connectionFactory);

        AtendimentoTelefonicoService atendimentoTelefonicoService = new AtendimentoTelefonicoService(connectionFactory);
        ExecucaoEntregaService execucaoEntregaService = new ExecucaoEntregaService(connectionFactory);
        EventoOperacionalIdempotenciaService eventoOperacionalIdempotenciaService =
                new EventoOperacionalIdempotenciaService(connectionFactory);
        ReplanejamentoWorkerService workerService = new ReplanejamentoWorkerService(
                connectionFactory,
                capacidadePolicy -> rotaService.planejarRotasPendentes(capacidadePolicy),
                rotaService::cancelarPlanejamentosAtivosBestEffort);
        PedidoTimelineService pedidoTimelineService = new PedidoTimelineService(connectionFactory);
        PedidoExecucaoService pedidoExecucaoService = new PedidoExecucaoService(connectionFactory);
        RoteiroEntregadorService roteiroEntregadorService = new RoteiroEntregadorService(connectionFactory);
        OperacaoPainelService operacaoPainelService = new OperacaoPainelService(connectionFactory);
        OperacaoEventosService operacaoEventosService = new OperacaoEventosService(connectionFactory);
        OperacaoMapaService operacaoMapaService = new OperacaoMapaService(connectionFactory);
        OperacaoReplanejamentoService operacaoReplanejamentoService =
                new OperacaoReplanejamentoService(connectionFactory);

        ApiServer app = new ApiServer(
                atendimentoTelefonicoService,
                execucaoEntregaService,
                eventoOperacionalIdempotenciaService,
                workerService,
                pedidoTimelineService,
                pedidoExecucaoService,
                roteiroEntregadorService,
                operacaoPainelService,
                operacaoEventosService,
                operacaoMapaService,
                operacaoReplanejamentoService,
                true);
        app.start(port);
    }

    private RunningServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/api/atendimento/pedidos", new AtendimentoHandler());
        server.createContext("/api/eventos", new EventoOperacionalHandler());
        server.createContext("/api/replanejamento/run", new ReplanejamentoHandler());
        server.createContext("/api/pedidos", new PedidoOperacionalHandler());
        server.createContext("/api/entregadores", new EntregadorRoteiroHandler());
        server.createContext("/api/operacao", new OperacaoReadOnlyHandler());
        server.createContext("/api/operacao/rotas/prontas/iniciar", new IniciarRotaProntaHandler());
        server.setExecutor(null);
        server.start();

        int resolvedPort = server.getAddress().getPort();
        if (startupLogsEnabled) {
            System.out.println("API online na porta " + resolvedPort);
            System.out.println("Endpoints: /health, /api/atendimento/pedidos, /api/eventos, /api/replanejamento/run, "
                    + "/api/pedidos/{pedidoId}/timeline, /api/pedidos/{pedidoId}/execucao, "
                    + "/api/entregadores/{entregadorId}/roteiro, "
                    + "/api/operacao/painel, /api/operacao/eventos, /api/operacao/mapa, "
                    + "/api/operacao/replanejamento/jobs, /api/operacao/replanejamento/jobs/{jobId}, "
                    + "/api/operacao/rotas/prontas/iniciar");
        }
        return new RunningServer(server, resolvedPort);
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
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
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            try {
                AtendimentoRequest req = parseBody(exchange, AtendimentoRequest.class);
                String telefone = requireText(req.telefone(), "telefone");
                int quantidadeGaloes = requireInt(req.quantidadeGaloes(), "quantidade_galoes");
                int atendenteId = requireInt(req.atendenteId(), "atendente_id");
                String metodoPagamento = req.metodoPagamento();
                String janelaTipo = req.janelaTipo();
                String janelaInicio = req.janelaInicio();
                String janelaFim = req.janelaFim();
                String origemCanal = req.origemCanal();
                boolean origemCanalManual = isOrigemCanalManual(origemCanal);
                String sourceEventId = normalizeOptionalText(req.sourceEventId());
                String manualRequestId = normalizeOptionalText(req.manualRequestId());
                String externalCallId = normalizeOptionalText(req.externalCallId());
                String idempotencyKeyHeader = resolveIdempotencyKeyHeader(exchange);
                boolean origemCanalOmitida = origemCanal == null || origemCanal.isBlank();
                if (sourceEventId != null && externalCallId != null && !sourceEventId.equals(externalCallId)) {
                    throw new IllegalArgumentException("sourceEventId diverge de externalCallId");
                }
                if (manualRequestId != null && externalCallId != null && !manualRequestId.equals(externalCallId)) {
                    throw new IllegalArgumentException("manualRequestId diverge de externalCallId");
                }
                if (manualRequestId != null
                        && idempotencyKeyHeader != null
                        && !manualRequestId.equals(idempotencyKeyHeader)) {
                    throw new IllegalArgumentException("manualRequestId diverge do header Idempotency-Key");
                }
                if (origemCanalOmitida
                        && sourceEventId == null
                        && manualRequestId == null
                        && externalCallId != null
                        && idempotencyKeyHeader != null
                        && !externalCallId.equals(idempotencyKeyHeader)) {
                    throw new IllegalArgumentException("Idempotency-Key diverge de externalCallId quando origemCanal for omitida");
                }
                if (manualRequestId == null && origemCanalManual) {
                    manualRequestId = idempotencyKeyHeader;
                }
                if (sourceEventId == null) {
                    if (origemCanalManual) {
                        if (manualRequestId == null) {
                            manualRequestId = externalCallId;
                        }
                    } else {
                        sourceEventId = externalCallId;
                    }
                }
                if (manualRequestId == null
                        && sourceEventId == null
                        && origemCanalOmitida
                        && idempotencyKeyHeader != null) {
                    manualRequestId = idempotencyKeyHeader;
                }
                if (!origemCanalManual
                        && sourceEventId != null
                        && idempotencyKeyHeader != null
                        && !sourceEventId.equals(idempotencyKeyHeader)) {
                    throw new IllegalArgumentException("sourceEventId diverge do header Idempotency-Key");
                }
                if (manualRequestId != null && externalCallId != null && !manualRequestId.equals(externalCallId)) {
                    throw new IllegalArgumentException("manualRequestId diverge de externalCallId");
                }

                AtendimentoTelefonicoResultado resultado = atendimentoTelefonicoService.registrarPedidoOmnichannel(
                        origemCanal,
                        sourceEventId,
                        manualRequestId,
                        telefone,
                        quantidadeGaloes,
                        atendenteId,
                        metodoPagamento,
                        janelaTipo,
                        janelaInicio,
                        janelaFim,
                        req.nomeCliente(),
                        req.endereco(),
                        req.latitude(),
                        req.longitude());
                writeJson(exchange, 200, resultado);
                dispararReplanejamentoAssincronoSeNecessario(DispatchEventTypes.PEDIDO_CRIADO, resultado.idempotente());
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (IllegalStateException e) {
                writeJson(exchange, 409, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(exchange, 500, Map.of("erro", "Falha no atendimento telefonico", "detalhe", e.getMessage()));
            }
        }
    }

    private final class EventoOperacionalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            try {
                EventoRequest req = parseBody(exchange, EventoRequest.class);
                String eventType = requireText(req.eventType(), "event_type").toUpperCase(Locale.ROOT);
                String externalEventId = normalizeOptionalText(req.externalEventId());
                ExecucaoEntregaResultado resultadoProcessamento;
                if (externalEventId == null) {
                    resultadoProcessamento = processarEventoOperacional(eventType, req);
                    writeJson(exchange, 200, resultadoProcessamento);
                    dispararReplanejamentoAssincronoSeNecessario(eventType, resultadoProcessamento);
                    return;
                }

                ScopeRef scopeRef = resolveScope(eventType, req);
                String requestHash = buildEventoRequestHash(eventType, req);
                EventoOperacionalIdempotenciaService.Resultado resultadoIdempotencia =
                        eventoOperacionalIdempotenciaService.processar(
                                externalEventId,
                                requestHash,
                                eventType,
                                scopeRef.scopeType(),
                                scopeRef.scopeId(),
                                () -> processarEventoOperacional(eventType, req));
                if (resultadoIdempotencia.conflito()) {
                    writeJson(exchange, 409, Map.of("erro", resultadoIdempotencia.erroConflito()));
                    return;
                }

                resultadoProcessamento = resultadoIdempotencia.payload();
                writeJson(exchange, 200, resultadoProcessamento);
                dispararReplanejamentoAssincronoSeNecessario(eventType, resultadoProcessamento);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (IllegalStateException e) {
                writeJson(exchange, 409, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(
                        exchange,
                        500,
                        Map.of("erro", "Falha ao processar evento operacional", "detalhe", e.getMessage()));
            }
        }
    }

    private final class ReplanejamentoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            writeJson(
                    exchange,
                    409,
                    Map.of(
                            "erro",
                            "Endpoint desativado: replanejamento manual nao e permitido. Use o fluxo orientado a eventos."));
        }
    }

    private final class IniciarRotaProntaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            try {
                IniciarRotaProntaRequest req = parseBody(exchange, IniciarRotaProntaRequest.class);
                int entregadorId = requireInt(req.entregadorId(), "entregadorId");
                ExecucaoEntregaResultado resultado = execucaoEntregaService.iniciarProximaRotaPronta(entregadorId);
                writeJson(exchange, 200, resultado);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (IllegalStateException e) {
                writeJson(exchange, 409, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(exchange, 500, Map.of("erro", "Falha ao iniciar rota pronta", "detalhe", e.getMessage()));
            }
        }
    }

    private final class PedidoOperacionalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            try {
                if (path.endsWith("/timeline")) {
                    int pedidoId = parsePedidoIdTimeline(path);
                    PedidoTimelineService.PedidoTimelineResultado resultado =
                            pedidoTimelineService.consultarTimeline(pedidoId);
                    writeJson(exchange, 200, resultado);
                    return;
                }

                if (path.endsWith("/execucao")) {
                    int pedidoId = parsePedidoIdExecucao(path);
                    PedidoExecucaoService.PedidoExecucaoResultado resultado =
                            pedidoExecucaoService.consultarExecucao(pedidoId);
                    writeJson(exchange, 200, resultado);
                    return;
                }

                writeJson(exchange, 400, Map.of("erro", "Path invalido para pedidos"));
            } catch (IllegalArgumentException e) {
                if (isPedidoNotFound(e)) {
                    writeJson(exchange, 404, Map.of("erro", e.getMessage()));
                } else {
                    writeJson(exchange, 400, Map.of("erro", e.getMessage()));
                }
            } catch (Exception e) {
                writeJson(
                        exchange, 500, Map.of("erro", "Falha ao consultar dados do pedido", "detalhe", e.getMessage()));
            }
        }
    }

    private final class EntregadorRoteiroHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            try {
                int entregadorId =
                        parseEntregadorIdRoteiro(exchange.getRequestURI().getPath());
                RoteiroEntregadorService.RoteiroEntregadorResultado roteiro =
                        roteiroEntregadorService.consultarRoteiro(entregadorId);
                writeJson(exchange, 200, roteiro);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(
                        exchange,
                        500,
                        Map.of("erro", "Falha ao consultar roteiro operacional", "detalhe", e.getMessage()));
            }
        }
    }

    private final class OperacaoReadOnlyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("erro", "Metodo nao permitido"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            try {
                if ("/api/operacao/painel".equals(path)) {
                    writeJson(exchange, 200, operacaoPainelService.consultarPainel());
                    return;
                }
                if ("/api/operacao/eventos".equals(path)) {
                    Integer limite = parseLimiteQuery(exchange.getRequestURI().getQuery());
                    writeJson(exchange, 200, operacaoEventosService.listarEventos(limite));
                    return;
                }
                if ("/api/operacao/mapa".equals(path)) {
                    writeJson(exchange, 200, operacaoMapaService.consultarMapa());
                    return;
                }
                if ("/api/operacao/replanejamento/jobs".equals(path)) {
                    Integer limite = parseLimiteQuery(exchange.getRequestURI().getQuery());
                    writeJson(exchange, 200, operacaoReplanejamentoService.listarJobs(limite));
                    return;
                }
                if (path != null && path.startsWith("/api/operacao/replanejamento/jobs/")) {
                    String jobId = parseJobIdReplanejamento(path);
                    writeJson(exchange, 200, operacaoReplanejamentoService.detalharJob(jobId));
                    return;
                }
                writeJson(exchange, 400, Map.of("erro", "Path invalido para operacao"));
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, Map.of("erro", e.getMessage()));
            } catch (Exception e) {
                writeJson(
                        exchange,
                        500,
                        Map.of("erro", "Falha ao consultar dados operacionais", "detalhe", e.getMessage()));
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

    private boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }

        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", CORS_ALLOW_METHODS);
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        String json = gson.toJson(payload);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(exchange);
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

    private static int parsePedidoIdTimeline(String path) {
        return parsePedidoIdWithSuffix(path, "/timeline", "timeline");
    }

    private static int parsePedidoIdExecucao(String path) {
        return parsePedidoIdWithSuffix(path, "/execucao", "execucao");
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

    private static int parseEntregadorIdRoteiro(String path) {
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

    private static Integer parseLimiteQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = parts[0];
            if (!"limite".equals(key)) {
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

    private static String parseJobIdReplanejamento(String path) {
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

    private static boolean isPedidoNotFound(IllegalArgumentException e) {
        return e.getMessage() != null && e.getMessage().startsWith("Pedido nao encontrado com id:");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static RunningServer startForTests(
            int port,
            AtendimentoTelefonicoService atendimentoTelefonicoService,
            ExecucaoEntregaService execucaoEntregaService,
            ReplanejamentoWorkerService replanejamentoWorkerService,
            PedidoTimelineService pedidoTimelineService,
            EventoOperacionalIdempotenciaService eventoOperacionalIdempotenciaService,
            ConnectionFactory connectionFactory)
            throws IOException {
        PedidoExecucaoService pedidoExecucaoService = new PedidoExecucaoService(connectionFactory);
        RoteiroEntregadorService roteiroEntregadorService = new RoteiroEntregadorService(connectionFactory);
        OperacaoPainelService operacaoPainelService = new OperacaoPainelService(connectionFactory);
        OperacaoEventosService operacaoEventosService = new OperacaoEventosService(connectionFactory);
        OperacaoMapaService operacaoMapaService = new OperacaoMapaService(connectionFactory);
        OperacaoReplanejamentoService operacaoReplanejamentoService =
                new OperacaoReplanejamentoService(connectionFactory);
        ApiServer app = new ApiServer(
                atendimentoTelefonicoService,
                execucaoEntregaService,
                eventoOperacionalIdempotenciaService,
                replanejamentoWorkerService,
                pedidoTimelineService,
                pedidoExecucaoService,
                roteiroEntregadorService,
                operacaoPainelService,
                operacaoEventosService,
                operacaoMapaService,
                operacaoReplanejamentoService,
                Boolean.getBoolean(TEST_VERBOSE_PROPERTY));
        return app.start(port);
    }

    public static final class RunningServer implements AutoCloseable {
        private final HttpServer server;
        private final int port;

        private RunningServer(HttpServer server, int port) {
            this.server = Objects.requireNonNull(server);
            this.port = port;
        }

        public int port() {
            return port;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record AtendimentoRequest(
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

    private ExecucaoEntregaResultado processarEventoOperacional(String eventType, EventoRequest req) {
        return switch (eventType) {
            case DispatchEventTypes.ROTA_INICIADA ->
                execucaoEntregaService.registrarRotaIniciada(
                        requireInt(req.rotaId(), "rota_id"), req.actorEntregadorId());
            case DispatchEventTypes.PEDIDO_ENTREGUE ->
                execucaoEntregaService.registrarPedidoEntregue(
                        requireInt(req.entregaId(), "entrega_id"), req.actorEntregadorId());
            case DispatchEventTypes.PEDIDO_FALHOU ->
                execucaoEntregaService.registrarPedidoFalhou(
                        requireInt(req.entregaId(), "entrega_id"), req.motivo(), req.actorEntregadorId());
            case DispatchEventTypes.PEDIDO_CANCELADO ->
                execucaoEntregaService.registrarPedidoCancelado(
                        requireInt(req.entregaId(), "entrega_id"),
                        req.motivo(),
                        req.cobrancaCancelamentoCentavos(),
                        req.actorEntregadorId());
            default -> throw new IllegalArgumentException("event_type invalido: " + eventType);
        };
    }

    private void dispararReplanejamentoAssincronoSeNecessario(String eventType, ExecucaoEntregaResultado resultado) {
        if (resultado == null) {
            return;
        }
        dispararReplanejamentoAssincronoSeNecessario(eventType, resultado.idempotente());
    }

    private void dispararReplanejamentoAssincronoSeNecessario(String eventType, boolean idempotente) {
        if (idempotente) {
            return;
        }
        boolean replanejaPorEvento =
                DispatchEventTypes.policyForEvent(eventType).replaneja();
        boolean replanejaPorRiscoJanelaHard = false;

        if (!replanejaPorEvento && DispatchEventTypes.PEDIDO_ENTREGUE.equals(eventType)) {
            try {
                replanejaPorRiscoJanelaHard = replanejamentoWorkerService.existePedidoHardEmRisco();
            } catch (Exception e) {
                System.err.println("Falha ao avaliar risco de janela HARD: " + e.getMessage());
            }
        }

        if (!replanejaPorEvento && !replanejaPorRiscoJanelaHard) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                replanejamentoWorkerService.processarPendentes(0, 100);
            } catch (Exception e) {
                System.err.println("Falha no worker imediato de replanejamento: " + e.getMessage());
            }
        });
    }

    private ScopeRef resolveScope(String eventType, EventoRequest req) {
        return switch (eventType) {
            case DispatchEventTypes.ROTA_INICIADA -> new ScopeRef("ROTA", requireInt(req.rotaId(), "rota_id"));
            case DispatchEventTypes.PEDIDO_ENTREGUE,
                    DispatchEventTypes.PEDIDO_FALHOU,
                    DispatchEventTypes.PEDIDO_CANCELADO ->
                new ScopeRef("ENTREGA", requireInt(req.entregaId(), "entrega_id"));
            default -> throw new IllegalArgumentException("event_type invalido: " + eventType);
        };
    }

    private String buildEventoRequestHash(String eventType, EventoRequest req) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("eventType", eventType);
        canonical.put("rotaId", req.rotaId());
        canonical.put("entregaId", req.entregaId());
        canonical.put("actorEntregadorId", req.actorEntregadorId());
        canonical.put("motivo", normalizeOptionalText(req.motivo()));
        canonical.put("cobrancaCancelamentoCentavos", req.cobrancaCancelamentoCentavos());
        String payload = gson.toJson(canonical);
        return sha256(payload);
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isOrigemCanalManual(String origemCanal) {
        if (origemCanal == null) {
            return false;
        }
        return "MANUAL".equalsIgnoreCase(origemCanal.trim());
    }

    private static String resolveIdempotencyKeyHeader(HttpExchange exchange) {
        String idempotencyKey = normalizeOptionalText(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        String idempotencyKeyAlias = normalizeOptionalText(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
        if (idempotencyKey != null && idempotencyKeyAlias != null && !idempotencyKey.equals(idempotencyKeyAlias)) {
            throw new IllegalArgumentException("Idempotency-Key e X-Idempotency-Key devem ter o mesmo valor");
        }
        if (idempotencyKey != null) {
            return idempotencyKey;
        }
        return idempotencyKeyAlias;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
                sb.append(Character.forDigit(b & 0x0F, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel no runtime", e);
        }
    }

    private record EventoRequest(
            String externalEventId,
            String eventType,
            Integer rotaId,
            Integer entregaId,
            Integer actorEntregadorId,
            String motivo,
            Integer cobrancaCancelamentoCentavos) {}

    private record ScopeRef(String scopeType, int scopeId) {}

    private record IniciarRotaProntaRequest(Integer entregadorId) {}
}
