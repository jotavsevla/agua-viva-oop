package com.aguaviva.repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class TransactionRunner {

    private final ConnectionSupplier connectionSupplier;

    public TransactionRunner(ConnectionFactory connectionFactory) {
        Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.connectionSupplier = connectionFactory::getConnection;
    }

    TransactionRunner(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = Objects.requireNonNull(connectionSupplier, "connectionSupplier nao pode ser nulo");
    }

    public <T> T inTransaction(TransactionalFunction<T> work) {
        Objects.requireNonNull(work, "work nao pode ser nulo");

        try (var conn = connectionSupplier.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (RuntimeException | Error e) {
                conn.rollback();
                throw e;
            } catch (Exception e) {
                conn.rollback();
                throw new IllegalStateException("Falha na transacao", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha na transacao", e);
        }
    }

    public void inTransactionVoid(TransactionalConsumer work) {
        Objects.requireNonNull(work, "work nao pode ser nulo");
        inTransaction(conn -> {
            work.accept(conn);
            return null;
        });
    }

    @FunctionalInterface
    public static interface TransactionalFunction<T> {
        T apply(Connection conn) throws Exception;
    }

    @FunctionalInterface
    public static interface TransactionalConsumer {
        void accept(Connection conn) throws Exception;
    }

    @FunctionalInterface
    interface ConnectionSupplier {
        Connection getConnection() throws SQLException;
    }
}
