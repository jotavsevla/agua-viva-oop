package com.aguaviva;

import com.aguaviva.api.ApiServer;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.Database;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    private static final Logger LOGGER = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        if (args.length > 0 && "api".equalsIgnoreCase(args[0])) {
            try {
                ApiServer.startFromEnv();
                // Mantem o processo vivo em modo servidor.
                Thread.currentThread().join();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Falha ao iniciar API", e);
                System.exit(1);
                return;
            }
        }

        LOGGER.info("=== Agua Viva OOP ===");
        LOGGER.info("Health check: conectando ao PostgreSQL...");

        ConnectionFactory factory = new ConnectionFactory();
        Database database = new Database(factory);

        try {
            if (!database.isHealthy()) {
                LOGGER.severe("Falha na conexao: banco indisponivel");
                System.exit(1);
                return;
            }

            String version = database.query("SELECT version()", rs -> {
                if (!rs.next()) {
                    return "desconhecida";
                }
                return rs.getString(1);
            });
            LOGGER.info("Conectado com sucesso!");
            LOGGER.info("PostgreSQL: " + version);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Falha na conexao", e);
            System.exit(1);
        } finally {
            factory.close();
        }
    }
}
