package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReplanejamentoPolicyMatrixTest {

    @Test
    void policyForEventDeveRetornarNoneParaNuloOuVazio() {
        assertEquals(ReplanejamentoPolicyMatrix.NONE, ReplanejamentoPolicyMatrix.policyForEvent(null));
        assertEquals(ReplanejamentoPolicyMatrix.NONE, ReplanejamentoPolicyMatrix.policyForEvent(" "));
    }

    @Test
    void policyForEventDeveRetornarPrimarioParaPedidoCriado() {
        var policy = ReplanejamentoPolicyMatrix.policyForEvent(DispatchEventTypes.PEDIDO_CRIADO);

        assertEquals(ReplanejamentoTriggerKind.PRIMARIO, policy.triggerKind());
        assertEquals(CapacidadePolicy.CHEIA, policy.capacidadePolicy());
        assertTrue(policy.replaneja());
    }

    @Test
    void consolidateDevePriorizarSecundarioSobrePrimario() {
        var policy = ReplanejamentoPolicyMatrix.consolidate(
                List.of(DispatchEventTypes.PEDIDO_CRIADO, DispatchEventTypes.PEDIDO_CANCELADO));

        assertEquals(ReplanejamentoTriggerKind.SECUNDARIO, policy.triggerKind());
        assertEquals(CapacidadePolicy.REMANESCENTE, policy.capacidadePolicy());
    }

    @Test
    void policyForTriggerDeveLancarParaNulo() {
        assertThrows(NullPointerException.class, () -> ReplanejamentoPolicyMatrix.policyForTrigger(null));
    }
}
