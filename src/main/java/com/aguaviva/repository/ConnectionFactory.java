package com.aguaviva.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cdimascio.dotenv.Dotenv;
import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionFactory {

    private final HikariDataSource dataSource;

    public ConnectionFactory() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String host = dotenv.get("POSTGRES_HOST", "localhost");
        String port = dotenv.get("POSTGRES_PORT", "5434");
        String db = dotenv.get("POSTGRES_DB", "agua_viva_oop_dev");
        String user = dotenv.get("POSTGRES_USER", "postgres");
        String password = dotenv.get("POSTGRES_PASSWORD", "postgres");

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(5000);

        this.dataSource = new HikariDataSource(config);
    }

    public ConnectionFactory(String host, String port, String db, String user, String password) {
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(5000);

        this.dataSource = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
