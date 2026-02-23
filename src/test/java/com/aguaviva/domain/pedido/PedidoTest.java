package com.aguaviva.domain.pedido;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PedidoTest {

    // ========================================================================
    // Criacao valida
    // ========================================================================

    @Test
    void deveCriarPedidoAsapValidoComStatusPendente() {
        Pedido pedido = new Pedido(1, 2, JanelaTipo.ASAP, null, null, 10);

        assertNotNull(pedido);
        assertEquals(0, pedido.getId());
        assertEquals(1, pedido.getClienteId());
        assertEquals(2, pedido.getQuantidadeGaloes());
        assertEquals(JanelaTipo.ASAP, pedido.getJanelaTipo());
        assertNull(pedido.getJanelaInicio());
        assertNull(pedido.getJanelaFim());
        assertEquals(PedidoStatus.PENDENTE, pedido.getStatus());
        assertEquals(10, pedido.getCriadoPorUserId());
    }

    @Test
    void deveCriarPedidoHardValido() {
        Pedido pedido = new Pedido(
                5, 1, 3, JanelaTipo.HARD, LocalTime.of(9, 0), LocalTime.of(11, 0), PedidoStatus.CONFIRMADO, 20);

        assertEquals(5, pedido.getId());
        assertEquals(1, pedido.getClienteId());
        assertEquals(3, pedido.getQuantidadeGaloes());
        assertEquals(JanelaTipo.HARD, pedido.getJanelaTipo());
        assertEquals(LocalTime.of(9, 0), pedido.getJanelaInicio());
        assertEquals(LocalTime.of(11, 0), pedido.getJanelaFim());
        assertEquals(PedidoStatus.CONFIRMADO, pedido.getStatus());
        assertEquals(20, pedido.getCriadoPorUserId());
    }

    // ========================================================================
    // Invariantes
    // ========================================================================

    @Test
    void deveRejeitarIdNegativo() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Pedido(-1, 1, 1, JanelaTipo.ASAP, null, null, PedidoStatus.PENDENTE, 10));
    }

    @Test
    void deveRejeitarClienteIdZero() {
        assertThrows(IllegalArgumentException.class, () -> new Pedido(0, 1, JanelaTipo.ASAP, null, null, 10));
    }

    @Test
    void deveRejeitarQuantidadeZero() {
        assertThrows(IllegalArgumentException.class, () -> new Pedido(1, 0, JanelaTipo.ASAP, null, null, 10));
    }

    @Test
    void deveRejeitarJanelaTipoNulo() {
        assertThrows(NullPointerException.class, () -> new Pedido(1, 1, null, null, null, 10));
    }

    @Test
    void deveRejeitarStatusNulo() {
        assertThrows(NullPointerException.class, () -> new Pedido(1, 1, 1, JanelaTipo.ASAP, null, null, null, 10));
    }

    @Test
    void deveRejeitarCriadoPorUserIdZero() {
        assertThrows(IllegalArgumentException.class, () -> new Pedido(1, 1, JanelaTipo.ASAP, null, null, 0));
    }

    @Test
    void deveRejeitarPedidoHardSemJanelaInicio() {
        assertThrows(
                IllegalArgumentException.class, () -> new Pedido(1, 2, JanelaTipo.HARD, null, LocalTime.of(10, 0), 10));
    }

    @Test
    void deveRejeitarPedidoHardSemJanelaFim() {
        assertThrows(
                IllegalArgumentException.class, () -> new Pedido(1, 2, JanelaTipo.HARD, LocalTime.of(9, 0), null, 10));
    }

    @Test
    void deveRejeitarPedidoHardComJanelaInvalida() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Pedido(1, 2, JanelaTipo.HARD, LocalTime.of(10, 0), LocalTime.of(9, 59), 10));
    }

    @Test
    void deveRejeitarPedidoAsapComJanelaInicio() {
        assertThrows(
                IllegalArgumentException.class, () -> new Pedido(1, 2, JanelaTipo.ASAP, LocalTime.of(10, 0), null, 10));
    }

    @Test
    void deveRejeitarPedidoAsapComJanelaFim() {
        assertThrows(
                IllegalArgumentException.class, () -> new Pedido(1, 2, JanelaTipo.ASAP, null, LocalTime.of(11, 0), 10));
    }

    // ========================================================================
    // Identidade
    // ========================================================================

    @Test
    void pedidosComMesmoIdDevemSerIguais() {
        Pedido p1 = new Pedido(1, 1, 2, JanelaTipo.ASAP, null, null, PedidoStatus.PENDENTE, 10);
        Pedido p2 = new Pedido(
                1, 99, 5, JanelaTipo.HARD, LocalTime.of(8, 0), LocalTime.of(9, 0), PedidoStatus.CONFIRMADO, 20);

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void pedidosComIdsDiferentesNaoDevemSerIguais() {
        Pedido p1 = new Pedido(1, 1, 2, JanelaTipo.ASAP, null, null, PedidoStatus.PENDENTE, 10);
        Pedido p2 = new Pedido(2, 1, 2, JanelaTipo.ASAP, null, null, PedidoStatus.PENDENTE, 10);

        assertNotEquals(p1, p2);
        assertNotEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void pedidosSemIdNaoDevemSerIguaisEntreSi() {
        Pedido p1 = new Pedido(1, 1, JanelaTipo.ASAP, null, null, 10);
        Pedido p2 = new Pedido(1, 1, JanelaTipo.ASAP, null, null, 10);

        assertNotEquals(p1, p2);
    }

    @Test
    void pedidoDeveSerIgualASiMesmo() {
        Pedido pedido = new Pedido(1, 1, 2, JanelaTipo.ASAP, null, null, PedidoStatus.PENDENTE, 10);
        assertEquals(pedido, pedido);
    }

    @Test
    void pedidoNaoDeveSerIgualANuloOuOutroTipo() {
        Pedido pedido = new Pedido(1, 1, 2, JanelaTipo.ASAP, null, null, PedidoStatus.PENDENTE, 10);
        assertNotEquals(pedido, null);
        assertNotEquals(pedido, "nao-pedido");
    }

    @Test
    void toStringNaoDeveExporCriadoPorUserId() {
        Pedido pedido = new Pedido(1, 2, JanelaTipo.ASAP, null, null, 77);
        String str = pedido.toString();

        assertFalse(str.contains("77"));
        assertTrue(str.contains("ASAP"));
        assertTrue(str.contains("PENDENTE"));
    }
}
