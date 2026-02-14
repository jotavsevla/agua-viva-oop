package com.aguaviva.api;

import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.UserRepository;
import com.aguaviva.service.AtendimentoTelefonicoService;
import com.aguaviva.service.AtendimentoTelefonicoResultado;
import com.aguaviva.service.ExecucaoEntregaService;
import com.aguaviva.service.PedidoTimelineService;
import com.aguaviva.service.PlanejamentoResultado;
import com.aguaviva.service.ReplanejamentoWorkerService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    private static final Gson GSON = new Gson();

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static AtendimentoTelefonicoService atendimentoService;
    private static ExecucaoEntregaService execucaoService;
    private static ReplanejamentoWorkerService replanejamentoService;
    private static PedidoTimelineService pedidoTimelineService;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new ConnectionFactory(
                "localhost", "5435",
                "agua_viva_oop_test",
                "postgres", "postgres"
        );
        userRepository = new UserRepository(factory);
        atendimentoService = new AtendimentoTelefonicoService(factory);
        execucaoService = new ExecucaoEntregaService(factory);
        replanejamentoService = new ReplanejamentoWorkerService(
                factory,
                () -> new PlanejamentoResultado(0, 0, 0)
        );
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
        }
    }

    private void limparBanco() throws Exception {
        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE dispatch_events, sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
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

    @Test
    void deveRegistrarPedidoManualQuandoExternalCallIdAusenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-manual@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService
        )) {
            String payload = GSON.toJson(Map.of(
                    "telefone", "(38) 99876-9001",
                    "quantidadeGaloes", 2,
                    "atendenteId", atendenteId
            ));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

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
                    HttpResponse.BodyHandlers.ofString()
            );

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
    void deveRegistrarPedidoIdempotenteQuandoExternalCallIdPresenteViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-idempotente@teste.com");
        HttpClient client = HttpClient.newHttpClient();

        try (ApiServer.RunningServer running = ApiServer.startForTests(
                0,
                atendimentoService,
                execucaoService,
                replanejamentoService,
                pedidoTimelineService
        )) {
            String payload = GSON.toJson(Map.of(
                    "externalCallId", "call-api-001",
                    "telefone", "(38) 99876-9002",
                    "quantidadeGaloes", 2,
                    "atendenteId", atendenteId
            ));

            HttpResponse<String> primeira = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JsonObject primeiraResposta = GSON.fromJson(primeira.body(), JsonObject.class);
            int pedidoIdPrimeiro = primeiraResposta.get("pedidoId").getAsInt();

            HttpResponse<String> segunda = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
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
    void deveRetornarTimelineDoPedidoComStatusAtualViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-timeline-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-timeline-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento = atendimentoService.registrarPedidoManual(
                "(38) 99876-9003",
                2,
                atendenteId
        );
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
                pedidoTimelineService
        )) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

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
                pedidoTimelineService
        )) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/pedidos/999999/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(404, resposta.statusCode());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            assertTrue(payload.get("erro").getAsString().contains("Pedido nao encontrado"));
        }
    }

    @Test
    void deveRetornarTimelineComCancelamentoPorFalhaEObservacaoViaHttp() throws Exception {
        int atendenteId = criarAtendenteId("api-timeline-falha-atendente@teste.com");
        int entregadorId = criarEntregadorId("api-timeline-falha-entregador@teste.com");

        AtendimentoTelefonicoResultado atendimento = atendimentoService.registrarPedidoManual(
                "(38) 99876-9004",
                1,
                atendenteId
        );
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
                pedidoTimelineService
        )) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

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

        AtendimentoTelefonicoResultado atendimento = atendimentoService.registrarPedidoManual(
                "(38) 99876-9005",
                1,
                atendenteId
        );
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
                pedidoTimelineService
        )) {
            HttpResponse<String> resposta = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, resposta.statusCode());
            JsonObject payload = GSON.fromJson(resposta.body(), JsonObject.class);
            assertEquals("CANCELADO", payload.get("statusAtual").getAsString());

            JsonArray eventos = payload.getAsJsonArray("eventos");
            assertNotNull(eventos);
            assertTrue(eventos.size() >= 3);
            JsonObject eventoCancelamento = buscarEvento(eventos, "EM_ROTA", "CANCELADO", "Evento operacional");
            assertNotNull(eventoCancelamento);
            assertEquals("cliente cancelou", eventoCancelamento.get("observacao").getAsString());
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
                pedidoTimelineService
        )) {
            String atendimentoPayload = GSON.toJson(Map.of(
                    "telefone", "(38) 99876-9006",
                    "quantidadeGaloes", 1,
                    "atendenteId", atendenteId
            ));
            HttpResponse<String> atendimentoResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/atendimento/pedidos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(atendimentoPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, atendimentoResp.statusCode());
            JsonObject atendimentoBody = GSON.fromJson(atendimentoResp.body(), JsonObject.class);
            int pedidoId = atendimentoBody.get("pedidoId").getAsInt();

            atualizarStatusPedido(pedidoId, "CONFIRMADO");
            int rotaId = criarRota(entregadorId, "PLANEJADA");
            int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

            String rotaIniciadaPayload = GSON.toJson(Map.of(
                    "eventType", "ROTA_INICIADA",
                    "rotaId", rotaId
            ));
            HttpResponse<String> rotaIniciadaResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(rotaIniciadaPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, rotaIniciadaResp.statusCode());

            String entreguePayload = GSON.toJson(Map.of(
                    "eventType", "PEDIDO_ENTREGUE",
                    "entregaId", entregaId
            ));
            HttpResponse<String> entregueResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/eventos"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(entreguePayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, entregueResp.statusCode());

            HttpResponse<String> timelineResp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + running.port() + "/api/pedidos/" + pedidoId + "/timeline"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
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

    private int contarLinhas(String tabela) throws Exception {
        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String externalCallIdPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT external_call_id FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
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

    private void atualizarStatusPedido(int pedidoId, String status) throws Exception {
        try (Connection conn = factory.getConnection();
             PreparedStatement stmt = conn.prepareStatement("UPDATE pedidos SET status = ? WHERE id = ?")) {
            stmt.setObject(1, status, java.sql.Types.OTHER);
            stmt.setInt(2, pedidoId);
            stmt.executeUpdate();
        }
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
}
