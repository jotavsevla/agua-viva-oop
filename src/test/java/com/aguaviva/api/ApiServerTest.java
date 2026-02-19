package com.aguaviva.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ClienteRepository;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.UserRepository;
import com.aguaviva.service.AtendimentoTelefonicoResultado;
import com.aguaviva.service.AtendimentoTelefonicoService;
import com.aguaviva.service.EventoOperacionalIdempotenciaService;
import com.aguaviva.service.ExecucaoEntregaService;
import com.aguaviva.service.PedidoTimelineService;
import com.aguaviva.service.PlanejamentoResultado;
import com.aguaviva.service.ReplanejamentoWorkerService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiServerTest {

    private static final Gson GSON = new Gson();

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static AtendimentoTelefonicoService atendimentoService;
    private static ExecucaoEntregaService execucaoService;
    private static ReplanejamentoWorkerService replanejamentoService;
    private static PedidoTimelineService pedidoTimelineService;
    private static EventoOperacionalIdempotenciaService eventoOperacionalIdempotenciaService;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        atendimentoService = new AtendimentoTelefonicoService(factory);
        execucaoService = new ExecucaoEntregaService(factory);
        eventoOperacionalIdempotenciaService = new EventoOperacionalIdempotenciaService(factory);
        replanejamentoService = new ReplanejamentoWorkerService(factory, () -> new PlanejamentoResultado(0, 0, 0));
        pedidoTimelineService = new PedidoTimelineService(factory);
        garantirSchema();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparAntes() throws Exception {
        limparBanco();
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparBanco();
    }

    private static void garantirSchema() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TYPE entrega_status ADD VALUE IF NOT EXISTS 'EM_EXECUCAO'");
            stmt.execute("ALTER TYPE entrega_status ADD VALUE IF NOT EXISTS 'CANCELADA'");
            stmt.execute("ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS external_call_id VARCHAR(64)");
            stmt.execute("ALTER TABLE pedidos DROP CONSTRAINT IF EXISTS uk_pedidos_external_call_id");
            stmt.execute("DROP INDEX IF EXISTS uk_pedidos_external_call_id");
            stmt.execute("ALTER TABLE pedidos ADD CONSTRAINT uk_pedidos_external_call_id UNIQUE (external_call_id)");
            stmt.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dispatch_event_status') "
                    + "THEN CREATE TYPE dispatch_event_status AS ENUM ('PENDENTE', 'PROCESSADO'); "
                    + "END IF; "
                    + "END $$;");
            stmt.execute("CREATE TABLE IF NOT EXISTS dispatch_events ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "event_type VARCHAR(64) NOT NULL, "
                    + "aggregate_type VARCHAR(32) NOT NULL, "
                    + "aggregate_id BIGINT, "
                    + "payload JSONB NOT NULL DEFAULT '{}'::jsonb, "
                    + "status dispatch_event_status NOT NULL DEFAULT 'PENDENTE', "
                    + "created_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "available_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "processed_em TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS eventos_operacionais_idempotencia ("
                    + "external_event_id VARCHAR(128) PRIMARY KEY, "
                    + "request_hash VARCHAR(64) NOT NULL, "
                    + "event_type VARCHAR(64) NOT NULL, "
                    + "scope_type VARCHAR(16) NOT NULL, "
                    + "scope_id BIGINT NOT NULL, "
                    + "response_json JSONB NOT NULL, "
                    + "status_code INTEGER NOT NULL, "
                    + "created_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'solver_job_status') "
                    + "THEN CREATE TYPE solver_job_status AS ENUM ('PENDENTE', 'EM_EXECUCAO', 'CONCLUIDO', 'CANCELADO', 'FALHOU'); "
                    + "END IF; "
                    + "END $$;");
            stmt.execute("CREATE TABLE IF NOT EXISTS solver_jobs ("
                    + "job_id VARCHAR(64) PRIMARY KEY, "
                    + "plan_version BIGINT NOT NULL, "
                    + "status solver_job_status NOT NULL DEFAULT 'PENDENTE', "
                    + "cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "solicitado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "iniciado_em TIMESTAMP, "
                    + "finalizado_em TIMESTAMP, "
                    + "erro TEXT, "
                    + "request_payload JSONB, "
                    + "response_payload JSONB)");
        }
    }

    private void limparBanco() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "TRUNCATE TABLE solver_jobs, eventos_operacionais_idempotencia, dispatch_events, sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private int criarAtendenteId(String email) throws Exception {
        User atendente = new User("Atendente API", email, Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
        return userRepository.save(atendente).getId();
    }

    private int criarEntregadorId(String email) throws Exception {
        User entregador = new User("Entregador API", email, Password.fromPlainText("senha123"), UserPapel.ENTREGADOR);
        return userRepository.save(entregador).getId();
    }

    private int criarClienteId(String telefone) throws Exception {
        Cliente cliente = new Cliente("Cliente API " + telefone, telefone, ClienteTipo.PF, "Rua API, 10");
        return clienteRepository.save(cliente).getId();
    }

    @Test
    void deveRegistrarPedidoManualQuandoExternalCallIdAusenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(
                    Map.of("telefone", "(38) 99876-9001", "quantidadeGaloes", 2, "atendenteId", atendenteId));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject primeiraResposta = GSON.fromJson(primeira.body(), JsonObject.class);
            int pedidoIdPrimeiro = primeiraResposta.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertFalse(primeiraResposta.get("idempotente").getAsBoolean());
            assertTrue(primeiraResposta.get("clienteCriado").getAsBoolean());
            assertTrue(pedidoIdPrimeiro > 0);

            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject segundaResposta = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoIdSegundo = segundaResposta.get("pedidoId").getAsInt();
            assertEquals(200, segunda.statusCode());
            assertTrue(segundaResposta.get("idempotente").getAsBoolean());
            assertEquals(pedidoIdPrimeiro, pedidoIdSegundo);
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("clientes"));
            assertEquals(null, externalCallIdPedido(pedidoIdPrimeiro));
        }
    }

    @Test
    void deveResponderPreflightCorsNoHealthViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/health"))
                            .header("Origin", "http://localhost:4174")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "content-type")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(204, resposta.statusCode());
            assertEquals(
                    "*",
                    resposta.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertTrue(resposta.headers()
                    .firstValue("Access-Control-Allow-Methods")
                    .orElse("")
                    .contains("GET"));
            assertTrue(resposta.headers()
                    .firstValue("Access-Control-Allow-Headers")
                    .orElse("")
                    .toLowerCase()
                    .contains("content-type"));
        }
    }

    @Test
    void deveRetornarHeadersCorsNoHealthViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/health"))
                            .header("Origin", "http://localhost:4174")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            assertEquals(
                    "*",
                    resposta.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        }
    }

    @Test
    void deveRegistrarPedidoIdempotenteQuandoExternalCallIdPresenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-idempotente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of(
                    "externalCallId",
                    "call-api-001",
                    "telefone",
                    "(38) 99876-9002",
                    "quantidadeGaloes",
                    2,
                    "atendenteId",
                    atendenteId));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject primeiraResposta = GSON.fromJson(primeira.body(), JsonObject.class);
            int pedidoIdPrimeiro = primeiraResposta.get("pedidoId").getAsInt();

            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject segundaResposta = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoIdSegundo = segundaResposta.get("pedidoId").getAsInt();

            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(primeiraResposta.get("idempotente").getAsBoolean());
            assertTrue(segundaResposta.get("idempotente").getAsBoolean());
            assertEquals(pedidoIdPrimeiro, pedidoIdSegundo);
            assertEquals("call-api-001", externalCallIdPedido(pedidoIdPrimeiro));
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("clientes"));
        }
    }

    @Test
    void deveRetornar400QuandoCheckoutEmValeNaoTemSaldoDisponivel() throws Exception {
        int atendenteId = criarAtendenteId("api-vale-sem-saldo@teste.com");
        criarClienteId("(38) 99876-9007");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of(
                    "externalCallId",
                    "call-api-vale-001",
                    "telefone",
                    "(38) 99876-9007",
                    "quantidadeGaloes",
                    2,
                    "atendenteId",
                    atendenteId,
                    "metodoPagamento",
                    "VALE"));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, resposta.statusCode());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertTrue(body.get("erro").getAsString().contains("cliente nao possui vale"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveTratarEventoOperacionalComoIdempotentePorExternalEventId() throws Exception {
        int atendenteId = criarAtendenteId("api-evento-idempotencia-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-evento-idempotencia-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9011", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(
                    Map.of("externalEventId", "evt-api-001", "eventType", "PEDIDO_ENTREGUE", "entregaId", entregaId));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject primeiraResposta = GSON.fromJson(primeira.body(), JsonObject.class);

            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject segundaResposta = GSON.fromJson(segunda.body(), JsonObject.class);

            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(primeiraResposta.get("idempotente").getAsBoolean());
            assertTrue(segundaResposta.get("idempotente").getAsBoolean());
            assertEquals(1, contarEventosPorTipo("PEDIDO_ENTREGUE"));
            assertEquals("ENTREGUE", statusPedido(pedidoId));
            assertEquals("ENTREGUE", statusEntrega(entregaId));
        }
    }

    @Test
    void deveRetornar409QuandoReutilizarExternalEventIdComPayloadDiferente() throws Exception {
        int atendenteId = criarAtendenteId("api-evento-conflito-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-evento-conflito-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9012", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String primeiraPayload = GSON.toJson(Map.of(
                    "externalEventId",
                    "evt-api-002",
                    "eventType",
                    "PEDIDO_CANCELADO",
                    "entregaId",
                    entregaId,
                    "motivo",
                    "cliente cancelou",
                    "cobrancaCancelamentoCentavos",
                    1000));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(primeiraPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            String segundaPayload = GSON.toJson(
                    Map.of("externalEventId", "evt-api-002", "eventType", "PEDIDO_FALHOU", "entregaId", entregaId));
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(segundaPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject segundaResposta = GSON.fromJson(segunda.body(), JsonObject.class);

            assertEquals(200, primeira.statusCode());
            assertEquals(409, segunda.statusCode());
            assertTrue(segundaResposta.get("erro").getAsString().contains("externalEventId"));
            assertEquals(1, contarEventosPorTipo("PEDIDO_CANCELADO"));
            assertEquals(0, contarEventosPorTipo("PEDIDO_FALHOU"));
            assertEquals("CANCELADO", statusPedido(pedidoId));
            assertEquals("CANCELADA", statusEntrega(entregaId));
        }
    }

    @Test
    void deveRetornar409QuandoEventoTerminalDivergirDeEntregaJaFinalizada() throws Exception {
        int atendenteId = criarAtendenteId("api-evento-finalizado-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-evento-finalizado-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-90130", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String entreguePayload = GSON.toJson(Map.of("eventType", "PEDIDO_ENTREGUE", "entregaId", entregaId));
            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(entreguePayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            String canceladoPayload = GSON.toJson(
                    Map.of("eventType", "PEDIDO_CANCELADO", "entregaId", entregaId, "motivo", "cliente cancelou"));
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(canceladoPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject conflito = GSON.fromJson(segunda.body(), JsonObject.class);

            assertEquals(200, primeira.statusCode());
            assertEquals(409, segunda.statusCode());
            assertTrue(conflito.get("erro").getAsString().contains("ja finalizada"));
            assertEquals("ENTREGUE", statusPedido(pedidoId));
            assertEquals("ENTREGUE", statusEntrega(entregaId));
            assertEquals(1, contarEventosPorTipo("PEDIDO_ENTREGUE"));
            assertEquals(0, contarEventosPorTipo("PEDIDO_CANCELADO"));
        }
    }

    @Test
    void deveManterCompatibilidadeQuandoEventoOperacionalNaoTemExternalEventId() throws Exception {
        int atendenteId = criarAtendenteId("api-evento-legado-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-evento-legado-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9013", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of("eventType", "PEDIDO_ENTREGUE", "entregaId", entregaId));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            assertEquals(1, contarEventosPorTipo("PEDIDO_ENTREGUE"));
            assertEquals("ENTREGUE", statusPedido(pedidoId));
            assertEquals("ENTREGUE", statusEntrega(entregaId));
        }
    }

    @Test
    void deveRetornar409QuandoEventoTerminalChegaComEntregaPendenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-evento-pendente-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-evento-pendente-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9014", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of("eventType", "PEDIDO_ENTREGUE", "entregaId", entregaId));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(409, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("EM_EXECUCAO"));
            assertEquals(0, contarEventosPorTipo("PEDIDO_ENTREGUE"));
            assertEquals("EM_ROTA", statusPedido(pedidoId));
            assertEquals("PENDENTE", statusEntrega(entregaId));
        }
    }

    @Test
    void deveRetornar409QuandoActorEntregadorNaoCorrespondeARotaNoEventoRotaIniciada() throws Exception {
        int atendenteId = criarAtendenteId("api-evento-ownership-atendente@teste.com");
        int entregadorCorreto = criarEntregadorId("api-evento-ownership-entregador-correto@teste.com");
        int outroEntregador = criarEntregadorId("api-evento-ownership-entregador-incorreto@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9015", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "CONFIRMADO");
        int rotaId = criarRota(entregadorCorreto, "PLANEJADA");
        criarEntrega(pedidoId, rotaId, "PENDENTE");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(
                    Map.of("eventType", "ROTA_INICIADA", "rotaId", rotaId, "actorEntregadorId", outroEntregador));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(409, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().toLowerCase().contains("entregador"));
            assertEquals("PLANEJADA", statusRota(rotaId));
            assertEquals("CONFIRMADO", statusPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar409QuandoActorEntregadorNaoCorrespondeAEntregaNoEventoTerminal() throws Exception {
        int atendenteId = criarAtendenteId("api-evento-ownership2-atendente@teste.com");
        int entregadorCorreto = criarEntregadorId("api-evento-ownership2-entregador-correto@teste.com");
        int outroEntregador = criarEntregadorId("api-evento-ownership2-entregador-incorreto@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9016", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorCorreto, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of(
                    "eventType", "PEDIDO_ENTREGUE", "entregaId", entregaId, "actorEntregadorId", outroEntregador));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(409, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().toLowerCase().contains("entregador"));
            assertEquals("EM_EXECUCAO", statusEntrega(entregaId));
            assertEquals("EM_ROTA", statusPedido(pedidoId));
        }
    }

    @Test
    void deveRetornarRoteiroOperacionalDoEntregadorViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-roteiro-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-roteiro-entregador@teste.com");
        int cliente1 = criarClienteId("(38) 99876-9017");
        int cliente2 = criarClienteId("(38) 99876-9018");
        int cliente3 = criarClienteId("(38) 99876-9019");

        int pedidoEmExecucao = criarPedidoDireto(cliente1, atendenteId, "EM_ROTA", 2);
        int pedidoPendente = criarPedidoDireto(cliente2, atendenteId, "EM_ROTA", 1);
        int pedidoConcluido = criarPedidoDireto(cliente3, atendenteId, "ENTREGUE", 1);

        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        criarEntregaComOrdem(pedidoEmExecucao, rotaId, 1, "EM_EXECUCAO");
        criarEntregaComOrdem(pedidoPendente, rotaId, 2, "PENDENTE");
        criarEntregaComOrdem(pedidoConcluido, rotaId, 3, "ENTREGUE");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/entregadores/" + entregadorId
                                    + "/roteiro"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            assertEquals(entregadorId, payload.get("entregadorId").getAsInt());
            assertTrue(payload.has("rota"));
            assertEquals(rotaId, payload.getAsJsonObject("rota").get("rotaId").getAsInt());
            assertEquals(
                    "EM_ANDAMENTO",
                    payload.getAsJsonObject("rota").get("status").getAsString());
            assertEquals(3, payload.get("cargaRemanescente").getAsInt());

            JsonArray pendentesExecucao = payload.getAsJsonArray("paradasPendentesExecucao");
            JsonArray concluidas = payload.getAsJsonArray("paradasConcluidas");
            assertEquals(2, pendentesExecucao.size());
            assertEquals(1, concluidas.size());
        }
    }

    @Test
    void deveRetornarRoteiroVazioQuandoEntregadorNaoTemRotaAtivaViaHttp() throws Exception {
        int entregadorId = criarEntregadorId("api-roteiro-vazio-entregador@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/entregadores/" + entregadorId
                                    + "/roteiro"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            assertEquals(entregadorId, payload.get("entregadorId").getAsInt());
            assertTrue(payload.getAsJsonArray("paradasPendentesExecucao").isEmpty());
            assertTrue(payload.getAsJsonArray("paradasConcluidas").isEmpty());
            assertTrue(payload.get("rota").isJsonNull());
            assertEquals(0, payload.get("cargaRemanescente").getAsInt());
        }
    }

    @Test
    void deveRetornarPainelOperacionalComDadosReaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-operacao-painel-atendente@teste.com");
        int entregadorPrimario = criarEntregadorId("api-operacao-painel-entregador-1@teste.com");
        int entregadorSecundario = criarEntregadorId("api-operacao-painel-entregador-2@teste.com");

        int clientePendente = criarClienteId("(38) 99876-90231");
        int clienteConfirmado = criarClienteId("(38) 99876-90232");
        int clienteEmRota = criarClienteId("(38) 99876-90233");
        int clienteEntregue = criarClienteId("(38) 99876-90234");
        int clienteCancelado = criarClienteId("(38) 99876-90235");

        int pedidoPendente = criarPedidoDireto(clientePendente, atendenteId, "PENDENTE", 1);
        int pedidoConfirmado = criarPedidoDireto(clienteConfirmado, atendenteId, "CONFIRMADO", 2);
        int pedidoEmRota = criarPedidoDireto(clienteEmRota, atendenteId, "EM_ROTA", 1);
        criarPedidoDireto(clienteEntregue, atendenteId, "ENTREGUE", 1);
        criarPedidoDireto(clienteCancelado, atendenteId, "CANCELADO", 1);

        int rotaEmAndamento = criarRota(entregadorPrimario, "EM_ANDAMENTO");
        int rotaPlanejada = criarRota(entregadorSecundario, "PLANEJADA");
        criarEntregaComOrdem(pedidoEmRota, rotaEmAndamento, 1, "EM_EXECUCAO");
        criarEntregaComOrdem(pedidoConfirmado, rotaPlanejada, 1, "PENDENTE");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/operacao/painel"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            assertEquals(
                    1,
                    payload.getAsJsonObject("pedidosPorStatus").get("pendente").getAsInt());
            assertEquals(
                    1,
                    payload.getAsJsonObject("pedidosPorStatus")
                            .get("confirmado")
                            .getAsInt());
            assertEquals(
                    1, payload.getAsJsonObject("pedidosPorStatus").get("emRota").getAsInt());
            assertEquals(
                    1,
                    payload.getAsJsonObject("pedidosPorStatus").get("entregue").getAsInt());
            assertEquals(
                    1,
                    payload.getAsJsonObject("pedidosPorStatus").get("cancelado").getAsInt());

            JsonArray rotasEmAndamento = payload.getAsJsonObject("rotas").getAsJsonArray("emAndamento");
            JsonArray rotasPlanejadas = payload.getAsJsonObject("rotas").getAsJsonArray("planejadas");
            assertEquals(1, rotasEmAndamento.size());
            assertEquals(1, rotasPlanejadas.size());

            JsonArray pendentesElegiveis = payload.getAsJsonObject("filas").getAsJsonArray("pendentesElegiveis");
            JsonArray confirmadosSecundaria = payload.getAsJsonObject("filas").getAsJsonArray("confirmadosSecundaria");
            JsonArray emRotaPrimaria = payload.getAsJsonObject("filas").getAsJsonArray("emRotaPrimaria");
            assertTrue(contemPedido(pendentesElegiveis, pedidoPendente));
            assertTrue(contemPedido(confirmadosSecundaria, pedidoConfirmado));
            assertTrue(contemPedido(emRotaPrimaria, pedidoEmRota));
        }
    }

    @Test
    void deveRetornarMapaOperacionalComRotasEParadasReaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-operacao-mapa-atendente@teste.com");
        int entregadorPrimario = criarEntregadorId("api-operacao-mapa-entregador-1@teste.com");
        int entregadorSecundario = criarEntregadorId("api-operacao-mapa-entregador-2@teste.com");

        int clientePrimario = criarClienteId("(38) 99876-90241");
        int clienteSecundario = criarClienteId("(38) 99876-90242");
        atualizarCoordenadasCliente(clientePrimario, -16.7310, -43.8710);
        atualizarCoordenadasCliente(clienteSecundario, -16.7330, -43.8740);

        int pedidoEmRota = criarPedidoDireto(clientePrimario, atendenteId, "EM_ROTA", 1);
        int pedidoConfirmado = criarPedidoDireto(clienteSecundario, atendenteId, "CONFIRMADO", 2);

        int rotaEmAndamento = criarRota(entregadorPrimario, "EM_ANDAMENTO");
        int rotaPlanejada = criarRota(entregadorSecundario, "PLANEJADA");
        int entregaEmExecucao = criarEntregaComOrdem(pedidoEmRota, rotaEmAndamento, 1, "EM_EXECUCAO");
        int entregaPendente = criarEntregaComOrdem(pedidoConfirmado, rotaPlanejada, 1, "PENDENTE");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/operacao/mapa"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            assertTrue(payload.has("deposito"));
            JsonObject deposito = payload.getAsJsonObject("deposito");
            assertTrue(deposito.has("lat"));
            assertTrue(deposito.has("lon"));

            JsonArray rotas = payload.getAsJsonArray("rotas");
            assertEquals(2, rotas.size());

            JsonObject rotaPrimaria = buscarRota(rotas, rotaEmAndamento);
            assertNotNull(rotaPrimaria);
            assertEquals("EM_ANDAMENTO", rotaPrimaria.get("statusRota").getAsString());
            assertEquals("PRIMARIA", rotaPrimaria.get("camada").getAsString());
            JsonArray paradasPrimaria = rotaPrimaria.getAsJsonArray("paradas");
            assertEquals(1, paradasPrimaria.size());
            JsonObject paradaPrimaria = paradasPrimaria.get(0).getAsJsonObject();
            assertEquals(pedidoEmRota, paradaPrimaria.get("pedidoId").getAsInt());
            assertEquals(entregaEmExecucao, paradaPrimaria.get("entregaId").getAsInt());
            assertEquals("EM_EXECUCAO", paradaPrimaria.get("statusEntrega").getAsString());

            JsonObject rotaSecundaria = buscarRota(rotas, rotaPlanejada);
            assertNotNull(rotaSecundaria);
            assertEquals("PLANEJADA", rotaSecundaria.get("statusRota").getAsString());
            assertEquals("SECUNDARIA", rotaSecundaria.get("camada").getAsString());
            JsonArray paradasSecundaria = rotaSecundaria.getAsJsonArray("paradas");
            assertEquals(1, paradasSecundaria.size());
            JsonObject paradaSecundaria = paradasSecundaria.get(0).getAsJsonObject();
            assertEquals(pedidoConfirmado, paradaSecundaria.get("pedidoId").getAsInt());
            assertEquals(entregaPendente, paradaSecundaria.get("entregaId").getAsInt());
            assertEquals("PENDENTE", paradaSecundaria.get("statusEntrega").getAsString());
        }
    }

    @Test
    void deveRetornarFeedOperacionalOrdenadoELimitadoViaHttp() throws Exception {
        try (Connection conn = factory.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO dispatch_events (event_type, aggregate_type, aggregate_id, payload, status, created_em, available_em) "
                            + "VALUES (?, 'PEDIDO', 1, '{}'::jsonb, ?, ?, ?)")) {
                stmt.setString(1, "PEDIDO_CANCELADO");
                stmt.setObject(2, "PENDENTE", java.sql.Types.OTHER);
                stmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.of(2026, 2, 16, 10, 0)));
                stmt.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2026, 2, 16, 10, 0)));
                stmt.executeUpdate();

                stmt.setString(1, "PEDIDO_FALHOU");
                stmt.setObject(2, "PROCESSADO", java.sql.Types.OTHER);
                stmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.of(2026, 2, 16, 10, 5)));
                stmt.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2026, 2, 16, 10, 5)));
                stmt.executeUpdate();

                stmt.setString(1, "PEDIDO_ENTREGUE");
                stmt.setObject(2, "PENDENTE", java.sql.Types.OTHER);
                stmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.of(2026, 2, 16, 10, 10)));
                stmt.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2026, 2, 16, 10, 10)));
                stmt.executeUpdate();
            }
        }

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/operacao/eventos?limite=2"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            JsonArray eventos = payload.getAsJsonArray("eventos");
            assertEquals(2, eventos.size());
            assertEquals(
                    "PEDIDO_ENTREGUE",
                    eventos.get(0).getAsJsonObject().get("eventType").getAsString());
            assertEquals(
                    "PEDIDO_FALHOU",
                    eventos.get(1).getAsJsonObject().get("eventType").getAsString());
        }
    }

    @Test
    void deveRetornar400QuandoLimiteDoFeedOperacionalExcederMaximoViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/operacao/eventos?limite=201"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(400, resposta.statusCode());
            assertTrue(payload.get("erro").getAsString().contains("limite maximo"));
        }
    }

    @Test
    void deveRetornarJobsDeReplanejamentoComLimiteViaHttp() throws Exception {
        inserirSolverJob(
                "job-antigo",
                10,
                "CONCLUIDO",
                false,
                "{}",
                "{\"rotas\":[]}",
                null,
                LocalDateTime.now().minusMinutes(5));
        inserirSolverJob(
                "job-mais-recente",
                11,
                "EM_EXECUCAO",
                true,
                "{}",
                null,
                "cancelamento solicitado",
                LocalDateTime.now().minusMinutes(1));

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port()
                                    + "/api/operacao/replanejamento/jobs?limite=1"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            assertEquals("test", payload.get("ambiente").getAsString());
            assertTrue(payload.get("habilitado").getAsBoolean());

            JsonArray jobs = payload.getAsJsonArray("jobs");
            assertEquals(1, jobs.size());
            JsonObject job = jobs.get(0).getAsJsonObject();
            assertEquals("job-mais-recente", job.get("jobId").getAsString());
            assertEquals("EM_EXECUCAO", job.get("status").getAsString());
            assertTrue(job.get("cancelRequested").getAsBoolean());
            assertTrue(job.get("hasRequestPayload").getAsBoolean());
            assertFalse(job.get("hasResponsePayload").getAsBoolean());
        }
    }

    @Test
    void deveRetornar400QuandoLimiteDeJobsOperacionaisExcederMaximoViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port()
                                    + "/api/operacao/replanejamento/jobs?limite=201"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(400, resposta.statusCode());
            assertTrue(payload.get("erro").getAsString().contains("limite maximo permitido"));
        }
    }

    @Test
    void deveRetornar400QuandoLimiteDeJobsOperacionaisForZeroViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port()
                                    + "/api/operacao/replanejamento/jobs?limite=0"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(400, resposta.statusCode());
            assertTrue(payload.get("erro").getAsString().contains("limite deve ser maior que zero"));
        }
    }

    @Test
    void deveRetornarListaVaziaDeJobsQuandoNaoHaReplanejamentoViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/operacao/replanejamento/jobs"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            JsonArray jobs = payload.getAsJsonArray("jobs");

            assertEquals(200, resposta.statusCode());
            assertEquals(0, jobs.size());
            assertTrue(payload.get("habilitado").getAsBoolean());
        }
    }

    @Test
    void deveRetornarDetalheDeJobComRotasEPedidosImpactadosViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-replanejamento-detalhe-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-replanejamento-detalhe-entregador@teste.com");
        int clienteId = criarClienteId("(38) 99876-9661");
        int pedidoId = criarPedidoDireto(clienteId, atendenteId, "CONFIRMADO", 1);
        long planVersion = 91L;
        String jobId = "job-detalhe-91";
        int rotaId = criarRotaComPlanVersion(entregadorId, "PLANEJADA", planVersion, 1);
        int entregaId = criarEntregaComPlanVersion(pedidoId, rotaId, 1, "PENDENTE", planVersion);
        inserirSolverJob(
                jobId,
                planVersion,
                "CONCLUIDO",
                false,
                "{}",
                "{\"rotas\":[]}",
                null,
                LocalDateTime.now().minusMinutes(1));

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/operacao/replanejamento/jobs/"
                                    + jobId))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            JsonObject job = payload.getAsJsonObject("job");

            assertEquals(200, resposta.statusCode());
            assertEquals("test", payload.get("ambiente").getAsString());
            assertTrue(payload.get("habilitado").getAsBoolean());
            assertEquals(jobId, job.get("jobId").getAsString());
            assertEquals(planVersion, job.get("planVersion").getAsLong());
            assertEquals("CONCLUIDO", job.get("status").getAsString());

            JsonArray rotas = job.getAsJsonArray("rotasImpactadas");
            assertEquals(1, rotas.size());
            JsonObject rota = rotas.get(0).getAsJsonObject();
            assertEquals(rotaId, rota.get("rotaId").getAsInt());
            assertEquals(entregadorId, rota.get("entregadorId").getAsInt());
            assertEquals("PLANEJADA", rota.get("statusRota").getAsString());
            assertEquals("SECUNDARIA", rota.get("camada").getAsString());
            assertEquals(1, rota.get("totalEntregas").getAsInt());

            JsonArray pedidos = job.getAsJsonArray("pedidosImpactados");
            assertEquals(1, pedidos.size());
            JsonObject pedido = pedidos.get(0).getAsJsonObject();
            assertEquals(pedidoId, pedido.get("pedidoId").getAsInt());
            assertEquals(entregaId, pedido.get("entregaId").getAsInt());
            assertEquals(rotaId, pedido.get("rotaId").getAsInt());
            assertEquals("CONFIRMADO", pedido.get("statusPedido").getAsString());
            assertEquals("PENDENTE", pedido.get("statusEntrega").getAsString());
        }
    }

    @Test
    void deveRetornar400QuandoDetalheDeJobNaoExistirViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port()
                                    + "/api/operacao/replanejamento/jobs/job-inexistente"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(400, resposta.statusCode());
            assertTrue(payload.get("erro").getAsString().contains("jobId nao encontrado"));
        }
    }

    @Test
    void deveRetornarExecucaoAtualDoPedidoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-execucao-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-execucao-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9020", 2, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "CONFIRMADO");
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/execucao"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            assertEquals(pedidoId, payload.get("pedidoId").getAsInt());
            assertEquals("CONFIRMADO", payload.get("statusPedido").getAsString());
            assertEquals("SECUNDARIA_CONFIRMADA", payload.get("camada").getAsString());
            assertTrue(payload.get("rotaPrimariaId").isJsonNull());
            assertTrue(payload.get("entregaAtivaId").isJsonNull());
            assertEquals(rotaId, payload.get("rotaId").getAsInt());
            assertEquals(entregaId, payload.get("entregaId").getAsInt());
        }
    }

    @Test
    void deveRetornar404QuandoPedidoNaoExisteNaExecucaoViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/pedidos/999999/execucao"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(404, resposta.statusCode());
            assertTrue(payload.get("erro").getAsString().contains("Pedido nao encontrado"));
        }
    }

    @Test
    void deveRetornarTimelineDoPedidoComStatusAtualViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-timeline-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-timeline-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9003", 2, atendenteId);
        int pedidoId = atendimento.pedidoId();

        atualizarStatusPedido(pedidoId, "CONFIRMADO");
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        execucaoService.registrarRotaIniciada(rotaId);
        execucaoService.registrarPedidoEntregue(entregaId);

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(pedidoId, payload.get("pedidoId").getAsInt());
            assertEquals("ENTREGUE", payload.get("statusAtual").getAsString());

            JsonArray eventos = payload.getAsJsonArray("eventos");
            assertNotNull(eventos);
            assertTrue(eventos.size() >= 3);
            assertTrue(contemEvento(eventos, "NOVO", "PENDENTE", "Atendimento"));
            assertTrue(contemEvento(eventos, "CONFIRMADO", "EM_ROTA", "Despacho"));
            assertTrue(contemEvento(eventos, "EM_ROTA", "ENTREGUE", "Evento operacional"));
        }
    }

    @Test
    void deveRetornar404QuandoPedidoNaoExisteNaTimelineViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/pedidos/999999/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(404, resposta.statusCode());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            assertTrue(payload.get("erro").getAsString().contains("Pedido nao encontrado"));
        }
    }

    @Test
    void deveRetornarTimelineComCancelamentoPorFalhaEObservacaoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-timeline-falha-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-timeline-falha-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9004", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();

        atualizarStatusPedido(pedidoId, "CONFIRMADO");
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        execucaoService.registrarRotaIniciada(rotaId);
        execucaoService.registrarPedidoFalhou(entregaId, "cliente ausente");

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals("CANCELADO", payload.get("statusAtual").getAsString());

            JsonArray eventos = payload.getAsJsonArray("eventos");
            assertNotNull(eventos);
            assertTrue(eventos.size() >= 3);
            JsonObject eventoCancelamento = buscarEvento(eventos, "EM_ROTA", "CANCELADO", "Evento operacional");
            assertNotNull(eventoCancelamento);
            assertEquals("cliente ausente", eventoCancelamento.get("observacao").getAsString());
        }
    }

    @Test
    void deveRetornarTimelineComCancelamentoSolicitadoEObservacaoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-timeline-cancel-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-timeline-cancel-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9005", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();

        atualizarStatusPedido(pedidoId, "CONFIRMADO");
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        execucaoService.registrarRotaIniciada(rotaId);
        execucaoService.registrarPedidoCancelado(entregaId, "cliente cancelou", 2500);

        HttpClient client = HttpClient.newHttpClient();
        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals("CANCELADO", payload.get("statusAtual").getAsString());

            JsonArray eventos = payload.getAsJsonArray("eventos");
            assertNotNull(eventos);
            assertTrue(eventos.size() >= 3);
            JsonObject eventoCancelamento = buscarEvento(eventos, "EM_ROTA", "CANCELADO", "Evento operacional");
            assertNotNull(eventoCancelamento);
            assertEquals(
                    "cliente cancelou", eventoCancelamento.get("observacao").getAsString());
        }
    }

    @Test
    void deveIntegrarAtendimentoEventosETimelineViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-e2e-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-e2e-entregador@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String atendimentoPayload = GSON.toJson(
                    Map.of("telefone", "(38) 99876-9006", "quantidadeGaloes", 1, "atendenteId", atendenteId));
            HttpResponse<String> atendimentoResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(atendimentoPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, atendimentoResp.statusCode());
            JsonObject atendimentoBody = GSON.fromJson(atendimentoResp.body(), JsonObject.class);
            int pedidoId = atendimentoBody.get("pedidoId").getAsInt();

            atualizarStatusPedido(pedidoId, "CONFIRMADO");
            int rotaId = criarRota(entregadorId, "PLANEJADA");
            int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

            String rotaIniciadaPayload = GSON.toJson(Map.of("eventType", "ROTA_INICIADA", "rotaId", rotaId));
            HttpResponse<String> rotaIniciadaResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(rotaIniciadaPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rotaIniciadaResp.statusCode());

            String entreguePayload = GSON.toJson(Map.of("eventType", "PEDIDO_ENTREGUE", "entregaId", entregaId));
            HttpResponse<String> entregueResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(entreguePayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, entregueResp.statusCode());

            HttpResponse<String> timelineResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, timelineResp.statusCode());
            JsonObject timeline = GSON.fromJson(timelineResp.body(), JsonObject.class);
            assertEquals(pedidoId, timeline.get("pedidoId").getAsInt());
            assertEquals("ENTREGUE", timeline.get("statusAtual").getAsString());

            JsonArray eventos = timeline.getAsJsonArray("eventos");
            assertNotNull(eventos);
            assertTrue(contemEvento(eventos, "NOVO", "PENDENTE", "Atendimento"));
            assertTrue(contemEvento(eventos, "CONFIRMADO", "EM_ROTA", "Despacho"));
            assertTrue(contemEvento(eventos, "EM_ROTA", "ENTREGUE", "Evento operacional"));
        }
    }

    @Test
    void deveDispararWorkerImediatoAposEventoTerminalDeCancelamentoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-worker-imediato-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-worker-imediato-entregador@teste.com");
        AtomicInteger chamadasReplanejamento = new AtomicInteger(0);
        ReplanejamentoWorkerService replanejamentoAssincrono = new ReplanejamentoWorkerService(factory, () -> {
            chamadasReplanejamento.incrementAndGet();
            return new PlanejamentoResultado(1, 1, 0);
        });
        HttpClient client = HttpClient.newHttpClient();

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9021", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoAssincrono,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of(
                    "eventType", "PEDIDO_CANCELADO", "entregaId", entregaId, "motivo", "cancelamento teste async"));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            aguardarAte(
                    () -> chamadasReplanejamento.get() >= 1, 3000, "worker imediato nao disparou apos cancelamento");
            assertTrue(chamadasReplanejamento.get() >= 1);
        }
    }

    @Test
    void naoDeveDispararWorkerImediatoQuandoEventoTerminalForPedidoEntregueViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-worker-entregue-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-worker-entregue-entregador@teste.com");
        AtomicInteger chamadasReplanejamento = new AtomicInteger(0);
        ReplanejamentoWorkerService replanejamentoAssincrono = new ReplanejamentoWorkerService(factory, () -> {
            chamadasReplanejamento.incrementAndGet();
            return new PlanejamentoResultado(0, 0, 0);
        });
        HttpClient client = HttpClient.newHttpClient();

        AtendimentoTelefonicoResultado atendimento =
                atendimentoService.registrarPedidoManual("(38) 99876-9022", 1, atendenteId);
        int pedidoId = atendimento.pedidoId();
        atualizarStatusPedido(pedidoId, "EM_ROTA");
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoAssincrono,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of("eventType", "PEDIDO_ENTREGUE", "entregaId", entregaId));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            Thread.sleep(350);
            assertEquals(0, chamadasReplanejamento.get());
        }
    }

    @Test
    void deveDispararWorkerAssincronoQuandoPedidoForCriadoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-worker-criado-atendente@teste.com");
        AtomicInteger chamadasReplanejamento = new AtomicInteger(0);
        ReplanejamentoWorkerService replanejamentoAssincrono = new ReplanejamentoWorkerService(factory, () -> {
            chamadasReplanejamento.incrementAndGet();
            return new PlanejamentoResultado(1, 1, 0);
        });
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoAssincrono,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of(
                    "externalCallId",
                    "api-worker-criado-evt-001",
                    "telefone",
                    "(38) 99876-9221",
                    "quantidadeGaloes",
                    1,
                    "atendenteId",
                    atendenteId,
                    "metodoPagamento",
                    "PIX"));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            aguardarAte(
                    () -> chamadasReplanejamento.get() >= 1, 3000, "worker assincrono nao disparou apos PEDIDO_CRIADO");
            assertTrue(chamadasReplanejamento.get() >= 1);
        }
    }

    @Test
    void deveRetornar409QuandoEndpointManualDeReplanejamentoForChamadoViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of("debounceSegundos", 0, "limiteEventos", 100));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/replanejamento/run"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(409, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().toLowerCase().contains("desativado"));
        }
    }

    @Test
    void deveIniciarRotaProntaComUmCliqueViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-m1-trigger-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-m1-trigger-entregador@teste.com");
        int clienteId = criarClienteId("(38) 99876-9010");
        HttpClient client = HttpClient.newHttpClient();

        int pedidoId = criarPedidoDireto(clienteId, atendenteId, "CONFIRMADO", 1);
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of("entregadorId", entregadorId));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + running.port() + "/api/operacao/rotas/prontas/iniciar"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(200, resposta.statusCode());
            assertEquals("ROTA_INICIADA", body.get("evento").getAsString());
            assertEquals(rotaId, body.get("rotaId").getAsInt());
            assertEquals(entregaId, body.get("entregaId").getAsInt());
            assertEquals(pedidoId, body.get("pedidoId").getAsInt());
            assertEquals("EM_ANDAMENTO", statusRota(rotaId));
            assertEquals("EM_EXECUCAO", statusEntrega(entregaId));
            assertEquals("EM_ROTA", statusPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar409QuandoOneClickNaoEncontrarRotaPlanejadaViaHttp() throws Exception {
        int entregadorId = criarEntregadorId("api-oneclick-sem-planejada@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of("entregadorId", entregadorId));
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + running.port() + "/api/operacao/rotas/prontas/iniciar"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);

            assertEquals(409, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("PLANEJADA"));
        }
    }

    @Test
    void devePermitirApenasUmaInicializacaoConcorrenteNoOneClickViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-oneclick-concorrente-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-oneclick-concorrente-entregador@teste.com");
        int clienteId = criarClienteId("(38) 99876-9550");
        int pedidoId = criarPedidoDireto(clienteId, atendenteId, "CONFIRMADO", 1);
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.of("entregadorId", entregadorId));
            URI oneClickUri = URI.create("http://localhost:" + running.port() + "/api/operacao/rotas/prontas/iniciar");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch disparo = new CountDownLatch(1);

            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(executor.submit(() -> {
                        disparo.await(5, TimeUnit.SECONDS);
                        return client.send(
                                HttpRequest.newBuilder()
                                        .uri(oneClickUri)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                    }));
                }

                disparo.countDown();

                int status200 = 0;
                int status409 = 0;
                for (Future<HttpResponse<String>> future : futures) {
                    HttpResponse<String> resposta = future.get(10, TimeUnit.SECONDS);
                    if (resposta.statusCode() == 200) {
                        status200++;
                    } else if (resposta.statusCode() == 409) {
                        status409++;
                    }
                }

                assertEquals(1, status200);
                assertEquals(1, status409);
            } finally {
                executor.shutdownNow();
            }

            assertEquals("EM_ANDAMENTO", statusRota(rotaId));
            assertEquals("EM_EXECUCAO", statusEntrega(entregaId));
            assertEquals("EM_ROTA", statusPedido(pedidoId));
            assertEquals(1, contarEventosPorTipo("ROTA_INICIADA"));
            assertEquals(0, contarPedidosComMaisDeUmaEntregaAberta());
        }
    }

    private int contarLinhas(String tabela) throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int contarDispatchPorStatus(String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT COUNT(*) FROM dispatch_events WHERE status::text = ?")) {
            stmt.setString(1, status);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int contarEventosPorTipo(String eventType) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT COUNT(*) FROM dispatch_events WHERE event_type = ?")) {
            stmt.setString(1, eventType);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String externalCallIdPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT external_call_id FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String statusPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT status::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String statusEntrega(int entregaId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT status::text FROM entregas WHERE id = ?")) {
            stmt.setInt(1, entregaId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String statusRota(int rotaId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT status::text FROM rotas WHERE id = ?")) {
            stmt.setInt(1, rotaId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int criarRota(int entregadorId, String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (?, CURRENT_DATE, 1, ?) RETURNING id")) {
            stmt.setInt(1, entregadorId);
            stmt.setObject(2, status, java.sql.Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int criarRotaComPlanVersion(int entregadorId, String status, long planVersion, int numeroNoDia)
            throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version) VALUES (?, CURRENT_DATE, ?, ?, ?) RETURNING id")) {
            stmt.setInt(1, entregadorId);
            stmt.setInt(2, numeroNoDia);
            stmt.setObject(3, status, java.sql.Types.OTHER);
            stmt.setLong(4, planVersion);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int criarEntrega(int pedidoId, int rotaId, String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status) VALUES (?, ?, 1, ?) RETURNING id")) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setObject(3, status, java.sql.Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int criarEntregaComPlanVersion(int pedidoId, int rotaId, int ordemNaRota, String status, long planVersion)
            throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status, plan_version) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setInt(3, ordemNaRota);
            stmt.setObject(4, status, java.sql.Types.OTHER);
            stmt.setLong(5, planVersion);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int contarPedidosComMaisDeUmaEntregaAberta() throws Exception {
        String sql = "SELECT COUNT(*) "
                + "FROM ("
                + "  SELECT e.pedido_id "
                + "  FROM entregas e "
                + "  WHERE e.status::text IN ('PENDENTE', 'EM_EXECUCAO') "
                + "  GROUP BY e.pedido_id "
                + "  HAVING COUNT(*) > 1"
                + ") duplicados";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void inserirSolverJob(
            String jobId,
            long planVersion,
            String status,
            boolean cancelRequested,
            String requestPayloadJson,
            String responsePayloadJson,
            String erro,
            LocalDateTime solicitadoEm)
            throws Exception {
        String sql = "INSERT INTO solver_jobs "
                + "(job_id, plan_version, status, cancel_requested, solicitado_em, iniciado_em, finalizado_em, erro, request_payload, response_payload) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CASE WHEN ? IS NULL THEN NULL ELSE CAST(? AS jsonb) END)";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, jobId);
            stmt.setLong(2, planVersion);
            stmt.setObject(3, status, java.sql.Types.OTHER);
            stmt.setBoolean(4, cancelRequested);
            stmt.setTimestamp(5, Timestamp.valueOf(solicitadoEm));
            stmt.setTimestamp(6, Timestamp.valueOf(solicitadoEm.plusSeconds(1)));
            stmt.setTimestamp(7, null);
            stmt.setString(8, erro);
            stmt.setString(9, requestPayloadJson == null ? "{}" : requestPayloadJson);
            stmt.setString(10, responsePayloadJson);
            stmt.setString(11, responsePayloadJson);
            stmt.executeUpdate();
        }
    }

    private int criarEntregaComOrdem(int pedidoId, int rotaId, int ordemNaRota, String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status) VALUES (?, ?, ?, ?) RETURNING id")) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setInt(3, ordemNaRota);
            stmt.setObject(4, status, java.sql.Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int criarPedidoDireto(int clienteId, int atendenteId, String status, int quantidadeGaloes)
            throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO pedidos (cliente_id, quantidade_galoes, janela_tipo, status, criado_por) "
                                + "VALUES (?, ?, 'ASAP', ?, ?) RETURNING id")) {
            stmt.setInt(1, clienteId);
            stmt.setInt(2, quantidadeGaloes);
            stmt.setObject(3, status, java.sql.Types.OTHER);
            stmt.setInt(4, atendenteId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void atualizarStatusPedido(int pedidoId, String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE pedidos SET status = ? WHERE id = ?")) {
            stmt.setObject(1, status, java.sql.Types.OTHER);
            stmt.setInt(2, pedidoId);
            stmt.executeUpdate();
        }
    }

    private void atualizarCoordenadasCliente(int clienteId, double latitude, double longitude) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("UPDATE clientes SET latitude = ?, longitude = ? WHERE id = ?")) {
            stmt.setDouble(1, latitude);
            stmt.setDouble(2, longitude);
            stmt.setInt(3, clienteId);
            stmt.executeUpdate();
        }
    }

    private static void aguardarAte(BooleanSupplier condicao, long timeoutMillis, String erro) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condicao.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(erro);
    }

    private boolean contemEvento(JsonArray eventos, String deStatus, String paraStatus, String origem) {
        for (int i = 0; i < eventos.size(); i++) {
            JsonObject evento = eventos.get(i).getAsJsonObject();
            if (deStatus.equals(evento.get("deStatus").getAsString())
                    && paraStatus.equals(evento.get("paraStatus").getAsString())
                    && origem.equals(evento.get("origem").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private JsonObject buscarEvento(JsonArray eventos, String deStatus, String paraStatus, String origem) {
        for (int i = 0; i < eventos.size(); i++) {
            JsonObject evento = eventos.get(i).getAsJsonObject();
            if (deStatus.equals(evento.get("deStatus").getAsString())
                    && paraStatus.equals(evento.get("paraStatus").getAsString())
                    && origem.equals(evento.get("origem").getAsString())) {
                return evento;
            }
        }
        return null;
    }

    private boolean contemPedido(JsonArray itens, int pedidoId) {
        for (int i = 0; i < itens.size(); i++) {
            JsonObject item = itens.get(i).getAsJsonObject();
            if (item.has("pedidoId") && item.get("pedidoId").getAsInt() == pedidoId) {
                return true;
            }
        }
        return false;
    }

    private JsonObject buscarRota(JsonArray rotas, int rotaId) {
        for (int i = 0; i < rotas.size(); i++) {
            JsonObject rota = rotas.get(i).getAsJsonObject();
            if (rota.has("rotaId") && rota.get("rotaId").getAsInt() == rotaId) {
                return rota;
            }
        }
        return null;
    }
}
