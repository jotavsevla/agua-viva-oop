package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OperacaoReplanejamentoService {

    private static final int LIMITE_PADRAO = 50;
    private static final int LIMITE_MAXIMO = 200;

    private final ConnectionFactory connectionFactory;

    public OperacaoReplanejamentoService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public OperacaoReplanejamentoResultado listarJobs(Integer limiteSolicitado) {
        int limite = limiteSolicitado == null ? LIMITE_PADRAO : limiteSolicitado;
        if (limite <= 0) {
            throw new IllegalArgumentException("limite deve ser maior que zero");
        }
        if (limite > LIMITE_MAXIMO) {
            throw new IllegalArgumentException("limite maximo permitido e 200");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            String ambiente = resolverAmbiente(conn);
            if (!hasSolverJobsSchema(conn)) {
                return new OperacaoReplanejamentoResultado(LocalDateTime.now().toString(), ambiente, false, List.of());
            }

            boolean hasRequestPayload = hasColumn(conn, "solver_jobs", "request_payload");
            boolean hasResponsePayload = hasColumn(conn, "solver_jobs", "response_payload");
            String sql = "SELECT job_id, plan_version, status::text AS status, cancel_requested, "
                    + "solicitado_em, iniciado_em, finalizado_em, erro, "
                    + (hasRequestPayload
                            ? "CASE WHEN request_payload IS NOT NULL THEN true ELSE false END AS has_request_payload, "
                            : "false AS has_request_payload, ")
                    + (hasResponsePayload
                            ? "CASE WHEN response_payload IS NOT NULL THEN true ELSE false END AS has_response_payload "
                            : "false AS has_response_payload ")
                    + "FROM solver_jobs "
                    + "ORDER BY solicitado_em DESC, job_id DESC "
                    + "LIMIT ?";

            List<SolverJobResumo> jobs = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limite);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        LocalDateTime iniciadoEm = rs.getObject("iniciado_em", LocalDateTime.class);
                        LocalDateTime finalizadoEm = rs.getObject("finalizado_em", LocalDateTime.class);
                        jobs.add(new SolverJobResumo(
                                rs.getString("job_id"),
                                rs.getLong("plan_version"),
                                rs.getString("status"),
                                rs.getBoolean("cancel_requested"),
                                rs.getObject("solicitado_em", LocalDateTime.class)
                                        .toString(),
                                iniciadoEm == null ? null : iniciadoEm.toString(),
                                finalizadoEm == null ? null : finalizadoEm.toString(),
                                rs.getString("erro"),
                                rs.getBoolean("has_request_payload"),
                                rs.getBoolean("has_response_payload")));
                    }
                }
            }

            return new OperacaoReplanejamentoResultado(LocalDateTime.now().toString(), ambiente, true, jobs);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar jobs de replanejamento", e);
        }
    }

    public OperacaoReplanejamentoJobDetalheResultado detalharJob(String jobIdSolicitado) {
        if (jobIdSolicitado == null || jobIdSolicitado.isBlank()) {
            throw new IllegalArgumentException("jobId obrigatorio");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            String ambiente = resolverAmbiente(conn);
            if (!hasSolverJobsSchema(conn)) {
                throw new IllegalArgumentException("feed de replanejamento indisponivel no schema atual");
            }

            boolean hasRequestPayload = hasColumn(conn, "solver_jobs", "request_payload");
            boolean hasResponsePayload = hasColumn(conn, "solver_jobs", "response_payload");
            SolverJobDetalhe job = buscarJobDetalhado(conn, jobIdSolicitado, hasRequestPayload, hasResponsePayload);

            return new OperacaoReplanejamentoJobDetalheResultado(
                    LocalDateTime.now().toString(), ambiente, true, job);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar job de replanejamento", e);
        }
    }

    private SolverJobDetalhe buscarJobDetalhado(
            Connection conn, String jobIdSolicitado, boolean hasRequestPayload, boolean hasResponsePayload)
            throws SQLException {
        String sql = "SELECT job_id, plan_version, status::text AS status, cancel_requested, "
                + "solicitado_em, iniciado_em, finalizado_em, erro, "
                + (hasRequestPayload
                        ? "CASE WHEN request_payload IS NOT NULL THEN true ELSE false END AS has_request_payload, "
                        : "false AS has_request_payload, ")
                + (hasResponsePayload
                        ? "CASE WHEN response_payload IS NOT NULL THEN true ELSE false END AS has_response_payload "
                        : "false AS has_response_payload ")
                + "FROM solver_jobs WHERE job_id = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, jobIdSolicitado);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("jobId nao encontrado: " + jobIdSolicitado);
                }

                long planVersion = rs.getLong("plan_version");
                LocalDateTime iniciadoEm = rs.getObject("iniciado_em", LocalDateTime.class);
                LocalDateTime finalizadoEm = rs.getObject("finalizado_em", LocalDateTime.class);

                List<RotaImpactadaResumo> rotasImpactadas = List.of();
                List<PedidoImpactadoResumo> pedidosImpactados = List.of();
                if (hasPlanVersionColumns(conn)) {
                    rotasImpactadas = buscarRotasImpactadas(conn, planVersion);
                    pedidosImpactados = buscarPedidosImpactados(conn, planVersion);
                }

                return new SolverJobDetalhe(
                        rs.getString("job_id"),
                        planVersion,
                        rs.getString("status"),
                        rs.getBoolean("cancel_requested"),
                        rs.getObject("solicitado_em", LocalDateTime.class).toString(),
                        iniciadoEm == null ? null : iniciadoEm.toString(),
                        finalizadoEm == null ? null : finalizadoEm.toString(),
                        rs.getString("erro"),
                        rs.getBoolean("has_request_payload"),
                        rs.getBoolean("has_response_payload"),
                        rotasImpactadas,
                        pedidosImpactados);
            }
        }
    }

    private List<RotaImpactadaResumo> buscarRotasImpactadas(Connection conn, long planVersion) throws SQLException {
        String sql = "SELECT r.id AS rota_id, r.entregador_id, r.status::text AS status_rota, "
                + "CASE WHEN r.status::text = 'EM_ANDAMENTO' THEN 'PRIMARIA' ELSE 'SECUNDARIA' END AS camada, "
                + "COUNT(e.id) AS total_entregas "
                + "FROM rotas r "
                + "LEFT JOIN entregas e ON e.rota_id = r.id "
                + "WHERE r.plan_version = ? "
                + "GROUP BY r.id, r.entregador_id, r.status "
                + "ORDER BY r.id DESC";
        List<RotaImpactadaResumo> rotas = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, planVersion);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rotas.add(new RotaImpactadaResumo(
                            rs.getInt("rota_id"),
                            rs.getInt("entregador_id"),
                            rs.getString("status_rota"),
                            rs.getString("camada"),
                            rs.getInt("total_entregas")));
                }
            }
        }
        return rotas;
    }

    private List<PedidoImpactadoResumo> buscarPedidosImpactados(Connection conn, long planVersion) throws SQLException {
        String sql = "SELECT p.id AS pedido_id, e.id AS entrega_id, r.id AS rota_id, "
                + "p.status::text AS status_pedido, e.status::text AS status_entrega "
                + "FROM entregas e "
                + "JOIN pedidos p ON p.id = e.pedido_id "
                + "JOIN rotas r ON r.id = e.rota_id "
                + "WHERE e.plan_version = ? "
                + "ORDER BY e.id DESC";
        List<PedidoImpactadoResumo> pedidos = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, planVersion);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    pedidos.add(new PedidoImpactadoResumo(
                            rs.getInt("pedido_id"),
                            rs.getInt("entrega_id"),
                            rs.getInt("rota_id"),
                            rs.getString("status_pedido"),
                            rs.getString("status_entrega")));
                }
            }
        }
        return pedidos;
    }

    private String resolverAmbiente(Connection conn) throws SQLException {
        String sql = "SELECT current_database()";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            rs.next();
            String db = rs.getString(1);
            if (db != null && db.contains("_test")) {
                return "test";
            }
            if (db != null && db.contains("_dev")) {
                return "dev";
            }
            return db == null || db.isBlank() ? "desconhecido" : db;
        }
    }

    private boolean hasSolverJobsSchema(Connection conn) throws SQLException {
        return hasTable(conn, "solver_jobs")
                && hasColumn(conn, "solver_jobs", "job_id")
                && hasColumn(conn, "solver_jobs", "status")
                && hasColumn(conn, "solver_jobs", "cancel_requested")
                && hasColumn(conn, "solver_jobs", "solicitado_em");
    }

    private boolean hasPlanVersionColumns(Connection conn) throws SQLException {
        return hasColumn(conn, "rotas", "plan_version") && hasColumn(conn, "entregas", "plan_version");
    }

    private boolean hasTable(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasColumn(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            stmt.setString(2, columnName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public record OperacaoReplanejamentoResultado(
            String atualizadoEm, String ambiente, boolean habilitado, List<SolverJobResumo> jobs) {
        public OperacaoReplanejamentoResultado {
            jobs = List.copyOf(jobs);
        }
    }

    public record SolverJobResumo(
            String jobId,
            long planVersion,
            String status,
            boolean cancelRequested,
            String solicitadoEm,
            String iniciadoEm,
            String finalizadoEm,
            String erro,
            boolean hasRequestPayload,
            boolean hasResponsePayload) {}

    public record OperacaoReplanejamentoJobDetalheResultado(
            String atualizadoEm, String ambiente, boolean habilitado, SolverJobDetalhe job) {}

    public record SolverJobDetalhe(
            String jobId,
            long planVersion,
            String status,
            boolean cancelRequested,
            String solicitadoEm,
            String iniciadoEm,
            String finalizadoEm,
            String erro,
            boolean hasRequestPayload,
            boolean hasResponsePayload,
            List<RotaImpactadaResumo> rotasImpactadas,
            List<PedidoImpactadoResumo> pedidosImpactados) {
        public SolverJobDetalhe {
            rotasImpactadas = List.copyOf(rotasImpactadas);
            pedidosImpactados = List.copyOf(pedidosImpactados);
        }
    }

    public record RotaImpactadaResumo(
            int rotaId, int entregadorId, String statusRota, String camada, int totalEntregas) {}

    public record PedidoImpactadoResumo(
            int pedidoId, int entregaId, int rotaId, String statusPedido, String statusEntrega) {}
}
