package com.aguaviva.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public final class Database {

    private final ConnectionFactory connectionFactory;

    public Database(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public boolean isHealthy() {
        try {
            return query("SELECT 1", rs -> rs.next() && rs.getInt(1) == 1);
        } catch (SQLException e) {
            return false;
        }
    }

    public <T> T query(String sql, SqlResultSetMapper<T> mapper) throws SQLException {
        return query(sql, stmt -> {}, mapper);
    }

    public <T> T query(String sql, SqlPreparedStatementBinder binder, SqlResultSetMapper<T> mapper)
            throws SQLException {
        Objects.requireNonNull(sql, "sql nao pode ser nulo");
        Objects.requireNonNull(binder, "binder nao pode ser nulo");
        Objects.requireNonNull(mapper, "mapper nao pode ser nulo");

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                return mapper.map(rs);
            }
        }
    }

    public int execute(String sql) throws SQLException {
        return execute(sql, stmt -> {});
    }

    public int execute(String sql, SqlPreparedStatementBinder binder) throws SQLException {
        Objects.requireNonNull(sql, "sql nao pode ser nulo");
        Objects.requireNonNull(binder, "binder nao pode ser nulo");

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            return stmt.executeUpdate();
        }
    }

    @FunctionalInterface
    public interface SqlPreparedStatementBinder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlResultSetMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
