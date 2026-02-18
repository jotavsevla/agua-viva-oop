package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            stmt.execute("TRUNCATE TABLE dispatch_events RESTART IDENTITY");
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
