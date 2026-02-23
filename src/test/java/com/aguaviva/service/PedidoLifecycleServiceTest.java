package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.pedido.JanelaTipo;
import com.aguaviva.domain.pedido.Pedido;
import com.aguaviva.domain.pedido.PedidoStatus;
import com.aguaviva.domain.pedido.PedidoTransitionResult;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ClienteRepository;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.PedidoRepository;
import com.aguaviva.repository.UserRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class PedidoLifecycleServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static PedidoRepository pedidoRepository;
    private static PedidoLifecycleService lifecycleService;

    @BeforeAll
    static void setUp() {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        pedidoRepository = new PedidoRepository(factory);
        lifecycleService = new PedidoLifecycleService();
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
            stmt.execute(
                    "TRUNCATE TABLE sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private int criarAtendenteId(String email) throws Exception {
        User atendente = new User("Atendente", email, Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
        return userRepository.save(atendente).getId();
    }

    private int criarClienteId(String telefone) throws Exception {
        Cliente cliente = new Cliente("Cliente " + telefone, telefone, ClienteTipo.PF, "Rua A, 100");
        return clienteRepository.save(cliente).getId();
    }

    @Test
    void deveTransicionarPedidoDePendenteParaConfirmado() throws Exception {
        int userId = criarAtendenteId("lifecycle1@teste.com");
        int clienteId = criarClienteId("(38) 99999-8101");
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 2, JanelaTipo.ASAP, null, null, userId));

        PedidoTransitionResult resultado;
        try (Connection conn = factory.getConnection()) {
            conn.setAutoCommit(false);
            resultado = lifecycleService.transicionar(conn, pedido.getId(), PedidoStatus.CONFIRMADO);
            conn.commit();
        }

        assertEquals(PedidoStatus.CONFIRMADO, resultado.novoStatus());
        assertFalse(resultado.geraCobrancaCancelamento());
        assertEquals("CONFIRMADO", statusDoPedido(pedido.getId()));
    }

    @Test
    void deveRejeitarTransicaoInvalida() throws Exception {
        int userId = criarAtendenteId("lifecycle2@teste.com");
        int clienteId = criarClienteId("(38) 99999-8102");
        Pedido pedido = pedidoRepository.save(
                new Pedido(0, clienteId, 2, JanelaTipo.ASAP, null, null, PedidoStatus.ENTREGUE, userId));

        try (Connection conn = factory.getConnection()) {
            conn.setAutoCommit(false);
            assertThrows(
                    IllegalStateException.class,
                    () -> lifecycleService.transicionar(conn, pedido.getId(), PedidoStatus.CONFIRMADO));
            conn.rollback();
        }

        assertEquals("ENTREGUE", statusDoPedido(pedido.getId()));
    }

    @Test
    void devePersistirEfeitosDeCancelamentoEmRotaQuandoColunasExistirem() throws Exception {
        int userId = criarAtendenteId("lifecycle3@teste.com");
        int clienteId = criarClienteId("(38) 99999-8103");
        Pedido pedido = pedidoRepository.save(new Pedido(
                0,
                clienteId,
                3,
                JanelaTipo.HARD,
                LocalTime.of(9, 0),
                LocalTime.of(11, 0),
                PedidoStatus.EM_ROTA,
                userId));

        PedidoTransitionResult resultado;
        try (Connection conn = factory.getConnection()) {
            conn.setAutoCommit(false);
            resultado = lifecycleService.transicionar(
                    conn,
                    pedido.getId(),
                    PedidoStatus.CANCELADO,
                    new PedidoLifecycleService.TransitionContext("cliente cancelou em rota", 3500));
            conn.commit();
        }

        assertTrue(resultado.geraCobrancaCancelamento());
        assertEquals("CANCELADO", statusDoPedido(pedido.getId()));

        if (hasColumn("pedidos", "cancelado_em")) {
            assertNotNull(canceladoEmDoPedido(pedido.getId()));
            assertEquals("cliente cancelou em rota", motivoCancelamentoDoPedido(pedido.getId()));
            assertEquals(3500, cobrancaCancelamentoDoPedido(pedido.getId()));
            assertEquals("PENDENTE", cobrancaStatusDoPedido(pedido.getId()));
        }
    }

    private boolean hasColumn(String tabela, String coluna) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tabela);
            stmt.setString(2, coluna);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String statusDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT status::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String canceladoEmDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT TO_CHAR(cancelado_em, 'YYYY-MM-DD HH24:MI:SS') FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String motivoCancelamentoDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT motivo_cancelamento FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int cobrancaCancelamentoDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT cobranca_cancelamento_centavos FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String cobrancaStatusDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT cobranca_status::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
