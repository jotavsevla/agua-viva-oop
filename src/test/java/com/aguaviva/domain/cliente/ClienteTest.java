package com.aguaviva.domain.cliente;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ClienteTest {

    // ========================================================================
    // Helpers
    // ========================================================================

    private BigDecimal latValida() {
        return new BigDecimal("-16.73444096");
    }

    private BigDecimal lonValida() {
        return new BigDecimal("-43.87721119");
    }

    // ========================================================================
    // Criacao valida
    // ========================================================================

    @Test
    void deveCriarClienteValidoComCamposObrigatorios() {
        Cliente cliente = new Cliente("Joao Silva", "(38) 99999-0001", ClienteTipo.PF, "Rua A, 100");

        assertNotNull(cliente);
        assertEquals(0, cliente.getId());
        assertEquals("Joao Silva", cliente.getNome());
        assertEquals("(38) 99999-0001", cliente.getTelefone());
        assertEquals(ClienteTipo.PF, cliente.getTipo());
        assertEquals("Rua A, 100", cliente.getEndereco());
        assertNull(cliente.getLatitude());
        assertNull(cliente.getLongitude());
        assertNull(cliente.getNotas());
    }

    @Test
    void deveCriarClienteValidoComTodosOsCampos() {
        Cliente cliente = new Cliente(
                10,
                "Empresa XYZ",
                "(38) 3333-1234",
                ClienteTipo.PJ,
                "Av. Central, 200",
                latValida(),
                lonValida(),
                "Portao lateral");

        assertEquals(10, cliente.getId());
        assertEquals("Empresa XYZ", cliente.getNome());
        assertEquals("(38) 3333-1234", cliente.getTelefone());
        assertEquals(ClienteTipo.PJ, cliente.getTipo());
        assertEquals("Av. Central, 200", cliente.getEndereco());
        assertEquals(0, latValida().compareTo(cliente.getLatitude()));
        assertEquals(0, lonValida().compareTo(cliente.getLongitude()));
        assertEquals("Portao lateral", cliente.getNotas());
    }

    // ========================================================================
    // Invariantes
    // ========================================================================

    @Test
    void deveRejeitarIdNegativo() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Cliente(-1, "Joao", "123", ClienteTipo.PF, "Rua A", null, null, null));
    }

    @Test
    void deveRejeitarNomeNulo() {
        assertThrows(IllegalArgumentException.class, () -> new Cliente(null, "123", ClienteTipo.PF, "Rua A"));
    }

    @Test
    void deveRejeitarTelefoneVazio() {
        assertThrows(IllegalArgumentException.class, () -> new Cliente("Joao", "", ClienteTipo.PF, "Rua A"));
    }

    @Test
    void deveRejeitarTipoNulo() {
        assertThrows(NullPointerException.class, () -> new Cliente("Joao", "123", null, "Rua A"));
    }

    @Test
    void deveRejeitarEnderecoComEspacosEmBranco() {
        assertThrows(IllegalArgumentException.class, () -> new Cliente("Joao", "123", ClienteTipo.PF, "   "));
    }

    @Test
    void deveRejeitarLatitudeSemLongitude() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Cliente("Joao", "123", ClienteTipo.PF, "Rua A", latValida(), null, null));
    }

    @Test
    void deveRejeitarLongitudeSemLatitude() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Cliente("Joao", "123", ClienteTipo.PF, "Rua A", null, lonValida(), null));
    }

    @Test
    void deveRejeitarLatitudeForaDoIntervalo() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Cliente("Joao", "123", ClienteTipo.PF, "Rua A", new BigDecimal("90.1"), lonValida(), null));
    }

    @Test
    void deveRejeitarLongitudeForaDoIntervalo() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Cliente("Joao", "123", ClienteTipo.PF, "Rua A", latValida(), new BigDecimal("180.1"), null));
    }

    @Test
    void deveAceitarCoordenadasNosLimitesDoIntervalo() {
        assertDoesNotThrow(() -> new Cliente(
                "Joao", "123", ClienteTipo.PF, "Rua A", new BigDecimal("-90"), new BigDecimal("-180"), null));
        assertDoesNotThrow(() ->
                new Cliente("Joao", "123", ClienteTipo.PF, "Rua A", new BigDecimal("90"), new BigDecimal("180"), null));
    }

    @Test
    void deveNormalizarCamposTextuais() {
        Cliente cliente = new Cliente(
                "  Joao  ", "  12345  ", ClienteTipo.PF, "  Rua A, 100  ", latValida(), lonValida(), "  Portao azul  ");

        assertEquals("Joao", cliente.getNome());
        assertEquals("12345", cliente.getTelefone());
        assertEquals("Rua A, 100", cliente.getEndereco());
        assertEquals("Portao azul", cliente.getNotas());
    }

    @Test
    void deveNormalizarNotasVaziasParaNull() {
        Cliente cliente = new Cliente("Joao", "123", ClienteTipo.PF, "Rua A", latValida(), lonValida(), "   ");
        assertNull(cliente.getNotas());
    }

    // ========================================================================
    // Identidade
    // ========================================================================

    @Test
    void clientesComMesmoIdDevemSerIguais() {
        Cliente c1 = new Cliente(1, "A", "111", ClienteTipo.PF, "Rua 1", null, null, null);
        Cliente c2 = new Cliente(1, "B", "222", ClienteTipo.PJ, "Rua 2", null, null, "nota");

        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void clientesComIdsDiferentesNaoDevemSerIguais() {
        Cliente c1 = new Cliente(1, "A", "111", ClienteTipo.PF, "Rua 1", null, null, null);
        Cliente c2 = new Cliente(2, "B", "222", ClienteTipo.PJ, "Rua 2", null, null, null);

        assertNotEquals(c1, c2);
        assertNotEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void clientesSemIdNaoDevemSerIguaisEntreSi() {
        Cliente c1 = new Cliente("A", "111", ClienteTipo.PF, "Rua 1");
        Cliente c2 = new Cliente("A", "111", ClienteTipo.PF, "Rua 1");

        assertNotEquals(c1, c2);
    }

    @Test
    void clienteDeveSerIgualASiMesmo() {
        Cliente cliente = new Cliente(1, "A", "111", ClienteTipo.PF, "Rua 1", null, null, null);
        assertEquals(cliente, cliente);
    }

    @Test
    void clienteNaoDeveSerIgualANuloOuOutroTipo() {
        Cliente cliente = new Cliente(1, "A", "111", ClienteTipo.PF, "Rua 1", null, null, null);
        assertNotEquals(cliente, null);
        assertNotEquals(cliente, "nao-cliente");
    }

    @Test
    void toStringNaoDeveExporNotas() {
        Cliente cliente = new Cliente(1, "Joao", "111", ClienteTipo.PF, "Rua 1", null, null, "Portao azul");
        String str = cliente.toString();

        assertFalse(str.contains("Portao azul"));
        assertTrue(str.contains("Joao"));
        assertTrue(str.contains("PF"));
    }
}
