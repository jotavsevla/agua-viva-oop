package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aguaviva.repository.ConnectionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class EventoOperacionalIdempotenciaServiceTest {

    private EventoOperacionalIdempotenciaService newService() {
        ConnectionFactory factory = new ConnectionFactory("localhost", "1", "db_invalido", "x", "y");
        return new EventoOperacionalIdempotenciaService(factory);
    }

    @Test
    void processarDeveValidarCamposObrigatoriosAntesDoBanco() {
        EventoOperacionalIdempotenciaService service = newService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.processar(null, "hash", "PEDIDO_ENTREGUE", "PEDIDO", 1L, () -> null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.processar("evt", null, "PEDIDO_ENTREGUE", "PEDIDO", 1L, () -> null));
        assertThrows(
                IllegalArgumentException.class, () -> service.processar("evt", "hash", null, "PEDIDO", 1L, () -> null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.processar("evt", "hash", "PEDIDO_ENTREGUE", null, 1L, () -> null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.processar("evt", "hash", "PEDIDO_ENTREGUE", "PEDIDO", 0L, () -> null));
    }

    @Test
    void processarDeveValidarLimitesDeTamanho() {
        EventoOperacionalIdempotenciaService service = newService();

        String externalEventId129 = "x".repeat(129);
        String hash65 = "h".repeat(65);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.processar(externalEventId129, "hash", "PEDIDO_ENTREGUE", "PEDIDO", 1L, () -> null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.processar("evt", hash65, "PEDIDO_ENTREGUE", "PEDIDO", 1L, () -> null));
    }
}
