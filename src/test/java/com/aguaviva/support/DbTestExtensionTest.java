package com.aguaviva.support;

import static org.junit.jupiter.api.Assertions.*;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("integration")
@ExtendWith(DbTestExtension.class)
class DbTestExtensionTest {

    @Test
    void deveInjetarConnectionFactoryEConnection(ConnectionFactory factory, Connection connection) throws Exception {
        assertNotNull(factory);
        assertNotNull(connection);
        assertFalse(connection.getAutoCommit());
    }
}
