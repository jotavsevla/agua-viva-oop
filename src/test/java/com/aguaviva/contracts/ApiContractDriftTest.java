package com.aguaviva.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApiContractDriftTest {

    private static final Path OPENAPI_PATH = Path.of("contracts", "v1", "openapi.yaml");
    private static final Path API_SERVER_PATH = Path.of("src", "main", "java", "com", "aguaviva", "api", "ApiServer.java");

    @Test
    void deveManterOpenApiAlinhadaComEndpointsOperacionaisAtivosNoServidor() throws Exception {
        String openApi = Files.readString(OPENAPI_PATH);
        String apiServer = Files.readString(API_SERVER_PATH);

        assertAll(
                () -> assertTrue(apiServer.contains("server.createContext(\"/api/pedidos\""), "ApiServer deve registrar /api/pedidos"),
                () -> assertTrue(apiServer.contains("server.createContext(\"/api/entregadores\""), "ApiServer deve registrar /api/entregadores"),
                () -> assertTrue(apiServer.contains("server.createContext(\"/api/operacao\""), "ApiServer deve registrar /api/operacao"),
                () -> assertTrue(
                        apiServer.contains("server.createContext(\"/api/operacao/rotas/prontas/iniciar\""),
                        "ApiServer deve registrar endpoint one-click de iniciar rota pronta"),
                () -> assertTrue(openApi.contains("/api/pedidos/{pedidoId}/execucao:"), "OpenAPI deve expor /api/pedidos/{pedidoId}/execucao"),
                () -> assertTrue(openApi.contains("/api/entregadores/{entregadorId}/roteiro:"), "OpenAPI deve expor /api/entregadores/{entregadorId}/roteiro"),
                () -> assertTrue(openApi.contains("/api/operacao/painel:"), "OpenAPI deve expor /api/operacao/painel"),
                () -> assertTrue(openApi.contains("/api/operacao/eventos:"), "OpenAPI deve expor /api/operacao/eventos"),
                () -> assertTrue(openApi.contains("/api/operacao/mapa:"), "OpenAPI deve expor /api/operacao/mapa"),
                () -> assertTrue(
                        openApi.contains("/api/operacao/rotas/prontas/iniciar:"),
                        "OpenAPI deve expor /api/operacao/rotas/prontas/iniciar"),
                () -> assertTrue(openApi.contains("/api/replanejamento/run:"), "OpenAPI deve expor /api/replanejamento/run"),
                () -> assertTrue(
                        openApi.contains("Endpoint desativado para execucao manual"),
                        "OpenAPI deve documentar que /api/replanejamento/run esta desativado"));
    }

    @Test
    void deveManterCamposCriticosDeEventoOperacionalNoContrato() throws Exception {
        String openApi = Files.readString(OPENAPI_PATH);

        assertAll(
                () -> assertTrue(openApi.contains("externalEventId:"), "Contrato deve mapear externalEventId"),
                () -> assertTrue(openApi.contains("actorEntregadorId:"), "Contrato deve mapear actorEntregadorId"),
                () -> assertTrue(openApi.contains("eventType:"), "Contrato deve mapear eventType"),
                () -> assertTrue(openApi.contains("idempotente:"), "Contrato deve mapear flag idempotente"),
                () -> assertTrue(openApi.contains("enum: [ROTA_INICIADA, PEDIDO_ENTREGUE, PEDIDO_FALHOU, PEDIDO_CANCELADO]"),
                        "Contrato deve manter enum de eventos operacionais aceitos"));
    }
}
