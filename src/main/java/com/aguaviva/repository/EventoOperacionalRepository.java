package com.aguaviva.repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class EventoOperacionalRepository {

    public EventoOperacionalRepository(ConnectionFactory connectionFactory) {
        Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public void lockPorExternalEventId(Connection conn, String externalEventId) throws SQLException {
        String sql = "SELECT pg_advisory_xact_lock(hashtext(?))";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalEventId);
            stmt.executeQuery();
        }
    }

    public Optional<RegistroExistente> buscarPorExternalEventId(Connection conn, String externalEventId)
            throws SQLException {
        String sql = """
                SELECT request_hash, response_json::text, status_code
                FROM eventos_operacionais_idempotencia
                WHERE external_event_id = ?
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalEventId);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new RegistroExistente(
                            rs.getString("request_hash"), rs.getString("response_json"), rs.getInt("status_code")));
                }
                return Optional.empty();
            }
        }
    }

    public void inserirRegistro(
            Connection conn,
            String externalEventId,
            String requestHash,
            String eventType,
            String scopeType,
            long scopeId,
            String responseJson,
            int statusCode)
            throws SQLException {
        String sql = """
                INSERT INTO eventos_operacionais_idempotencia (
                external_event_id, request_hash, event_type, scope_type, scope_id, response_json, status_code)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalEventId);
            stmt.setString(2, requestHash);
            stmt.setString(3, eventType);
            stmt.setString(4, scopeType);
            stmt.setLong(5, scopeId);
            stmt.setString(6, responseJson);
            stmt.setInt(7, statusCode);
            stmt.executeUpdate();
        }
    }

    public boolean hasTable(Connection conn, String table) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
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

    public record RegistroExistente(String requestHash, String responseJson, int statusCode) {}
}
