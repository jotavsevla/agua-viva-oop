package com.aguaviva.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TransactionRunnerTest {

    @Test
    void inTransactionDeveCommitarENotificarResultadoNoSucesso() {
        TrackingConnection tracking = TrackingConnection.create();
        TransactionRunner runner = new TransactionRunner(() -> tracking.connection());
        AtomicReference<Connection> connRecebida = new AtomicReference<>();

        String result = runner.inTransaction(conn -> {
            connRecebida.set(conn);
            return "ok";
        });

        assertEquals("ok", result);
        assertSame(tracking.connection(), connRecebida.get());
        assertTrue(tracking.commitCalled);
        assertFalse(tracking.rollbackCalled);
        assertTrue(tracking.closeCalled);
        assertEquals(List.of(false, true), tracking.autoCommitValues);
    }

    @Test
    void inTransactionDeveRollbackQuandoWorkLancaExcecao() {
        TrackingConnection tracking = TrackingConnection.create();
        TransactionRunner runner = new TransactionRunner(() -> tracking.connection());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> runner.inTransaction(conn -> {
            throw new RuntimeException("falhou");
        }));

        assertEquals("falhou", ex.getMessage());
        assertFalse(tracking.commitCalled);
        assertTrue(tracking.rollbackCalled);
        assertTrue(tracking.closeCalled);
        assertEquals(List.of(false, true), tracking.autoCommitValues);
    }

    @Test
    void inTransactionVoidDeveCommitarNoSucesso() {
        TrackingConnection tracking = TrackingConnection.create();
        TransactionRunner runner = new TransactionRunner(() -> tracking.connection());
        AtomicReference<Connection> connRecebida = new AtomicReference<>();

        runner.inTransactionVoid(conn -> connRecebida.set(conn));

        assertSame(tracking.connection(), connRecebida.get());
        assertTrue(tracking.commitCalled);
        assertFalse(tracking.rollbackCalled);
        assertTrue(tracking.closeCalled);
        assertEquals(List.of(false, true), tracking.autoCommitValues);
    }

    @Test
    void inTransactionVoidDeveRollbackQuandoWorkLancaExcecao() {
        TrackingConnection tracking = TrackingConnection.create();
        TransactionRunner runner = new TransactionRunner(() -> tracking.connection());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> runner.inTransactionVoid(conn -> {
                    throw new IllegalArgumentException("erro no consumer");
                }));

        assertEquals("erro no consumer", ex.getMessage());
        assertFalse(tracking.commitCalled);
        assertTrue(tracking.rollbackCalled);
        assertTrue(tracking.closeCalled);
        assertEquals(List.of(false, true), tracking.autoCommitValues);
    }

    @Test
    void inTransactionDeveEncapsularSQLExceptionDoGetConnection() {
        SQLException sqlException = new SQLException("db indisponivel");
        TransactionRunner runner = new TransactionRunner(() -> {
            throw sqlException;
        });

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> runner.inTransaction(conn -> "nao executa"));

        assertEquals("Falha na transacao", ex.getMessage());
        assertSame(sqlException, ex.getCause());
    }

    @Test
    void inTransactionDeveRestaurarAutoCommitMesmoAposRollback() {
        TrackingConnection tracking = TrackingConnection.create();
        TransactionRunner runner = new TransactionRunner(() -> tracking.connection());

        assertThrows(RuntimeException.class, () -> runner.inTransaction(conn -> {
            throw new RuntimeException("rollback");
        }));

        assertEquals(List.of(false, true), tracking.autoCommitValues);
        assertTrue(tracking.rollbackCalled);
    }

    private static final class TrackingConnection implements InvocationHandler {

        private final List<Boolean> autoCommitValues = new ArrayList<>();
        private boolean commitCalled;
        private boolean rollbackCalled;
        private boolean closeCalled;
        private Connection connection;

        static TrackingConnection create() {
            TrackingConnection tracking = new TrackingConnection();
            Connection proxy = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, tracking);
            tracking.connection = proxy;
            return tracking;
        }

        Connection connection() {
            return connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("setAutoCommit".equals(name)) {
                autoCommitValues.add((Boolean) args[0]);
                return null;
            }
            if ("commit".equals(name)) {
                commitCalled = true;
                return null;
            }
            if ("rollback".equals(name)) {
                rollbackCalled = true;
                return null;
            }
            if ("close".equals(name)) {
                closeCalled = true;
                return null;
            }
            if ("toString".equals(name)) {
                return "TrackingConnection";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method.getReturnType());
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0F;
            }
            if (returnType == double.class) {
                return 0D;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return null;
        }
    }
}
