package com.aguaviva.repository;

import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserRepository {

    private final ConnectionFactory connectionFactory;

    public UserRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    // ========================================================================
    // Escrita
    // ========================================================================

    public User save(User user) throws SQLException {
        String sql = "INSERT INTO users (nome, email, senha_hash, papel, telefone, ativo) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, user.getNome());
            stmt.setString(2, user.getEmail());
            stmt.setString(3, user.toSenhaHash());
            setPapelParameter(stmt, 4, user.getPapel());
            stmt.setString(5, user.getTelefone());
            stmt.setBoolean(6, user.isAtivo());

            stmt.executeUpdate();

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    int generatedId = generatedKeys.getInt(1);
                    return new User(
                            generatedId,
                            user.getNome(),
                            user.getEmail(),
                            Password.fromHash(user.toSenhaHash()),
                            user.getPapel(),
                            user.getTelefone(),
                            user.isAtivo());
                }
            }
            throw new SQLException("Falha ao salvar usuario, nenhum ID gerado.");
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Email ja cadastrado: " + user.getEmail());
            }
            throw e;
        }
    }

    public void update(User user) throws SQLException {
        String sql =
                "UPDATE users SET nome = ?, email = ?, senha_hash = ?, papel = ?, telefone = ?, ativo = ?, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?";

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, user.getNome());
            stmt.setString(2, user.getEmail());
            stmt.setString(3, user.toSenhaHash());
            setPapelParameter(stmt, 4, user.getPapel());
            stmt.setString(5, user.getTelefone());
            stmt.setBoolean(6, user.isAtivo());
            stmt.setInt(7, user.getId());

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Usuario nao encontrado com id: " + user.getId());
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Email ja cadastrado: " + user.getEmail());
            }
            throw e;
        }
    }

    public boolean desativar(int id) throws SQLException {
        String sql = "UPDATE users SET ativo = false, atualizado_em = CURRENT_TIMESTAMP WHERE id = ? AND ativo = true";

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        }
    }

    // ========================================================================
    // Leitura
    // ========================================================================

    public Optional<User> findById(int id) throws SQLException {
        String sql = "SELECT id, nome, email, senha_hash, papel, telefone, ativo FROM users WHERE id = ?";

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(toUser(rs));
                }
                return Optional.empty();
            }
        }
    }

    public Optional<User> findByEmail(String email) throws SQLException {
        String sql = "SELECT id, nome, email, senha_hash, papel, telefone, ativo FROM users WHERE email = ?";

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, email.trim().toLowerCase());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(toUser(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<User> findAll() throws SQLException {
        String sql = "SELECT id, nome, email, senha_hash, papel, telefone, ativo FROM users ORDER BY id";
        List<User> users = new ArrayList<>();

        try (Connection conn = connectionFactory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                users.add(toUser(rs));
            }
        }
        return users;
    }

    // ========================================================================
    // Mapeamento (privado)
    // ========================================================================

    private User toUser(ResultSet rs) throws SQLException {
        return new User(
                rs.getInt("id"),
                rs.getString("nome"),
                rs.getString("email"),
                Password.fromHash(rs.getString("senha_hash")),
                toPapel(rs.getString("papel")),
                rs.getString("telefone"),
                rs.getBoolean("ativo"));
    }

    private void setPapelParameter(PreparedStatement stmt, int index, UserPapel papel) throws SQLException {
        stmt.setObject(index, papel.name().toLowerCase(), Types.OTHER);
    }

    private UserPapel toPapel(String dbValue) {
        return UserPapel.valueOf(dbValue.toUpperCase());
    }
}
