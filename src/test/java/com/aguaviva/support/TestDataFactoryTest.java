package com.aguaviva.support;

import static org.junit.jupiter.api.Assertions.*;

import com.aguaviva.domain.pedido.JanelaTipo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TestDataFactoryTest {

    @Test
    void deveCriarObjetosBasicos() {
        var cliente = TestDataFactory.aCliente();
        var user = TestDataFactory.aUser();
        var pedido = TestDataFactory.aPedido(1, 1);
        var pedidoHard = TestDataFactory.aPedidoHard(1, 1);

        assertNotNull(cliente.getNome());
        assertNotNull(user.getEmail());
        assertEquals(JanelaTipo.ASAP, pedido.getJanelaTipo());
        assertEquals(JanelaTipo.HARD, pedidoHard.getJanelaTipo());
    }
}
