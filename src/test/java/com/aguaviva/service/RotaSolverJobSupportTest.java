package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.aguaviva.repository.ConnectionFactory;
import com.google.gson.Gson;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class RotaSolverJobSupportTest {

    @Test
    void finalizarSolverJobNaoDevePropagarFalhaDeBanco() {
        ConnectionFactory factory = new ConnectionFactory("localhost", "1", "db_inexistente", "x", "y");
        try {
            assertDoesNotThrow(
                    () -> RotaSolverJobSupport.finalizarSolverJob(factory, new Gson(), "job-1", "FALHOU", "erro", null));
        } finally {
            factory.close();
        }
    }
}
