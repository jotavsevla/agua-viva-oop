package com.aguaviva.config;

import com.aguaviva.repository.ConnectionFactory;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public record ApiRuntimeConfig(
        String appEnv,
        int apiPort,
        String solverUrl,
        ConnectionFactory.DatabaseConfig databaseConfig,
        StructuredConfig structuredConfig) {

    private static final Set<String> STRICT_ENVS =
            Set.of("prod", "production", "staging", "hml", "homolog", "homologacao");
    private static final String DEFAULT_APP_ENV = "local";
    private static final String DEFAULT_API_PORT = "8081";
    private static final String DEFAULT_SOLVER_URL = "http://localhost:8080";
    private static final String DEFAULT_STRUCTURED_CONFIG_PATH = "config/api-config.local.json";
    private static final Gson GSON = new Gson();

    public ApiRuntimeConfig {
        Objects.requireNonNull(appEnv, "appEnv nao pode ser nulo");
        Objects.requireNonNull(solverUrl, "solverUrl nao pode ser nulo");
        Objects.requireNonNull(databaseConfig, "databaseConfig nao pode ser nulo");
        Objects.requireNonNull(structuredConfig, "structuredConfig nao pode ser nulo");
    }

    public static ApiRuntimeConfig fromEnvironment() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        return fromSources(System.getenv(), dotenv::get);
    }

    static ApiRuntimeConfig fromSources(Map<String, String> runtimeEnv, Function<String, String> dotenvLookup) {
        Objects.requireNonNull(runtimeEnv, "runtimeEnv nao pode ser nulo");
        Objects.requireNonNull(dotenvLookup, "dotenvLookup nao pode ser nulo");

        String appEnv = resolveValue("APP_ENV", DEFAULT_APP_ENV, runtimeEnv, dotenvLookup)
                .toLowerCase(Locale.ROOT);
        int apiPort = parsePort(resolveValue("API_PORT", DEFAULT_API_PORT, runtimeEnv, dotenvLookup));
        String solverUrl = resolveValue("SOLVER_URL", DEFAULT_SOLVER_URL, runtimeEnv, dotenvLookup);
        validateSolverUrl(solverUrl);

        ConnectionFactory.DatabaseConfig dbConfig = ConnectionFactory.resolveConfig(runtimeEnv, dotenvLookup);
        StructuredConfig structuredConfig = resolveStructuredConfig(runtimeEnv, dotenvLookup);

        if (STRICT_ENVS.contains(appEnv)) {
            validateStrictConfig(appEnv, solverUrl, dbConfig, structuredConfig, runtimeEnv, dotenvLookup);
        }

        return new ApiRuntimeConfig(appEnv, apiPort, solverUrl, dbConfig, structuredConfig);
    }

    public boolean startupLogsEnabled() {
        return structuredConfig.featureFlags().getOrDefault("startupLogs", true);
    }

    public boolean featureFlag(String key, boolean defaultValue) {
        String normalized = normalizeOptional(key);
        if (normalized == null) {
            throw new IllegalArgumentException("key de feature flag nao pode ser nula/vazia");
        }
        return structuredConfig.featureFlags().getOrDefault(normalized, defaultValue);
    }

    private static void validateStrictConfig(
            String appEnv,
            String solverUrl,
            ConnectionFactory.DatabaseConfig dbConfig,
            StructuredConfig structuredConfig,
            Map<String, String> runtimeEnv,
            Function<String, String> dotenvLookup) {
        requireValue("API_PORT", runtimeEnv, dotenvLookup, appEnv);
        requireValue("SOLVER_URL", runtimeEnv, dotenvLookup, appEnv);
        requireValue("POSTGRES_HOST", runtimeEnv, dotenvLookup, appEnv);
        requireValue("POSTGRES_PORT", runtimeEnv, dotenvLookup, appEnv);
        requireValue("POSTGRES_DB", runtimeEnv, dotenvLookup, appEnv);
        requireValue("POSTGRES_USER", runtimeEnv, dotenvLookup, appEnv);
        String password = resolveOptional("POSTGRES_PASSWORD", runtimeEnv, dotenvLookup);
        String passwordFile = resolveOptional("POSTGRES_PASSWORD_FILE", runtimeEnv, dotenvLookup);
        if (password == null && passwordFile == null) {
            throw new IllegalStateException(
                    "Ambiente " + appEnv + " exige POSTGRES_PASSWORD ou POSTGRES_PASSWORD_FILE definido");
        }

        if ("postgres".equals(dbConfig.password())) {
            throw new IllegalStateException("Ambiente " + appEnv + " nao aceita senha padrao de banco");
        }
        if (isLoopbackHost(dbConfig.host())) {
            throw new IllegalStateException(
                    "Ambiente " + appEnv + " nao aceita POSTGRES_HOST local: " + dbConfig.host());
        }
        if (isLoopbackHost(URI.create(solverUrl).getHost())) {
            throw new IllegalStateException("Ambiente " + appEnv + " nao aceita SOLVER_URL local: " + solverUrl);
        }
        if ("none".equals(structuredConfig.sourcePath())) {
            throw new IllegalStateException("Ambiente " + appEnv + " exige API_CONFIG_FILE configurado");
        }
    }

    private static StructuredConfig resolveStructuredConfig(
            Map<String, String> runtimeEnv, Function<String, String> dotenvLookup) {
        String explicitPath = resolveOptional("API_CONFIG_FILE", runtimeEnv, dotenvLookup);
        Path path;
        if (explicitPath != null) {
            path = Path.of(explicitPath);
        } else {
            path = Path.of(DEFAULT_STRUCTURED_CONFIG_PATH);
            if (!Files.exists(path)) {
                return StructuredConfig.empty();
            }
        }

        if (!Files.exists(path)) {
            throw new IllegalStateException("Arquivo de configuracao nao encontrado: " + path);
        }

        try {
            String content = Files.readString(path);
            JsonObject root = GSON.fromJson(content, JsonObject.class);
            if (root == null) {
                throw new IllegalStateException("Arquivo de configuracao vazio ou invalido: " + path);
            }

            Map<String, RateLimitRule> rateLimits = parseRateLimits(root.get("rateLimits"), path);
            Map<String, Boolean> featureFlags = parseFeatureFlags(root.get("featureFlags"), path);
            return new StructuredConfig(
                    rateLimits, featureFlags, path.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler arquivo de configuracao: " + path, e);
        }
    }

    private static Map<String, RateLimitRule> parseRateLimits(JsonElement element, Path sourcePath) {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            throw new IllegalStateException("Campo rateLimits deve ser objeto em " + sourcePath);
        }

        Map<String, RateLimitRule> rules = new LinkedHashMap<>();
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String routeKey = normalizeOptional(entry.getKey());
            if (routeKey == null) {
                throw new IllegalStateException("Chave de rateLimit vazia em " + sourcePath);
            }
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalStateException("RateLimit invalido para " + routeKey + " em " + sourcePath);
            }
            JsonObject ruleObject = entry.getValue().getAsJsonObject();
            int requests = requirePositiveInt(ruleObject, "requests", routeKey, sourcePath);
            String window = requireText(ruleObject, "window", routeKey, sourcePath);
            rules.put(routeKey, new RateLimitRule(requests, window));
        }
        return Map.copyOf(rules);
    }

    private static Map<String, Boolean> parseFeatureFlags(JsonElement element, Path sourcePath) {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            throw new IllegalStateException("Campo featureFlags deve ser objeto em " + sourcePath);
        }

        Map<String, Boolean> flags = new LinkedHashMap<>();
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String flagName = normalizeOptional(entry.getKey());
            if (flagName == null) {
                throw new IllegalStateException("Nome de feature flag vazio em " + sourcePath);
            }
            JsonElement value = entry.getValue();
            if (value == null
                    || value.isJsonNull()
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalStateException("Feature flag invalida para " + flagName + " em " + sourcePath);
            }
            flags.put(flagName, value.getAsBoolean());
        }
        return Map.copyOf(flags);
    }

    private static int requirePositiveInt(JsonObject source, String field, String routeKey, Path sourcePath) {
        JsonElement value = source.get(field);
        if (value == null
                || value.isJsonNull()
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException(
                    "Campo " + field + " invalido em rateLimit " + routeKey + " (" + sourcePath + ")");
        }
        int parsed = value.getAsInt();
        if (parsed <= 0) {
            throw new IllegalStateException(
                    "Campo " + field + " deve ser > 0 em rateLimit " + routeKey + " (" + sourcePath + ")");
        }
        return parsed;
    }

    private static String requireText(JsonObject source, String field, String routeKey, Path sourcePath) {
        JsonElement value = source.get(field);
        if (value == null
                || value.isJsonNull()
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException(
                    "Campo " + field + " invalido em rateLimit " + routeKey + " (" + sourcePath + ")");
        }
        String text = normalizeOptional(value.getAsString());
        if (text == null) {
            throw new IllegalStateException(
                    "Campo " + field + " vazio em rateLimit " + routeKey + " (" + sourcePath + ")");
        }
        return text;
    }

    private static void validateSolverUrl(String solverUrl) {
        try {
            URI uri = new URI(solverUrl);
            String scheme = normalizeOptional(uri.getScheme());
            String host = normalizeOptional(uri.getHost());
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalStateException("SOLVER_URL deve usar http/https: " + solverUrl);
            }
            if (host == null) {
                throw new IllegalStateException("SOLVER_URL sem host valido: " + solverUrl);
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("SOLVER_URL invalida: " + solverUrl, e);
        }
    }

    private static int parsePort(String rawValue) {
        try {
            int port = Integer.parseInt(rawValue);
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("API_PORT fora do intervalo valido: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("API_PORT invalida: " + rawValue, e);
        }
    }

    private static void requireValue(
            String key, Map<String, String> runtimeEnv, Function<String, String> dotenvLookup, String appEnv) {
        if (resolveOptional(key, runtimeEnv, dotenvLookup) == null) {
            throw new IllegalStateException("Ambiente " + appEnv + " exige " + key + " definido");
        }
    }

    private static String resolveValue(
            String key, String fallback, Map<String, String> runtimeEnv, Function<String, String> dotenvLookup) {
        String value = resolveOptional(key, runtimeEnv, dotenvLookup);
        if (value != null) {
            return value;
        }
        return fallback;
    }

    private static String resolveOptional(
            String key, Map<String, String> runtimeEnv, Function<String, String> dotenvLookup) {
        String runtimeValue = normalizeOptional(runtimeEnv.get(key));
        if (runtimeValue != null) {
            return runtimeValue;
        }
        return normalizeOptional(dotenvLookup.apply(key));
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = normalizeOptional(host);
        if (normalized == null) {
            return false;
        }
        return "localhost".equalsIgnoreCase(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record StructuredConfig(
            Map<String, RateLimitRule> rateLimits, Map<String, Boolean> featureFlags, String sourcePath) {
        public StructuredConfig {
            Objects.requireNonNull(rateLimits, "rateLimits nao pode ser nulo");
            Objects.requireNonNull(featureFlags, "featureFlags nao pode ser nulo");
        }

        static StructuredConfig empty() {
            return new StructuredConfig(Map.of(), Map.of(), "none");
        }
    }

    public record RateLimitRule(int requests, String window) {
        public RateLimitRule {
            if (requests <= 0) {
                throw new IllegalArgumentException("requests deve ser maior que zero");
            }
            if (window == null || window.isBlank()) {
                throw new IllegalArgumentException("window obrigatorio");
            }
        }
    }
}
