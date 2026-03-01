package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.support.TestConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class OperacaoEventosServiceTest {

    private static ConnectionFactory factory;
    private static OperacaoEventosService service;

    @BeforeAll
    static void setUp() throws Exception {
        factory = TestConnectionFactory.newConnectionFactory();
        service = new OperacaoEventosService(factory);
        garantirSchemaDispatch();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparAntes() throws Exception {
        limparEventos();
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparEventos();
    }

    @Test
    void deveListarEventosOrdenadosPorRecenciaERespeitarLimite() throws Exception {
        long eventoAntigo = inserirEvento(
                "PEDIDO_CRIADO",
                "PENDENTE",
                "PEDIDO",
                101L,
                "{\"v\":1}",
                LocalDateTime.now().minusMinutes(2),
                null);
        long eventoRecente = inserirEvento(
                "PEDIDO_ENTREGUE",
                "PROCESSADO",
                "ENTREGA",
                202L,
                "{\"v\":2}",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().minusSeconds(30));

        OperacaoEventosService.OperacaoEventosResultado resultado = service.listarEventos(1);

        assertEquals(1, resultado.eventos().size());
        OperacaoEventosService.EventoOperacional evento = resultado.eventos().getFirst();
        assertEquals(eventoRecente, evento.id());
        assertEquals("PEDIDO_ENTREGUE", evento.eventType());
        assertEquals("PROCESSADO", evento.status());
        assertEquals("ENTREGA", evento.aggregateType());
        assertEquals(202L, evento.aggregateId());

        OperacaoEventosService.OperacaoEventosResultado resultadoSemLimite = service.listarEventos(null);
        assertEquals(2, resultadoSemLimite.eventos().size());
        assertEquals(eventoRecente, resultadoSemLimite.eventos().get(0).id());
        assertEquals(eventoAntigo, resultadoSemLimite.eventos().get(1).id());
    }

    @Test
    void deveValidarLimiteSolicitado() {
        IllegalArgumentException limiteZero =
                assertThrows(IllegalArgumentException.class, () -> service.listarEventos(0));
        assertTrue(limiteZero.getMessage().contains("maior que zero"));

        IllegalArgumentException limiteAcimaMaximo =
                assertThrows(IllegalArgumentException.class, () -> service.listarEventos(201));
        assertTrue(limiteAcimaMaximo.getMessage().contains("200"));
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
            stmt.execute("TRUNCATE TABLE dispatch_events RESTART IDENTITY CASCADE");
        }
    }

    private long inserirEvento(
            String eventType,
            String status,
            String aggregateType,
            Long aggregateId,
            String payloadJson,
            LocalDateTime createdEm,
            LocalDateTime processedEm)
            throws Exception {
        String sql = "INSERT INTO dispatch_events (event_type, status, aggregate_type, aggregate_id, payload, created_em, available_em, processed_em) "
                + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?) RETURNING id";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, eventType);
            stmt.setObject(2, status, Types.OTHER);
            stmt.setString(3, aggregateType);
            if (aggregateId == null) {
                stmt.setNull(4, Types.BIGINT);
            } else {
                stmt.setLong(4, aggregateId);
            }
            stmt.setString(5, payloadJson);
            stmt.setTimestamp(6, Timestamp.valueOf(createdEm));
            stmt.setTimestamp(7, Timestamp.valueOf(createdEm));
            if (processedEm == null) {
                stmt.setNull(8, Types.TIMESTAMP);
            } else {
                stmt.setTimestamp(8, Timestamp.valueOf(processedEm));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
