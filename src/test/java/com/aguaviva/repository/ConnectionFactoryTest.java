package com.aguaviva.repository;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ConnectionFactoryTest {

    private static ConnectionFactory factory;

    @BeforeAll
    static void setUp() {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void deveConectarNoBancoDeTeste() throws Exception {
        try (Connection conn = factory.getConnection()) {
            assertNotNull(conn);
            assertTrue(conn.isValid(2));
        }
    }

    @Test
    void deveRetornarVersaoDoPostgreSQL() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT version()")) {

            assertTrue(rs.next());
            String version = rs.getString(1);
            assertNotNull(version);
            assertTrue(version.contains("PostgreSQL"));
        }
    }

    @Test
    void devePriorizarVariaveisDeAmbienteDeRuntimeNaResolucaoPadrao() {
        ConnectionFactory.DatabaseConfig config = ConnectionFactory.resolveConfig(
                Map.of(
                        "POSTGRES_HOST", "runtime-host",
                        "POSTGRES_PORT", "7777",
                        "POSTGRES_DB", "runtime_db",
                        "POSTGRES_USER", "runtime_user",
                        "POSTGRES_PASSWORD", "runtime_pwd"),
                key -> switch (key) {
                    case "POSTGRES_HOST" -> "dotenv-host";
                    case "POSTGRES_PORT" -> "5555";
                    case "POSTGRES_DB" -> "dotenv_db";
                    case "POSTGRES_USER" -> "dotenv_user";
                    case "POSTGRES_PASSWORD" -> "dotenv_pwd";
                    default -> null;
                });

        assertEquals("runtime-host", config.host());
        assertEquals("7777", config.port());
        assertEquals("runtime_db", config.db());
        assertEquals("runtime_user", config.user());
        assertEquals("runtime_pwd", config.password());
    }

    @Test
    void deveUsarDotEnvQuandoRuntimeNaoEstiverDefinido() {
        ConnectionFactory.DatabaseConfig config = ConnectionFactory.resolveConfig(
                Map.of(),
                key -> switch (key) {
                    case "POSTGRES_HOST" -> "dotenv-host";
                    case "POSTGRES_PORT" -> "5555";
                    case "POSTGRES_DB" -> "dotenv_db";
                    case "POSTGRES_USER" -> "dotenv_user";
                    case "POSTGRES_PASSWORD" -> "dotenv_pwd";
                    default -> null;
                });

        assertEquals("dotenv-host", config.host());
        assertEquals("5555", config.port());
        assertEquals("dotenv_db", config.db());
        assertEquals("dotenv_user", config.user());
        assertEquals("dotenv_pwd", config.password());
    }

    @Test
    void deveAplicarDefaultsQuandoRuntimeEDotEnvEstiveremAusentes() {
        ConnectionFactory.DatabaseConfig config = ConnectionFactory.resolveConfig(Map.of(), key -> null);

        assertEquals("localhost", config.host());
        assertEquals("5434", config.port());
        assertEquals("agua_viva_oop_dev", config.db());
        assertEquals("postgres", config.user());
        assertEquals("postgres", config.password());
    }
}
