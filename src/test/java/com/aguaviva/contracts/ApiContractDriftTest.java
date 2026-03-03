package com.aguaviva.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApiContractDriftTest {

    private static final Path OPENAPI_PATH = Path.of("contracts", "v1", "openapi.yaml");
    private static final Path API_SERVER_PATH =
            Path.of("src", "main", "java", "com", "aguaviva", "api", "ApiServer.java");
    private static final Pattern OPENAPI_PATH_PATTERN = Pattern.compile("^\\s{2}(/[^:]+):\\s*$");
    private static final Pattern OPENAPI_METHOD_PATTERN =
            Pattern.compile("^\\s{4}(get|post|put|patch|delete|options|head):\\s*$");
    private static final Pattern CREATE_CONTEXT_PATTERN = Pattern.compile("server\\.createContext\\(\"([^\"]+)\"");

    @Test
    void deveManterMatrizPathMetodoAlinhadaComApiServer() throws Exception {
        String openApi = Files.readString(OPENAPI_PATH);
        String apiServer = Files.readString(API_SERVER_PATH);
        Map<String, Set<String>> pathMethodsOpenApi = parsePathMethods(openApi);
        Map<String, Set<String>> expected = expectedApiPathMethods();
        Set<String> apiServerContexts = parseApiServerContexts(apiServer);

        assertAll(
                () -> assertTrue(
                        apiServerContexts.contains("/api/pedidos"), "ApiServer deve registrar contexto /api/pedidos"),
                () -> assertTrue(
                        apiServerContexts.contains("/api/entregadores"),
                        "ApiServer deve registrar contexto /api/entregadores"),
                () -> assertTrue(
                        apiServerContexts.contains("/api/operacao"), "ApiServer deve registrar contexto /api/operacao"),
                () -> assertTrue(
                        apiServerContexts.contains("/api/operacao/rotas/prontas/iniciar"),
                        "ApiServer deve registrar contexto /api/operacao/rotas/prontas/iniciar"),
                () -> assertTrue(
                        expected.entrySet().stream().allMatch(entry -> {
                            Set<String> methods = pathMethodsOpenApi.get(entry.getKey());
                            return methods != null && methods.containsAll(entry.getValue());
                        }),
                        "OpenAPI deve manter matriz path/metodo alinhada com endpoints ativos"),
                () -> assertTrue(
                        apiServerContexts.stream()
                                .filter(path -> "/health".equals(path) || path.startsWith("/api/"))
                                .allMatch(context -> pathMethodsOpenApi.keySet().stream()
                                        .anyMatch(path -> path.equals(context) || path.startsWith(context + "/"))),
                        "Todo contexto exposto pelo ApiServer deve ter cobertura no contrato OpenAPI"),
                () -> assertTrue(
                        openApi.contains("Endpoint desativado para execucao manual"),
                        "OpenAPI deve documentar que /api/replanejamento/run esta desativado"));
    }

    @Test
    void deveManterCamposCriticosDeEventoOperacionalNoContrato() throws Exception {
        String openApi = Files.readString(OPENAPI_PATH);

        assertAll(
                () -> assertTrue(openApi.contains("externalEventId:"), "Contrato deve mapear externalEventId"),
                () -> assertTrue(openApi.contains("sourceEventId:"), "Contrato deve mapear sourceEventId"),
                () -> assertTrue(openApi.contains("manualRequestId:"), "Contrato deve mapear manualRequestId"),
                () -> assertTrue(openApi.contains("origemCanal:"), "Contrato deve mapear origemCanal"),
                () -> assertTrue(openApi.contains("Idempotency-Key"), "Contrato deve mapear header Idempotency-Key"),
                () -> assertTrue(
                        openApi.contains("X-Idempotency-Key"), "Contrato deve mapear header X-Idempotency-Key"),
                () -> assertTrue(openApi.contains("actorEntregadorId:"), "Contrato deve mapear actorEntregadorId"),
                () -> assertTrue(openApi.contains("eventType:"), "Contrato deve mapear eventType"),
                () -> assertTrue(openApi.contains("idempotente:"), "Contrato deve mapear flag idempotente"),
                () -> assertTrue(
                        extractEventoRequestEnum(openApi)
                                .equals(Set.of(
                                        "ROTA_INICIADA", "PEDIDO_ENTREGUE", "PEDIDO_FALHOU", "PEDIDO_CANCELADO")),
                        "Contrato deve manter enum de eventos operacionais aceitos"));
    }

    private static Map<String, Set<String>> expectedApiPathMethods() {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("/health", Set.of("GET"));
        expected.put("/api/atendimento/pedidos", Set.of("POST"));
        expected.put("/api/eventos", Set.of("POST"));
        expected.put("/api/replanejamento/run", Set.of("POST"));
        expected.put("/api/operacao/rotas/prontas/iniciar", Set.of("POST"));
        expected.put("/api/pedidos/{pedidoId}/timeline", Set.of("GET"));
        expected.put("/api/pedidos/{pedidoId}/execucao", Set.of("GET"));
        expected.put("/api/entregadores/{entregadorId}/roteiro", Set.of("GET"));
        expected.put("/api/operacao/painel", Set.of("GET"));
        expected.put("/api/operacao/eventos", Set.of("GET"));
        expected.put("/api/operacao/mapa", Set.of("GET"));
        expected.put("/api/operacao/replanejamento/jobs", Set.of("GET"));
        expected.put("/api/operacao/replanejamento/jobs/{jobId}", Set.of("GET"));
        return expected;
    }

    private static Map<String, Set<String>> parsePathMethods(String openApi) {
        int start = openApi.indexOf("\npaths:");
        int end = openApi.indexOf("\ncomponents:");
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("Nao foi possivel localizar bloco paths/components no openapi");
        }

        Map<String, Set<String>> pathMethods = new LinkedHashMap<>();
        String[] lines = openApi.substring(start, end).split("\\R");
        String currentPath = null;
        for (String line : lines) {
            Matcher pathMatcher = OPENAPI_PATH_PATTERN.matcher(line);
            if (pathMatcher.matches()) {
                currentPath = pathMatcher.group(1);
                pathMethods.putIfAbsent(currentPath, new LinkedHashSet<>());
                continue;
            }
            Matcher methodMatcher = OPENAPI_METHOD_PATTERN.matcher(line);
            if (currentPath != null && methodMatcher.matches()) {
                pathMethods.get(currentPath).add(methodMatcher.group(1).toUpperCase());
            }
        }
        return pathMethods;
    }

    private static Set<String> parseApiServerContexts(String apiServer) {
        Set<String> contexts = new LinkedHashSet<>();
        Matcher matcher = CREATE_CONTEXT_PATTERN.matcher(apiServer);
        while (matcher.find()) {
            contexts.add(matcher.group(1));
        }
        return contexts;
    }

    private static Set<String> extractEventoRequestEnum(String openApi) {
        int start = openApi.indexOf("\n    EventoRequest:");
        if (start < 0) {
            throw new IllegalStateException("Schema EventoRequest nao encontrado em openapi");
        }
        int end = openApi.indexOf("\n    EventoResponse:", start);
        if (end < 0) {
            throw new IllegalStateException("Schema EventoResponse nao encontrado apos EventoRequest");
        }
        String eventoRequestBlock = openApi.substring(start, end);
        Matcher matcher = Pattern.compile("enum:\\s*\\[(.+?)]").matcher(eventoRequestBlock);
        if (!matcher.find()) {
            throw new IllegalStateException("Enum de eventType nao encontrado no schema EventoRequest");
        }

        Set<String> values = new LinkedHashSet<>();
        for (String raw : matcher.group(1).split(",")) {
            values.add(raw.trim());
        }
        return values;
    }
}
