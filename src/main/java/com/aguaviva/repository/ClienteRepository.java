package com.aguaviva.repository;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.exception.DatabaseException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ClienteRepository {

    private final ConnectionFactory connectionFactory;

    public ClienteRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    // ========================================================================
    // Escrita
    // ========================================================================

    public Cliente save(Cliente cliente) {
        String sql = """
                INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude, notas)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (var conn = connectionFactory.getConnection();
                var stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, cliente.getNome());
            stmt.setString(2, cliente.getTelefone());
            setTipoParameter(stmt, 3, cliente.getTipo());
            stmt.setString(4, cliente.getEndereco());
            stmt.setBigDecimal(5, cliente.getLatitude());
            stmt.setBigDecimal(6, cliente.getLongitude());
            stmt.setString(7, cliente.getNotas());

            stmt.executeUpdate();

            try (var generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    int generatedId = generatedKeys.getInt(1);
                    return new Cliente(
                            generatedId,
                            cliente.getNome(),
                            cliente.getTelefone(),
                            cliente.getTipo(),
                            cliente.getEndereco(),
                            cliente.getLatitude(),
                            cliente.getLongitude(),
                            cliente.getNotas());
                }
            }
            throw new SQLException("Falha ao salvar cliente, nenhum ID gerado.");
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Telefone ja cadastrado: " + cliente.getTelefone());
            }
            throw new DatabaseException("Falha ao salvar cliente", e);
        }
    }

    public void update(Cliente cliente) {
        String sql = """
                UPDATE clientes
                SET nome = ?, telefone = ?, tipo = ?, endereco = ?, latitude = ?, longitude = ?, notas = ?,
                atualizado_em = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        try (var conn = connectionFactory.getConnection();
                var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, cliente.getNome());
            stmt.setString(2, cliente.getTelefone());
            setTipoParameter(stmt, 3, cliente.getTipo());
            stmt.setString(4, cliente.getEndereco());
            stmt.setBigDecimal(5, cliente.getLatitude());
            stmt.setBigDecimal(6, cliente.getLongitude());
            stmt.setString(7, cliente.getNotas());
            stmt.setInt(8, cliente.getId());

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Cliente nao encontrado com id: " + cliente.getId());
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Telefone ja cadastrado: " + cliente.getTelefone());
            }
            throw new DatabaseException("Falha ao atualizar cliente", e);
        }
    }

    // ========================================================================
    // Leitura
    // ========================================================================

    public Optional<Cliente> findById(int id) {
        String sql = "SELECT id, nome, telefone, tipo, endereco, latitude, longitude, notas FROM clientes WHERE id = ?";

        try {
            try (var conn = connectionFactory.getConnection();
                    var stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, id);

                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(toCliente(rs));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao buscar cliente por id", e);
        }
    }

    public Optional<Cliente> findByTelefone(String telefone) {
        String sql = """
                SELECT id, nome, telefone, tipo, endereco, latitude, longitude, notas
                FROM clientes WHERE telefone = ?
                """;

        try {
            try (var conn = connectionFactory.getConnection();
                    var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, telefone.trim());

                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(toCliente(rs));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao buscar cliente por telefone", e);
        }
    }

    public List<Cliente> findAll() {
        String sql = "SELECT id, nome, telefone, tipo, endereco, latitude, longitude, notas FROM clientes ORDER BY id";
        List<Cliente> clientes = new ArrayList<>();

        try {
            try (var conn = connectionFactory.getConnection();
                    var stmt = conn.prepareStatement(sql);
                    var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    clientes.add(toCliente(rs));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao listar clientes", e);
        }
        return clientes;
    }

    // ========================================================================
    // Mapeamento (privado)
    // ========================================================================

    private Cliente toCliente(ResultSet rs) throws SQLException {
        return new Cliente(
                rs.getInt("id"),
                rs.getString("nome"),
                rs.getString("telefone"),
                toTipo(rs.getString("tipo")),
                rs.getString("endereco"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                rs.getString("notas"));
    }

    private void setTipoParameter(PreparedStatement stmt, int index, ClienteTipo tipo) throws SQLException {
        stmt.setObject(index, tipo.name(), Types.OTHER);
    }

    private ClienteTipo toTipo(String dbValue) {
        return ClienteTipo.valueOf(dbValue.toUpperCase());
    }
}
