package com.aguaviva.service;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        solverStub.setStatusCode(200);
        solverStub.setSolveResponse("{\"rotas\":[],\"nao_atendidos\":[]}");
        solverStub.resetRequestCount();
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

    private int criarClienteSemCoordenadaComSaldo(String telefone, int saldo) throws Exception {
        Cliente cliente = new Cliente(
                "Cliente sem coord " + telefone,
                telefone,
                ClienteTipo.PF,
                "Rua sem coordenada",
                null,
                null,
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

    private RotaService criarService(PedidoLifecycleService lifecycleService) {
        return new RotaService(
                new SolverClient(solverStub.baseUrl()),
                factory,
                lifecycleService
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

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> criarService().planejarRotasPendentes());

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

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> criarService().planejarRotasPendentes());

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

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> criarService(lifecycleComFalha).planejarRotasPendentes());

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

        Pedido pedidoAtendidoPrimeiraExecucao = pedidoRepository.save(
                new Pedido(cliente1, 1, JanelaTipo.ASAP, null, null, atendenteId)
        );
        Pedido pedidoFicaPendente = pedidoRepository.save(
                new Pedido(cliente2, 1, JanelaTipo.ASAP, null, null, atendenteId)
        );

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

        assertEquals(2, contarLinhas("rotas"));
        assertEquals(2, contarLinhas("entregas"));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoAtendidoPrimeiraExecucao.getId()));
        assertEquals("CONFIRMADO", statusDoPedido(pedidoFicaPendente.getId()));
        assertEquals(2, maxNumeroNoDiaDoEntregador(entregadorId));
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
        Pedido pedido = pedidoRepository.save(new Pedido(clienteSemCoordenada, 1, JanelaTipo.ASAP, null, null, atendenteId));

        PlanejamentoResultado resultado = criarService().planejarRotasPendentes();

        assertEquals(0, resultado.rotasCriadas());
        assertEquals(0, resultado.entregasCriadas());
        assertEquals(0, resultado.pedidosNaoAtendidos());
        assertEquals(0, solverStub.requestCount());
        assertEquals(0, contarLinhas("rotas"));
        assertEquals("PENDENTE", statusDoPedido(pedido.getId()));
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

    private static void aguardarAte(BooleanSupplier condicao, long timeoutMillis, String erro) throws InterruptedException {
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
                     "SELECT TO_CHAR(hora_prevista, 'YYYY-MM-DD HH24:MI') FROM entregas WHERE pedido_id = ?")
        ) {
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
                     "SELECT COALESCE(MAX(numero_no_dia), 0) FROM rotas WHERE entregador_id = ?")
        ) {
            stmt.setInt(1, entregadorId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static final class SolverStubServer {
        private HttpServer server;
        private volatile String solveResponse = "{\"rotas\":[],\"nao_atendidos\":[]}";
        private volatile int statusCode = 200;
        private volatile int solveDelayMillis = 0;
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
                byte[] bytes = solveResponse.getBytes(StandardCharsets.UTF_8);
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
