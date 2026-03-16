package com.aguaviva.solver;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SolverClientTest {

    // ========================================================================
    // Helpers
    // ========================================================================

    private Gson gson() {
        return new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    // ========================================================================
    // Serializacao: Java → JSON (request pro solver)
    // ========================================================================

    @Test
    void deveSerializarRequestComPedidoAsap() {
        var req = SolverRequest.of(
                null,
                null,
                new Coordenada(-16.7344, -43.8772),
                5,
                "08:00",
                "18:00",
                List.of(1, 2),
                List.of(new PedidoSolver(42, -16.71, -43.85, 2, "ASAP", null, null, 2)));

        String json = gson().toJson(req);

        assertTrue(json.contains("\"deposito\""));
        assertTrue(json.contains("\"capacidade_veiculo\":5"));
        assertTrue(json.contains("\"horario_inicio\":\"08:00\""));
        assertTrue(json.contains("\"entregadores\":[1,2]"));
        assertTrue(json.contains("\"pedido_id\":42"));
        assertTrue(json.contains("\"janela_tipo\":\"ASAP\""));
        assertFalse(json.contains("janela_inicio"));
    }

    @Test
    void deveSerializarRequestComPedidoHard() {
        var pedido = new PedidoSolver(10, -16.72, -43.86, 1, "HARD", "09:00", "11:00", 1);

        String json = gson().toJson(pedido);

        assertTrue(json.contains("\"janela_tipo\":\"HARD\""));
        assertTrue(json.contains("\"janela_inicio\":\"09:00\""));
        assertTrue(json.contains("\"janela_fim\":\"11:00\""));
        assertTrue(json.contains("\"prioridade\":1"));
    }

    @Test
    void deveSerializarCapacidadesPorEntregadorQuandoInformadas() {
        var req = new SolverRequest(
                "job-cap",
                5L,
                new Coordenada(-16.7344, -43.8772),
                5,
                List.of(3, 5),
                "08:00",
                "18:00",
                List.of(1, 2),
                List.of(new PedidoSolver(42, -16.71, -43.85, 2, "ASAP", null, null, 2)));

        String json = gson().toJson(req);

        assertTrue(json.contains("\"capacidades_entregadores\":[3,5]"));
    }

    @Test
    void deveSerializarCoordenadaDoDeposito() {
        var coord = new Coordenada(-16.734440968489228, -43.877211192130325);

        String json = gson().toJson(coord);

        assertTrue(json.contains("-16.734440968489228"));
        assertTrue(json.contains("-43.877211192130325"));
    }

    // ========================================================================
    // Deserializacao: JSON → Java (resposta do solver)
    // ========================================================================

    @Test
    void deveDeserializarResponseComRotas() {
        String json = """
                {
                  "rotas": [
                    {
                      "entregador_id": 1,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": 42, "lat": -16.71, "lon": -43.85, "hora_prevista": "09:30"},
                        {"ordem": 2, "pedido_id": 15, "lat": -16.72, "lon": -43.86, "hora_prevista": "10:15"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """;

        SolverResponse resp = gson().fromJson(json, SolverResponse.class);

        assertEquals(1, resp.rotas().size());
        assertTrue(resp.naoAtendidos().isEmpty());

        RotaSolver rota = resp.rotas().get(0);
        assertEquals(1, rota.entregadorId());
        assertEquals(1, rota.numeroNoDia());
        assertEquals(2, rota.paradas().size());

        Parada primeira = rota.paradas().get(0);
        assertEquals(1, primeira.ordem());
        assertEquals(42, primeira.pedidoId());
        assertEquals(-16.71, primeira.lat(), 0.0001);
        assertEquals("09:30", primeira.horaPrevista());
    }

    @Test
    void deveDeserializarResponseComNaoAtendidos() {
        String json = """
                {
                  "rotas": [],
                  "nao_atendidos": [42, 15, 7]
                }
                """;

        SolverResponse resp = gson().fromJson(json, SolverResponse.class);

        assertTrue(resp.rotas().isEmpty());
        assertEquals(List.of(42, 15, 7), resp.naoAtendidos());
    }

    @Test
    void deveDeserializarResponseComMultiplasRotas() {
        String json = """
                {
                  "rotas": [
                    {
                      "entregador_id": 1,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": 10, "lat": -16.71, "lon": -43.85, "hora_prevista": "08:30"}
                      ]
                    },
                    {
                      "entregador_id": 1,
                      "numero_no_dia": 2,
                      "paradas": [
                        {"ordem": 1, "pedido_id": 20, "lat": -16.73, "lon": -43.87, "hora_prevista": "11:00"}
                      ]
                    },
                    {
                      "entregador_id": 3,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": 30, "lat": -16.72, "lon": -43.86, "hora_prevista": "09:00"},
                        {"ordem": 2, "pedido_id": 31, "lat": -16.74, "lon": -43.88, "hora_prevista": "09:45"}
                      ]
                    }
                  ],
                  "nao_atendidos": [99]
                }
                """;

        SolverResponse resp = gson().fromJson(json, SolverResponse.class);

        assertEquals(3, resp.rotas().size());
        assertEquals(List.of(99), resp.naoAtendidos());

        // Entregador 1 com 2 viagens
        assertEquals(1, resp.rotas().get(0).numeroNoDia());
        assertEquals(2, resp.rotas().get(1).numeroNoDia());

        // Entregador 3 com 2 paradas
        assertEquals(2, resp.rotas().get(2).paradas().size());
    }

    // ========================================================================
    // Roundtrip: serializa request, deserializa response
    // ========================================================================

    @Test
    void deveSerializarEDeserializarCorretamente() {
        var req = SolverRequest.of(
                null,
                null,
                new Coordenada(-16.7344, -43.8772),
                5,
                "08:00",
                "18:00",
                List.of(1),
                List.of(new PedidoSolver(1, -16.71, -43.85, 3, "ASAP", null, null, 2)));

        // Serializa
        String json = gson().toJson(req);

        // Deserializa de volta
        SolverRequest back = gson().fromJson(json, SolverRequest.class);

        assertEquals(req.deposito().lat(), back.deposito().lat(), 0.0001);
        assertEquals(req.capacidadeVeiculo(), back.capacidadeVeiculo());
        assertEquals(req.entregadores(), back.entregadores());
        assertEquals(1, back.pedidos().size());
        assertEquals(1, back.pedidos().get(0).pedidoId());
    }

    @Test
    void deveSerializarMetadadosDeJobAsyncNoRequest() {
        var req = SolverRequest.of(
                "job-123",
                7L,
                new Coordenada(-16.7344, -43.8772),
                5,
                "08:00",
                "18:00",
                List.of(1),
                List.of(new PedidoSolver(1, -16.71, -43.85, 1, "ASAP", null, null, 2)));

        String json = gson().toJson(req);

        assertTrue(json.contains("\"job_id\":\"job-123\""));
        assertTrue(json.contains("\"plan_version\":7"));
    }

    @Test
    void deveDeserializarResultadoDeJobAsync() {
        String json = """
                {
                  "job_id": "job-xyz",
                  "status": "CONCLUIDO",
                  "response": {
                    "rotas": [],
                    "nao_atendidos": [42]
                  },
                  "erro": null
                }
                """;

        SolverJobResult result = gson().fromJson(json, SolverJobResult.class);

        assertEquals("job-xyz", result.jobId());
        assertEquals("CONCLUIDO", result.status());
        assertNotNull(result.response());
        assertEquals(List.of(42), result.response().naoAtendidos());
    }

    // ========================================================================
    // SolverClient: construcao
    // ========================================================================

    @Test
    void deveCriarClientComUrl() {
        var client = new SolverClient("http://localhost:8080");
        assertNotNull(client);
    }

    @Test
    void deveRejeitarUrlNula() {
        assertThrows(NullPointerException.class, () -> new SolverClient(null));
    }

    @Test
    void deveRejeitarUrlVazia() {
        assertThrows(IllegalArgumentException.class, () -> new SolverClient(""));
    }

    @Test
    void deveEnviarSolveSemUpgradeH2cParaCompatibilidadeComFastApi() throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>(null);
        AtomicReference<String> bodyRecebido = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/solve", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            bodyRecebido.set(new String(body, StandardCharsets.UTF_8));
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));

            byte[] responseBody;
            int status;
            if (upgradeHeader.get() != null && !upgradeHeader.get().isBlank()) {
                status = 422;
                responseBody = """
                        {"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                responseBody = """
                        {"rotas":[],"nao_atendidos":[]}
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            SolverClient client = new SolverClient("http://localhost:" + port);
            SolverRequest request = SolverRequest.of(
                    "job-http11",
                    1L,
                    new Coordenada(-16.7344, -43.8772),
                    5,
                    "08:00",
                    "18:00",
                    List.of(1),
                    List.of(new PedidoSolver(1, -16.71, -43.85, 1, "ASAP", null, null, 2)));

            SolverResponse response = client.solve(request);
            assertNotNull(response);
            assertTrue(response.rotas().isEmpty());
            assertTrue(response.naoAtendidos().isEmpty());
            assertNull(upgradeHeader.get());
            assertFalse(bodyRecebido.get().isBlank());
        } finally {
            server.stop(0);
        }
    }
}
