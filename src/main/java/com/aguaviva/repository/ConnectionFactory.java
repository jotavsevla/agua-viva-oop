package com.aguaviva.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class ConnectionFactory {

    private final HikariDataSource dataSource;

    public ConnectionFactory() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        DatabaseConfig config = resolveConfig(System.getenv(), dotenv::get);
        this.dataSource = criarDataSource(config.host(), config.port(), config.db(), config.user(), config.password());
    }

    public ConnectionFactory(DatabaseConfig config) {
        Objects.requireNonNull(config, "config nao pode ser nulo");
        this.dataSource = criarDataSource(config.host(), config.port(), config.db(), config.user(), config.password());
    }

    public ConnectionFactory(String host, String port, String db, String user, String password) {
        this.dataSource = criarDataSource(host, port, db, user, password);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public static DatabaseConfig resolveConfig(Map<String, String> runtimeEnv, Function<String, String> dotenvLookup) {
        Objects.requireNonNull(runtimeEnv, "runtimeEnv nao pode ser nulo");
        Objects.requireNonNull(dotenvLookup, "dotenvLookup nao pode ser nulo");

        String host = resolveValue("POSTGRES_HOST", "localhost", runtimeEnv, dotenvLookup);
        String port = resolveValue("POSTGRES_PORT", "5434", runtimeEnv, dotenvLookup);
        String db = resolveValue("POSTGRES_DB", "agua_viva_oop_dev", runtimeEnv, dotenvLookup);
        String user = resolveValue("POSTGRES_USER", "postgres", runtimeEnv, dotenvLookup);
        String password =
                resolveSecretValue("POSTGRES_PASSWORD", "POSTGRES_PASSWORD_FILE", "postgres", runtimeEnv, dotenvLookup);
        return new DatabaseConfig(host, port, db, user, password);
    }

    private static String resolveValue(
            String key, String fallback, Map<String, String> runtimeEnv, Function<String, String> dotenvLookup) {
        String runtimeValue = normalizeOptional(runtimeEnv.get(key));
        if (runtimeValue != null) {
            return runtimeValue;
        }
        String dotenvValue = normalizeOptional(dotenvLookup.apply(key));
        if (dotenvValue != null) {
            return dotenvValue;
        }
        return fallback;
    }

    private static String resolveSecretValue(
            String key,
            String fileKey,
            String fallback,
            Map<String, String> runtimeEnv,
            Function<String, String> dotenvLookup) {
        String filePath = resolveOptional(fileKey, runtimeEnv, dotenvLookup);
        if (filePath != null) {
            try {
                String fileValue = normalizeOptional(Files.readString(Path.of(filePath)));
                if (fileValue == null) {
                    throw new IllegalArgumentException(fileKey + " aponta para arquivo vazio: " + filePath);
                }
                return fileValue;
            } catch (IOException e) {
                throw new IllegalStateException("Falha ao ler segredo em " + fileKey + ": " + filePath, e);
            }
        }

        String inlineValue = resolveOptional(key, runtimeEnv, dotenvLookup);
        if (inlineValue != null) {
            return inlineValue;
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static record DatabaseConfig(String host, String port, String db, String user, String password) {}

    private static HikariDataSource criarDataSource(String host, String port, String db, String user, String password) {
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }
}
