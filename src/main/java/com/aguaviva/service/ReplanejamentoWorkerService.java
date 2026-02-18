package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public class ReplanejamentoWorkerService {

    private static final long ADVISORY_LOCK_KEY = 114_011L;

    private final ConnectionFactory connectionFactory;
    private final Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor;
    private final DispatchEventService dispatchEventService;

    public ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory, Supplier<PlanejamentoResultado> replanejamentoExecutor) {
        this(
                connectionFactory,
                capacidadePolicy -> Objects.requireNonNull(replanejamentoExecutor, "ReplanejamentoExecutor nao pode ser nulo")
                        .get(),
                new DispatchEventService());
    }

    public ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory,
            Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor) {
        this(connectionFactory, replanejamentoExecutor, new DispatchEventService());
    }

    ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory,
            Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor,
            DispatchEventService dispatchEventService) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.replanejamentoExecutor =
                Objects.requireNonNull(replanejamentoExecutor, "ReplanejamentoExecutor nao pode ser nulo");
        this.dispatchEventService =
                Objects.requireNonNull(dispatchEventService, "DispatchEventService nao pode ser nulo");
    }

    public ReplanejamentoWorkerResultado processarPendentes(int debounceSegundos, int limiteEventos) {
        if (debounceSegundos < 0) {
            throw new IllegalArgumentException("debounceSegundos nao pode ser negativo");
        }
        if (limiteEventos <= 0) {
            throw new IllegalArgumentException("limiteEventos deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                dispatchEventService.assertSchema(conn);

                if (!tryAcquireWorkerLock(conn)) {
                    conn.commit();
                    return new ReplanejamentoWorkerResultado(0, false, 0, 0, 0);
                }

                List<DispatchEventRef> eventos =
                        buscarEventosPendentesComDebounce(conn, debounceSegundos, limiteEventos);
                if (eventos.isEmpty()) {
                    conn.commit();
                    return new ReplanejamentoWorkerResultado(0, false, 0, 0, 0);
                }

                ReplanejamentoPolicyMatrix.ReplanejamentoEventPolicy politicaLote = ReplanejamentoPolicyMatrix.consolidate(
                        eventos.stream().map(DispatchEventRef::eventType).toList());
                boolean deveReplanejar = politicaLote.replaneja();

                PlanejamentoResultado planejamento = new PlanejamentoResultado(0, 0, 0);
                if (deveReplanejar) {
                    planejamento = replanejamentoExecutor.apply(politicaLote.capacidadePolicy());
                }

                marcarEventosProcessados(conn, eventos);
                conn.commit();

                return new ReplanejamentoWorkerResultado(
                        eventos.size(),
                        deveReplanejar,
                        planejamento.rotasCriadas(),
                        planejamento.entregasCriadas(),
                        planejamento.pedidosNaoAtendidos());
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao processar worker de replanejamento", e);
        }
    }

    private boolean tryAcquireWorkerLock(Connection conn) throws SQLException {
        String sql = "SELECT pg_try_advisory_xact_lock(?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private List<DispatchEventRef> buscarEventosPendentesComDebounce(
            Connection conn, int debounceSegundos, int limiteEventos) throws SQLException {
        String sql = "SELECT id, event_type "
                + "FROM dispatch_events "
                + "WHERE status = 'PENDENTE' "
                + "AND available_em <= (CURRENT_TIMESTAMP - (? * INTERVAL '1 second')) "
                + "ORDER BY created_em, id "
                + "LIMIT ? "
                + "FOR UPDATE SKIP LOCKED";

        List<DispatchEventRef> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, debounceSegundos);
            stmt.setInt(2, limiteEventos);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new DispatchEventRef(rs.getLong("id"), rs.getString("event_type")));
                }
            }
        }
        return result;
    }

    private void marcarEventosProcessados(Connection conn, List<DispatchEventRef> eventos) throws SQLException {
        if (eventos.isEmpty()) {
            return;
        }

        String sql =
                "UPDATE dispatch_events SET status = 'PROCESSADO', processed_em = CURRENT_TIMESTAMP WHERE id = ANY (?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            Object[] ids = eventos.stream().map(DispatchEventRef::id).toArray();
            Array sqlArray = conn.createArrayOf("bigint", ids);
            stmt.setArray(1, sqlArray);
            stmt.executeUpdate();
        }
    }

    private record DispatchEventRef(long id, String eventType) {}
}
