package com.aguaviva.support;

import static org.junit.jupiter.api.Assertions.*;

import com.aguaviva.solver.Coordenada;
import com.aguaviva.solver.PedidoSolver;
import com.aguaviva.solver.SolverClient;
import com.aguaviva.solver.SolverRequest;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SolverStubServerTest {

    @Test
    void stubServerDeveResponderSolveECapturarCorpo() throws Exception {
        try (SolverStubServer stub = new SolverStubServer()) {
            SolverClient client = new SolverClient(stub.baseUrl());

            SolverRequest request = SolverRequest.of(
                    new Coordenada(-16.73, -43.87),
                    5,
                    "08:00",
                    "18:00",
                    List.of(1),
                    List.of(new PedidoSolver(1, -16.71, -43.85, 1, "ASAP", null, null, 2)));

            var response = client.solve(request);
            assertNotNull(response);
            assertTrue(response.rotas().isEmpty());
            assertTrue(stub.lastSolveRequestBody().contains("\"pedidos\""));
        }
    }
}
