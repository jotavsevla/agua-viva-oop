package com.aguaviva.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApiRuntimeConfigTest {

    @Test
    void deveCarregarConfigEstruturadaPadraoQuandoArquivoLocalExistir() {
        ApiRuntimeConfig config = ApiRuntimeConfig.fromSources(Map.of(), key -> null);

        assertEquals("local", config.appEnv());
        assertTrue(config.structuredConfig().rateLimits().containsKey("POST /api/atendimento/pedidos"));
        assertTrue(config.structuredConfig().sourcePath().contains("api-config.local.json"));
        assertTrue(config.featureFlag("startupLogs", false));
        assertTrue(!config.featureFlag("rateLimitEnabled", true));
    }

    @Test
    void deveFalharEmAmbienteEstritoSemValoresObrigatorios() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ApiRuntimeConfig.fromSources(Map.of("APP_ENV", "prod"), key -> null));

        assertTrue(ex.getMessage().contains("API_PORT"));
    }

    @Test
    void deveAceitarAmbienteEstritoComSegredoEmArquivo() throws Exception {
        Path secretFile = Files.createTempFile("pg-secret-", ".txt");
        Files.writeString(secretFile, "senha-segura\n");

        ApiRuntimeConfig config = ApiRuntimeConfig.fromSources(
                Map.of(
                        "APP_ENV", "prod",
                        "API_PORT", "8082",
                        "SOLVER_URL", "https://solver.internal",
                        "POSTGRES_HOST", "postgres.internal",
                        "POSTGRES_PORT", "5432",
                        "POSTGRES_DB", "agua_viva",
                        "POSTGRES_USER", "agua_viva_app",
                        "POSTGRES_PASSWORD_FILE", secretFile.toString()),
                key -> null);

        assertEquals("senha-segura", config.databaseConfig().password());
        Files.deleteIfExists(secretFile);
    }

    @Test
    void deveFalharQuandoRateLimitDoJsonForInvalido() throws Exception {
        Path configFile = Files.createTempFile("api-config-", ".json");
        Files.writeString(configFile, """
                {
                  "rateLimits": {
                    "POST /api/teste": {
                      "requests": 0,
                      "window": "1m"
                    }
                  }
                }
                """);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ApiRuntimeConfig.fromSources(Map.of("API_CONFIG_FILE", configFile.toString()), key -> null));

        assertTrue(ex.getMessage().contains("rateLimit"));
        Files.deleteIfExists(configFile);
    }

    @Test
    void deveFalharEmAmbienteEstritoQuandoArquivoDeConfigNaoExistir() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ApiRuntimeConfig.fromSources(
                        Map.of(
                                "APP_ENV", "prod",
                                "API_PORT", "8082",
                                "SOLVER_URL", "https://solver.internal",
                                "POSTGRES_HOST", "postgres.internal",
                                "POSTGRES_PORT", "5432",
                                "POSTGRES_DB", "agua_viva",
                                "POSTGRES_USER", "agua_viva_app",
                                "POSTGRES_PASSWORD", "segredo-forte",
                                "API_CONFIG_FILE", "/tmp/api-config-inexistente.json"),
                        key -> null));

        assertTrue(ex.getMessage().contains("configuracao nao encontrado"));
    }
}
