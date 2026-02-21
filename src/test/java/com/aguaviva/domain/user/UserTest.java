package com.aguaviva.domain.user;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UserTest {

    // ========================================================================
    // Helpers
    // ========================================================================

    private Password senhaValida() {
        return Password.fromPlainText("senha123");
    }

    private User criarComPapel(UserPapel papel) {
        return new User("Teste", "teste@email.com", senhaValida(), papel);
    }

    // ========================================================================
    // Criacao de usuario valido
    // ========================================================================

    @Test
    void deveCriarUsuarioValidoComDadosObrigatorios() {
        User user = new User("Joao Silva", "joao@email.com", senhaValida(), UserPapel.ADMIN);

        assertNotNull(user);
        assertEquals("Joao Silva", user.getNome());
        assertEquals("joao@email.com", user.getEmail());
        assertEquals(UserPapel.ADMIN, user.getPapel());
        assertTrue(user.isAtivo());
        assertEquals(0, user.getId());
        assertNull(user.getTelefone());
    }

    @Test
    void deveCriarUsuarioValidoComTodosOsCampos() {
        User user =
                new User(1, "Maria", "maria@email.com", senhaValida(), UserPapel.ATENDENTE, "(11) 99999-0000", true);

        assertEquals(1, user.getId());
        assertEquals("Maria", user.getNome());
        assertEquals("maria@email.com", user.getEmail());
        assertEquals(UserPapel.ATENDENTE, user.getPapel());
        assertEquals("(11) 99999-0000", user.getTelefone());
        assertTrue(user.isAtivo());
    }

    @Test
    void deveCriarUsuarioInativoQuandoCampoAtivoForFalse() {
        User user =
                new User(1, "Maria", "maria@email.com", senhaValida(), UserPapel.ATENDENTE, "(11) 99999-0000", false);
        assertFalse(user.isAtivo());
    }

    // ========================================================================
    // Validacao de invariantes (construtor rejeita dados invalidos)
    // ========================================================================

    @Test
    void deveRejeitarNomeNulo() {
        assertThrows(IllegalArgumentException.class, () -> new User(null, "a@b.com", senhaValida(), UserPapel.ADMIN));
    }

    @Test
    void deveRejeitarNomeVazio() {
        assertThrows(IllegalArgumentException.class, () -> new User("", "a@b.com", senhaValida(), UserPapel.ADMIN));
    }

    @Test
    void deveRejeitarNomeComEspacosEmBranco() {
        assertThrows(IllegalArgumentException.class, () -> new User("   ", "a@b.com", senhaValida(), UserPapel.ADMIN));
    }

    @Test
    void deveRejeitarEmailNulo() {
        assertThrows(IllegalArgumentException.class, () -> new User("Joao", null, senhaValida(), UserPapel.ADMIN));
    }

    @Test
    void deveRejeitarEmailVazio() {
        assertThrows(IllegalArgumentException.class, () -> new User("Joao", "", senhaValida(), UserPapel.ADMIN));
    }

    @Test
    void deveRejeitarEmailSemArroba() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new User("Joao", "joaoemail.com", senhaValida(), UserPapel.ADMIN));
    }

    @Test
    void deveRejeitarPasswordNulo() {
        assertThrows(NullPointerException.class, () -> new User("Joao", "joao@email.com", null, UserPapel.ADMIN));
    }

    @Test
    void deveRejeitarPapelNulo() {
        assertThrows(NullPointerException.class, () -> new User("Joao", "joao@email.com", senhaValida(), null));
    }

    // ========================================================================
    // Hierarquia de papeis (podeGerenciar)
    // ========================================================================

    @Test
    void supervisorPodeGerenciarAdmin() {
        User supervisor = criarComPapel(UserPapel.SUPERVISOR);
        User admin = criarComPapel(UserPapel.ADMIN);
        assertTrue(supervisor.podeGerenciar(admin));
    }

    @Test
    void supervisorPodeGerenciarAtendente() {
        User supervisor = criarComPapel(UserPapel.SUPERVISOR);
        User atendente = criarComPapel(UserPapel.ATENDENTE);
        assertTrue(supervisor.podeGerenciar(atendente));
    }

    @Test
    void supervisorPodeGerenciarEntregador() {
        User supervisor = criarComPapel(UserPapel.SUPERVISOR);
        User entregador = criarComPapel(UserPapel.ENTREGADOR);
        assertTrue(supervisor.podeGerenciar(entregador));
    }

    @Test
    void adminPodeGerenciarAtendente() {
        User admin = criarComPapel(UserPapel.ADMIN);
        User atendente = criarComPapel(UserPapel.ATENDENTE);
        assertTrue(admin.podeGerenciar(atendente));
    }

    @Test
    void adminPodeGerenciarEntregador() {
        User admin = criarComPapel(UserPapel.ADMIN);
        User entregador = criarComPapel(UserPapel.ENTREGADOR);
        assertTrue(admin.podeGerenciar(entregador));
    }

    @Test
    void adminNaoPodeGerenciarSupervisor() {
        User admin = criarComPapel(UserPapel.ADMIN);
        User supervisor = criarComPapel(UserPapel.SUPERVISOR);
        assertFalse(admin.podeGerenciar(supervisor));
    }

    @Test
    void adminNaoPodeGerenciarOutroAdmin() {
        User admin1 = criarComPapel(UserPapel.ADMIN);
        User admin2 = criarComPapel(UserPapel.ADMIN);
        assertFalse(admin1.podeGerenciar(admin2));
    }

    @Test
    void atendentePodeGerenciarEntregador() {
        User atendente = criarComPapel(UserPapel.ATENDENTE);
        User entregador = criarComPapel(UserPapel.ENTREGADOR);
        assertTrue(atendente.podeGerenciar(entregador));
    }

    @Test
    void atendenteNaoPodeGerenciarAdmin() {
        User atendente = criarComPapel(UserPapel.ATENDENTE);
        User admin = criarComPapel(UserPapel.ADMIN);
        assertFalse(atendente.podeGerenciar(admin));
    }

    @Test
    void entregadorNaoPodeGerenciarNinguem() {
        User entregador = criarComPapel(UserPapel.ENTREGADOR);
        User atendente = criarComPapel(UserPapel.ATENDENTE);
        assertFalse(entregador.podeGerenciar(atendente));
    }

    // ========================================================================
    // Composicao com Password
    // ========================================================================

    @Test
    void deveVerificarSenhaCorreta() {
        User user = new User("Joao", "joao@email.com", Password.fromPlainText("senha123"), UserPapel.ADMIN);
        assertTrue(user.verificarSenha("senha123"));
    }

    @Test
    void deveRejeitarSenhaIncorreta() {
        User user = new User("Joao", "joao@email.com", Password.fromPlainText("senha123"), UserPapel.ADMIN);
        assertFalse(user.verificarSenha("senhaErrada"));
    }

    @Test
    void deveExporSenhaHashParaPersistencia() {
        Password password = senhaValida();
        User user = new User("Joao", "joao@email.com", password, UserPapel.ADMIN);

        assertEquals(password.toHash(), user.toSenhaHash());
        assertFalse(user.toSenhaHash().isBlank());
    }

    // ========================================================================
    // Identidade (equals / hashCode / toString)
    // ========================================================================

    @Test
    void usuariosComMesmoIdDevemSerIguais() {
        User u1 = new User(1, "Joao", "joao@email.com", senhaValida(), UserPapel.ADMIN, null, true);
        User u2 = new User(1, "Maria", "maria@email.com", senhaValida(), UserPapel.ATENDENTE, null, false);

        assertEquals(u1, u2);
        assertEquals(u1.hashCode(), u2.hashCode());
    }

    @Test
    void usuariosComIdsDiferentesNaoDevemSerIguais() {
        User u1 = new User(1, "Joao", "joao@email.com", senhaValida(), UserPapel.ADMIN, null, true);
        User u2 = new User(2, "Joao", "joao@email.com", senhaValida(), UserPapel.ADMIN, null, true);

        assertNotEquals(u1, u2);
        assertNotEquals(u1.hashCode(), u2.hashCode());
    }

    @Test
    void usuariosSemIdNaoDevemSerIguaisEntreSi() {
        User u1 = new User("Joao", "joao@email.com", senhaValida(), UserPapel.ADMIN);
        User u2 = new User("Joao", "joao@email.com", senhaValida(), UserPapel.ADMIN);

        assertNotEquals(u1, u2);
    }

    @Test
    void usuarioDeveSerIgualASiMesmo() {
        User user = new User(1, "Joao", "joao@email.com", senhaValida(), UserPapel.ADMIN, null, true);
        assertEquals(user, user);
    }

    @Test
    void usuarioNaoDeveSerIgualANuloOuOutroTipo() {
        User user = new User(1, "Joao", "joao@email.com", senhaValida(), UserPapel.ADMIN, null, true);
        assertNotEquals(user, null);
        assertNotEquals(user, "nao-user");
    }

    @Test
    void toStringNaoDeveExporSenha() {
        User user = new User("Joao", "joao@email.com", Password.fromPlainText("senha123"), UserPapel.ADMIN);
        String str = user.toString();

        assertFalse(str.contains("senha"));
        assertFalse(str.contains("$2a$"));
        assertTrue(str.contains("Joao"));
        assertTrue(str.contains("ADMIN"));
    }

    // ========================================================================
    // Normalizacao de email
    // ========================================================================

    @Test
    void deveNormalizarEmailParaMinusculo() {
        User user = new User("Joao", "Joao@Email.COM", senhaValida(), UserPapel.ADMIN);
        assertEquals("joao@email.com", user.getEmail());
    }
}
