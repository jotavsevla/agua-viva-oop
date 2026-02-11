package com.aguaviva;

import com.aguaviva.repository.ConnectionFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class App {

    public static void main(String[] args) {
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
