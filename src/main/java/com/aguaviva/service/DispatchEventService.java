package com.aguaviva.service;

import com.google.gson.Gson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

public class DispatchEventService {

    private final Gson gson = new Gson();

    public long publicar(Connection conn, String eventType, String aggregateType, Long aggregateId, Object payload)
            throws SQLException {
        Objects.requireNonNull(conn, "Connection nao pode ser nula");
        validateText(eventType, "eventType");
        validateText(aggregateType, "aggregateType");
        assertSchema(conn);

        String payloadJson = payload == null ? "{}" : gson.toJson(payload);

        String sql = "INSERT INTO dispatch_events (event_type, aggregate_type, aggregate_id, payload) "
                + "VALUES (?, ?, ?, CAST(? AS jsonb)) RETURNING id";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, eventType);
            stmt.setString(2, aggregateType);
            if (aggregateId == null) {
                stmt.setNull(3, Types.BIGINT);
            } else {
                stmt.setLong(3, aggregateId);
            }
            stmt.setString(4, payloadJson);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

        throw new SQLException("Falha ao inserir evento no outbox");
    }

    public void assertSchema(Connection conn) throws SQLException {
        if (!hasTable(conn, "dispatch_events")) {
            throw new IllegalStateException("Schema desatualizado: tabela dispatch_events ausente");
        }
        if (!hasColumn(conn, "dispatch_events", "status")) {
            throw new IllegalStateException("Schema desatualizado: coluna dispatch_events.status ausente");
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

    private static void validateText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " nao pode ser nulo ou vazio");
        }
    }
}
