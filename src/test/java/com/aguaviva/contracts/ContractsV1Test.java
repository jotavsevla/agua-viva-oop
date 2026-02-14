package com.aguaviva.contracts;

import com.aguaviva.service.DispatchEventTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractsV1Test {

    private static final Path CONTRACTS_V1_DIR = Path.of("contracts", "v1");

    @Test
    void deveDefinirOpenApiV1ComEndpointsCoreOperacionais() throws Exception {
        Path openApiPath = CONTRACTS_V1_DIR.resolve("openapi.yaml");
        assertTrue(Files.exists(openApiPath), "Arquivo contracts/v1/openapi.yaml deve existir");

        String openApi = Files.readString(openApiPath);
        assertAll(
                () -> assertTrue(openApi.contains("openapi: 3.0.3"), "OpenAPI version deve ser 3.0.3"),
                () -> assertTrue(openApi.contains("version: 1.0.0"), "Versao do contrato deve ser 1.0.0"),
                () -> assertTrue(openApi.contains("/health:"), "Contrato deve expor /health"),
                () -> assertTrue(openApi.contains("/api/atendimento/pedidos:"), "Contrato deve expor endpoint de atendimento"),
                () -> assertTrue(openApi.contains("/api/eventos:"), "Contrato deve expor endpoint de eventos"),
                () -> assertTrue(openApi.contains("/api/replanejamento/run:"), "Contrato deve expor endpoint de replanejamento"),
                () -> assertTrue(
                        openApi.contains("enum: [ROTA_INICIADA, PEDIDO_ENTREGUE, PEDIDO_FALHOU, PEDIDO_CANCELADO]"),
                        "Contrato deve listar eventos operacionais aceitos pela API"
                )
        );
    }

    @Test
    void deveManterCatalogoEventosAlinhadoAoBackend() throws Exception {
        Path catalogoPath = CONTRACTS_V1_DIR.resolve(Path.of("events", "catalogo-eventos.json"));
        assertTrue(Files.exists(catalogoPath), "Arquivo contracts/v1/events/catalogo-eventos.json deve existir");

        JsonObject catalogo = JsonParser.parseString(Files.readString(catalogoPath)).getAsJsonObject();
        assertEquals("1.0.0", catalogo.get("version").getAsString(), "Catalogo deve usar versao 1.0.0");

        JsonArray eventos = catalogo.getAsJsonArray("eventos");
        Set<String> declarados = eventos.asList().stream()
                .map(element -> element.getAsJsonObject().get("event_type").getAsString())
                .collect(Collectors.toSet());

        Set<String> esperados = Set.of(
                DispatchEventTypes.PEDIDO_CRIADO,
                DispatchEventTypes.ROTA_INICIADA,
                DispatchEventTypes.PEDIDO_ENTREGUE,
                DispatchEventTypes.PEDIDO_FALHOU,
                DispatchEventTypes.PEDIDO_CANCELADO
        );
        assertEquals(esperados, declarados, "Catalogo deve cobrir eventos do backend");

        Set<String> triggersReplanejamento = eventos.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(evento -> evento.get("trigger_replanejamento").getAsBoolean())
                .map(evento -> evento.get("event_type").getAsString())
                .collect(Collectors.toSet());
        assertEquals(
                Set.of(
                        DispatchEventTypes.PEDIDO_CRIADO,
                        DispatchEventTypes.PEDIDO_FALHOU,
                        DispatchEventTypes.PEDIDO_CANCELADO
                ),
                triggersReplanejamento,
                "Catalogo deve refletir eventos que disparam replanejamento"
        );
    }

    @Test
    void deveTerExemplosJsonValidosParaEndpointsCore() throws Exception {
        Path examplesDir = CONTRACTS_V1_DIR.resolve("examples");
        assertTrue(Files.exists(examplesDir), "Diretorio contracts/v1/examples deve existir");

        String[] exemplosObrigatorios = new String[]{
                "atendimento-pedido.request.json",
                "atendimento-pedido.response.json",
                "evento-operacional.request.json",
                "evento-operacional.response.json",
                "replanejamento-run.request.json",
                "replanejamento-run.response.json",
                "pedido-timeline.response.json"
        };

        for (String nomeArquivo : exemplosObrigatorios) {
            Path arquivo = examplesDir.resolve(nomeArquivo);
            assertTrue(Files.exists(arquivo), "Exemplo obrigatorio ausente: " + nomeArquivo);
            JsonObject conteudo = JsonParser.parseString(Files.readString(arquivo)).getAsJsonObject();
            assertTrue(!conteudo.isEmpty(), "Exemplo nao pode ser JSON vazio: " + nomeArquivo);
        }
    }
}
