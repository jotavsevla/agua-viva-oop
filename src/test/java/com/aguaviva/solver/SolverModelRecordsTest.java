package com.aguaviva.solver;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SolverModelRecordsTest {

    @Test
    void pedidoSolverDeveRejeitarGaloesMenorQueUm() {
        assertThrows(IllegalArgumentException.class, () -> new PedidoSolver(1, 0.0, 0.0, 0, "ASAP", null, null, 1));
    }

    @Test
    void solverResponseDeveNormalizarListasNulas() {
        SolverResponse response = new SolverResponse(null, null);

        assertNotNull(response.rotas());
        assertNotNull(response.naoAtendidos());
        assertTrue(response.rotas().isEmpty());
        assertTrue(response.naoAtendidos().isEmpty());
    }

    @Test
    void solverRequestFactoryOfDevePopularCamposBasicos() {
        SolverRequest request = SolverRequest.of(
                new Coordenada(-16.73, -43.87),
                5,
                "08:00",
                "18:00",
                List.of(1, 2),
                List.of(new PedidoSolver(10, -16.71, -43.85, 2, "ASAP", null, null, 2)));

        assertNull(request.jobId());
        assertNull(request.planVersion());
        assertEquals(5, request.capacidadeVeiculo());
        assertEquals(2, request.entregadores().size());
        assertEquals(1, request.pedidos().size());
    }

    @Test
    void solverRequestFactoryOfComJobDevePopularMetadados() {
        SolverRequest request = SolverRequest.of(
                "job-1",
                9L,
                new Coordenada(-16.73, -43.87),
                5,
                "08:00",
                "18:00",
                List.of(1),
                List.of(new PedidoSolver(10, -16.71, -43.85, 2, "ASAP", null, null, 2)));

        assertEquals("job-1", request.jobId());
        assertEquals(9L, request.planVersion());
    }
}
