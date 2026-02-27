package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ReplanejamentoWorkerServiceTest {

    private static ConnectionFactory factory;
    private static ReplanejamentoWorkerService workerService;
    private static AtomicInteger replanejamentoCalls;
    private static AtomicReference<CapacidadePolicy> lastCapacidadePolicy;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
        garantirSchemaDispatch();
        replanejamentoCalls = new AtomicInteger(0);
        lastCapacidadePolicy = new AtomicReference<>();
        workerService = new ReplanejamentoWorkerService(factory, capacidadePolicy -> {
            replanejamentoCalls.incrementAndGet();
            lastCapacidadePolicy.set(capacidadePolicy);
            return new PlanejamentoResultado(2, 3, 1);
        });
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparAntes() throws Exception {
        replanejamentoCalls.set(0);
        lastCapacidadePolicy.set(null);
        limparEventos();
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparEventos();
    }

    private static void garantirSchemaDispatch() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dispatch_event_status') "
                    + "THEN CREATE TYPE dispatch_event_status AS ENUM ('PENDENTE', 'PROCESSADO'); "
                    + "END IF; "
                    + "END $$;");
            stmt.execute("CREATE TABLE IF NOT EXISTS dispatch_events ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "event_type VARCHAR(64) NOT NULL, "
                    + "aggregate_type VARCHAR(32) NOT NULL, "
                    + "aggregate_id BIGINT, "
                    + "payload JSONB NOT NULL DEFAULT '{}'::jsonb, "
                    + "status dispatch_event_status NOT NULL DEFAULT 'PENDENTE', "
                    + "created_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "available_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "processed_em TIMESTAMP)");
        }
    }

    private void limparEventos() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "TRUNCATE TABLE dispatch_events, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void deveCoalescerEventosEExecutarReplanejamentoUmaVez() throws Exception {
        inserirEvento(DispatchEventTypes.PEDIDO_CRIADO, 30);
        inserirEvento(DispatchEventTypes.PEDIDO_CANCELADO, 30);
        inserirEvento(DispatchEventTypes.PEDIDO_ENTREGUE, 30);

        ReplanejamentoWorkerResultado resultado = workerService.processarPendentes(15, 100);

        assertTrue(resultado.replanejou());
        assertEquals(3, resultado.eventosProcessados());
        assertEquals(1, replanejamentoCalls.get());
        assertEquals(CapacidadePolicy.REMANESCENTE, lastCapacidadePolicy.get());
        assertEquals(2, resultado.rotasCriadas());
        assertEquals(3, resultado.entregasCriadas());
        assertEquals(1, resultado.pedidosNaoAtendidos());
        assertEquals(3, contarProcessados());
    }

    @Test
    void deveUsarCapacidadeCheiaQuandoLoteForApenasPrimario() throws Exception {
        inserirEvento(DispatchEventTypes.PEDIDO_CRIADO, 30);
        inserirEvento(DispatchEventTypes.PEDIDO_ENTREGUE, 30);

        ReplanejamentoWorkerResultado resultado = workerService.processarPendentes(0, 100);

        assertTrue(resultado.replanejou());
        assertEquals(2, resultado.eventosProcessados());
        assertEquals(1, replanejamentoCalls.get());
        assertEquals(CapacidadePolicy.CHEIA, lastCapacidadePolicy.get());
        assertEquals(2, contarProcessados());
    }

    @Test
    void naoDeveReplanejarQuandoNaoHaEventoTrigger() throws Exception {
        inserirEvento(DispatchEventTypes.ROTA_INICIADA, 30);
        inserirEvento(DispatchEventTypes.PEDIDO_ENTREGUE, 30);

        ReplanejamentoWorkerResultado resultado = workerService.processarPendentes(10, 100);

        assertFalse(resultado.replanejou());
        assertEquals(2, resultado.eventosProcessados());
        assertEquals(0, replanejamentoCalls.get());
        assertTrue(lastCapacidadePolicy.get() == null);
        assertEquals(2, contarProcessados());
    }

    @Test
    void deveRespeitarDebounce() throws Exception {
        inserirEvento(DispatchEventTypes.PEDIDO_CRIADO, 2);

        ReplanejamentoWorkerResultado resultado = workerService.processarPendentes(15, 100);

        assertFalse(resultado.replanejou());
        assertEquals(0, resultado.eventosProcessados());
        assertEquals(0, replanejamentoCalls.get());
        assertEquals(1, contarPendentes());
    }

    @Test
    void deveDispararReplanejamentoQuandoEventoForCancelamento() throws Exception {
        inserirEvento(DispatchEventTypes.PEDIDO_CANCELADO, 40);

        ReplanejamentoWorkerResultado resultado = workerService.processarPendentes(0, 100);

        assertTrue(resultado.replanejou());
        assertEquals(1, resultado.eventosProcessados());
        assertEquals(1, replanejamentoCalls.get());
        assertEquals(CapacidadePolicy.REMANESCENTE, lastCapacidadePolicy.get());
        assertEquals(1, contarProcessados());
    }

    @Test
    void naoDeveDispararReplanejamentoQuandoEventoForApenasRotaIniciada() throws Exception {
        inserirEvento(DispatchEventTypes.ROTA_INICIADA, 40);

        ReplanejamentoWorkerResultado resultado = workerService.processarPendentes(0, 100);

        assertFalse(resultado.replanejou());
        assertEquals(1, resultado.eventosProcessados());
        assertEquals(0, replanejamentoCalls.get());
        assertEquals(1, contarProcessados());
    }

    @Test
    void deveReplanejarSemEventosQuandoHouverPedidoHardEmRisco() throws Exception {
        inserirPedidoHardEmRisco();

        ReplanejamentoWorkerResultado resultado = workerService.processarPendentes(0, 100);

        assertTrue(resultado.replanejou());
        assertEquals(0, resultado.eventosProcessados());
        assertEquals(1, replanejamentoCalls.get());
        assertEquals(CapacidadePolicy.REMANESCENTE, lastCapacidadePolicy.get());
    }

    @Test
    void deveDetectarRiscoHardQuandoHorizonteCruzarMeiaNoite() throws Exception {
        inserirPedidoHard(LocalTime.of(23, 20), LocalTime.of(23, 50));

        assertTrue(workerService.existePedidoHardEmRisco(LocalTime.of(23, 40), 30));
    }

    @Test
    void naoDeveDetectarRiscoHardQuandoJanelaEstiverForaDoHorizonteCircular() throws Exception {
        inserirPedidoHard(LocalTime.of(23, 20), LocalTime.of(23, 50));

        assertFalse(workerService.existePedidoHardEmRisco(LocalTime.of(0, 30), 30));
    }

    @Test
    void deveGarantirUmUnicoLiderQuandoWorkersConcorremPeloMesmoLote() throws Exception {
        inserirEvento(DispatchEventTypes.PEDIDO_CRIADO, 40);
        inserirEvento(DispatchEventTypes.PEDIDO_CANCELADO, 40);

        AtomicInteger chamadasConcorrentes = new AtomicInteger(0);
        CountDownLatch executorEntrou = new CountDownLatch(1);
        CountDownLatch liberarExecutor = new CountDownLatch(1);
        ReplanejamentoWorkerService workerConcorrente = new ReplanejamentoWorkerService(factory, capacidadePolicy -> {
            chamadasConcorrentes.incrementAndGet();
            executorEntrou.countDown();
            try {
                liberarExecutor.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("thread interrompida no teste de concorrencia", e);
            }
            return new PlanejamentoResultado(1, 1, 0);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReplanejamentoWorkerResultado> futureA =
                    executor.submit(() -> workerConcorrente.processarPendentes(0, 100));
            assertTrue(executorEntrou.await(5, TimeUnit.SECONDS), "executor primario nao iniciou a tempo");
            Future<ReplanejamentoWorkerResultado> futureB =
                    executor.submit(() -> workerConcorrente.processarPendentes(0, 100));

            liberarExecutor.countDown();

            ReplanejamentoWorkerResultado resultadoA = futureA.get(10, TimeUnit.SECONDS);
            ReplanejamentoWorkerResultado resultadoB = futureB.get(10, TimeUnit.SECONDS);

            int totalEventosProcessados = resultadoA.eventosProcessados() + resultadoB.eventosProcessados();
            int totalReplanejamentos = (resultadoA.replanejou() ? 1 : 0) + (resultadoB.replanejou() ? 1 : 0);

            assertEquals(2, totalEventosProcessados);
            assertEquals(1, totalReplanejamentos);
            assertEquals(1, chamadasConcorrentes.get());
            assertEquals(2, contarProcessados());
            assertEquals(0, contarPendentes());
        } finally {
            liberarExecutor.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deveSolicitarPreempcaoAoDetectarLockOcupadoEReprocessarNaRetentativa() throws Exception {
        inserirEvento(DispatchEventTypes.PEDIDO_CRIADO, 40);
        inserirEvento(DispatchEventTypes.PEDIDO_FALHOU, 40);

        AtomicInteger pedidosPreempcao = new AtomicInteger(0);
        AtomicInteger chamadasExecutor = new AtomicInteger(0);
        CountDownLatch primeiraExecucaoIniciada = new CountDownLatch(1);
        CountDownLatch liberarPrimeiraExecucao = new CountDownLatch(1);

        ReplanejamentoWorkerService workerComPreempcao = new ReplanejamentoWorkerService(
                factory,
                capacidadePolicy -> {
                    chamadasExecutor.incrementAndGet();
                    primeiraExecucaoIniciada.countDown();
                    try {
                        liberarPrimeiraExecucao.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("executor interrompido", e);
                    }
                    return new PlanejamentoResultado(1, 1, 0);
                },
                new DispatchEventService(),
                pedidosPreempcao::incrementAndGet);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReplanejamentoWorkerResultado> primeiraRodada =
                    executor.submit(() -> workerComPreempcao.processarPendentes(0, 1));
            assertTrue(primeiraExecucaoIniciada.await(1, TimeUnit.SECONDS));

            Future<ReplanejamentoWorkerResultado> segundaRodada =
                    executor.submit(() -> workerComPreempcao.processarPendentes(0, 1));

            Thread.sleep(200);
            liberarPrimeiraExecucao.countDown();

            ReplanejamentoWorkerResultado primeira = primeiraRodada.get(3, TimeUnit.SECONDS);
            ReplanejamentoWorkerResultado segunda = segundaRodada.get(3, TimeUnit.SECONDS);

            assertTrue(primeira.replanejou());
            assertTrue(segunda.replanejou());
            assertEquals(1, primeira.eventosProcessados());
            assertEquals(1, segunda.eventosProcessados());
            assertEquals(2, chamadasExecutor.get());
            assertTrue(pedidosPreempcao.get() >= 1);
            assertEquals(2, contarProcessados());
        } finally {
            liberarPrimeiraExecucao.countDown();
            executor.shutdownNow();
        }
    }

    private void inserirEvento(String eventType, int secondsAgo) throws Exception {
        String sql = "INSERT INTO dispatch_events (event_type, aggregate_type, aggregate_id, payload, available_em) "
                + "VALUES (?, 'PEDIDO', 1, '{}'::jsonb, CURRENT_TIMESTAMP - (? * INTERVAL '1 second'))";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, eventType);
            stmt.setInt(2, secondsAgo);
            stmt.executeUpdate();
        }
    }

    private void inserirPedidoHardEmRisco() throws Exception {
        LocalTime agora = obterHorarioAtualDoBanco();
        inserirPedidoHard(agora.minusMinutes(30), agora.plusMinutes(5));
    }

    private void inserirPedidoHard(LocalTime janelaInicio, LocalTime janelaFim) throws Exception {
        int userId;
        int clienteId;

        try (Connection conn = factory.getConnection();
                PreparedStatement stmtUser = conn.prepareStatement(
                        "INSERT INTO users (nome, email, senha_hash, papel, ativo) VALUES (?, ?, ?, ?, true) RETURNING id");
                PreparedStatement stmtCliente = conn.prepareStatement(
                        "INSERT INTO clientes (nome, telefone, tipo, endereco) VALUES (?, ?, ?, ?) RETURNING id");
                PreparedStatement stmtPedido = conn.prepareStatement(
                        "INSERT INTO pedidos (cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            stmtUser.setString(1, "Atendente Worker");
            stmtUser.setString(2, "worker-hard-risco@teste.com");
            stmtUser.setString(3, "$2a$10$abcdefghijklmnopqrstuv");
            stmtUser.setObject(4, "atendente", Types.OTHER);
            try (ResultSet rs = stmtUser.executeQuery()) {
                rs.next();
                userId = rs.getInt("id");
            }

            stmtCliente.setString(1, "Cliente HARD");
            stmtCliente.setString(2, "38999997777");
            stmtCliente.setObject(3, "PF", Types.OTHER);
            stmtCliente.setString(4, "Rua Janela Hard");
            try (ResultSet rs = stmtCliente.executeQuery()) {
                rs.next();
                clienteId = rs.getInt("id");
            }

            stmtPedido.setInt(1, clienteId);
            stmtPedido.setInt(2, 1);
            stmtPedido.setObject(3, "HARD", Types.OTHER);
            stmtPedido.setTime(4, Time.valueOf(janelaInicio));
            stmtPedido.setTime(5, Time.valueOf(janelaFim));
            stmtPedido.setObject(6, "PENDENTE", Types.OTHER);
            stmtPedido.setInt(7, userId);
            stmtPedido.executeUpdate();
        }
    }

    private LocalTime obterHorarioAtualDoBanco() throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT CURRENT_TIME");
                ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getTime(1).toLocalTime();
        }
    }

    private int contarProcessados() throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM dispatch_events WHERE status::text = 'PROCESSADO'");
                ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int contarPendentes() throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT COUNT(*) FROM dispatch_events WHERE status::text = 'PENDENTE'");
                ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
