package com.aguaviva.repository;

import com.aguaviva.domain.exception.DatabaseException;
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
        } catch (DatabaseException e) {
            return false;
        }
    }

    public <T> T query(String sql, SqlResultSetMapper<T> mapper) {
        return query(sql, stmt -> {}, mapper);
    }

    public <T> T query(String sql, SqlPreparedStatementBinder binder, SqlResultSetMapper<T> mapper) {
        Objects.requireNonNull(sql, "sql nao pode ser nulo");
        Objects.requireNonNull(binder, "binder nao pode ser nulo");
        Objects.requireNonNull(mapper, "mapper nao pode ser nulo");

        try {
            try (var conn = connectionFactory.getConnection();
                    var stmt = conn.prepareStatement(sql)) {
                binder.bind(stmt);
                try (var rs = stmt.executeQuery()) {
                    return mapper.map(rs);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao executar query no banco de dados", e);
        }
    }

    public int execute(String sql) {
        return execute(sql, stmt -> {});
    }

    public int execute(String sql, SqlPreparedStatementBinder binder) {
        Objects.requireNonNull(sql, "sql nao pode ser nulo");
        Objects.requireNonNull(binder, "binder nao pode ser nulo");

        try {
            try (var conn = connectionFactory.getConnection();
                    var stmt = conn.prepareStatement(sql)) {
                binder.bind(stmt);
                return stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao executar comando no banco de dados", e);
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
