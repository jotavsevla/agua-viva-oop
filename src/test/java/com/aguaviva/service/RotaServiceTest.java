package com.aguaviva.service;

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
import com.aguaviva.solver.SolverClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RotaServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static PedidoRepository pedidoRepository;
    private static SolverStubServer solverStub;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new ConnectionFactory(
                "localhost", "5435",
                "agua_viva_oop_test",
                "postgres", "postgres"
        );
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        pedidoRepository = new PedidoRepository(factory);

        solverStub = new SolverStubServer();
        solverStub.start();
    }

    @AfterAll
    static void tearDown() {
        if (solverStub != null) {
            solverStub.stop();
        }
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

    private int criarAtendenteId(String email) throws Exception {
        User atendente = new User("Atendente", email, Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
        return userRepository.save(atendente).getId();
    }

    private int criarEntregadorId(String email, boolean ativo) throws Exception {
        User entregador = new User(0, "Entregador", email, Password.fromPlainText("senha123"), UserPapel.ENTREGADOR, null, ativo);
        return userRepository.save(entregador).getId();
    }

    private int criarClienteComSaldo(String telefone, int saldo) throws Exception {
        Cliente cliente = new Cliente(
                "Cliente " + telefone,
                telefone,
                ClienteTipo.PF,
                "Rua Teste",
                BigDecimal.valueOf(-16.7210),
                BigDecimal.valueOf(-43.8610),
                null
        );
        int clienteId = clienteRepository.save(cliente).getId();

        try (Connection conn = factory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO saldo_vales (cliente_id, quantidade) VALUES (?, ?)")
        ) {
            stmt.setInt(1, clienteId);
            stmt.setInt(2, saldo);
            stmt.executeUpdate();
        }

        return clienteId;
    }

    private RotaService criarService() {
        return new RotaService(
                new SolverClient(solverStub.baseUrl()),
                factory
        );
    }

    @Test
    void devePlanejarRotasEPersistirEntregasAtualizandoStatusDosPedidosAtendidos() throws Exception {
        int atendenteId = criarAtendenteId("atendente@teste.com");
        int entregadorId = criarEntregadorId("entregador@teste.com", true);

        int cliente1 = criarClienteComSaldo("(38) 99999-7001", 10);
        int cliente2 = criarClienteComSaldo("(38) 99999-7002", 10);

        Pedido pedido1 = pedidoRepository.save(new Pedido(cliente1, 2, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedido2 = pedidoRepository.save(new Pedido(cliente2, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "09:30"},
                        {"ordem": 2, "pedido_id": %d, "lat": -16.7220, "lon": -43.8620, "hora_prevista": "10:00"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido1.getId(), pedido2.getId()));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(2, resultado.entregasCriadas());
        assertEquals(0, resultado.pedidosNaoAtendidos());

        assertEquals(1, contarLinhas("rotas"));
        assertEquals(2, contarLinhas("entregas"));
        assertEquals("CONFIRMADO", statusDoPedido(pedido1.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedido2.getId()));
        assertNotNull(horaPrevistaDaEntrega(pedido1.getId()));
    }

    @Test
    void deveManterPedidoPendenteQuandoSolverRetornaNaoAtendido() throws Exception {
        int atendenteId = criarAtendenteId("atendente2@teste.com");
        int entregadorId = criarEntregadorId("entregador2@teste.com", true);

        int cliente1 = criarClienteComSaldo("(38) 99999-7101", 10);
        int cliente2 = criarClienteComSaldo("(38) 99999-7102", 10);

        Pedido pedidoAtendido = pedidoRepository.save(new Pedido(cliente1, 2, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoNaoAtendido = pedidoRepository.save(new Pedido(cliente2, 2, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "09:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": [%d]
                }
                """.formatted(entregadorId, pedidoAtendido.getId(), pedidoNaoAtendido.getId()));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(1, resultado.pedidosNaoAtendidos());

        assertEquals("CONFIRMADO", statusDoPedido(pedidoAtendido.getId()));
        assertEquals("PENDENTE", statusDoPedido(pedidoNaoAtendido.getId()));
    }

    private int contarLinhas(String tabela) throws Exception {
        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
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

    private String horaPrevistaDaEntrega(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT TO_CHAR(hora_prevista, 'YYYY-MM-DD HH24:MI') FROM entregas WHERE pedido_id = ?")
        ) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static final class SolverStubServer {
        private HttpServer server;
        private volatile String solveResponse = "{\"rotas\":[],\"nao_atendidos\":[]}";

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/solve", new SolveHandler());
            server.start();
        }

        void stop() {
            server.stop(0);
        }

        void setSolveResponse(String json) {
            this.solveResponse = json;
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private final class SolveHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = solveResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }
}
