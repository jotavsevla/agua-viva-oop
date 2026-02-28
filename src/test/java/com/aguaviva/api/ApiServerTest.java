package com.aguaviva.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
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
            stmt.execute("CREATE TABLE IF NOT EXISTS atendimentos_idempotencia ("
                    + "origem_canal VARCHAR(32) NOT NULL, "
                    + "source_event_id VARCHAR(128) NOT NULL, "
                    + "pedido_id INTEGER NOT NULL, "
                    + "cliente_id INTEGER NOT NULL, "
                    + "telefone_normalizado VARCHAR(15) NOT NULL, "
                    + "criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (origem_canal, source_event_id))");
            stmt.execute("ALTER TABLE atendimentos_idempotencia ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64)");
            stmt.execute("INSERT INTO configuracoes (chave, valor, descricao) VALUES ("
                    + "'cobertura_bbox', '-43.9600,-16.8200,-43.7800,-16.6200', "
                    + "'Cobertura operacional de atendimento em bbox') "
                    + "ON CONFLICT (chave) DO NOTHING");
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
                    "TRUNCATE TABLE solver_jobs, eventos_operacionais_idempotencia, atendimentos_idempotencia, dispatch_events, sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
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
        Cliente cliente = new Cliente(
                "Cliente API " + telefone,
                telefone,
                ClienteTipo.PF,
                "Rua API, 10",
                BigDecimal.valueOf(-16.72),
                BigDecimal.valueOf(-43.86),
                null);
        return clienteRepository.save(cliente).getId();
    }

    @Test
    void deveRegistrarPedidoManualQuandoExternalCallIdAusenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual@teste.com");
        criarClienteId("(38) 99876-9001");
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
            assertFalse(primeiraResposta.get("clienteCriado").getAsBoolean());
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
    void deveCriarCadastroNoAtendimentoManualQuandoTelefoneNaoExistirViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-criacao@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("manualRequestId", "manual-api-20260222-001"),
                    Map.entry("telefone", "(38) 99876-9331"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Manual API"),
                    Map.entry("endereco", "Rua Nova, 123, Montes Claros - MG"),
                    Map.entry("latitude", -16.7310),
                    Map.entry("longitude", -43.8710)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(200, resposta.statusCode());
            assertTrue(body.get("clienteCriado").getAsBoolean());
            assertFalse(body.get("idempotente").getAsBoolean());
            assertEquals(1, contarLinhas("clientes"));
            assertEquals(1, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveAplicarFallbackDeExternalCallIdComoManualRequestIdQuandoOrigemManualViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-fallback-external@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String manualLegacyKey = "manual-legacy-evt-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("externalCallId", manualLegacyKey),
                    Map.entry("telefone", "(38) 99876-9339"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Manual Legacy"),
                    Map.entry("endereco", "Rua Legacy, 101, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();

            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("clientes"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("MANUAL", manualLegacyKey));
            assertNull(externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void devePermitirIdempotenciaManualViaHeaderIdempotencyKeyQuandoSemManualRequestIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-header-idem@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String manualHeaderKey = "manual-header-evt-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("telefone", "(38) 99876-9340"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Manual Header"),
                    Map.entry("endereco", "Rua Header, 102, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(
                    bodyPrimeira.get("pedidoId").getAsInt(),
                    bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("MANUAL", manualHeaderKey));
        }
    }

    @Test
    void devePermitirIdempotenciaManualViaHeaderXIdempotencyKeyQuandoSemManualRequestIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-header-x-idem@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String manualHeaderKey = "manual-xheader-evt-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("telefone", "(38) 99876-9342"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Manual X Header"),
                    Map.entry("endereco", "Rua Header, 104, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(
                    bodyPrimeira.get("pedidoId").getAsInt(),
                    bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("MANUAL", manualHeaderKey));
        }
    }

    @Test
    void devePermitirIdempotenciaManualQuandoHeadersIdempotencySaoIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-headers-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String manualHeaderKey = "manual-headers-iguais-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("telefone", "(38) 99876-9345"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Header Duplo Igual"),
                    Map.entry("endereco", "Rua Header, 107, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", manualHeaderKey)
                            .header("X-Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", manualHeaderKey)
                            .header("X-Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(
                    bodyPrimeira.get("pedidoId").getAsInt(),
                    bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("MANUAL", manualHeaderKey));
        }
    }

    @Test
    void deveRetornar400QuandoHeadersIdempotencyKeyDivergiremViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-headers-divergentes@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("telefone", "(38) 99876-9346"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Header Duplo Divergente"),
                    Map.entry("endereco", "Rua Header, 108, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "manual-header-valor-a")
                            .header("X-Idempotency-Key", "manual-header-valor-b")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("devem ter o mesmo valor"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirIdempotenciaManualQuandoOrigemCanalOmitidaEHeaderIdempotencyKeyViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-header-sem-origem@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String manualHeaderKey = "manual-header-sem-origem-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("telefone", "(38) 99876-9343"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Manual Sem Origem"),
                    Map.entry("endereco", "Rua Header, 105, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", manualHeaderKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(
                    bodyPrimeira.get("pedidoId").getAsInt(),
                    bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("MANUAL", manualHeaderKey));
        }
    }

    @Test
    void deveRetornar400QuandoManualRequestIdDivergirDoHeaderIdempotencyKeyViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-header-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("manualRequestId", "manual-body-key-001"),
                    Map.entry("telefone", "(38) 99876-9341"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Header Divergente"),
                    Map.entry("endereco", "Rua Header, 103, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "manual-header-key-999")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("manualRequestId diverge do header Idempotency-Key"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoManualRequestIdDivergirDeExternalCallIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-external-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("manualRequestId", "manual-body-key-100"),
                    Map.entry("externalCallId", "manual-legacy-key-999"),
                    Map.entry("telefone", "(38) 99876-9347"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Chaves Divergentes"),
                    Map.entry("endereco", "Rua Header, 109, Montes Claros - MG"),
                    Map.entry("latitude", -16.7315),
                    Map.entry("longitude", -43.8715)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("manualRequestId diverge de externalCallId"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoSourceEventIdDivergirDeExternalCallIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-external-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-100"),
                    Map.entry("externalCallId", "wa-evt-101"),
                    Map.entry("telefone", "(38) 99876-9348"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Auto Chaves Divergentes"),
                    Map.entry("endereco", "Rua Header, 110, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId diverge de externalCallId"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirManualComIdempotencyKeyEExternalCallIdIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-header-external-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "manual-header-external-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("externalCallId", chave),
                    Map.entry("telefone", "(38) 99876-9349"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Header + External"),
                    Map.entry("endereco", "Rua Header, 111, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("MANUAL", chave));
            assertNull(externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar400QuandoIdempotencyKeyDivergirDeExternalCallIdNoCanalManualViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-header-external-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("externalCallId", "manual-external-key-B"),
                    Map.entry("telefone", "(38) 99876-9350"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Header + External Divergente"),
                    Map.entry("endereco", "Rua Header, 112, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "manual-header-key-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("manualRequestId diverge de externalCallId"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirManualComXIdempotencyKeyEExternalCallIdIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-xheader-external-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "manual-xheader-external-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("externalCallId", chave),
                    Map.entry("telefone", "(38) 99876-9351"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente X Header + External"),
                    Map.entry("endereco", "Rua Header, 113, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("MANUAL", chave));
            assertNull(externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar400QuandoXIdempotencyKeyDivergirDeExternalCallIdNoCanalManualViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-xheader-external-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("externalCallId", "manual-xexternal-key-B"),
                    Map.entry("telefone", "(38) 99876-9352"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente X Header + External Divergente"),
                    Map.entry("endereco", "Rua Header, 114, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", "manual-xheader-key-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("manualRequestId diverge de externalCallId"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirOrigemOmitidaComExternalCallIdEIdempotencyKeyIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-external-header-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "origem-omitida-ext-header-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("externalCallId", chave),
                    Map.entry("telefone", "(38) 99876-9353"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida"),
                    Map.entry("endereco", "Rua Header, 115, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("TELEFONIA_FIXO", chave));
            assertEquals(chave, externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar400QuandoOrigemOmitidaComExternalCallIdEIdempotencyKeyDivergiremViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-external-header-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("externalCallId", "origem-omitida-ext-key-B"),
                    Map.entry("telefone", "(38) 99876-9354"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Divergente"),
                    Map.entry("endereco", "Rua Header, 116, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "origem-omitida-ext-key-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("Idempotency-Key diverge de externalCallId"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirOrigemOmitidaComExternalCallIdEXIdempotencyKeyIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-external-xheader-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "origem-omitida-ext-xheader-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("externalCallId", chave),
                    Map.entry("telefone", "(38) 99876-9355"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida X"),
                    Map.entry("endereco", "Rua Header, 117, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("TELEFONIA_FIXO", chave));
            assertEquals(chave, externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar400QuandoOrigemOmitidaComExternalCallIdEXIdempotencyKeyDivergiremViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-external-xheader-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("externalCallId", "origem-omitida-ext-xkey-B"),
                    Map.entry("telefone", "(38) 99876-9356"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida X Divergente"),
                    Map.entry("endereco", "Rua Header, 118, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", "origem-omitida-ext-xkey-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("Idempotency-Key diverge de externalCallId"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirOrigemOmitidaComExternalCallIdEHeadersIdempotenciaIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-external-duplo-header-igual@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "origem-omitida-ext-duplo-header-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("externalCallId", chave),
                    Map.entry("telefone", "(38) 99876-9357"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Header Duplo"),
                    Map.entry("endereco", "Rua Header, 119, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("TELEFONIA_FIXO", chave));
            assertEquals(chave, externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar400QuandoOrigemOmitidaComHeadersIdempotenciaIguaisEDivergentesDoExternalViaHttp()
            throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-external-duplo-header-div-external@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("externalCallId", "origem-omitida-ext-duplo-header-B"),
                    Map.entry("telefone", "(38) 99876-9358"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Header Duplo Divergente"),
                    Map.entry("endereco", "Rua Header, 120, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "origem-omitida-ext-duplo-header-A")
                            .header("X-Idempotency-Key", "origem-omitida-ext-duplo-header-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("Idempotency-Key diverge de externalCallId"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoOrigemOmitidaComHeadersIdempotenciaDivergentesEntreSiViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-external-duplo-header-div-headers@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("externalCallId", "origem-omitida-ext-duplo-header-C"),
                    Map.entry("telefone", "(38) 99876-9359"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Header Duplo Divergente Entre Si"),
                    Map.entry("endereco", "Rua Header, 121, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "origem-omitida-ext-duplo-header-C1")
                            .header("X-Idempotency-Key", "origem-omitida-ext-duplo-header-C2")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro")
                    .getAsString()
                    .contains("Idempotency-Key e X-Idempotency-Key devem ter o mesmo valor"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoCanalAutomaticoNaoEnviarSourceEventIdMesmoComHeaderIdempotencyKeyViaHttp()
            throws Exception {
        int atendenteId = criarAtendenteId("api-auto-sem-source-com-header@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("telefone", "(38) 99876-9344"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente auto sem source e com header"),
                    Map.entry("endereco", "Rua Header, 106, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "auto-header-key-0001")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId obrigatorio"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirOrigemOmitidaComSourceEventIdEIdempotencyKeyIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-source-header-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "origem-omitida-source-header-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("sourceEventId", chave),
                    Map.entry("telefone", "(38) 99876-9367"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Source Header Igual"),
                    Map.entry("endereco", "Rua Header, 129, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("TELEFONIA_FIXO", chave));
            assertEquals(chave, externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void devePermitirOrigemOmitidaComSourceEventIdEXIdempotencyKeyIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-source-xheader-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "origem-omitida-source-xheader-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("sourceEventId", chave),
                    Map.entry("telefone", "(38) 99876-9368"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Source X Header Igual"),
                    Map.entry("endereco", "Rua Header, 130, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("TELEFONIA_FIXO", chave));
            assertEquals(chave, externalCallIdPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar400QuandoOrigemOmitidaComSourceEventIdEIdempotencyKeyDivergiremViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-source-header-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("sourceEventId", "origem-omitida-source-header-B"),
                    Map.entry("telefone", "(38) 99876-9369"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Source Header Divergente"),
                    Map.entry("endereco", "Rua Header, 131, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "origem-omitida-source-header-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId diverge do header Idempotency-Key"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoOrigemOmitidaComSourceEventIdEManualRequestIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-origem-omitida-source-manual-key@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("sourceEventId", "origem-omitida-source-manual-001"),
                    Map.entry("manualRequestId", "origem-omitida-manual-001"),
                    Map.entry("telefone", "(38) 99876-9370"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Origem Omitida Source + Manual"),
                    Map.entry("endereco", "Rua Header, 132, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro")
                    .getAsString()
                    .contains("manualRequestId so pode ser usado com origemCanal=MANUAL"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirCanalAutomaticoComSourceEventIdEIdempotencyKeyIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-source-header-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "wa-evt-header-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", chave),
                    Map.entry("telefone", "(38) 99876-9360"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Header Igual"),
                    Map.entry("endereco", "Rua Header, 122, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("WHATSAPP", chave));
        }
    }

    @Test
    void deveRetornar400QuandoCanalAutomaticoComSourceEventIdEIdempotencyKeyDivergiremViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-source-header-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-header-B"),
                    Map.entry("telefone", "(38) 99876-9361"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Header Divergente"),
                    Map.entry("endereco", "Rua Header, 123, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "wa-evt-header-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId diverge do header Idempotency-Key"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirCanalAutomaticoComSourceEventIdEXIdempotencyKeyIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-source-xheader-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "wa-evt-xheader-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", chave),
                    Map.entry("telefone", "(38) 99876-9362"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA X Header Igual"),
                    Map.entry("endereco", "Rua Header, 124, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("WHATSAPP", chave));
        }
    }

    @Test
    void deveRetornar400QuandoCanalAutomaticoComSourceEventIdEXIdempotencyKeyDivergiremViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-source-xheader-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-xheader-B"),
                    Map.entry("telefone", "(38) 99876-9363"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA X Header Divergente"),
                    Map.entry("endereco", "Rua Header, 125, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("X-Idempotency-Key", "wa-evt-xheader-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId diverge do header Idempotency-Key"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void devePermitirCanalAutomaticoComSourceEventIdEHeadersIdempotenciaIguaisViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-source-duplo-header-iguais@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String chave = "wa-evt-duplo-header-0001";
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", chave),
                    Map.entry("telefone", "(38) 99876-9364"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Duplo Header Igual"),
                    Map.entry("endereco", "Rua Header, 126, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", chave)
                            .header("X-Idempotency-Key", chave)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = bodyPrimeira.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertTrue(bodySegunda.get("idempotente").getAsBoolean());
            assertEquals(pedidoId, bodySegunda.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarAtendimentosIdempotenciaPorCanalEChave("WHATSAPP", chave));
        }
    }

    @Test
    void deveRetornar400QuandoCanalAutomaticoComHeadersIdempotenciaIguaisEDivergentesDoSourceEventIdViaHttp()
            throws Exception {
        int atendenteId = criarAtendenteId("api-auto-source-duplo-header-div-source@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-duplo-header-B"),
                    Map.entry("telefone", "(38) 99876-9365"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Duplo Header Divergente Source"),
                    Map.entry("endereco", "Rua Header, 127, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "wa-evt-duplo-header-A")
                            .header("X-Idempotency-Key", "wa-evt-duplo-header-A")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId diverge do header Idempotency-Key"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoCanalAutomaticoComHeadersIdempotenciaDivergentesEntreSiViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-source-duplo-header-div-headers@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-duplo-header-C"),
                    Map.entry("telefone", "(38) 99876-9366"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Duplo Header Divergente Headers"),
                    Map.entry("endereco", "Rua Header, 128, Montes Claros - MG"),
                    Map.entry("latitude", -16.7316),
                    Map.entry("longitude", -43.8716)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "wa-evt-duplo-header-C1")
                            .header("X-Idempotency-Key", "wa-evt-duplo-header-C2")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro")
                    .getAsString()
                    .contains("Idempotency-Key e X-Idempotency-Key devem ter o mesmo valor"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRespeitarIdempotenciaPorCanalESourceEventIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-omnichannel-idem@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-0001"),
                    Map.entry("telefone", "(38) 99876-9332"),
                    Map.entry("quantidadeGaloes", 2),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA"),
                    Map.entry("endereco", "Rua Bot, 42, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body1 = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject body2 = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(body1.get("idempotente").getAsBoolean());
            assertTrue(body2.get("idempotente").getAsBoolean());
            assertEquals(body1.get("pedidoId").getAsInt(), body2.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("clientes"));
        }
    }

    @Test
    void deveRetornar409QuandoSourceEventIdForReutilizadoComPayloadDivergenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-omnichannel-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payloadBase = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-div-0001"),
                    Map.entry("telefone", "(38) 99876-9390"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Divergente"),
                    Map.entry("endereco", "Rua Bot, 90, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));
            String payloadDivergente = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", "wa-evt-div-0001"),
                    Map.entry("telefone", "(38) 99876-9390"),
                    Map.entry("quantidadeGaloes", 2),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Divergente"),
                    Map.entry("endereco", "Rua Bot, 90, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payloadBase))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payloadDivergente))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(409, segunda.statusCode());
            assertTrue(bodySegunda.get("erro").getAsString().contains("payload divergente"));
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("atendimentos_idempotencia"));
        }
    }

    @Test
    void deveRetornar409QuandoManualRequestIdForReutilizadoComPayloadDivergenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-divergente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payloadBase = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("manualRequestId", "manual-evt-div-0001"),
                    Map.entry("telefone", "(38) 99876-9391"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Manual Divergente"),
                    Map.entry("endereco", "Rua Manual, 91, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));
            String payloadDivergente = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("manualRequestId", "manual-evt-div-0001"),
                    Map.entry("telefone", "(38) 99876-9391"),
                    Map.entry("quantidadeGaloes", 2),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Manual Divergente"),
                    Map.entry("endereco", "Rua Manual, 91, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payloadBase))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payloadDivergente))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject bodyPrimeira = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject bodySegunda = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(409, segunda.statusCode());
            assertTrue(bodySegunda.get("erro").getAsString().contains("payload divergente"));
            assertFalse(bodyPrimeira.get("idempotente").getAsBoolean());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("atendimentos_idempotencia"));
        }
    }

    @Test
    void deveAplicarFallbackDeExternalCallIdComoSourceEventIdEmCanalAutomaticoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-omnichannel-fallback@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("externalCallId", "wa-legacy-fallback-0001"),
                    Map.entry("telefone", "(38) 99876-9335"),
                    Map.entry("quantidadeGaloes", 2),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Legacy"),
                    Map.entry("endereco", "Rua Bot, 45, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body1 = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject body2 = GSON.fromJson(segunda.body(), JsonObject.class);
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(body1.get("idempotente").getAsBoolean());
            assertTrue(body2.get("idempotente").getAsBoolean());
            assertEquals(body1.get("pedidoId").getAsInt(), body2.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("atendimentos_idempotencia"));
        }
    }

    @Test
    void devePersistirExternalCallIdHasheadoQuandoSourceEventIdLongoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-omnichannel-source-longo@teste.com");
        HttpClient client = HttpClient.newHttpClient();
        String sourceEventIdLongo = IntStream.range(0, 110).mapToObj(i -> "y").collect(Collectors.joining());

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("sourceEventId", sourceEventIdLongo),
                    Map.entry("telefone", "(38) 99876-9338"),
                    Map.entry("quantidadeGaloes", 2),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente WA Source Longo"),
                    Map.entry("endereco", "Rua Bot, 48, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body1 = GSON.fromJson(primeira.body(), JsonObject.class);
            JsonObject body2 = GSON.fromJson(segunda.body(), JsonObject.class);
            int pedidoId = body1.get("pedidoId").getAsInt();
            assertEquals(200, primeira.statusCode());
            assertEquals(200, segunda.statusCode());
            assertFalse(body1.get("idempotente").getAsBoolean());
            assertTrue(body2.get("idempotente").getAsBoolean());
            assertEquals(body1.get("pedidoId").getAsInt(), body2.get("pedidoId").getAsInt());
            assertEquals(1, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("atendimentos_idempotencia"));

            String externalCallId = externalCallIdPedido(pedidoId);
            assertTrue(externalCallId != null && externalCallId.length() == 64);
        }
    }

    @Test
    void deveRetornar400QuandoCanalAutomaticoNaoEnviarSourceEventIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-sem-source@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "WHATSAPP"),
                    Map.entry("telefone", "(38) 99876-9336"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente sem sourceEventId"),
                    Map.entry("endereco", "Rua Bot, 55, Montes Claros - MG"),
                    Map.entry("latitude", -16.7320),
                    Map.entry("longitude", -43.8720)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId obrigatorio"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoCanalAutomaticoReceberManualRequestIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-auto-manual-key@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "BINA_FIXO"),
                    Map.entry("sourceEventId", "bina-evt-777"),
                    Map.entry("manualRequestId", "manual-ui-777"),
                    Map.entry("telefone", "(38) 99876-9337"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente chave invalida"),
                    Map.entry("endereco", "Rua Bot, 56, Montes Claros - MG"),
                    Map.entry("latitude", -16.7330),
                    Map.entry("longitude", -43.8730)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("manualRequestId so pode ser usado"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRetornar400QuandoCanalManualReceberSourceEventIdViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual-source-event@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("sourceEventId", "manual-source-event-777"),
                    Map.entry("telefone", "(38) 99876-9338"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente chave manual invalida"),
                    Map.entry("endereco", "Rua Bot, 57, Montes Claros - MG"),
                    Map.entry("latitude", -16.7330),
                    Map.entry("longitude", -43.8730)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("sourceEventId nao pode ser usado"));
            assertEquals(0, contarLinhas("pedidos"));
        }
    }

    @Test
    void deveRejeitarCadastroForaDaCoberturaMocViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-coverage@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                factory)) {
            String payload = GSON.toJson(Map.ofEntries(
                    Map.entry("origemCanal", "MANUAL"),
                    Map.entry("telefone", "(38) 99876-9333"),
                    Map.entry("quantidadeGaloes", 1),
                    Map.entry("atendenteId", atendenteId),
                    Map.entry("nomeCliente", "Cliente Fora MOC"),
                    Map.entry("endereco", "Rua Distante, 1"),
                    Map.entry("latitude", -18.0000),
                    Map.entry("longitude", -44.5000)));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(400, resposta.statusCode());
            assertTrue(body.get("erro").getAsString().contains("fora da cobertura operacional"));
            assertEquals(0, contarLinhas("pedidos"));
            assertEquals(1, contarLinhas("clientes"));
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
    void deveRetornarEstadoDoBancoNoHealthViaHttp() throws Exception {
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
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(200, resposta.statusCode());
            assertEquals("ok", body.get("status").getAsString());
            assertEquals("ok", body.get("database").getAsString());
        }
    }

    @Test
    void deveRetornar503NoHealthQuandoBancoEstiverIndisponivelViaHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ConnectionFactory indisponivel =
                new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
        indisponivel.close();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService,
                eventoOperacionalIdempotenciaService,
                indisponivel)) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/health"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals(503, resposta.statusCode());
            assertEquals("degraded", body.get("status").getAsString());
            assertEquals("down", body.get("database").getAsString());
        }
    }

    @Test
    void deveRegistrarPedidoIdempotenteQuandoExternalCallIdPresenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-idempotente@teste.com");
        criarClienteId("(38) 99876-9002");
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
    void deveAceitarJanelaHardViaHttpQuandoPayloadForValido() throws Exception {
        int atendenteId = criarAtendenteId("api-hard-window@teste.com");
        criarClienteId("(38) 99876-9044");
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
                    "call-api-hard-001",
                    "telefone",
                    "(38) 99876-9044",
                    "quantidadeGaloes",
                    1,
                    "atendenteId",
                    atendenteId,
                    "metodoPagamento",
                    "PIX",
                    "janelaTipo",
                    "HARD",
                    "janelaInicio",
                    "09:00",
                    "janelaFim",
                    "11:00"));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resposta.statusCode());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            int pedidoId = body.get("pedidoId").getAsInt();
            assertEquals("HARD", janelaTipoPedido(pedidoId));
            assertEquals("09:00:00", janelaInicioPedido(pedidoId));
            assertEquals("11:00:00", janelaFimPedido(pedidoId));
        }
    }

    @Test
    void deveRetornar400QuandoJanelaHardNaoInformarHorarioCompletoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-hard-window-invalido@teste.com");
        criarClienteId("(38) 99876-9045");
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
                    "call-api-hard-002",
                    "telefone",
                    "(38) 99876-9045",
                    "quantidadeGaloes",
                    1,
                    "atendenteId",
                    atendenteId,
                    "metodoPagamento",
                    "PIX",
                    "janelaTipo",
                    "HARD",
                    "janelaInicio",
                    "09:00"));

            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, resposta.statusCode());
            JsonObject body = GSON.fromJson(resposta.body(), JsonObject.class);
            assertTrue(body.get("erro").getAsString().contains("janelaTipo=HARD"));
            assertEquals(0, contarLinhas("pedidos"));
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
        criarClienteId("(38) 99876-9011");

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
        criarClienteId("(38) 99876-9012");

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
        criarClienteId("(38) 99876-90130");

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
        criarClienteId("(38) 99876-9013");

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
        criarClienteId("(38) 99876-9014");

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
        criarClienteId("(38) 99876-9015");

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
        criarClienteId("(38) 99876-9016");

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
            JsonElement payloadElement = JsonParser.parseString(resposta.body());
            assertTrue(
                    payloadElement.isJsonObject(), "Payload do mapa deve ser um objeto JSON. body=" + resposta.body());
            JsonObject payload = payloadElement.getAsJsonObject();

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
            JsonArray trajetoPrimaria = rotaPrimaria.getAsJsonArray("trajeto");
            assertEquals(3, trajetoPrimaria.size());
            JsonObject pontoInicialPrimaria = trajetoPrimaria.get(0).getAsJsonObject();
            JsonObject pontoFinalPrimaria = trajetoPrimaria.get(2).getAsJsonObject();
            assertEquals("DEPOSITO", pontoInicialPrimaria.get("tipo").getAsString());
            assertEquals("DEPOSITO", pontoFinalPrimaria.get("tipo").getAsString());

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
            JsonArray trajetoSecundaria = rotaSecundaria.getAsJsonArray("trajeto");
            assertEquals(3, trajetoSecundaria.size());
            JsonObject pontoInicialSecundaria = trajetoSecundaria.get(0).getAsJsonObject();
            JsonObject pontoFinalSecundaria = trajetoSecundaria.get(2).getAsJsonObject();
            assertEquals("DEPOSITO", pontoInicialSecundaria.get("tipo").getAsString());
            assertEquals("DEPOSITO", pontoFinalSecundaria.get("tipo").getAsString());
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
        int rotaId = criarRotaComPlanVersion(entregadorId, "PLANEJADA", planVersion, 1, jobId);
        int entregaId = criarEntregaComPlanVersion(pedidoId, rotaId, 1, "PENDENTE", planVersion, jobId);
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
        criarClienteId("(38) 99876-9020");

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
        criarClienteId("(38) 99876-9003");

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
        criarClienteId("(38) 99876-9004");

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
        criarClienteId("(38) 99876-9005");

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
        criarClienteId("(38) 99876-9006");
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
        criarClienteId("(38) 99876-9021");
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
        criarClienteId("(38) 99876-9022");
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
        criarClienteId("(38) 99876-9221");
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

    private int contarAtendimentosIdempotenciaPorCanalEChave(String origemCanal, String dedupeKey) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM atendimentos_idempotencia WHERE origem_canal = ? AND source_event_id = ?")) {
            stmt.setString(1, origemCanal);
            stmt.setString(2, dedupeKey);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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

    private String janelaTipoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT janela_tipo::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String janelaInicioPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT janela_inicio::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String janelaFimPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT janela_fim::text FROM pedidos WHERE id = ?")) {
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

    private int criarRotaComPlanVersion(
            int entregadorId, String status, long planVersion, int numeroNoDia, String jobId) throws Exception {
        try (Connection conn = factory.getConnection()) {
            boolean hasJobId = hasColumn(conn, "rotas", "job_id");
            String sql = hasJobId
                    ? "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version, job_id) VALUES (?, CURRENT_DATE, ?, ?, ?, ?) RETURNING id"
                    : "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version) VALUES (?, CURRENT_DATE, ?, ?, ?) RETURNING id";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, entregadorId);
                stmt.setInt(2, numeroNoDia);
                stmt.setObject(3, status, java.sql.Types.OTHER);
                stmt.setLong(4, planVersion);
                if (hasJobId) {
                    stmt.setString(5, jobId);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
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

    private int criarEntregaComPlanVersion(
            int pedidoId, int rotaId, int ordemNaRota, String status, long planVersion, String jobId) throws Exception {
        try (Connection conn = factory.getConnection()) {
            boolean hasJobId = hasColumn(conn, "entregas", "job_id");
            String sql = hasJobId
                    ? "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status, plan_version, job_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id"
                    : "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status, plan_version) VALUES (?, ?, ?, ?, ?) RETURNING id";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, pedidoId);
                stmt.setInt(2, rotaId);
                stmt.setInt(3, ordemNaRota);
                stmt.setObject(4, status, java.sql.Types.OTHER);
                stmt.setLong(5, planVersion);
                if (hasJobId) {
                    stmt.setString(6, jobId);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
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
