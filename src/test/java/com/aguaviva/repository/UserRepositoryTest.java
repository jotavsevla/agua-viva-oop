package com.aguaviva.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.support.TestConnectionFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class UserRepositoryTest {

    private static ConnectionFactory factory;
    private static UserRepository repository;

    @BeforeAll
    static void setUp() {
        factory = TestConnectionFactory.newConnectionFactory();
        repository = new UserRepository(factory);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparTabela() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM users");
            stmt.execute("ALTER SEQUENCE users_id_seq RESTART WITH 1");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private User criarUsuario(String nome, String email, UserPapel papel) {
        return new User(nome, email, Password.fromPlainText("senha123"), papel);
    }

    private User criarAdmin() {
        return criarUsuario("Admin Teste", "admin@teste.com", UserPapel.ADMIN);
    }

    private User criarEntregador() {
        return criarUsuario("Entregador Teste", "entregador@teste.com", UserPapel.ENTREGADOR);
    }

    // ========================================================================
    // save (inserir usuario)
    // ========================================================================

    @Test
    void deveSalvarUsuarioERetornarComIdGerado() throws Exception {
        User novo = criarAdmin();
        User salvo = repository.save(novo);

        assertTrue(salvo.getId() > 0);
        assertEquals("Admin Teste", salvo.getNome());
        assertEquals("admin@teste.com", salvo.getEmail());
        assertEquals(UserPapel.ADMIN, salvo.getPapel());
        assertTrue(salvo.isAtivo());
    }

    @Test
    void deveSalvarUsuarioERecuperarPorId() throws Exception {
        User salvo = repository.save(criarAdmin());

        Optional<User> encontrado = repository.findById(salvo.getId());

        assertTrue(encontrado.isPresent());
        User user = encontrado.get();
        assertEquals(salvo.getId(), user.getId());
        assertEquals("Admin Teste", user.getNome());
        assertEquals("admin@teste.com", user.getEmail());
        assertEquals(UserPapel.ADMIN, user.getPapel());
        assertTrue(user.isAtivo());
    }

    @Test
    void deveSalvarUsuarioComSenhaHashEConseguirVerificar() throws Exception {
        User salvo = repository.save(criarAdmin());

        Optional<User> encontrado = repository.findById(salvo.getId());
        assertTrue(encontrado.isPresent());
        assertTrue(encontrado.get().verificarSenha("senha123"));
    }

    @Test
    void deveRetornarOptionalVazioQuandoIdNaoExiste() throws Exception {
        Optional<User> resultado = repository.findById(999);
        assertTrue(resultado.isEmpty());
    }

    // ========================================================================
    // Mapeamento de UserPapel (todos os valores do enum)
    // ========================================================================

    @Test
    void deveSalvarERecuperarTodosOsPapeis() throws Exception {
        for (UserPapel papel : UserPapel.values()) {
            String email = papel.name().toLowerCase() + "@teste.com";
            User salvo = repository.save(criarUsuario("Teste " + papel.name(), email, papel));

            Optional<User> encontrado = repository.findById(salvo.getId());
            assertTrue(encontrado.isPresent());
            assertEquals(papel, encontrado.get().getPapel(), "Falha no mapeamento do papel: " + papel);
        }
    }

    // ========================================================================
    // findByEmail
    // ========================================================================

    @Test
    void deveEncontrarUsuarioPorEmail() throws Exception {
        repository.save(criarAdmin());

        Optional<User> encontrado = repository.findByEmail("admin@teste.com");

        assertTrue(encontrado.isPresent());
        assertEquals("Admin Teste", encontrado.get().getNome());
    }

    @Test
    void deveEncontrarUsuarioPorEmailIgnorandoCase() throws Exception {
        repository.save(criarAdmin());

        Optional<User> encontrado = repository.findByEmail("ADMIN@TESTE.COM");

        assertTrue(encontrado.isPresent());
        assertEquals("admin@teste.com", encontrado.get().getEmail());
    }

    @Test
    void deveRetornarOptionalVazioQuandoEmailNaoExiste() throws Exception {
        Optional<User> resultado = repository.findByEmail("naoexiste@teste.com");
        assertTrue(resultado.isEmpty());
    }

    // ========================================================================
    // Restricao de email unico
    // ========================================================================

    @Test
    void deveLancarExcecaoAoSalvarEmailDuplicado() throws Exception {
        repository.save(criarAdmin());

        User duplicado = criarUsuario("Outro Admin", "admin@teste.com", UserPapel.ADMIN);

        assertThrows(IllegalArgumentException.class, () -> repository.save(duplicado));
    }

    @Test
    void deveLancarExcecaoComMensagemInformativaParaEmailDuplicado() throws Exception {
        repository.save(criarAdmin());

        User duplicado = criarUsuario("Outro", "admin@teste.com", UserPapel.ADMIN);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> repository.save(duplicado));
        assertTrue(ex.getMessage().contains("admin@teste.com"));
    }

    // ========================================================================
    // findAll
    // ========================================================================

    @Test
    void deveRetornarListaVaziaQuandoNaoHaUsuarios() throws Exception {
        List<User> users = repository.findAll();
        assertTrue(users.isEmpty());
    }

    @Test
    void deveRetornarTodosOsUsuarios() throws Exception {
        repository.save(criarAdmin());
        repository.save(criarEntregador());

        List<User> users = repository.findAll();

        assertEquals(2, users.size());
    }

    @Test
    void deveRetornarUsuariosOrdenadosPorId() throws Exception {
        repository.save(criarAdmin());
        repository.save(criarEntregador());

        List<User> users = repository.findAll();

        assertTrue(users.get(0).getId() < users.get(1).getId());
    }

    // ========================================================================
    // update
    // ========================================================================

    @Test
    void deveAtualizarDadosDoUsuario() throws Exception {
        User salvo = repository.save(criarAdmin());

        User atualizado = new User(
                salvo.getId(),
                "Novo Nome",
                "novo@email.com",
                Password.fromPlainText("novaSenha123"),
                UserPapel.SUPERVISOR,
                "(11) 99999-1234",
                true);

        repository.update(atualizado);

        Optional<User> encontrado = repository.findById(salvo.getId());
        assertTrue(encontrado.isPresent());
        User user = encontrado.get();
        assertEquals("Novo Nome", user.getNome());
        assertEquals("novo@email.com", user.getEmail());
        assertEquals(UserPapel.SUPERVISOR, user.getPapel());
        assertEquals("(11) 99999-1234", user.getTelefone());
        assertTrue(user.verificarSenha("novaSenha123"));
    }

    @Test
    void deveLancarExcecaoAoAtualizarUsuarioInexistente() throws Exception {
        User fantasma = new User(
                999, "Fantasma", "fantasma@teste.com", Password.fromPlainText("senha123"), UserPapel.ADMIN, null, true);

        assertThrows(SQLException.class, () -> repository.update(fantasma));
    }

    @Test
    void deveLancarExcecaoAoAtualizarParaEmailDuplicado() throws Exception {
        repository.save(criarAdmin());
        User entregador = repository.save(criarEntregador());

        User comEmailDuplicado = new User(
                entregador.getId(),
                "Entregador",
                "admin@teste.com",
                Password.fromPlainText("senha123"),
                UserPapel.ENTREGADOR,
                null,
                true);

        assertThrows(IllegalArgumentException.class, () -> repository.update(comEmailDuplicado));
    }

    // ========================================================================
    // desativar (soft delete)
    // ========================================================================

    @Test
    void deveDesativarUsuarioExistente() throws Exception {
        User salvo = repository.save(criarAdmin());

        boolean resultado = repository.desativar(salvo.getId());

        assertTrue(resultado);

        Optional<User> encontrado = repository.findById(salvo.getId());
        assertTrue(encontrado.isPresent());
        assertFalse(encontrado.get().isAtivo());
    }

    @Test
    void deveRetornarFalseAoDesativarUsuarioJaInativo() throws Exception {
        User salvo = repository.save(criarAdmin());
        repository.desativar(salvo.getId());

        boolean resultado = repository.desativar(salvo.getId());

        assertFalse(resultado);
    }

    @Test
    void deveRetornarFalseAoDesativarIdInexistente() throws Exception {
        boolean resultado = repository.desativar(999);
        assertFalse(resultado);
    }

    // ========================================================================
    // Campos opcionais (telefone)
    // ========================================================================

    @Test
    void deveSalvarUsuarioComTelefone() throws Exception {
        User comTelefone = new User(
                0,
                "Maria",
                "maria@teste.com",
                Password.fromPlainText("senha123"),
                UserPapel.ATENDENTE,
                "(11) 99999-0000",
                true);

        User salvo = repository.save(comTelefone);
        Optional<User> encontrado = repository.findById(salvo.getId());

        assertTrue(encontrado.isPresent());
        assertEquals("(11) 99999-0000", encontrado.get().getTelefone());
    }

    @Test
    void deveSalvarUsuarioSemTelefone() throws Exception {
        User semTelefone = criarAdmin();

        User salvo = repository.save(semTelefone);
        Optional<User> encontrado = repository.findById(salvo.getId());

        assertTrue(encontrado.isPresent());
        assertNull(encontrado.get().getTelefone());
    }
}
