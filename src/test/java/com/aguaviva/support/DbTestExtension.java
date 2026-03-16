package com.aguaviva.support;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

public class DbTestExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(DbTestExtension.class);
    private static final String KEY_FACTORY = "factory";
    private static final String KEY_CONNECTION = "connection";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        ConnectionFactory factory = TestConnectionFactory.newConnectionFactory();
        Connection connection = factory.getConnection();
        connection.setAutoCommit(false);

        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.put(KEY_FACTORY, factory);
        store.put(KEY_CONNECTION, connection);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        Connection connection = store.remove(KEY_CONNECTION, Connection.class);
        ConnectionFactory factory = store.remove(KEY_FACTORY, ConnectionFactory.class);

        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.rollback();
                }
            } finally {
                connection.close();
            }
        }

        if (factory != null) {
            factory.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> parameterType = parameterContext.getParameter().getType();
        return parameterType == Connection.class || parameterType == ConnectionFactory.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
        Class<?> parameterType = parameterContext.getParameter().getType();
        if (parameterType == Connection.class) {
            return store.get(KEY_CONNECTION, Connection.class);
        }
        return store.get(KEY_FACTORY, ConnectionFactory.class);
    }
}
