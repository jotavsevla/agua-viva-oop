package com.aguaviva;

import com.aguaviva.api.ApiServer;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.Database;

public class App {

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
                System.err.println("Falha ao iniciar API: " + e.getMessage());
                System.exit(1);
                return;
            }
        }

        System.out.println("=== Agua Viva OOP ===");
        System.out.println("Health check: conectando ao PostgreSQL...");

        ConnectionFactory factory = new ConnectionFactory();
        Database database = new Database(factory);

        try {
            if (!database.isHealthy()) {
                System.err.println("Falha na conexao: banco indisponivel");
                System.exit(1);
                return;
            }

            String version = database.query("SELECT version()", rs -> {
                if (!rs.next()) {
                    return "desconhecida";
                }
                return rs.getString(1);
            });
            System.out.println("Conectado com sucesso!");
            System.out.println("PostgreSQL: " + version);

        } catch (Exception e) {
            System.err.println("Falha na conexao: " + e.getMessage());
            System.exit(1);
        } finally {
            factory.close();
        }
    }
}
