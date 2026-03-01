package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.solver.SolverRequest;
import com.aguaviva.solver.SolverResponse;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

final class RotaSolverJobSupport {

    private RotaSolverJobSupport() {}

    static boolean hasPlanVersionColumns(Connection conn) throws SQLException {
        return hasColumn(conn, "rotas", "plan_version") && hasColumn(conn, "entregas", "plan_version");
    }

    static boolean hasJobIdColumns(Connection conn) throws SQLException {
        return hasColumn(conn, "rotas", "job_id") && hasColumn(conn, "entregas", "job_id");
    }

    static long nextPlanVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SEQUENCE IF NOT EXISTS solver_plan_version_seq START WITH 1 INCREMENT BY 1");
        }

        try (PreparedStatement stmt = conn.prepareStatement("SELECT nextval('solver_plan_version_seq')");
                ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("Falha ao obter nextval de solver_plan_version_seq");
            }
            return rs.getLong(1);
        }
    }

    static boolean hasSolverJobsSchema(Connection conn) throws SQLException {
        return hasTable(conn, "solver_jobs")
                && hasColumn(conn, "solver_jobs", "job_id")
                && hasColumn(conn, "solver_jobs", "status")
                && hasColumn(conn, "solver_jobs", "cancel_requested");
    }

    static boolean isCancelamentoSolicitadoNoBanco(Connection conn, String jobId) throws SQLException {
        String sql = "SELECT cancel_requested, status::text FROM solver_jobs WHERE job_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, jobId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                boolean cancelRequested = rs.getBoolean("cancel_requested");
                String status = rs.getString("status");
                return cancelRequested || "CANCELADO".equals(status);
            }
        }
    }

    static List<String> marcarCancelamentoSolicitadoEmJobsAtivos(Connection conn, int limite) throws SQLException {
        String selectSql = "SELECT job_id FROM solver_jobs "
                + "WHERE status::text IN ('PENDENTE', 'EM_EXECUCAO') "
                + "AND cancel_requested = false "
                + "ORDER BY solicitado_em DESC "
                + "LIMIT ?";

        List<String> jobIds = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setInt(1, Math.max(1, limite));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    jobIds.add(rs.getString("job_id"));
                }
            }
        }

        if (jobIds.isEmpty()) {
            return jobIds;
        }

        String updateSql = "UPDATE solver_jobs "
                + "SET cancel_requested = true, status = ?, finalizado_em = CURRENT_TIMESTAMP "
                + "WHERE job_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            for (String jobId : jobIds) {
                stmt.setObject(1, "CANCELADO", Types.OTHER);
                stmt.setString(2, jobId);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }

        return jobIds;
    }

    static void registrarSolverJobEmExecucao(
            ConnectionFactory connectionFactory, Gson gson, String jobId, long planVersion, SolverRequest request)
            throws SQLException {
        try (Connection conn = connectionFactory.getConnection()) {
            if (!hasSolverJobsSchema(conn)) {
                return;
            }
            boolean hasRequestPayload = hasColumn(conn, "solver_jobs", "request_payload");
            String requestPayload = gson.toJson(request);
            String sql =
                    "INSERT INTO solver_jobs (job_id, plan_version, status, cancel_requested, solicitado_em, iniciado_em, finalizado_em, erro) "
                            + "VALUES (?, ?, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL) "
                            + "ON CONFLICT (job_id) DO UPDATE SET "
                            + "plan_version = EXCLUDED.plan_version, "
                            + "status = EXCLUDED.status, "
                            + "cancel_requested = false, "
                            + "solicitado_em = CURRENT_TIMESTAMP, "
                            + "iniciado_em = CURRENT_TIMESTAMP, "
                            + "finalizado_em = NULL, "
                            + "erro = NULL";
            if (hasRequestPayload) {
                sql =
                        "INSERT INTO solver_jobs (job_id, plan_version, status, cancel_requested, solicitado_em, iniciado_em, finalizado_em, erro, request_payload) "
                                + "VALUES (?, ?, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, CAST(? AS jsonb)) "
                                + "ON CONFLICT (job_id) DO UPDATE SET "
                                + "plan_version = EXCLUDED.plan_version, "
                                + "status = EXCLUDED.status, "
                                + "cancel_requested = false, "
                                + "solicitado_em = CURRENT_TIMESTAMP, "
                                + "iniciado_em = CURRENT_TIMESTAMP, "
                                + "finalizado_em = NULL, "
                                + "erro = NULL, "
                                + "request_payload = EXCLUDED.request_payload";
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, jobId);
                stmt.setLong(2, planVersion);
                stmt.setObject(3, "EM_EXECUCAO", Types.OTHER);
                if (hasRequestPayload) {
                    stmt.setString(4, requestPayload);
                }
                stmt.executeUpdate();
            }
        }
    }

    static void finalizarSolverJob(
            ConnectionFactory connectionFactory, Gson gson, String jobId, String status, String erro, SolverResponse response) {
        try (Connection conn = connectionFactory.getConnection()) {
            if (!hasSolverJobsSchema(conn)) {
                return;
            }
            boolean hasResponsePayload = hasColumn(conn, "solver_jobs", "response_payload");
            String responsePayload = response == null ? null : gson.toJson(response);
            String sql = "UPDATE solver_jobs "
                    + "SET status = ?, finalizado_em = CURRENT_TIMESTAMP, erro = ?, "
                    + "cancel_requested = CASE WHEN ? = 'CANCELADO' THEN true ELSE cancel_requested END "
                    + "WHERE job_id = ?";
            if (hasResponsePayload) {
                sql = "UPDATE solver_jobs "
                        + "SET status = ?, finalizado_em = CURRENT_TIMESTAMP, erro = ?, "
                        + "cancel_requested = CASE WHEN ? = 'CANCELADO' THEN true ELSE cancel_requested END, "
                        + "response_payload = CASE WHEN ? IS NULL THEN NULL ELSE CAST(? AS jsonb) END "
                        + "WHERE job_id = ?";
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, status, Types.OTHER);
                stmt.setString(2, erro);
                stmt.setString(3, status);
                if (hasResponsePayload) {
                    stmt.setString(4, responsePayload);
                    stmt.setString(5, responsePayload);
                    stmt.setString(6, jobId);
                } else {
                    stmt.setString(4, jobId);
                }
                stmt.executeUpdate();
            }
        } catch (Exception ignored) {
            // Melhor esforco para trilha operacional de jobs.
        }
    }

    private static boolean hasTable(Connection conn, String tabela) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tabela);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasColumn(Connection conn, String tabela, String coluna) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tabela);
            stmt.setString(2, coluna);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }
}
