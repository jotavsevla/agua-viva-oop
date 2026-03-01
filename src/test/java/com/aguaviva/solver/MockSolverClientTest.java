package com.aguaviva.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class MockSolverClientTest {

    @Test
    void deveGerarRotasDeterministicasDistribuindoPedidosEntreEntregadores() {
        MockSolverClient client = new MockSolverClient();
        SolverRequest request = new SolverRequest(
                "job-123",
                1L,
                new Coordenada(-16.72, -43.86),
                5,
                "08:00",
                "18:00",
                List.of(10, 20),
                List.of(
                        new PedidoSolver(101, -16.70, -43.80, 1, "ASAP", null, null, 2),
                        new PedidoSolver(102, -16.71, -43.81, 1, "ASAP", null, null, 2),
                        new PedidoSolver(103, -16.72, -43.82, 1, "ASAP", null, null, 2)));

        SolverResponse response = client.solve(request);

        assertNotNull(response);
        assertEquals(2, response.getRotas().size());
        assertTrue(response.getNaoAtendidos().isEmpty());

        Set<Integer> pedidosAtendidos = response.getRotas().stream()
                .flatMap(rota -> rota.getParadas().stream())
                .map(Parada::getPedidoId)
                .collect(Collectors.toSet());
        assertEquals(Set.of(101, 102, 103), pedidosAtendidos);
    }

    @Test
    void deveRetornarVazioQuandoNaoHouverPedidosOuEntregadores() {
        MockSolverClient client = new MockSolverClient();
        SolverRequest semPedidos = new SolverRequest(
                "job-0", 1L, new Coordenada(-16.72, -43.86), 5, "08:00", "18:00", List.of(10), List.of());

        SolverResponse responseSemPedidos = client.solve(semPedidos);
        assertTrue(responseSemPedidos.getRotas().isEmpty());

        SolverRequest semEntregadores = new SolverRequest(
                "job-1",
                1L,
                new Coordenada(-16.72, -43.86),
                5,
                "08:00",
                "18:00",
                List.of(),
                List.of(new PedidoSolver(201, -16.70, -43.80, 1, "ASAP", null, null, 2)));
        SolverResponse responseSemEntregadores = client.solve(semEntregadores);
        assertTrue(responseSemEntregadores.getRotas().isEmpty());
    }
}
