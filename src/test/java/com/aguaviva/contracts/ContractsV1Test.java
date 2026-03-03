package com.aguaviva.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.service.DispatchEventTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
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
                () -> assertTrue(
                        openApi.contains("/api/atendimento/pedidos:"), "Contrato deve expor endpoint de atendimento"),
                () -> assertTrue(openApi.contains("/api/eventos:"), "Contrato deve expor endpoint de eventos"),
                () -> assertTrue(
                        openApi.contains("/api/replanejamento/run:"), "Contrato deve expor endpoint de replanejamento"),
                () -> assertTrue(
                        openApi.contains("/api/operacao/rotas/prontas/iniciar:"),
                        "Contrato deve expor endpoint one-click de iniciar rota pronta"),
                () -> assertTrue(
                        openApi.contains("/api/pedidos/{pedidoId}/execucao:"),
                        "Contrato deve expor endpoint de execucao de pedido"),
                () -> assertTrue(
                        openApi.contains("/api/operacao/painel:"),
                        "Contrato deve expor endpoint de painel operacional"),
                () -> assertTrue(
                        openApi.contains("/api/operacao/eventos:"),
                        "Contrato deve expor endpoint de eventos operacionais"),
                () -> assertTrue(
                        openApi.contains("/api/operacao/mapa:"), "Contrato deve expor endpoint de mapa operacional"),
                () -> assertTrue(
                        openApi.contains("/api/operacao/replanejamento/jobs:"),
                        "Contrato deve expor endpoint de jobs de replanejamento"),
                () -> assertTrue(
                        openApi.contains("/api/operacao/replanejamento/jobs/{jobId}:"),
                        "Contrato deve expor endpoint de detalhe de job de replanejamento"),
                () -> assertTrue(
                        openApi.contains("externalEventId:"),
                        "Contrato deve mapear chave de idempotencia externalEventId"),
                () -> assertTrue(
                        openApi.contains("sourceEventId:"), "Contrato deve mapear chave omnichannel sourceEventId"),
                () -> assertTrue(
                        openApi.contains("manualRequestId:"),
                        "Contrato deve mapear chave de idempotencia manualRequestId"),
                () -> assertTrue(
                        openApi.contains("origemCanal:"),
                        "Contrato deve mapear origemCanal no atendimento omnichannel"),
                () -> assertTrue(
                        openApi.contains("Idempotency-Key"),
                        "Contrato deve mapear header Idempotency-Key para atendimento manual"),
                () -> assertTrue(
                        openApi.contains("X-Idempotency-Key"),
                        "Contrato deve mapear header X-Idempotency-Key para atendimento manual"),
                () -> assertTrue(
                        openApi.contains("actorEntregadorId:"),
                        "Contrato deve mapear actorEntregadorId para ownership operacional"),
                () -> assertTrue(
                        openApi.contains("enum: [ROTA_INICIADA, PEDIDO_ENTREGUE, PEDIDO_FALHOU, PEDIDO_CANCELADO]"),
                        "Contrato deve listar eventos operacionais aceitos pela API"));
    }

    @Test
    void deveManterCatalogoEventosAlinhadoAoBackend() throws Exception {
        Path catalogoPath = CONTRACTS_V1_DIR.resolve(Path.of("events", "catalogo-eventos.json"));
        assertTrue(Files.exists(catalogoPath), "Arquivo contracts/v1/events/catalogo-eventos.json deve existir");

        JsonObject catalogo =
                JsonParser.parseString(Files.readString(catalogoPath)).getAsJsonObject();
        assertEquals("1.0.0", catalogo.get("version").getAsString(), "Catalogo deve usar versao 1.0.0");

        JsonArray eventos = catalogo.getAsJsonArray("eventos");
        Set<String> declarados = eventos.asList().stream()
                .map(element -> element.getAsJsonObject().get("event_type").getAsString())
                .collect(Collectors.toSet());

        Set<String> esperados = Set.of(
                DispatchEventTypes.PEDIDO_CRIADO,
                DispatchEventTypes.ROTA_INICIADA,
                DispatchEventTypes.ROTA_CONCLUIDA,
                DispatchEventTypes.PEDIDO_ENTREGUE,
                DispatchEventTypes.PEDIDO_FALHOU,
                DispatchEventTypes.PEDIDO_CANCELADO);
        assertEquals(esperados, declarados, "Catalogo deve cobrir eventos do backend");

        Set<String> triggersReplanejamento = eventos.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(evento -> evento.get("trigger_replanejamento").getAsBoolean())
                .map(evento -> evento.get("event_type").getAsString())
                .collect(Collectors.toSet());
        assertEquals(
                Set.of(
                        DispatchEventTypes.PEDIDO_CRIADO,
                        DispatchEventTypes.ROTA_CONCLUIDA,
                        DispatchEventTypes.PEDIDO_FALHOU,
                        DispatchEventTypes.PEDIDO_CANCELADO),
                triggersReplanejamento,
                "Catalogo deve refletir eventos que disparam replanejamento");

        Map<String, String> triggerKindPorEvento = eventos.asList().stream()
                .map(element -> element.getAsJsonObject())
                .collect(Collectors.toMap(
                        evento -> evento.get("event_type").getAsString(),
                        evento -> evento.get("trigger_kind").getAsString()));
        assertEquals("PRIMARIO", triggerKindPorEvento.get(DispatchEventTypes.PEDIDO_CRIADO));
        assertEquals("SECUNDARIO", triggerKindPorEvento.get(DispatchEventTypes.ROTA_CONCLUIDA));
        assertEquals("SECUNDARIO", triggerKindPorEvento.get(DispatchEventTypes.PEDIDO_FALHOU));
        assertEquals("SECUNDARIO", triggerKindPorEvento.get(DispatchEventTypes.PEDIDO_CANCELADO));
        assertEquals("NONE", triggerKindPorEvento.get(DispatchEventTypes.PEDIDO_ENTREGUE));
    }

    @Test
    void deveTerExemplosJsonValidosParaEndpointsCore() throws Exception {
        Path examplesDir = CONTRACTS_V1_DIR.resolve("examples");
        assertTrue(Files.exists(examplesDir), "Diretorio contracts/v1/examples deve existir");

        String[] exemplosObrigatorios = new String[] {
            "atendimento-pedido.request.json",
            "atendimento-pedido-manual.request.json",
            "poc-atendimento.request.json",
            "atendimento-pedido.response.json",
            "evento-operacional.request.json",
            "evento-operacional.response.json",
            "replanejamento-run.request.json",
            "replanejamento-run.response.json",
            "operacao-iniciar-rota-pronta.request.json",
            "operacao-iniciar-rota-pronta.response.json",
            "pedido-timeline.response.json",
            "pedido-execucao.response.json",
            "entregador-roteiro.response.json",
            "operacao-painel.response.json",
            "operacao-eventos.response.json",
            "operacao-mapa.response.json",
            "operacao-replanejamento-jobs.response.json",
            "operacao-replanejamento-job-detalhe.response.json"
        };

        for (String nomeArquivo : exemplosObrigatorios) {
            Path arquivo = examplesDir.resolve(nomeArquivo);
            assertTrue(Files.exists(arquivo), "Exemplo obrigatorio ausente: " + nomeArquivo);
            JsonObject conteudo =
                    JsonParser.parseString(Files.readString(arquivo)).getAsJsonObject();
            assertTrue(!conteudo.isEmpty(), "Exemplo nao pode ser JSON vazio: " + nomeArquivo);
        }
    }

    @Test
    void deveManterSemanticaDoContratoNoExemploDePainelOperacional() throws Exception {
        Map<String, Object> openApiDocument = OpenApiYamlSupport.load(CONTRACTS_V1_DIR.resolve("openapi.yaml"));
        JsonObject painelExemplo = JsonParser.parseString(Files.readString(
                        CONTRACTS_V1_DIR.resolve(Path.of("examples", "operacao-painel.response.json"))))
                .getAsJsonObject();

        Set<String> requiredPainel = extractSchemaRequired(openApiDocument, "OperacaoPainelResponse");
        Set<String> requiredPedidos = extractSchemaRequired(openApiDocument, "OperacaoPainelPedidosPorStatus");
        Set<String> requiredIndicadores = extractSchemaRequired(openApiDocument, "OperacaoPainelIndicadoresEntrega");

        assertObjectContainsRequiredFields(painelExemplo, requiredPainel, "OperacaoPainelResponse");
        JsonObject pedidosPorStatus = painelExemplo.getAsJsonObject("pedidosPorStatus");
        assertNotNull(pedidosPorStatus, "Exemplo deve incluir objeto pedidosPorStatus");
        assertObjectContainsRequiredFields(
                pedidosPorStatus, requiredPedidos, "OperacaoPainelResponse.pedidosPorStatus");

        JsonObject indicadoresEntrega = painelExemplo.getAsJsonObject("indicadoresEntrega");
        assertNotNull(indicadoresEntrega, "Exemplo deve incluir objeto indicadoresEntrega");
        assertObjectContainsRequiredFields(
                indicadoresEntrega, requiredIndicadores, "OperacaoPainelResponse.indicadoresEntrega");

        int totalFinalizadas = indicadoresEntrega.get("totalFinalizadas").getAsInt();
        int entregasConcluidas = indicadoresEntrega.get("entregasConcluidas").getAsInt();
        int entregasCanceladas = indicadoresEntrega.get("entregasCanceladas").getAsInt();
        double taxaSucessoPercentual =
                indicadoresEntrega.get("taxaSucessoPercentual").getAsDouble();

        assertEquals(
                entregasConcluidas + entregasCanceladas,
                totalFinalizadas,
                "Semantica invalida: totalFinalizadas deve ser soma de entregas concluidas + canceladas");
        double taxaEsperada = totalFinalizadas == 0
                ? 0.0
                : BigDecimal.valueOf(entregasConcluidas)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalFinalizadas), 2, RoundingMode.HALF_UP)
                        .doubleValue();
        assertEquals(
                taxaEsperada,
                taxaSucessoPercentual,
                0.001,
                "Semantica invalida: taxaSucessoPercentual deve refletir entregasConcluidas/totalFinalizadas");
    }

    private static Set<String> extractSchemaRequired(Map<String, Object> openApiDocument, String schemaName) {
        Map<String, Object> components = OpenApiYamlSupport.requiredMap(openApiDocument, "components", "openapi");
        Map<String, Object> schemas = OpenApiYamlSupport.requiredMap(components, "schemas", "openapi.components");
        Map<String, Object> schema = OpenApiYamlSupport.requiredMap(schemas, schemaName, "openapi.components.schemas");

        return OpenApiYamlSupport.requiredList(schema, "required", "openapi.components.schemas." + schemaName).stream()
                .map(String::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void assertObjectContainsRequiredFields(
            JsonObject payload, Set<String> requiredFields, String scope) {
        for (String field : requiredFields) {
            assertTrue(payload.has(field), "Exemplo deve conter campo obrigatorio " + scope + "." + field);
        }
    }
}
