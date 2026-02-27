package com.aguaviva.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class DatabaseTest {

    private static ConnectionFactory factory;
    private static Database database;

    @BeforeAll
    static void setUp() {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
        database = new Database(factory);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void devePermitirQuerySimples() throws Exception {
        String versao = database.query("SELECT version()", rs -> {
            rs.next();
            return rs.getString(1);
        });

        assertTrue(versao.contains("PostgreSQL"));
    }

    @Test
    void devePermitirQueryComParametros() throws Exception {
        int valor = database.query(
                "SELECT ?::int + ?::int",
                stmt -> {
                    stmt.setInt(1, 20);
                    stmt.setInt(2, 22);
                },
                rs -> {
                    rs.next();
                    return rs.getInt(1);
                });

        assertEquals(42, valor);
    }

    @Test
    void deveResponderHealthDoBanco() {
        assertTrue(database.isHealthy());
    }
}
