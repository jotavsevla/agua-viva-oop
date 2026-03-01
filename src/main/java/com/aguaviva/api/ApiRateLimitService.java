package com.aguaviva.api;

import com.aguaviva.config.ApiRuntimeConfig;
import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiRateLimitService {

    private static final Pattern WINDOW_PATTERN = Pattern.compile("^(\\d+)([smhd])$");
    private final ConnectionFactory connectionFactory;
    private final Map<String, ApiRuntimeConfig.RateLimitRule> rules;
    private final boolean enabled;

    public ApiRateLimitService(
            ConnectionFactory connectionFactory, Map<String, ApiRuntimeConfig.RateLimitRule> rules, boolean enabled) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory nao pode ser nulo");
        this.rules = Objects.requireNonNull(rules, "rules nao pode ser nulo");
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void ensureSchema() {
        if (!enabled) {
            return;
        }
        String sql = "CREATE TABLE IF NOT EXISTS api_rate_limit_counters ("
                + "rate_key VARCHAR(255) NOT NULL, "
                + "window_start TIMESTAMP NOT NULL, "
                + "request_count INTEGER NOT NULL, "
                + "updated_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (rate_key, window_start)"
                + ")";
        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao preparar schema de rate limit", e);
        }
    }

    public RateLimitDecision evaluate(String method, String path) {
        String normalizedMethod = normalizeRequired(method, "method").toUpperCase(Locale.ROOT);
        String normalizedPath = normalizeRequired(path, "path");
        String rateKey = normalizedMethod + " " + normalizedPath;
        if (!enabled) {
            return RateLimitDecision.disabled(rateKey);
        }

        ApiRuntimeConfig.RateLimitRule rule = rules.get(rateKey);
        if (rule == null) {
            return RateLimitDecision.noRule(rateKey);
        }

        int windowSeconds = parseWindowSeconds(rule.window());
        int currentCount = incrementAndReadCount(rateKey, windowSeconds);
        boolean allowed = currentCount <= rule.requests();
        return new RateLimitDecision(rateKey, allowed, currentCount, rule.requests(), rule.window(), true, true);
    }

    static int parseWindowSeconds(String window) {
        String normalized = normalizeRequired(window, "window").toLowerCase(Locale.ROOT);
        Matcher matcher = WINDOW_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Formato de janela invalido para rate limit: " + window);
        }

        int value;
        try {
            value = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor invalido de janela para rate limit: " + window, e);
        }
        if (value <= 0) {
            throw new IllegalArgumentException("Janela deve ser maior que zero: " + window);
        }

        return switch (matcher.group(2)) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            default -> throw new IllegalArgumentException("Unidade invalida para rate limit: " + window);
        };
    }

    private int incrementAndReadCount(String rateKey, int windowSeconds) {
        String upsert = "WITH win AS ("
                + "  SELECT to_timestamp(floor(extract(epoch FROM CURRENT_TIMESTAMP) / ?) * ?) AS window_start"
                + "), upsert AS ("
                + "  INSERT INTO api_rate_limit_counters(rate_key, window_start, request_count, updated_em) "
                + "  SELECT ?, window_start, 1, CURRENT_TIMESTAMP FROM win "
                + "  ON CONFLICT (rate_key, window_start) "
                + "  DO UPDATE SET request_count = api_rate_limit_counters.request_count + 1, updated_em = CURRENT_TIMESTAMP "
                + "  RETURNING request_count"
                + ") SELECT request_count FROM upsert";
        String cleanup =
                "DELETE FROM api_rate_limit_counters WHERE updated_em < (CURRENT_TIMESTAMP - INTERVAL '2 days')";
        try (Connection conn = connectionFactory.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(upsert)) {
                stmt.setInt(1, windowSeconds);
                stmt.setInt(2, windowSeconds);
                stmt.setString(3, rateKey);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Falha ao ler contador de rate limit");
                    }
                    int count = rs.getInt(1);
                    bestEffortCleanup(conn, cleanup);
                    return count;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao avaliar rate limit para " + rateKey, e);
        }
    }

    private void bestEffortCleanup(Connection conn, String cleanupSql) {
        try (PreparedStatement cleanupStmt = conn.prepareStatement(cleanupSql)) {
            cleanupStmt.executeUpdate();
        } catch (Exception ignored) {
            // Limpeza best-effort para conter crescimento da tabela.
        }
    }

    private static String normalizeRequired(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " nao pode ser nulo");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " nao pode ser vazio");
        }
        return trimmed;
    }

    public record RateLimitDecision(
            String key,
            boolean allowed,
            int currentCount,
            int limit,
            String window,
            boolean enabled,
            boolean configured) {
        static RateLimitDecision disabled(String key) {
            return new RateLimitDecision(key, true, 0, 0, "", false, false);
        }

        static RateLimitDecision noRule(String key) {
            return new RateLimitDecision(key, true, 0, 0, "", true, false);
        }
    }
}
