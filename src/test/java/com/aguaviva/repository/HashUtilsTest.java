package com.aguaviva.repository;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class HashUtilsTest {

    @Test
    void deveGerarHashConhecidoParaHello() {
        String hash = HashUtils.sha256("hello");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
    }

    @Test
    void deveGerarHashConhecidoParaStringVazia() {
        String hash = HashUtils.sha256("");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void devePropagarNullPointerExceptionParaEntradaNula() {
        assertThrows(NullPointerException.class, () -> HashUtils.sha256(null));
    }

    @Test
    void deveProcessarUtf8DeFormaConsistente() {
        String hash = assertDoesNotThrow(() -> HashUtils.sha256("café"));
        assertEquals(64, hash.length());
        assertEquals(hash, HashUtils.sha256("café"));
    }

    @Test
    void saidaDeveTerSempreSessentaEQuatroCaracteres() {
        assertEquals(64, HashUtils.sha256("hello").length());
        assertEquals(64, HashUtils.sha256("").length());
        assertEquals(64, HashUtils.sha256("agua-viva").length());
    }

    @Test
    void saidaDeveSerHexadecimalMinusculo() {
        String hash = HashUtils.sha256("hello");
        assertTrue(hash.matches("^[0-9a-f]{64}$"));
    }
}
