package com.aguaviva.support;

import com.aguaviva.repository.ConnectionFactory;

public final class TestConnectionFactory {

    private TestConnectionFactory() {}

    public static ConnectionFactory newConnectionFactory() {
        return new ConnectionFactory(
                envOrDefault("POSTGRES_HOST", "localhost"),
                envOrDefault("POSTGRES_PORT", "5435"),
                envOrDefault("POSTGRES_DB", "agua_viva_oop_test"),
                envOrDefault("POSTGRES_USER", "postgres"),
                envOrDefault("POSTGRES_PASSWORD", "postgres"));
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
