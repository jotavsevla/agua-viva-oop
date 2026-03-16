package com.aguaviva.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApiServerRequestParsersTest {

    @Test
    void requireIntDeveAceitarValorPositivo() {
        assertEquals(10, ApiServerRequestParsers.requireInt(10, "pedidoId"));
    }

    @Test
    void requireIntDeveRejeitarNuloOuNaoPositivo() {
        assertThrows(IllegalArgumentException.class, () -> ApiServerRequestParsers.requireInt(null, "pedidoId"));
        assertThrows(IllegalArgumentException.class, () -> ApiServerRequestParsers.requireInt(0, "pedidoId"));
    }

    @Test
    void parsePedidoIdTimelineDeveExtrairId() {
        assertEquals(42, ApiServerRequestParsers.parsePedidoIdTimeline("/api/pedidos/42/timeline"));
    }

    @Test
    void parsePedidoIdExecucaoDeveExtrairId() {
        assertEquals(7, ApiServerRequestParsers.parsePedidoIdExecucao("/api/pedidos/7/execucao"));
    }

    @Test
    void parseEntregadorIdRoteiroDeveExtrairId() {
        assertEquals(9, ApiServerRequestParsers.parseEntregadorIdRoteiro("/api/entregadores/9/roteiro"));
    }

    @Test
    void parseLimiteQueryDeveLerParametroLimite() {
        assertEquals(25, ApiServerRequestParsers.parseLimiteQuery("foo=bar&limite=25"));
        assertNull(ApiServerRequestParsers.parseLimiteQuery("foo=bar"));
    }

    @Test
    void parseJobIdReplanejamentoDeveExtrairJobId() {
        assertEquals("job-123", ApiServerRequestParsers.parseJobIdReplanejamento("/api/operacao/replanejamento/jobs/job-123"));
    }

    @Test
    void normalizeOptionalTextEOrigemManualDevemFuncionar() {
        assertNull(ApiServerRequestParsers.normalizeOptionalText("   "));
        assertEquals("abc", ApiServerRequestParsers.normalizeOptionalText(" abc "));
        assertTrue(ApiServerRequestParsers.isOrigemCanalManual(" manual "));
        assertFalse(ApiServerRequestParsers.isOrigemCanalManual("WHATSAPP"));
    }
}
