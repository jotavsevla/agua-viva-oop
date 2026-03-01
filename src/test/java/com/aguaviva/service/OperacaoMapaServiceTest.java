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
class OperacaoMapaServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static PedidoRepository pedidoRepository;
    private static OperacaoMapaService service;

    @BeforeAll
    static void setUp() {
        factory = TestConnectionFactory.newConnectionFactory();
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        pedidoRepository = new PedidoRepository(factory);
        service = new OperacaoMapaService(factory);
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
        atualizarDeposito(-16.735, -43.878);
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparBase();
    }

    @Test
    void deveRetornarTrajetoCompletoComDepositoEParadas() throws Exception {
        int atendenteId = criarUsuario("atendente-mapa@teste.com", UserPapel.ATENDENTE);
        int entregadorId = criarUsuario("entregador-mapa@teste.com", UserPapel.ENTREGADOR);

        int clienteId = criarCliente("(38) 99811-1001", BigDecimal.valueOf(-16.721), BigDecimal.valueOf(-43.861));
        int pedidoId = criarPedido(clienteId, atendenteId);
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, 1, "PENDENTE");

        OperacaoMapaService.OperacaoMapaResultado resultado = service.consultarMapa();

        assertTrue(!resultado.ambiente().isBlank());
        assertEquals(1, resultado.rotas().size());
        OperacaoMapaService.RotaMapaResumo rota = resultado.rotas().getFirst();
        assertEquals(rotaId, rota.rotaId());
        assertEquals(entregadorId, rota.entregadorId());
        assertEquals("PLANEJADA", rota.statusRota());
        assertEquals("SECUNDARIA", rota.camada());
        assertEquals(1, rota.paradas().size());

        OperacaoMapaService.ParadaMapaResumo parada = rota.paradas().getFirst();
        assertEquals(pedidoId, parada.pedidoId());
        assertEquals(entregaId, parada.entregaId());
        assertEquals(1, parada.ordemNaRota());

        assertEquals(3, rota.trajeto().size());
        assertEquals("DEPOSITO", rota.trajeto().get(0).tipo());
        assertEquals("PARADA", rota.trajeto().get(1).tipo());
        assertEquals("DEPOSITO", rota.trajeto().get(2).tipo());
    }

    @Test
    void deveManterRotaSemParadasQuandoClienteNaoTemGeolocalizacao() throws Exception {
        int atendenteId = criarUsuario("atendente-mapa-nogeo@teste.com", UserPapel.ATENDENTE);
        int entregadorId = criarUsuario("entregador-mapa-nogeo@teste.com", UserPapel.ENTREGADOR);

        int clienteId = criarCliente("(38) 99811-1002", null, null);
        int pedidoId = criarPedido(clienteId, atendenteId);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        criarEntrega(pedidoId, rotaId, 1, "PENDENTE");

        OperacaoMapaService.OperacaoMapaResultado resultado = service.consultarMapa();

        assertEquals(1, resultado.rotas().size());
        OperacaoMapaService.RotaMapaResumo rota = resultado.rotas().getFirst();
        assertEquals("PRIMARIA", rota.camada());
        assertTrue(rota.paradas().isEmpty());
        assertEquals(1, rota.trajeto().size());
        assertEquals("DEPOSITO", rota.trajeto().getFirst().tipo());
    }

    private void limparBase() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "TRUNCATE TABLE sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private void atualizarDeposito(double lat, double lon) throws Exception {
        try (Connection conn = factory.getConnection()) {
            upsertConfiguracao(conn, "deposito_latitude", Double.toString(lat), "Latitude do deposito");
            upsertConfiguracao(conn, "deposito_longitude", Double.toString(lon), "Longitude do deposito");
        }
    }

    private void upsertConfiguracao(Connection conn, String chave, String valor, String descricao) throws Exception {
        String sql = "INSERT INTO configuracoes (chave, valor, descricao) VALUES (?, ?, ?) "
                + "ON CONFLICT (chave) DO UPDATE SET valor = EXCLUDED.valor, descricao = EXCLUDED.descricao";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, chave);
            stmt.setString(2, valor);
            stmt.setString(3, descricao);
            stmt.executeUpdate();
        }
    }

    private int criarUsuario(String email, UserPapel papel) throws Exception {
        User user = new User("Usuario Operacao", email, Password.fromPlainText("senha123"), papel);
        return userRepository.save(user).getId();
    }

    private int criarCliente(String telefone, BigDecimal latitude, BigDecimal longitude) throws Exception {
        Cliente cliente = new Cliente(
                "Cliente Mapa " + telefone,
                telefone,
                ClienteTipo.PF,
                "Rua Mapa, 100",
                latitude,
                longitude,
                null);
        return clienteRepository.save(cliente).getId();
    }

    private int criarPedido(int clienteId, int atendenteId) throws Exception {
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));
        return pedido.getId();
    }

    private int criarRota(int entregadorId, String status) throws Exception {
        String sql = "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) "
                + "VALUES (?, CURRENT_DATE, 1, ?) RETURNING id";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            stmt.setObject(2, status, Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int criarEntrega(int pedidoId, int rotaId, int ordemNaRota, String status) throws Exception {
        String sql = "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status) "
                + "VALUES (?, ?, ?, ?) RETURNING id";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setInt(3, ordemNaRota);
            stmt.setObject(4, status, Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
