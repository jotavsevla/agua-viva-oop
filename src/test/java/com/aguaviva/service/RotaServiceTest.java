package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.aguaviva.solver.SolverClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RotaServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static PedidoRepository pedidoRepository;
    private static SolverStubServer solverStub;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
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
        solverStub.setStatusCode(200);
        solverStub.setSolveResponse("{\"rotas\":[],\"nao_atendidos\":[]}");
        solverStub.resetRequestCount();
        solverStub.clearDynamicSolveHandler();
        limparBanco();
        atualizarConfiguracao("capacidade_veiculo", "5");
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

    private int criarEntregadorId(String email, boolean ativo) throws Exception {
        User entregador =
                new User(0, "Entregador", email, Password.fromPlainText("senha123"), UserPapel.ENTREGADOR, null, ativo);
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
                null);
        int clienteId = clienteRepository.save(cliente).getId();

        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("INSERT INTO saldo_vales (cliente_id, quantidade) VALUES (?, ?)")) {
            stmt.setInt(1, clienteId);
            stmt.setInt(2, saldo);
            stmt.executeUpdate();
        }

        return clienteId;
    }

    private int criarClienteSemCoordenadaComSaldo(String telefone, int saldo) throws Exception {
        Cliente cliente = new Cliente(
                "Cliente sem coord " + telefone, telefone, ClienteTipo.PF, "Rua sem coordenada", null, null, null);
        int clienteId = clienteRepository.save(cliente).getId();

        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("INSERT INTO saldo_vales (cliente_id, quantidade) VALUES (?, ?)")) {
            stmt.setInt(1, clienteId);
            stmt.setInt(2, saldo);
            stmt.executeUpdate();
        }

        return clienteId;
    }

    private int criarClienteComCoordenadaSemSaldo(String telefone) throws Exception {
        Cliente cliente = new Cliente(
                "Cliente sem saldo " + telefone,
                telefone,
                ClienteTipo.PF,
                "Rua sem saldo",
                BigDecimal.valueOf(-16.7310),
                BigDecimal.valueOf(-43.8710),
                null);
        return clienteRepository.save(cliente).getId();
    }

    private RotaService criarService() {
        return new RotaService(new SolverClient(solverStub.baseUrl()), factory);
    }

    private RotaService criarService(PedidoLifecycleService lifecycleService) {
        return new RotaService(new SolverClient(solverStub.baseUrl()), factory, lifecycleService);
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

        Pedido pedidoAtendido =
                pedidoRepository.save(new Pedido(cliente1, 2, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoNaoAtendido =
                pedidoRepository.save(new Pedido(cliente2, 2, JanelaTipo.ASAP, null, null, atendenteId));

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

    @Test
    void deveFazerRollbackQuandoFalhaAoPersistirEntregaNoMeioDaTransacao() throws Exception {
        int atendenteId = criarAtendenteId("atendente3@teste.com");
        int entregadorId = criarEntregadorId("entregador3@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-7201", 10);

        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 2, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "09:30"},
                        {"ordem": 2, "pedido_id": %d, "lat": -16.7211, "lon": -43.8611, "hora_prevista": "09:45"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId(), pedido.getId()));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> criarService().planejarRotasPendentes());

        assertEquals("Falha ao planejar rotas", ex.getMessage());
        assertInstanceOf(Exception.class, ex.getCause());

        assertEquals(0, contarLinhas("rotas"));
        assertEquals(0, contarLinhas("entregas"));
        assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
    }

    @Test
    void deveFazerRollbackQuandoSolverRetornaErroHttp() throws Exception {
        int atendenteId = criarAtendenteId("atendente4@teste.com");
        criarEntregadorId("entregador4@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-7301", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setStatusCode(500);
        solverStub.setSolveResponse("{\"erro\":\"solver indisponivel\"}");

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> criarService().planejarRotasPendentes());

        assertEquals("Falha ao planejar rotas", ex.getMessage());
        assertEquals(0, contarLinhas("rotas"));
        assertEquals(0, contarLinhas("entregas"));
        assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
    }

    @Test
    void deveFazerRollbackQuandoLifecycleDePedidoFalha() throws Exception {
        int atendenteId = criarAtendenteId("atendente4b@teste.com");
        int entregadorId = criarEntregadorId("entregador4b@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-7302", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

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
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId()));

        PedidoLifecycleService lifecycleComFalha = new PedidoLifecycleService() {
            @Override
            public PedidoTransitionResult transicionar(Connection conn, int pedidoId, PedidoStatus statusDestino) {
                throw new IllegalStateException("falha simulada no lifecycle");
            }
        };

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> criarService(lifecycleComFalha)
                .planejarRotasPendentes());

        assertEquals("Falha ao planejar rotas", ex.getMessage());
        assertEquals("falha simulada no lifecycle", ex.getCause().getMessage());
        assertEquals(0, contarLinhas("rotas"));
        assertEquals(0, contarLinhas("entregas"));
        assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
    }

    @Test
    void deveSerIdempotenteQuandoPlanejamentoForExecutadoDuasVezesSemNovosPendentes() throws Exception {
        int atendenteId = criarAtendenteId("atendente5@teste.com");
        int entregadorId = criarEntregadorId("entregador5@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-7401", 10);

        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "08:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId()));

        PlanejamentoResultado primeiraExecucao = criarService().planejarRotasPendentes();
        PlanejamentoResultado segundaExecucao = criarService().planejarRotasPendentes();

        assertEquals(1, primeiraExecucao.rotasCriadas());
        assertEquals(1, primeiraExecucao.entregasCriadas());
        assertEquals(0, primeiraExecucao.pedidosNaoAtendidos());

        assertEquals(0, segundaExecucao.rotasCriadas());
        assertEquals(0, segundaExecucao.entregasCriadas());
        assertEquals(0, segundaExecucao.pedidosNaoAtendidos());

        assertEquals(1, contarLinhas("rotas"));
        assertEquals(1, contarLinhas("entregas"));
        assertEquals(1, solverStub.requestCount());
    }

    @Test
    void deveReprocessarPendentesMesmoComNumeroNoDiaRepetidoPeloSolver() throws Exception {
        int atendenteId = criarAtendenteId("atendente6@teste.com");
        int entregadorId = criarEntregadorId("entregador6@teste.com", true);
        int cliente1 = criarClienteComSaldo("(38) 99999-7501", 10);
        int cliente2 = criarClienteComSaldo("(38) 99999-7502", 10);

        Pedido pedidoAtendidoPrimeiraExecucao =
                pedidoRepository.save(new Pedido(cliente1, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoFicaPendente =
                pedidoRepository.save(new Pedido(cliente2, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse(
                """
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "08:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": [%d]
                }
                """.formatted(entregadorId, pedidoAtendidoPrimeiraExecucao.getId(), pedidoFicaPendente.getId()));

        PlanejamentoResultado primeiraExecucao = criarService().planejarRotasPendentes();
        assertEquals(1, primeiraExecucao.rotasCriadas());
        assertEquals(1, primeiraExecucao.entregasCriadas());
        assertEquals(1, primeiraExecucao.pedidosNaoAtendidos());

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7220, "lon": -43.8620, "hora_prevista": "10:00"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedidoFicaPendente.getId()));

        PlanejamentoResultado segundaExecucao = criarService().planejarRotasPendentes();

        assertEquals(1, segundaExecucao.rotasCriadas());
        assertEquals(1, segundaExecucao.entregasCriadas());
        assertEquals(0, segundaExecucao.pedidosNaoAtendidos());

        assertEquals(1, contarLinhas("rotas"));
        assertEquals(1, contarLinhas("entregas"));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoAtendidoPrimeiraExecucao.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoFicaPendente.getId()));
        assertEquals(1, maxNumeroNoDiaDoEntregador(entregadorId));
    }

    @Test
    void deveRetornarSemChamarSolverQuandoNaoHaEntregadoresAtivos() throws Exception {
        int atendenteId = criarAtendenteId("atendente7@teste.com");
        int clienteId = criarClienteComSaldo("(38) 99999-7601", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(0, resultado.rotasCriadas());
        assertEquals(0, resultado.entregasCriadas());
        assertEquals(0, resultado.pedidosNaoAtendidos());
        assertEquals(0, solverStub.requestCount());
        assertEquals(0, contarLinhas("rotas"));
        assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
    }

    @Test
    void deveRetornarSemChamarSolverQuandoNaoHaPedidosElegiveisParaSolver() throws Exception {
        int atendenteId = criarAtendenteId("atendente8@teste.com");
        criarEntregadorId("entregador8@teste.com", true);
        int clienteSemCoordenada = criarClienteSemCoordenadaComSaldo("(38) 99999-7701", 10);
        Pedido pedido =
                pedidoRepository.save(new Pedido(clienteSemCoordenada, 1, JanelaTipo.ASAP, null, null, atendenteId));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(0, resultado.rotasCriadas());
        assertEquals(0, resultado.entregasCriadas());
        assertEquals(0, resultado.pedidosNaoAtendidos());
        assertEquals(0, solverStub.requestCount());
        assertEquals(0, contarLinhas("rotas"));
        assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
    }

    @Test
    void devePlanejarPedidoComPagamentoNaoValeMesmoSemSaldoDeVales() throws Exception {
        int atendenteId = criarAtendenteId("atendente8b@teste.com");
        int entregadorId = criarEntregadorId("entregador8b@teste.com", true);
        int clienteId = criarClienteComCoordenadaSemSaldo("(38) 99999-7702");
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));
        atualizarMetodoPagamentoPedido(pedido.getId(), "PIX");

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "09:10"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId()));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(0, resultado.pedidosNaoAtendidos());
        assertEquals(1, solverStub.requestCount());
        assertEquals("CONFIRMADO", statusDoPedido(pedido.getId()));
    }

    @Test
    void deveEnviarCapacidadesRemanescentesPorEntregadorNoContratoDoSolver() throws Exception {
        int atendenteId = criarAtendenteId("atendente-capacidade@teste.com");
        int entregadorComExecucao = criarEntregadorId("entregador-capacidade-1@teste.com", true);
        int entregadorLivre = criarEntregadorId("entregador-capacidade-2@teste.com", true);
        int clienteExecucao = criarClienteComSaldo("(38) 99999-7711", 10);
        int clienteNovo = criarClienteComSaldo("(38) 99999-7712", 10);

        Pedido pedidoExecucao = pedidoRepository.save(new Pedido(clienteExecucao, 2, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoNovo = pedidoRepository.save(new Pedido(clienteNovo, 1, JanelaTipo.ASAP, null, null, atendenteId));
        atualizarStatusPedido(pedidoExecucao.getId(), "EM_ROTA");

        int rotaEmAndamento = inserirRotaComStatus(entregadorComExecucao, "EM_ANDAMENTO", 1);
        inserirEntregaComStatus(pedidoExecucao.getId(), rotaEmAndamento, 1, "EM_EXECUCAO");

        java.util.concurrent.atomic.AtomicReference<String> payloadSolver = new java.util.concurrent.atomic.AtomicReference<>("");
        solverStub.setDynamicSolveHandler(requestBody -> {
            payloadSolver.set(requestBody);
            return """
                    {
                      "rotas": [
                        {
                          "entregador_id": %d,
                          "numero_no_dia": 1,
                          "paradas": [
                            {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "09:10"}
                          ]
                        }
                      ],
                      "nao_atendidos": []
                    }
                    """.formatted(entregadorLivre, pedidoNovo.getId());
        });

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(1, solverStub.requestCount());
        assertTrue(payloadSolver.get().contains("\"entregadores\":[" + entregadorComExecucao + "," + entregadorLivre + "]"));
        assertTrue(payloadSolver.get().contains("\"capacidades_entregadores\":[3,5]"));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoNovo.getId()));
    }

    @Test
    void deveConsiderarPendentesDaRotaEmAndamentoNoCalculoDaCapacidadeRemanescente() throws Exception {
        int atendenteId = criarAtendenteId("atendente-capacidade-pendente@teste.com");
        int entregadorComCarga = criarEntregadorId("entregador-capacidade-pendente-1@teste.com", true);
        int entregadorLivre = criarEntregadorId("entregador-capacidade-pendente-2@teste.com", true);
        int clienteExecucao = criarClienteComSaldo("(38) 99999-7715", 10);
        int clientePendente = criarClienteComSaldo("(38) 99999-7716", 10);
        int clienteNovo = criarClienteComSaldo("(38) 99999-7717", 10);

        Pedido pedidoExecucao = pedidoRepository.save(new Pedido(clienteExecucao, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoPendente = pedidoRepository.save(new Pedido(clientePendente, 2, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoNovo = pedidoRepository.save(new Pedido(clienteNovo, 1, JanelaTipo.ASAP, null, null, atendenteId));
        atualizarStatusPedido(pedidoExecucao.getId(), "EM_ROTA");
        atualizarStatusPedido(pedidoPendente.getId(), "EM_ROTA");

        int rotaEmAndamento = inserirRotaComStatus(entregadorComCarga, "EM_ANDAMENTO", 1);
        inserirEntregaComStatus(pedidoExecucao.getId(), rotaEmAndamento, 1, "EM_EXECUCAO");
        inserirEntregaComStatus(pedidoPendente.getId(), rotaEmAndamento, 2, "PENDENTE");

        java.util.concurrent.atomic.AtomicReference<String> payloadSolver = new java.util.concurrent.atomic.AtomicReference<>("");
        solverStub.setDynamicSolveHandler(requestBody -> {
            payloadSolver.set(requestBody);
            return """
                    {
                      "rotas": [
                        {
                          "entregador_id": %d,
                          "numero_no_dia": 1,
                          "paradas": [
                            {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "09:20"}
                          ]
                        }
                      ],
                      "nao_atendidos": []
                    }
                    """.formatted(entregadorLivre, pedidoNovo.getId());
        });

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(1, solverStub.requestCount());
        assertTrue(payloadSolver.get().contains("\"entregadores\":[" + entregadorComCarga + "," + entregadorLivre + "]"));
        assertTrue(payloadSolver.get().contains("\"capacidades_entregadores\":[2,5]"));
    }

    @Test
    void deveDistribuirPlanejamentoEntreMultiplosEntregadoresRespeitandoCapacidade() throws Exception {
        int atendenteId = criarAtendenteId("atendente-multi-entregador@teste.com");
        int entregadorA = criarEntregadorId("entregador-multi-1@teste.com", true);
        int entregadorB = criarEntregadorId("entregador-multi-2@teste.com", true);
        atualizarConfiguracao("capacidade_veiculo", "1");

        int cliente1 = criarClienteComSaldo("(38) 99999-7731", 10);
        int cliente2 = criarClienteComSaldo("(38) 99999-7732", 10);
        int cliente3 = criarClienteComSaldo("(38) 99999-7733", 10);

        Pedido pedido1 = pedidoRepository.save(new Pedido(cliente1, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedido2 = pedidoRepository.save(new Pedido(cliente2, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedido3 = pedidoRepository.save(new Pedido(cliente3, 1, JanelaTipo.ASAP, null, null, atendenteId));

        java.util.concurrent.atomic.AtomicReference<String> payloadSolver = new java.util.concurrent.atomic.AtomicReference<>("");
        solverStub.setDynamicSolveHandler(requestBody -> {
            payloadSolver.set(requestBody);
            return """
                    {
                      "rotas": [
                        {
                          "entregador_id": %d,
                          "numero_no_dia": 1,
                          "paradas": [
                            {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "09:15"}
                          ]
                        },
                        {
                          "entregador_id": %d,
                          "numero_no_dia": 1,
                          "paradas": [
                            {"ordem": 1, "pedido_id": %d, "lat": -16.7320, "lon": -43.8720, "hora_prevista": "09:25"}
                          ]
                        }
                      ],
                      "nao_atendidos": [%d]
                    }
                    """
                    .formatted(entregadorA, pedido1.getId(), entregadorB, pedido2.getId(), pedido3.getId());
        });

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(2, resultado.rotasCriadas());
        assertEquals(2, resultado.entregasCriadas());
        assertEquals(1, resultado.pedidosNaoAtendidos());
        assertEquals(1, solverStub.requestCount());
        assertTrue(payloadSolver.get().contains("\"entregadores\":[" + entregadorA + "," + entregadorB + "]"));
        assertTrue(payloadSolver.get().contains("\"capacidades_entregadores\":[1,1]"));
        assertEquals("CONFIRMADO", statusDoPedido(pedido1.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedido2.getId()));
        assertEquals("PENDENTE", statusDoPedido(pedido3.getId()));

        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT r.entregador_id, COUNT(*) "
                                + "FROM rotas r "
                                + "JOIN entregas e ON e.rota_id = r.id "
                                + "WHERE r.status::text = 'PLANEJADA' "
                                + "GROUP BY r.entregador_id "
                                + "ORDER BY r.entregador_id");
                ResultSet rs = stmt.executeQuery()) {
            int linhas = 0;
            while (rs.next()) {
                linhas++;
                assertEquals(1, rs.getInt(2));
            }
            assertEquals(2, linhas);
        }
    }

    @Test
    void devePromoverPedidosConfirmadosPorFifoAteLimiteDaCapacidadeLivre() throws Exception {
        int atendenteId = criarAtendenteId("atendente-fifo-confirmado@teste.com");
        int entregadorId = criarEntregadorId("entregador-fifo-confirmado@teste.com", true);
        int clienteAntigo = criarClienteComSaldo("(38) 99999-7713", 10);
        int clienteNovo = criarClienteComSaldo("(38) 99999-7714", 10);

        Pedido pedidoAntigo = pedidoRepository.save(new Pedido(clienteAntigo, 3, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoNovo = pedidoRepository.save(new Pedido(clienteNovo, 3, JanelaTipo.ASAP, null, null, atendenteId));
        atualizarStatusPedido(pedidoAntigo.getId(), "CONFIRMADO");
        atualizarStatusPedido(pedidoNovo.getId(), "CONFIRMADO");

        java.util.concurrent.atomic.AtomicReference<String> payloadSolver = new java.util.concurrent.atomic.AtomicReference<>("");
        solverStub.setDynamicSolveHandler(requestBody -> {
            payloadSolver.set(requestBody);
            return """
                    {
                      "rotas": [
                        {
                          "entregador_id": %d,
                          "numero_no_dia": 1,
                          "paradas": [
                            {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "09:30"}
                          ]
                        }
                      ],
                      "nao_atendidos": []
                    }
                    """.formatted(entregadorId, pedidoAntigo.getId());
        });

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(1, solverStub.requestCount());
        assertTrue(payloadSolver.get().contains("\"pedido_id\":" + pedidoAntigo.getId()));
        assertTrue(!payloadSolver.get().contains("\"pedido_id\":" + pedidoNovo.getId()));
        assertEquals(1, contarEntregasPorPedido(pedidoAntigo.getId()));
        assertEquals(0, contarEntregasPorPedido(pedidoNovo.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoAntigo.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoNovo.getId()));
    }

    @Test
    void devePularConfirmadoQueNaoCabeESelecionarProximoElegivelCapacityAware() throws Exception {
        int atendenteId = criarAtendenteId("atendente-capacity-aware@teste.com");
        int entregadorId = criarEntregadorId("entregador-capacity-aware@teste.com", true);
        int clienteExecucao = criarClienteComSaldo("(38) 99999-7718", 10);
        int clienteGrande = criarClienteComSaldo("(38) 99999-7719", 10);
        int clientePequeno = criarClienteComSaldo("(38) 99999-7720", 10);

        Pedido pedidoExecucao = pedidoRepository.save(new Pedido(clienteExecucao, 2, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoGrande = pedidoRepository.save(new Pedido(clienteGrande, 4, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoPequeno = pedidoRepository.save(new Pedido(clientePequeno, 2, JanelaTipo.ASAP, null, null, atendenteId));
        atualizarStatusPedido(pedidoExecucao.getId(), "EM_ROTA");
        atualizarStatusPedido(pedidoGrande.getId(), "CONFIRMADO");
        atualizarStatusPedido(pedidoPequeno.getId(), "CONFIRMADO");

        int rotaEmAndamento = inserirRotaComStatus(entregadorId, "EM_ANDAMENTO", 1);
        inserirEntregaComStatus(pedidoExecucao.getId(), rotaEmAndamento, 1, "EM_EXECUCAO");

        java.util.concurrent.atomic.AtomicReference<String> payloadSolver = new java.util.concurrent.atomic.AtomicReference<>("");
        solverStub.setDynamicSolveHandler(requestBody -> {
            payloadSolver.set(requestBody);
            return """
                    {
                      "rotas": [
                        {
                          "entregador_id": %d,
                          "numero_no_dia": 2,
                          "paradas": [
                            {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "10:15"}
                          ]
                        }
                      ],
                      "nao_atendidos": []
                    }
                    """.formatted(entregadorId, pedidoPequeno.getId());
        });

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(1, solverStub.requestCount());
        assertTrue(payloadSolver.get().contains("\"pedido_id\":" + pedidoPequeno.getId()));
        assertTrue(!payloadSolver.get().contains("\"pedido_id\":" + pedidoGrande.getId()));
        assertEquals(0, contarEntregasPorPedido(pedidoGrande.getId()));
        assertEquals(1, contarEntregasPorPedido(pedidoPequeno.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoGrande.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoPequeno.getId()));
    }

    @Test
    void deveSubstituirCamadaSecundariaPlanejadaSemAcumularRotas() throws Exception {
        int atendenteId = criarAtendenteId("atendente-camada-secundaria@teste.com");
        int entregadorId = criarEntregadorId("entregador-camada-secundaria@teste.com", true);
        int clienteExecucao = criarClienteComSaldo("(38) 99999-7721", 10);
        int clientePlanejado = criarClienteComSaldo("(38) 99999-7722", 10);
        int clienteNovo = criarClienteComSaldo("(38) 99999-7723", 10);

        Pedido pedidoExecucao = pedidoRepository.save(new Pedido(clienteExecucao, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoPlanejado = pedidoRepository.save(new Pedido(clientePlanejado, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoNovo = pedidoRepository.save(new Pedido(clienteNovo, 1, JanelaTipo.ASAP, null, null, atendenteId));
        atualizarStatusPedido(pedidoExecucao.getId(), "EM_ROTA");
        atualizarStatusPedido(pedidoPlanejado.getId(), "CONFIRMADO");

        int rotaPrimaria = inserirRotaComStatus(entregadorId, "EM_ANDAMENTO", 1);
        inserirEntregaComStatus(pedidoExecucao.getId(), rotaPrimaria, 1, "EM_EXECUCAO");
        int rotaSecundariaAntiga = inserirRotaComStatus(entregadorId, "PLANEJADA", 2);
        inserirEntregaComStatus(pedidoPlanejado.getId(), rotaSecundariaAntiga, 1, "PENDENTE");

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 2,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "10:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedidoNovo.getId()));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(1, solverStub.requestCount());
        assertEquals(1, contarRotasPorStatus("PLANEJADA"));
        assertEquals("EM_EXECUCAO", statusDaEntregaMaisRecenteDoPedido(pedidoExecucao.getId()));
        assertEquals(0, contarEntregasPorPedido(pedidoPlanejado.getId()));
        assertEquals(1, contarEntregasPorPedido(pedidoNovo.getId()));
    }

    @Test
    void deveFalharComMensagemClaraQuandoSolverRetornarMaisDeUmaRotaPlanejadaParaMesmoEntregador() throws Exception {
        int atendenteId = criarAtendenteId("atendente-contrato-solver@teste.com");
        int entregadorId = criarEntregadorId("entregador-contrato-solver@teste.com", true);
        int cliente1 = criarClienteComSaldo("(38) 99999-77231", 10);
        int cliente2 = criarClienteComSaldo("(38) 99999-77232", 10);

        Pedido pedido1 = pedidoRepository.save(new Pedido(cliente1, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedido2 = pedidoRepository.save(new Pedido(cliente2, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "10:30"}
                      ]
                    },
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 2,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7320, "lon": -43.8720, "hora_prevista": "10:45"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido1.getId(), entregadorId, pedido2.getId()));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> criarService().planejarRotasPendentes());

        assertEquals("Falha ao planejar rotas", ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("mais de uma rota PLANEJADA"));
        assertEquals(0, contarLinhas("rotas"));
        assertEquals(0, contarLinhas("entregas"));
        assertEquals("PENDENTE", statusDoPedido(pedido1.getId()));
        assertEquals("PENDENTE", statusDoPedido(pedido2.getId()));
    }

    @Test
    void deveReplanejarComSolverAposCancelamentoSemInsercaoLocal() throws Exception {
        int atendenteId = criarAtendenteId("atendente8c@teste.com");
        int entregadorId = criarEntregadorId("entregador8c@teste.com", true);
        int clienteCancelado = criarClienteComSaldo("(38) 99999-7703", 10);
        int clienteNovo = criarClienteComSaldo("(38) 99999-7704", 10);

        Pedido pedidoCancelado =
                pedidoRepository.save(new Pedido(clienteCancelado, 1, JanelaTipo.ASAP, null, null, atendenteId));
        Pedido pedidoNovo = pedidoRepository.save(new Pedido(clienteNovo, 2, JanelaTipo.ASAP, null, null, atendenteId));

        atualizarStatusPedido(pedidoCancelado.getId(), "CANCELADO");
        int rotaId = inserirRotaComStatus(entregadorId, "EM_ANDAMENTO", 1);
        inserirEntregaComStatus(pedidoCancelado.getId(), rotaId, 1, "CANCELADA");

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 2,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7310, "lon": -43.8710, "hora_prevista": "09:40"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedidoNovo.getId()));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(1, resultado.rotasCriadas());
        assertEquals(1, resultado.entregasCriadas());
        assertEquals(0, resultado.pedidosNaoAtendidos());
        assertEquals(1, solverStub.requestCount());
        assertEquals("CONFIRMADO", statusDoPedido(pedidoNovo.getId()));
        assertEquals(1, contarEntregasPorPedido(pedidoNovo.getId()));
        assertTrue(rotaDaEntregaDoPedido(pedidoNovo.getId()) > 0);
        assertEquals(1, ordemEntregaDoPedido(pedidoNovo.getId()));
        assertEquals(2, contarLinhas("entregas"));
    }

    @Test
    void deveRetornarSemProcessarQuandoLockDistribuidoDePlanejamentoNaoDisponivel() throws Exception {
        int atendenteId = criarAtendenteId("atendente9@teste.com");
        criarEntregadorId("entregador9@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-7801", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": 1,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "08:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(pedido.getId()));

        try (Connection concorrente = factory.getConnection();
                PreparedStatement stmt = concorrente.prepareStatement("SELECT pg_advisory_lock(?)")) {
            stmt.setLong(1, RotaService.PLANEJAMENTO_LOCK_KEY);
            stmt.execute();

            PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

            assertEquals(0, resultado.rotasCriadas());
            assertEquals(0, resultado.entregasCriadas());
            assertEquals(0, resultado.pedidosNaoAtendidos());
            assertEquals(0, solverStub.requestCount());
            assertEquals(0, contarLinhas("rotas"));
            assertEquals(0, contarLinhas("entregas"));
            assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
        } finally {
            try (Connection concorrente = factory.getConnection();
                    PreparedStatement unlock = concorrente.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                unlock.setLong(1, RotaService.PLANEJAMENTO_LOCK_KEY);
                unlock.execute();
            }
        }
    }

    @Test
    void devePlanejarComSucessoAoReexecutarAposLockLiberado() throws Exception {
        int atendenteId = criarAtendenteId("atendente-retry@teste.com");
        int entregadorId = criarEntregadorId("entregador-retry@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-7851", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "08:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId()));

        RotaService service = criarService();

        // 1) Lock bloqueado — tentativa retorna vazio
        try (Connection concorrente = factory.getConnection();
                PreparedStatement stmt = concorrente.prepareStatement("SELECT pg_advisory_lock(?)")) {
            stmt.setLong(1, RotaService.PLANEJAMENTO_LOCK_KEY);
            stmt.execute();

            PlanejamentoResultado bloqueado = service.planejarRotasPendentes();

            assertEquals(0, bloqueado.rotasCriadas());
            assertEquals(0, bloqueado.entregasCriadas());
            assertEquals(0, solverStub.requestCount());
            assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
        } finally {
            try (Connection concorrente = factory.getConnection();
                    PreparedStatement unlock = concorrente.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                unlock.setLong(1, RotaService.PLANEJAMENTO_LOCK_KEY);
                unlock.execute();
            }
        }

        // 2) Lock liberado — retry com sucesso
        PlanejamentoResultado retry = service.planejarRotasPendentes();

        assertEquals(1, retry.rotasCriadas());
        assertEquals(1, retry.entregasCriadas());
        assertEquals(0, retry.pedidosNaoAtendidos());
        assertEquals(1, solverStub.requestCount());
        assertEquals(1, contarLinhas("rotas"));
        assertEquals(1, contarLinhas("entregas"));
        assertEquals("CONFIRMADO", statusDoPedido(pedido.getId()));
    }

    @Test
    void devePermitirApenasUmPlanejamentoConcorrenteSemCancelarJobAtivo() throws Exception {
        int atendenteId = criarAtendenteId("atendente10@teste.com");
        int entregadorId = criarEntregadorId("entregador10@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-7901", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveDelayMillis(400);
        solverStub.resetCancelCount();
        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "08:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId()));

        RotaService service = criarService();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PlanejamentoResultado> primeira = executor.submit(service::planejarRotasPendentes);
            aguardarAte(() -> solverStub.requestCount() == 1, 3000, "Primeira chamada nao iniciou o solver");
            Future<PlanejamentoResultado> segunda = executor.submit(service::planejarRotasPendentes);

            PlanejamentoResultado resultadoPrimeira = primeira.get(5, TimeUnit.SECONDS);
            PlanejamentoResultado resultadoSegunda = segunda.get(5, TimeUnit.SECONDS);

            assertEquals(1, resultadoPrimeira.rotasCriadas());
            assertEquals(1, resultadoPrimeira.entregasCriadas());
            assertEquals(0, resultadoPrimeira.pedidosNaoAtendidos());

            assertEquals(0, resultadoSegunda.rotasCriadas());
            assertEquals(0, resultadoSegunda.entregasCriadas());
            assertEquals(0, resultadoSegunda.pedidosNaoAtendidos());

            assertEquals(0, solverStub.cancelCount());
            assertEquals(1, solverStub.requestCount());
            assertEquals(1, contarLinhas("rotas"));
            assertEquals(1, contarLinhas("entregas"));
            assertEquals("CONFIRMADO", statusDoPedido(pedido.getId()));
        } finally {
            solverStub.setSolveDelayMillis(0);
            executor.shutdownNow();
        }
    }

    @Test
    void deveEvitarDuplicidadeQuandoMultiplasInstanciasPlanejamAoMesmoTempo() throws Exception {
        int atendenteId = criarAtendenteId("atendente11@teste.com");
        int entregadorId = criarEntregadorId("entregador11@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-8001", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveDelayMillis(250);
        solverStub.resetCancelCount();
        solverStub.resetRequestCount();
        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "08:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId()));

        RotaService instanciaA = criarService();
        RotaService instanciaB = criarService();
        int concorrencia = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concorrencia);
        List<Future<PlanejamentoResultado>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concorrencia; i++) {
                RotaService alvo = (i % 2 == 0) ? instanciaA : instanciaB;
                futures.add(executor.submit(alvo::planejarRotasPendentes));
            }

            int resultadosComPlanejamento = 0;
            int resultadosSemPlanejamento = 0;
            for (Future<PlanejamentoResultado> future : futures) {
                PlanejamentoResultado resultado = future.get(5, TimeUnit.SECONDS);
                if (resultado.rotasCriadas() == 1) {
                    resultadosComPlanejamento++;
                } else if (resultado.rotasCriadas() == 0 && resultado.entregasCriadas() == 0) {
                    resultadosSemPlanejamento++;
                }
            }

            assertEquals(1, resultadosComPlanejamento);
            assertEquals(concorrencia - 1, resultadosSemPlanejamento);
            assertEquals(1, solverStub.requestCount());
            assertEquals(0, solverStub.cancelCount());
            assertEquals(1, contarLinhas("rotas"));
            assertEquals(1, contarLinhas("entregas"));
            assertEquals("CONFIRMADO", statusDoPedido(pedido.getId()));
        } finally {
            solverStub.setSolveDelayMillis(0);
            executor.shutdownNow();
        }
    }

    @Test
    void deveProcessarTodosPedidosEmRodadasSucessivasComAltaDisputa() throws Exception {
        int atendenteId = criarAtendenteId("atendente-disputa@teste.com");
        int entregadorId = criarEntregadorId("entregador-disputa@teste.com", true);

        int totalPedidos = 5;
        List<Integer> pedidoIds = new ArrayList<>();
        for (int i = 0; i < totalPedidos; i++) {
            int clienteId = criarClienteComSaldo("(38) 99999-9" + String.format("%03d", i), 10);
            Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));
            pedidoIds.add(pedido.getId());
        }

        // Handler dinamico: le pedido_ids do request e monta resposta com 1 rota por pedido
        Pattern pedidoIdPattern = Pattern.compile("\"pedido_id\"\\s*:\\s*(\\d+)");
        solverStub.setDynamicSolveHandler(requestBody -> {
            Matcher matcher = pedidoIdPattern.matcher(requestBody);
            List<String> paradas = new ArrayList<>();
            int ordem = 1;
            while (matcher.find()) {
                int pid = Integer.parseInt(matcher.group(1));
                paradas.add("""
                        {"ordem": %d, "pedido_id": %d, "lat": -16.72%02d, "lon": -43.86%02d, "hora_prevista": "%02d:30"}""".formatted(ordem, pid, ordem, ordem, 8 + ordem));
                ordem++;
            }
            if (paradas.isEmpty()) {
                return "{\"rotas\":[],\"nao_atendidos\":[]}";
            }
            return """
                    {"rotas":[{"entregador_id": %d, "numero_no_dia": 1, "paradas": [%s]}], "nao_atendidos": []}""".formatted(entregadorId, String.join(",", paradas));
        });
        solverStub.setSolveDelayMillis(150);

        RotaService instanciaA = criarService();
        RotaService instanciaB = criarService();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            // Multiplas rodadas: cada thread tenta planejar em loop
            int maxRodadas = 10;
            ConcurrentHashMap<Integer, PlanejamentoResultado> resultados = new ConcurrentHashMap<>();
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                RotaService alvo = (t % 2 == 0) ? instanciaA : instanciaB;
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int rodada = 0; rodada < maxRodadas; rodada++) {
                            PlanejamentoResultado r = alvo.planejarRotasPendentes();
                            if (r.rotasCriadas() > 0 || r.entregasCriadas() > 0) {
                                resultados.put(threadId * 100 + rodada, r);
                            }
                        }
                    } catch (Exception e) {
                        // Nao esperado — testes falharam se cair aqui
                    } finally {
                        done.countDown();
                    }
                });
            }

            done.await(30, TimeUnit.SECONDS);

            // Validacao: todos os pedidos devem estar CONFIRMADO
            for (int pedidoId : pedidoIds) {
                assertEquals(
                        "CONFIRMADO", statusDoPedido(pedidoId), "Pedido " + pedidoId + " deveria estar CONFIRMADO");
            }

            // Sem duplicidade: cada pedido tem exatamente 1 entrega
            assertEquals(totalPedidos, contarLinhas("entregas"), "Deve haver exatamente " + totalPedidos + " entregas");
        } finally {
            solverStub.setSolveDelayMillis(0);
            solverStub.clearDynamicSolveHandler();
            executor.shutdownNow();
        }
    }

    @Test
    void deveEvitarDuplicidadeComRetriesSimultaneosAposLiberacaoDeLock() throws Exception {
        int atendenteId = criarAtendenteId("atendente-retry-storm@teste.com");
        int entregadorId = criarEntregadorId("entregador-retry-storm@teste.com", true);
        int clienteId = criarClienteComSaldo("(38) 99999-9100", 10);
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

        solverStub.setSolveResponse("""
                {
                  "rotas": [
                    {
                      "entregador_id": %d,
                      "numero_no_dia": 1,
                      "paradas": [
                        {"ordem": 1, "pedido_id": %d, "lat": -16.7210, "lon": -43.8610, "hora_prevista": "08:30"}
                      ]
                    }
                  ],
                  "nao_atendidos": []
                }
                """.formatted(entregadorId, pedido.getId()));

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            // Fase 1: segurar lock externamente, todas threads falham
            Connection lockConn = factory.getConnection();
            try (PreparedStatement stmt = lockConn.prepareStatement("SELECT pg_advisory_lock(?)")) {
                stmt.setLong(1, RotaService.PLANEJAMENTO_LOCK_KEY);
                stmt.execute();
            }

            List<Future<PlanejamentoResultado>> bloqueados = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                RotaService service = criarService();
                bloqueados.add(executor.submit(service::planejarRotasPendentes));
            }

            for (Future<PlanejamentoResultado> f : bloqueados) {
                PlanejamentoResultado r = f.get(5, TimeUnit.SECONDS);
                assertEquals(0, r.rotasCriadas(), "Deveria estar bloqueado");
            }

            // Fase 2: liberar lock e disparar retries simultaneos com barreira
            try (PreparedStatement unlock = lockConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                unlock.setLong(1, RotaService.PLANEJAMENTO_LOCK_KEY);
                unlock.execute();
            }
            lockConn.close();

            List<Future<PlanejamentoResultado>> retries = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                RotaService service = criarService();
                retries.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return service.planejarRotasPendentes();
                }));
            }

            int vencedores = 0;
            int perdedores = 0;
            for (Future<PlanejamentoResultado> f : retries) {
                PlanejamentoResultado r = f.get(10, TimeUnit.SECONDS);
                if (r.rotasCriadas() == 1) {
                    vencedores++;
                } else if (r.rotasCriadas() == 0) {
                    perdedores++;
                }
            }

            assertEquals(1, vencedores, "Exatamente 1 thread deve vencer o retry");
            assertEquals(threads - 1, perdedores, "Demais threads devem perder");
            assertEquals(1, contarLinhas("rotas"), "Deve haver exatamente 1 rota");
            assertEquals(1, contarLinhas("entregas"), "Deve haver exatamente 1 entrega");
            assertEquals("CONFIRMADO", statusDoPedido(pedido.getId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deveDistribuirVitoriasEntreInstanciasEmMultiplasRodadas() throws Exception {
        int atendenteId = criarAtendenteId("atendente-fairness@teste.com");
        int entregadorId = criarEntregadorId("entregador-fairness@teste.com", true);

        Pattern pedidoIdPattern = Pattern.compile("\"pedido_id\"\\s*:\\s*(\\d+)");
        solverStub.setDynamicSolveHandler(requestBody -> {
            Matcher matcher = pedidoIdPattern.matcher(requestBody);
            List<String> paradas = new ArrayList<>();
            int ordem = 1;
            while (matcher.find()) {
                int pid = Integer.parseInt(matcher.group(1));
                paradas.add("""
                        {"ordem": %d, "pedido_id": %d, "lat": -16.72%02d, "lon": -43.86%02d, "hora_prevista": "%02d:30"}""".formatted(ordem, pid, ordem, ordem, 8 + ordem));
                ordem++;
            }
            if (paradas.isEmpty()) {
                return "{\"rotas\":[],\"nao_atendidos\":[]}";
            }
            return """
                    {"rotas":[{"entregador_id": %d, "numero_no_dia": 1, "paradas": [%s]}], "nao_atendidos": []}""".formatted(entregadorId, String.join(",", paradas));
        });

        int rodadas = 10;
        int threadsPerInstance = 2;
        int totalThreads = threadsPerInstance * 2;
        int atrasoInstanciaSemVantagemMs = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

        AtomicInteger vitoriasA = new AtomicInteger(0);
        AtomicInteger vitoriasB = new AtomicInteger(0);

        try {
            for (int rodada = 0; rodada < rodadas; rodada++) {
                int clienteId = criarClienteComSaldo("(38) 99999-8" + String.format("%03d", rodada), 10);
                pedidoRepository.save(new Pedido(clienteId, 1, JanelaTipo.ASAP, null, null, atendenteId));

                RotaService instanciaA = criarService();
                RotaService instanciaB = criarService();
                CyclicBarrier barreira = new CyclicBarrier(totalThreads);
                boolean vantagemA = rodada % 2 == 0;
                int vencedoresDaRodada = 0;

                List<Future<String>> futures = new ArrayList<>();
                for (int t = 0; t < threadsPerInstance; t++) {
                    futures.add(executor.submit(() -> {
                        barreira.await(5, TimeUnit.SECONDS);
                        if (!vantagemA) {
                            Thread.sleep(atrasoInstanciaSemVantagemMs);
                        }
                        PlanejamentoResultado r = instanciaA.planejarRotasPendentes();
                        return r.rotasCriadas() > 0 ? "A" : null;
                    }));
                    futures.add(executor.submit(() -> {
                        barreira.await(5, TimeUnit.SECONDS);
                        if (vantagemA) {
                            Thread.sleep(atrasoInstanciaSemVantagemMs);
                        }
                        PlanejamentoResultado r = instanciaB.planejarRotasPendentes();
                        return r.rotasCriadas() > 0 ? "B" : null;
                    }));
                }

                for (Future<String> f : futures) {
                    String vencedor = f.get(10, TimeUnit.SECONDS);
                    if ("A".equals(vencedor)) {
                        vitoriasA.incrementAndGet();
                        vencedoresDaRodada++;
                    } else if ("B".equals(vencedor)) {
                        vitoriasB.incrementAndGet();
                        vencedoresDaRodada++;
                    }
                }
                assertTrue(vencedoresDaRodada >= 1, "Cada rodada deve ter ao menos 1 vencedor");
            }

            // Vantagem alternada por rodada para garantir determinismo em CI e
            // validar que ambas instancias conseguem vencer sob concorrencia real.
            assertTrue(vitoriasA.get() + vitoriasB.get() >= rodadas, "Total de vitorias deve cobrir todas as rodadas");
            assertTrue(
                    vitoriasA.get() >= 1,
                    "Instancia A deveria vencer pelo menos 1 rodada, mas venceu " + vitoriasA.get());
            assertTrue(
                    vitoriasB.get() >= 1,
                    "Instancia B deveria vencer pelo menos 1 rodada, mas venceu " + vitoriasB.get());
        } finally {
            solverStub.clearDynamicSolveHandler();
            executor.shutdownNow();
        }
    }

    private static void aguardarAte(BooleanSupplier condicao, long timeoutMillis, String erro)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condicao.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(erro);
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
                        "SELECT TO_CHAR(hora_prevista, 'YYYY-MM-DD HH24:MI') FROM entregas WHERE pedido_id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int maxNumeroNoDiaDoEntregador(int entregadorId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COALESCE(MAX(numero_no_dia), 0) FROM rotas WHERE entregador_id = ?")) {
            stmt.setInt(1, entregadorId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int inserirRotaComStatus(int entregadorId, String status, int numeroNoDia) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (?, CURRENT_DATE, ?, ?) RETURNING id")) {
            stmt.setInt(1, entregadorId);
            stmt.setInt(2, numeroNoDia);
            stmt.setObject(3, status, java.sql.Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void inserirEntregaComStatus(int pedidoId, int rotaId, int ordemNaRota, String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status) VALUES (?, ?, ?, ?)")) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setInt(3, ordemNaRota);
            stmt.setObject(4, status, java.sql.Types.OTHER);
            stmt.executeUpdate();
        }
    }

    private void atualizarStatusPedido(int pedidoId, String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE pedidos SET status = ? WHERE id = ?")) {
            stmt.setObject(1, status, java.sql.Types.OTHER);
            stmt.setInt(2, pedidoId);
            stmt.executeUpdate();
        }
    }

    private int contarEntregasPorPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT COUNT(*) FROM entregas WHERE pedido_id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int contarRotasPorStatus(String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT COUNT(*) FROM rotas WHERE status::text = ? AND data = CURRENT_DATE")) {
            stmt.setString(1, status);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String statusDaEntregaMaisRecenteDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT status::text FROM entregas WHERE pedido_id = ? ORDER BY id DESC LIMIT 1")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int rotaDaEntregaDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT rota_id FROM entregas WHERE pedido_id = ? ORDER BY id DESC LIMIT 1")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int ordemEntregaDoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT ordem_na_rota FROM entregas WHERE pedido_id = ? ORDER BY id DESC LIMIT 1")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void atualizarMetodoPagamentoPedido(int pedidoId, String metodoPagamento) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("UPDATE pedidos SET metodo_pagamento = ? WHERE id = ?")) {
            stmt.setObject(1, metodoPagamento, java.sql.Types.OTHER);
            stmt.setInt(2, pedidoId);
            stmt.executeUpdate();
        }
    }

    private void atualizarConfiguracao(String chave, String valor) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE configuracoes SET valor = ? WHERE chave = ?")) {
            stmt.setString(1, valor);
            stmt.setString(2, chave);
            stmt.executeUpdate();
        }
    }

    private static final class SolverStubServer {
        private HttpServer server;
        private volatile String solveResponse = "{\"rotas\":[],\"nao_atendidos\":[]}";
        private volatile int statusCode = 200;
        private volatile int solveDelayMillis = 0;
        private volatile Function<String, String> dynamicHandler = null;
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final AtomicInteger cancelCount = new AtomicInteger(0);

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/solve", new SolveHandler());
            server.createContext("/cancel", new CancelHandler());
            server.start();
        }

        void stop() {
            server.stop(0);
        }

        void setSolveResponse(String json) {
            this.solveResponse = json;
        }

        void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        int requestCount() {
            return requestCount.get();
        }

        void resetRequestCount() {
            requestCount.set(0);
        }

        int cancelCount() {
            return cancelCount.get();
        }

        void resetCancelCount() {
            cancelCount.set(0);
        }

        void setSolveDelayMillis(int solveDelayMillis) {
            this.solveDelayMillis = Math.max(0, solveDelayMillis);
        }

        void setDynamicSolveHandler(Function<String, String> handler) {
            this.dynamicHandler = handler;
        }

        void clearDynamicSolveHandler() {
            this.dynamicHandler = null;
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private final class SolveHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                if (solveDelayMillis > 0) {
                    try {
                        Thread.sleep(solveDelayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String response;
                Function<String, String> handler = dynamicHandler;
                if (handler != null) {
                    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    response = handler.apply(requestBody);
                } else {
                    response = solveResponse;
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }

        private final class CancelHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                cancelCount.incrementAndGet();
                byte[] bytes = "{\"cancelado\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }
}
