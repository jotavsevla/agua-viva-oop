package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class EventoOperacionalIdempotenciaService {

    private final ConnectionFactory connectionFactory;
    private final Gson gson = new Gson();

    public EventoOperacionalIdempotenciaService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public Resultado processar(
            String externalEventId,
            String requestHash,
            String eventType,
            String scopeType,
            long scopeId,
            Supplier<ExecucaoEntregaResultado> processamento) {
        validateText(externalEventId, "externalEventId");
        validateText(requestHash, "requestHash");
        validateText(eventType, "eventType");
        validateText(scopeType, "scopeType");
        if (externalEventId.length() > 128) {
            throw new IllegalArgumentException("externalEventId deve ter no maximo 128 caracteres");
        }
        if (requestHash.length() > 64) {
            throw new IllegalArgumentException("requestHash deve ter no maximo 64 caracteres");
        }
        if (scopeId <= 0) {
            throw new IllegalArgumentException("scopeId deve ser maior que zero");
        }
        Objects.requireNonNull(processamento, "Processamento nao pode ser nulo");

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertSchema(conn);
                lockPorExternalEventId(conn, externalEventId);

                Optional<RegistroExistente> existente = buscarPorExternalEventId(conn, externalEventId);
                if (existente.isPresent()) {
                    RegistroExistente registro = existente.get();
                    if (!requestHash.equals(registro.requestHash())) {
                        conn.commit();
                        return Resultado.conflito(
                                "externalEventId reutilizado com payload diferente: " + externalEventId);
                    }

                    ExecucaoEntregaResultado resposta = fromJson(registro.responseJson());
                    conn.commit();
                    return Resultado.sucesso(tornarIdempotente(resposta));
                }

                ExecucaoEntregaResultado resposta = processamento.get();
                inserirRegistro(conn, externalEventId, requestHash, eventType, scopeType, scopeId, resposta);
                conn.commit();
                return Resultado.sucesso(resposta);
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao processar idempotencia de evento operacional", e);
        }
    }

    public void assertSchema(Connection conn) throws SQLException {
        if (!hasTable(conn, "eventos_operacionais_idempotencia")) {
            throw new IllegalStateException("Schema desatualizado: tabela eventos_operacionais_idempotencia ausente");
        }
        if (!hasColumn(conn, "eventos_operacionais_idempotencia", "external_event_id")) {
            throw new IllegalStateException(
                    "Schema desatualizado: coluna eventos_operacionais_idempotencia.external_event_id ausente");
        }
        if (!hasColumn(conn, "eventos_operacionais_idempotencia", "request_hash")) {
            throw new IllegalStateException(
                    "Schema desatualizado: coluna eventos_operacionais_idempotencia.request_hash ausente");
        }
        if (!hasColumn(conn, "eventos_operacionais_idempotencia", "response_json")) {
            throw new IllegalStateException(
                    "Schema desatualizado: coluna eventos_operacionais_idempotencia.response_json ausente");
        }
    }

    private void lockPorExternalEventId(Connection conn, String externalEventId) throws SQLException {
        String sql = "SELECT pg_advisory_xact_lock(hashtext(?))";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalEventId);
            stmt.executeQuery();
        }
    }

    private Optional<RegistroExistente> buscarPorExternalEventId(Connection conn, String externalEventId)
            throws SQLException {
        String sql = "SELECT request_hash, response_json::text, status_code "
                + "FROM eventos_operacionais_idempotencia "
                + "WHERE external_event_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalEventId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new RegistroExistente(
                            rs.getString("request_hash"), rs.getString("response_json"), rs.getInt("status_code")));
                }
                return Optional.empty();
            }
        }
    }

    private void inserirRegistro(
            Connection conn,
            String externalEventId,
            String requestHash,
            String eventType,
            String scopeType,
            long scopeId,
            ExecucaoEntregaResultado resposta)
            throws SQLException {
        String sql = "INSERT INTO eventos_operacionais_idempotencia ("
                + "external_event_id, request_hash, event_type, scope_type, scope_id, response_json, status_code) "
                + "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalEventId);
            stmt.setString(2, requestHash);
            stmt.setString(3, eventType);
            stmt.setString(4, scopeType);
            stmt.setLong(5, scopeId);
            stmt.setString(6, gson.toJson(resposta));
            stmt.setInt(7, 200);
            stmt.executeUpdate();
        }
    }

    private ExecucaoEntregaResultado fromJson(String json) {
        ExecucaoEntregaResultado payload = gson.fromJson(json, ExecucaoEntregaResultado.class);
        if (payload == null) {
            throw new IllegalStateException("response_json invalido para evento operacional idempotente");
        }
        return payload;
    }

    private ExecucaoEntregaResultado tornarIdempotente(ExecucaoEntregaResultado payload) {
        return new ExecucaoEntregaResultado(
                payload.evento(), payload.rotaId(), payload.entregaId(), payload.pedidoId(), true);
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

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " obrigatorio");
        }
    }

    private record RegistroExistente(String requestHash, String responseJson, int statusCode) {}

    public record Resultado(ExecucaoEntregaResultado payload, boolean conflito, String erroConflito) {
        public static Resultado sucesso(ExecucaoEntregaResultado payload) {
            return new Resultado(Objects.requireNonNull(payload), false, null);
        }

        public static Resultado conflito(String erroConflito) {
            return new Resultado(null, true, Objects.requireNonNull(erroConflito));
        }
    }
}
