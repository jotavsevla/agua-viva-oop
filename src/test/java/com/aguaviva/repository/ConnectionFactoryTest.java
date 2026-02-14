package com.aguaviva.repository;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
}
