package com.aguaviva.repository;

import com.aguaviva.domain.pedido.JanelaTipo;
import com.aguaviva.domain.pedido.Pedido;
import com.aguaviva.domain.pedido.PedidoStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PedidoRepository {

    private final ConnectionFactory connectionFactory;

    public PedidoRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    // ========================================================================
    // Escrita
    // ========================================================================

    public Pedido save(Pedido pedido) throws SQLException {
        String sql = "INSERT INTO pedidos (cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setInt(1, pedido.getClienteId());
            stmt.setInt(2, pedido.getQuantidadeGaloes());
            setJanelaTipoParameter(stmt, 3, pedido.getJanelaTipo());
            stmt.setObject(4, pedido.getJanelaInicio());
            stmt.setObject(5, pedido.getJanelaFim());
            setPedidoStatusParameter(stmt, 6, pedido.getStatus());
            stmt.setInt(7, pedido.getCriadoPorUserId());

            stmt.executeUpdate();

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    int generatedId = generatedKeys.getInt(1);
                    return new Pedido(
                            generatedId,
                            pedido.getClienteId(),
                            pedido.getQuantidadeGaloes(),
                            pedido.getJanelaTipo(),
                            pedido.getJanelaInicio(),
                            pedido.getJanelaFim(),
                            pedido.getStatus(),
                            pedido.getCriadoPorUserId()
                    );
                }
            }
            throw new SQLException("Falha ao salvar pedido, nenhum ID gerado.");
        } catch (SQLException e) {
            if ("23503".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Cliente ou usuario criador nao encontrado");
            }
            throw e;
        }
    }

    public void update(Pedido pedido) throws SQLException {
        String sql = "UPDATE pedidos "
                + "SET cliente_id = ?, quantidade_galoes = ?, janela_tipo = ?, janela_inicio = ?, janela_fim = ?, status = ?, criado_por = ?, "
                + "atualizado_em = CURRENT_TIMESTAMP "
                + "WHERE id = ?";

        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, pedido.getClienteId());
            stmt.setInt(2, pedido.getQuantidadeGaloes());
            setJanelaTipoParameter(stmt, 3, pedido.getJanelaTipo());
            stmt.setObject(4, pedido.getJanelaInicio());
            stmt.setObject(5, pedido.getJanelaFim());
            setPedidoStatusParameter(stmt, 6, pedido.getStatus());
            stmt.setInt(7, pedido.getCriadoPorUserId());
            stmt.setInt(8, pedido.getId());

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Pedido nao encontrado com id: " + pedido.getId());
            }
        } catch (SQLException e) {
            if ("23503".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Cliente ou usuario criador nao encontrado");
            }
            throw e;
        }
    }

    // ========================================================================
    // Leitura
    // ========================================================================

    public Optional<Pedido> findById(int id) throws SQLException {
        String sql = "SELECT id, cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por "
                + "FROM pedidos WHERE id = ?";

        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(toPedido(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<Pedido> findByCliente(int clienteId) throws SQLException {
        String sql = "SELECT id, cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por "
                + "FROM pedidos WHERE cliente_id = ? ORDER BY criado_em DESC, id DESC";
        List<Pedido> pedidos = new ArrayList<>();

        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, clienteId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    pedidos.add(toPedido(rs));
                }
            }
        }
        return pedidos;
    }

    public List<Pedido> findPendentes() throws SQLException {
        String sql = "SELECT id, cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por "
                + "FROM pedidos WHERE status = 'PENDENTE' ORDER BY criado_em, id";
        List<Pedido> pedidos = new ArrayList<>();

        try (Connection conn = connectionFactory.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                pedidos.add(toPedido(rs));
            }
        }
        return pedidos;
    }

    // ========================================================================
    // Mapeamento (privado)
    // ========================================================================

    private Pedido toPedido(ResultSet rs) throws SQLException {
        return new Pedido(
                rs.getInt("id"),
                rs.getInt("cliente_id"),
                rs.getInt("quantidade_galoes"),
                toJanelaTipo(rs.getString("janela_tipo")),
                rs.getObject("janela_inicio", LocalTime.class),
                rs.getObject("janela_fim", LocalTime.class),
                toPedidoStatus(rs.getString("status")),
                rs.getInt("criado_por")
        );
    }

    private void setJanelaTipoParameter(PreparedStatement stmt, int index, JanelaTipo janelaTipo) throws SQLException {
        stmt.setObject(index, janelaTipo.name(), Types.OTHER);
    }

    private void setPedidoStatusParameter(PreparedStatement stmt, int index, PedidoStatus status) throws SQLException {
        stmt.setObject(index, status.name(), Types.OTHER);
    }

    private JanelaTipo toJanelaTipo(String dbValue) {
        return JanelaTipo.valueOf(dbValue.toUpperCase());
    }

    private PedidoStatus toPedidoStatus(String dbValue) {
        return PedidoStatus.valueOf(dbValue.toUpperCase());
    }
}
