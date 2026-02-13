package com.aguaviva.repository;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.pedido.JanelaTipo;
import com.aguaviva.domain.pedido.Pedido;
import com.aguaviva.domain.pedido.PedidoStatus;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PedidoRepositoryTest {

    private static ConnectionFactory factory;
    private static PedidoRepository pedidoRepository;
    private static ClienteRepository clienteRepository;
    private static UserRepository userRepository;

    @BeforeAll
    static void setUp() {
        factory = new ConnectionFactory(
                "localhost", "5435",
                "agua_viva_oop_test",
                "postgres", "postgres"
        );
        pedidoRepository = new PedidoRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        userRepository = new UserRepository(factory);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparAntes() throws Exception {
        limparBanco();
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparBanco();
    }

    private void limparBanco() throws Exception {
        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private int criarUsuarioId(String email) throws SQLException {
        User user = new User("Atendente", email, Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
        return userRepository.save(user).getId();
    }

    private int criarClienteId(String telefone) throws SQLException {
        Cliente cliente = new Cliente("Cliente Teste", telefone, ClienteTipo.PF, "Rua A, 100");
        return clienteRepository.save(cliente).getId();
    }

    private Pedido criarPedidoAsap(int clienteId, int criadoPorUserId) {
        return new Pedido(clienteId, 2, JanelaTipo.ASAP, null, null, criadoPorUserId);
    }

    private Pedido criarPedidoHard(int clienteId, int criadoPorUserId, PedidoStatus status) {
        return new Pedido(
                0,
                clienteId,
                3,
                JanelaTipo.HARD,
                LocalTime.of(9, 0),
                LocalTime.of(11, 0),
                status,
                criadoPorUserId
        );
    }

    // ========================================================================
    // save / findById
    // ========================================================================

    @Test
    void deveSalvarPedidoAsapERetornarComIdGerado() throws Exception {
        int userId = criarUsuarioId("atendente1@teste.com");
        int clienteId = criarClienteId("(38) 99999-1001");

        Pedido salvo = pedidoRepository.save(criarPedidoAsap(clienteId, userId));

        assertTrue(salvo.getId() > 0);
        assertEquals(clienteId, salvo.getClienteId());
        assertEquals(2, salvo.getQuantidadeGaloes());
        assertEquals(JanelaTipo.ASAP, salvo.getJanelaTipo());
        assertNull(salvo.getJanelaInicio());
        assertNull(salvo.getJanelaFim());
        assertEquals(PedidoStatus.PENDENTE, salvo.getStatus());
        assertEquals(userId, salvo.getCriadoPorUserId());
    }

    @Test
    void deveSalvarPedidoHardERecuperarPorId() throws Exception {
        int userId = criarUsuarioId("atendente2@teste.com");
        int clienteId = criarClienteId("(38) 99999-1002");
        Pedido salvo = pedidoRepository.save(criarPedidoHard(clienteId, userId, PedidoStatus.CONFIRMADO));

        Optional<Pedido> encontrado = pedidoRepository.findById(salvo.getId());

        assertTrue(encontrado.isPresent());
        Pedido pedido = encontrado.get();
        assertEquals(salvo.getId(), pedido.getId());
        assertEquals(clienteId, pedido.getClienteId());
        assertEquals(3, pedido.getQuantidadeGaloes());
        assertEquals(JanelaTipo.HARD, pedido.getJanelaTipo());
        assertEquals(LocalTime.of(9, 0), pedido.getJanelaInicio());
        assertEquals(LocalTime.of(11, 0), pedido.getJanelaFim());
        assertEquals(PedidoStatus.CONFIRMADO, pedido.getStatus());
        assertEquals(userId, pedido.getCriadoPorUserId());
    }

    @Test
    void deveRetornarOptionalVazioQuandoIdNaoExiste() throws Exception {
        Optional<Pedido> resultado = pedidoRepository.findById(999);
        assertTrue(resultado.isEmpty());
    }

    @Test
    void deveLancarExcecaoAoSalvarPedidoComClienteInexistente() throws Exception {
        int userId = criarUsuarioId("atendente3@teste.com");
        Pedido pedido = criarPedidoAsap(999, userId);

        assertThrows(IllegalArgumentException.class, () -> pedidoRepository.save(pedido));
    }

    @Test
    void deveLancarExcecaoAoSalvarPedidoComUsuarioInexistente() throws Exception {
        int clienteId = criarClienteId("(38) 99999-1003");
        Pedido pedido = criarPedidoAsap(clienteId, 999);

        assertThrows(IllegalArgumentException.class, () -> pedidoRepository.save(pedido));
    }

    // ========================================================================
    // findByCliente
    // ========================================================================

    @Test
    void deveEncontrarPedidosPorCliente() throws Exception {
        int userId = criarUsuarioId("atendente4@teste.com");
        int cliente1 = criarClienteId("(38) 99999-2001");
        int cliente2 = criarClienteId("(38) 99999-2002");

        pedidoRepository.save(criarPedidoAsap(cliente1, userId));
        pedidoRepository.save(criarPedidoHard(cliente1, userId, PedidoStatus.CONFIRMADO));
        pedidoRepository.save(criarPedidoAsap(cliente2, userId));

        List<Pedido> pedidosDoCliente1 = pedidoRepository.findByCliente(cliente1);

        assertEquals(2, pedidosDoCliente1.size());
        assertTrue(pedidosDoCliente1.stream().allMatch(p -> p.getClienteId() == cliente1));
    }

    @Test
    void deveRetornarListaVaziaQuandoClienteNaoTemPedidos() throws Exception {
        int userId = criarUsuarioId("atendente5@teste.com");
        int clienteComPedido = criarClienteId("(38) 99999-2003");
        int clienteSemPedido = criarClienteId("(38) 99999-2004");

        pedidoRepository.save(criarPedidoAsap(clienteComPedido, userId));

        List<Pedido> pedidos = pedidoRepository.findByCliente(clienteSemPedido);

        assertTrue(pedidos.isEmpty());
    }

    // ========================================================================
    // findPendentes
    // ========================================================================

    @Test
    void deveRetornarApenasPedidosPendentes() throws Exception {
        int userId = criarUsuarioId("atendente6@teste.com");
        int clienteId = criarClienteId("(38) 99999-3001");

        pedidoRepository.save(criarPedidoAsap(clienteId, userId));
        pedidoRepository.save(criarPedidoHard(clienteId, userId, PedidoStatus.CONFIRMADO));
        pedidoRepository.save(criarPedidoHard(clienteId, userId, PedidoStatus.EM_ROTA));

        List<Pedido> pendentes = pedidoRepository.findPendentes();

        assertEquals(1, pendentes.size());
        assertEquals(PedidoStatus.PENDENTE, pendentes.get(0).getStatus());
    }

    @Test
    void deveRetornarListaVaziaQuandoNaoHaPedidosPendentes() throws Exception {
        int userId = criarUsuarioId("atendente7@teste.com");
        int clienteId = criarClienteId("(38) 99999-3002");

        pedidoRepository.save(criarPedidoHard(clienteId, userId, PedidoStatus.CONFIRMADO));
        pedidoRepository.save(criarPedidoHard(clienteId, userId, PedidoStatus.EM_ROTA));

        List<Pedido> pendentes = pedidoRepository.findPendentes();

        assertTrue(pendentes.isEmpty());
    }

    // ========================================================================
    // update
    // ========================================================================

    @Test
    void deveAtualizarDadosDoPedido() throws Exception {
        int userId = criarUsuarioId("atendente8@teste.com");
        int clienteId = criarClienteId("(38) 99999-4001");
        Pedido salvo = pedidoRepository.save(criarPedidoAsap(clienteId, userId));

        Pedido atualizado = new Pedido(
                salvo.getId(),
                clienteId,
                4,
                JanelaTipo.HARD,
                LocalTime.of(14, 0),
                LocalTime.of(16, 0),
                PedidoStatus.CONFIRMADO,
                userId
        );

        pedidoRepository.update(atualizado);

        Optional<Pedido> encontrado = pedidoRepository.findById(salvo.getId());
        assertTrue(encontrado.isPresent());
        Pedido pedido = encontrado.get();
        assertEquals(4, pedido.getQuantidadeGaloes());
        assertEquals(JanelaTipo.HARD, pedido.getJanelaTipo());
        assertEquals(LocalTime.of(14, 0), pedido.getJanelaInicio());
        assertEquals(LocalTime.of(16, 0), pedido.getJanelaFim());
        assertEquals(PedidoStatus.CONFIRMADO, pedido.getStatus());
    }

    @Test
    void deveLancarExcecaoAoAtualizarPedidoInexistente() throws Exception {
        int userId = criarUsuarioId("atendente9@teste.com");
        int clienteId = criarClienteId("(38) 99999-4002");

        Pedido fantasma = new Pedido(
                999,
                clienteId,
                2,
                JanelaTipo.ASAP,
                null,
                null,
                PedidoStatus.PENDENTE,
                userId
        );

        assertThrows(SQLException.class, () -> pedidoRepository.update(fantasma));
    }

    @Test
    void deveLancarExcecaoAoAtualizarPedidoComUsuarioInvalido() throws Exception {
        int userId = criarUsuarioId("atendente10@teste.com");
        int clienteId = criarClienteId("(38) 99999-4003");
        Pedido salvo = pedidoRepository.save(criarPedidoAsap(clienteId, userId));

        Pedido invalido = new Pedido(
                salvo.getId(),
                clienteId,
                2,
                JanelaTipo.ASAP,
                null,
                null,
                PedidoStatus.PENDENTE,
                999
        );

        assertThrows(IllegalArgumentException.class, () -> pedidoRepository.update(invalido));
    }
}
