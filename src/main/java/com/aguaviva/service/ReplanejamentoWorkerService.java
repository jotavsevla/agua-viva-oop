package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public class ReplanejamentoWorkerService {

    private static final long ADVISORY_LOCK_KEY = 114_011L;
    // Em cenarios com solver frio/lento, o lock pode ficar ocupado por varios segundos.
    // Mantemos retentativa mais longa para evitar deixar eventos pendentes sem processamento.
    private static final int MAX_TENTATIVAS_LOCK_OCUPADO = 60;
    private static final long RETRY_LOCK_SLEEP_MS = 75L;
    private static final long RETRY_LOCK_SLEEP_MAX_MS = 500L;
    private static final int HARD_WINDOW_RISCO_HORIZONTE_MINUTOS = 30;

    private final ConnectionFactory connectionFactory;
    private final Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor;
    private final DispatchEventService dispatchEventService;
    private final Runnable onWorkerLockBusy;

    public ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory, Supplier<PlanejamentoResultado> replanejamentoExecutor) {
        this(
                connectionFactory,
                capacidadePolicy -> Objects.requireNonNull(
                                replanejamentoExecutor, "ReplanejamentoExecutor nao pode ser nulo")
                        .get(),
                new DispatchEventService(),
                () -> {});
    }

    public ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory,
            Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor) {
        this(connectionFactory, replanejamentoExecutor, new DispatchEventService(), () -> {});
    }

    public ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory,
            Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor,
            Runnable onWorkerLockBusy) {
        this(connectionFactory, replanejamentoExecutor, new DispatchEventService(), onWorkerLockBusy);
    }

    ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory,
            Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor,
            DispatchEventService dispatchEventService) {
        this(connectionFactory, replanejamentoExecutor, dispatchEventService, () -> {});
    }

    ReplanejamentoWorkerService(
            ConnectionFactory connectionFactory,
            Function<CapacidadePolicy, PlanejamentoResultado> replanejamentoExecutor,
            DispatchEventService dispatchEventService,
            Runnable onWorkerLockBusy) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.replanejamentoExecutor =
                Objects.requireNonNull(replanejamentoExecutor, "ReplanejamentoExecutor nao pode ser nulo");
        this.dispatchEventService =
                Objects.requireNonNull(dispatchEventService, "DispatchEventService nao pode ser nulo");
        this.onWorkerLockBusy = Objects.requireNonNull(onWorkerLockBusy, "onWorkerLockBusy nao pode ser nulo");
    }

    public ReplanejamentoWorkerResultado processarPendentes(int debounceSegundos, int limiteEventos) {
        if (debounceSegundos < 0) {
            throw new IllegalArgumentException("debounceSegundos nao pode ser negativo");
        }
        if (limiteEventos <= 0) {
            throw new IllegalArgumentException("limiteEventos deve ser maior que zero");
        }

        boolean preempcaoSolicitada = false;
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_LOCK_OCUPADO; tentativa++) {
            WorkerAttempt tentativaWorker = processarUmaTentativa(debounceSegundos, limiteEventos);
            if (!tentativaWorker.lockOcupado()) {
                return tentativaWorker.resultado();
            }

            if (!preempcaoSolicitada) {
                onWorkerLockBusy.run();
                preempcaoSolicitada = true;
            }

            if (tentativa == MAX_TENTATIVAS_LOCK_OCUPADO) {
                break;
            }

            if (!aguardarRetryLock(tentativa)) {
                break;
            }
        }

        return new ReplanejamentoWorkerResultado(0, false, 0, 0, 0);
    }

    public boolean existePedidoHardEmRisco() {
        try (Connection conn = connectionFactory.getConnection()) {
            LocalTime referencia = obterHorarioAtualDoBanco(conn);
            return existePedidoHardEmRisco(conn, referencia, HARD_WINDOW_RISCO_HORIZONTE_MINUTOS);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar risco de janela HARD", e);
        }
    }

    boolean existePedidoHardEmRisco(LocalTime referencia, int horizonteMinutos) {
        Objects.requireNonNull(referencia, "referencia nao pode ser nula");
        try (Connection conn = connectionFactory.getConnection()) {
            return existePedidoHardEmRisco(conn, referencia, horizonteMinutos);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar risco de janela HARD", e);
        }
    }

    private WorkerAttempt processarUmaTentativa(int debounceSegundos, int limiteEventos) {
        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                dispatchEventService.assertSchema(conn);

                if (!tryAcquireWorkerLock(conn)) {
                    conn.commit();
                    return WorkerAttempt.lockBusy();
                }

                List<DispatchEventRef> eventos =
                        buscarEventosPendentesComDebounce(conn, debounceSegundos, limiteEventos);
                LocalTime referencia = obterHorarioAtualDoBanco(conn);
                boolean hardWindowEmRisco =
                        existePedidoHardEmRisco(conn, referencia, HARD_WINDOW_RISCO_HORIZONTE_MINUTOS);
                if (eventos.isEmpty() && !hardWindowEmRisco) {
                    conn.commit();
                    return WorkerAttempt.withResult(new ReplanejamentoWorkerResultado(0, false, 0, 0, 0));
                }

                ReplanejamentoPolicyMatrix.ReplanejamentoEventPolicy politicaLote =
                        ReplanejamentoPolicyMatrix.consolidate(eventos.stream()
                                .map(DispatchEventRef::eventType)
                                .toList());
                boolean deveReplanejar = hardWindowEmRisco || politicaLote.replaneja();
                CapacidadePolicy capacidadePolicy =
                        hardWindowEmRisco ? CapacidadePolicy.REMANESCENTE : politicaLote.capacidadePolicy();

                PlanejamentoResultado planejamento = new PlanejamentoResultado(0, 0, 0);
                if (deveReplanejar) {
                    planejamento = replanejamentoExecutor.apply(capacidadePolicy);
                }

                marcarEventosProcessados(conn, eventos);
                conn.commit();

                return WorkerAttempt.withResult(new ReplanejamentoWorkerResultado(
                        eventos.size(),
                        deveReplanejar,
                        planejamento.rotasCriadas(),
                        planejamento.entregasCriadas(),
                        planejamento.pedidosNaoAtendidos()));
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

    private boolean aguardarRetryLock(int tentativa) {
        try {
            long backoff = RETRY_LOCK_SLEEP_MS * Math.max(1L, tentativa);
            Thread.sleep(Math.min(backoff, RETRY_LOCK_SLEEP_MAX_MS));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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

    private boolean existePedidoHardEmRisco(Connection conn, LocalTime referencia, int horizonteMinutos)
            throws SQLException {
        if (horizonteMinutos < 0) {
            throw new IllegalArgumentException("horizonteMinutos nao pode ser negativo");
        }
        Objects.requireNonNull(referencia, "referencia nao pode ser nula");
        if (!hasTable(conn, "pedidos")
                || !hasTable(conn, "entregas")
                || !hasColumn(conn, "pedidos", "janela_tipo")
                || !hasColumn(conn, "pedidos", "janela_fim")
                || !hasColumn(conn, "pedidos", "status")
                || !hasColumn(conn, "entregas", "pedido_id")
                || !hasColumn(conn, "entregas", "status")) {
            return false;
        }

        String sql = "SELECT 1 "
                + "FROM pedidos p "
                + "WHERE p.status::text IN ('PENDENTE', 'CONFIRMADO') "
                + "AND p.janela_tipo::text = 'HARD' "
                + "AND p.janela_fim IS NOT NULL "
                + "AND MOD((EXTRACT(EPOCH FROM p.janela_fim) - EXTRACT(EPOCH FROM CAST(? AS TIME)) + 86400), 86400) <= (? * 60) "
                + "AND NOT EXISTS ("
                + "    SELECT 1 FROM entregas e "
                + "    WHERE e.pedido_id = p.id "
                + "    AND e.status::text IN ('PENDENTE', 'EM_EXECUCAO')"
                + ") "
                + "LIMIT 1";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTime(1, Time.valueOf(referencia));
            stmt.setInt(2, horizonteMinutos);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private LocalTime obterHorarioAtualDoBanco(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT CURRENT_TIME");
                ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("Falha ao obter horario atual do banco");
            }
            return rs.getTime(1).toLocalTime();
        }
    }

    private boolean hasTable(Connection conn, String table) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record WorkerAttempt(boolean lockOcupado, ReplanejamentoWorkerResultado resultado) {
        static WorkerAttempt lockBusy() {
            return new WorkerAttempt(true, null);
        }

        static WorkerAttempt withResult(ReplanejamentoWorkerResultado resultado) {
            return new WorkerAttempt(false, resultado);
        }
    }

    private record DispatchEventRef(long id, String eventType) {}
}
