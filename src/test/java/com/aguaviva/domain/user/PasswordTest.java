package com.aguaviva.domain.user;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PasswordTest {

    // ========================================================================
    // Validacao (politica de senha)
    // ========================================================================

    @Test
    void deveRejeitarSenhaNula() {
        assertThrows(IllegalArgumentException.class, () -> Password.validate(null));
    }

    @Test
    void deveRejeitarSenhaVazia() {
        assertThrows(IllegalArgumentException.class, () -> Password.validate(""));
    }

    @Test
    void deveRejeitarSenhaComEspacosEmBranco() {
        assertThrows(IllegalArgumentException.class, () -> Password.validate("        "));
    }

    @Test
    void deveRejeitarSenhaCurta() {
        assertThrows(IllegalArgumentException.class, () -> Password.validate("abc1234"));
    }

    @Test
    void deveAceitarSenhaValida() {
        assertDoesNotThrow(() -> Password.validate("senha123"));
    }

    // ========================================================================
    // Criacao via fromPlainText (hash + validacao)
    // ========================================================================

    @Test
    void deveCriarPasswordAPartirDePlainText() {
        Password password = Password.fromPlainText("senha123");
        assertNotNull(password);
    }

    @Test
    void fromPlainTextDeveRejeitarSenhaInvalida() {
        assertThrows(IllegalArgumentException.class, () -> Password.fromPlainText("curta"));
    }

    @Test
    void deveGerarHashDiferenteDaSenhaOriginal() {
        Password password = Password.fromPlainText("senha123");
        assertNotEquals("senha123", password.toHash());
    }

    @Test
    void deveGerarHashesDiferentesParaMesmaSenha() {
        Password p1 = Password.fromPlainText("senha123");
        Password p2 = Password.fromPlainText("senha123");
        assertNotEquals(p1.toHash(), p2.toHash(), "Salt aleatorio deve gerar hashes diferentes");
    }

    // ========================================================================
    // Comparacao (matches)
    // ========================================================================

    @Test
    void deveCompararCorretamenteComSenhaCorreta() {
        Password password = Password.fromPlainText("senha123");
        assertTrue(password.matches("senha123"));
    }

    @Test
    void deveRejeitarSenhaIncorreta() {
        Password password = Password.fromPlainText("senha123");
        assertFalse(password.matches("senhaErrada"));
    }

    @Test
    void deveRetornarFalseQuandoMatchesReceberNull() {
        Password password = Password.fromPlainText("senha123");
        assertFalse(password.matches(null));
    }

    // ========================================================================
    // Reconstrucao via fromHash (para o Repository)
    // ========================================================================

    @Test
    void deveCriarPasswordAPartirDeHash() {
        Password original = Password.fromPlainText("senha123");
        String hash = original.toHash();

        Password reconstruido = Password.fromHash(hash);
        assertTrue(reconstruido.matches("senha123"));
    }

    @Test
    void deveLancarExcecaoParaHashNulo() {
        assertThrows(IllegalArgumentException.class, () -> Password.fromHash(null));
    }

    @Test
    void deveLancarExcecaoParaHashVazio() {
        assertThrows(IllegalArgumentException.class, () -> Password.fromHash(""));
    }

    // ========================================================================
    // Persistencia (toHash)
    // ========================================================================

    @Test
    void deveExporHashParaPersistencia() {
        Password password = Password.fromPlainText("senha123");
        String hash = password.toHash();

        assertNotNull(hash);
        assertFalse(hash.isBlank());
    }

    // ========================================================================
    // Value Object: equals, hashCode, toString
    // ========================================================================

    @Test
    void passwordsComMesmoHashDevemSerIguais() {
        Password original = Password.fromPlainText("senha123");
        String hash = original.toHash();

        Password p1 = Password.fromHash(hash);
        Password p2 = Password.fromHash(hash);

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void passwordsComHashesDiferentesNaoDevemSerIguais() {
        Password p1 = Password.fromPlainText("senha123");
        Password p2 = Password.fromPlainText("senha123");

        assertNotEquals(p1, p2, "Hashes diferentes (salt) = objetos diferentes");
    }

    @Test
    void passwordsComHashesDiferentesDevemTerHashCodesDiferentes() {
        Password p1 = Password.fromHash("hash-a");
        Password p2 = Password.fromHash("hash-b");

        assertNotEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void passwordDeveSerIgualASiMesmo() {
        Password password = Password.fromPlainText("senha123");
        assertEquals(password, password);
    }

    @Test
    void passwordNaoDeveSerIgualANuloOuOutroTipo() {
        Password password = Password.fromPlainText("senha123");
        assertNotEquals(password, null);
        assertNotEquals(password, "nao-password");
    }

    @Test
    void toStringNaoDeveExporOHash() {
        Password password = Password.fromPlainText("senha123");
        String str = password.toString();

        assertFalse(str.contains(password.toHash()), "toString nunca deve expor o hash");
        assertTrue(str.contains("****"), "toString deve mascarar o conteudo");
    }
}
