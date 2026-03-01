package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.pedido.JanelaTipo;
import com.aguaviva.domain.pedido.Pedido;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ClienteRepository;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.PedidoRepository;
import com.aguaviva.repository.UserRepository;
import com.aguaviva.support.TestConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class OperacaoPainelServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static PedidoRepository pedidoRepository;
    private static OperacaoPainelService service;

    @BeforeAll
    static void setUp() {
        factory = TestConnectionFactory.newConnectionFactory();
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        pedidoRepository = new PedidoRepository(factory);
        service = new OperacaoPainelService(factory);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparAntes() throws Exception {
        limparBase();
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparBase();
    }

    @Test
    void deveConsolidarResumoDePedidosRotasEFilasOperacionais() throws Exception {
        int atendenteId = criarUsuario("atendente-painel@teste.com", UserPapel.ATENDENTE);
        int entregadorId = criarUsuario("entregador-painel@teste.com", UserPapel.ENTREGADOR);

        int clientePendente = criarCliente("(38) 99911-1001", -16.72, -43.86);
        int clienteConfirmado = criarCliente("(38) 99911-1002", -16.721, -43.861);
        int clienteEmRota = criarCliente("(38) 99911-1003", -16.722, -43.862);

        int pedidoPendente = criarPedido(clientePendente, atendenteId);
        int pedidoConfirmado = criarPedido(clienteConfirmado, atendenteId);
        int pedidoEmRota = criarPedido(clienteEmRota, atendenteId);

        atualizarStatusPedido(pedidoConfirmado, "CONFIRMADO");
        atualizarStatusPedido(pedidoEmRota, "EM_ROTA");

        int rotaPlanejada = criarRota(entregadorId, "PLANEJADA", 1);
        int rotaEmAndamento = criarRota(entregadorId, "EM_ANDAMENTO", 2);

        criarEntrega(pedidoConfirmado, rotaPlanejada, 1, "PENDENTE");
        criarEntrega(pedidoEmRota, rotaEmAndamento, 1, "EM_EXECUCAO");

        OperacaoPainelService.OperacaoPainelResultado resultado = service.consultarPainel();

        assertTrue(!resultado.ambiente().isBlank());
        OperacaoPainelService.PedidosPorStatus pedidos = resultado.pedidosPorStatus();
        assertEquals(1, pedidos.pendente());
        assertEquals(1, pedidos.confirmado());
        assertEquals(1, pedidos.emRota());
        assertEquals(0, pedidos.entregue());
        assertEquals(0, pedidos.cancelado());

        OperacaoPainelService.RotasResumo rotas = resultado.rotas();
        assertEquals(1, rotas.emAndamento().size());
        assertEquals(1, rotas.planejadas().size());
        assertEquals(rotaEmAndamento, rotas.emAndamento().getFirst().rotaId());
        assertEquals(1, rotas.emAndamento().getFirst().emExecucao());
        assertEquals(rotaPlanejada, rotas.planejadas().getFirst().rotaId());
        assertEquals(1, rotas.planejadas().getFirst().pendentes());

        OperacaoPainelService.FilasResumo filas = resultado.filas();
        assertEquals(1, filas.pendentesElegiveis().size());
        assertEquals(pedidoPendente, filas.pendentesElegiveis().getFirst().pedidoId());
        assertEquals(1, filas.confirmadosSecundaria().size());
        assertEquals(pedidoConfirmado, filas.confirmadosSecundaria().getFirst().pedidoId());
        assertEquals(1, filas.emRotaPrimaria().size());
        assertEquals(pedidoEmRota, filas.emRotaPrimaria().getFirst().pedidoId());
    }

    private void limparBase() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "TRUNCATE TABLE sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private int criarUsuario(String email, UserPapel papel) throws Exception {
        User user = new User("Usuario Painel", email, Password.fromPlainText("senha123"), papel);
        return userRepository.save(user).getId();
    }

    private int criarCliente(String telefone, double latitude, double longitude) throws Exception {
        Cliente cliente = new Cliente(
                "Cliente Painel " + telefone,
                telefone,
                ClienteTipo.PF,
                "Rua Painel, 50",
                BigDecimal.valueOf(latitude),
                BigDecimal.valueOf(longitude),
                null);
        return clienteRepository.save(cliente).getId();
    }

    private int criarPedido(int clienteId, int atendenteId) throws Exception {
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));
        return pedido.getId();
    }

    private void atualizarStatusPedido(int pedidoId, String status) throws Exception {
        String sql = "UPDATE pedidos SET status = ?, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, status, Types.OTHER);
            stmt.setInt(2, pedidoId);
            stmt.executeUpdate();
        }
    }

    private int criarRota(int entregadorId, String status, int numeroNoDia) throws Exception {
        String sql = "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) "
                + "VALUES (?, CURRENT_DATE, ?, ?) RETURNING id";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            stmt.setInt(2, numeroNoDia);
            stmt.setObject(3, status, Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void criarEntrega(int pedidoId, int rotaId, int ordemNaRota, String status) throws Exception {
        String sql = "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status) VALUES (?, ?, ?, ?)";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setInt(3, ordemNaRota);
            stmt.setObject(4, status, Types.OTHER);
            stmt.executeUpdate();
        }
    }
}
