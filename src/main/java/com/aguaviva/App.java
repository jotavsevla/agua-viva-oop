package com.aguaviva;

import com.aguaviva.api.ApiServer;
import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

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

        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT version()")) {

            if (rs.next()) {
                System.out.println("Conectado com sucesso!");
                System.out.println("PostgreSQL: " + rs.getString(1));
            }

        } catch (Exception e) {
            System.err.println("Falha na conexao: " + e.getMessage());
            System.exit(1);
        } finally {
            factory.close();
        }
    }
}
