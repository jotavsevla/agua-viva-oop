package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public class PedidoExecucaoService {

    private final ConnectionFactory connectionFactory;

    public PedidoExecucaoService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public PedidoExecucaoResultado consultarExecucao(int pedidoId) {
        if (pedidoId <= 0) {
            throw new IllegalArgumentException("pedidoId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            String statusPedido = buscarStatusPedido(conn, pedidoId);
            ExecucaoRef execucao = buscarExecucaoAtual(conn, pedidoId);
            if (execucao == null) {
                return new PedidoExecucaoResultado(pedidoId, null, null, statusPedido, "SEM_ENTREGA", null, null);
            }

            Integer rotaPrimariaId = "EM_ANDAMENTO".equals(execucao.rotaStatus()) ? execucao.rotaId() : null;
            Integer entregaAtivaId = "EM_EXECUCAO".equals(execucao.entregaStatus()) ? execucao.entregaId() : null;
            String camada = resolverCamada(execucao.rotaStatus(), execucao.entregaStatus());
            return new PedidoExecucaoResultado(
                    pedidoId,
                    rotaPrimariaId,
                    entregaAtivaId,
                    statusPedido,
                    camada,
                    execucao.rotaId(),
                    execucao.entregaId());
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar execucao do pedido", e);
        }
    }

    private String buscarStatusPedido(Connection conn, int pedidoId) throws SQLException {
        String sql = "SELECT status::text FROM pedidos WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Pedido nao encontrado com id: " + pedidoId);
                }
                return rs.getString("status");
            }
        }
    }

    private ExecucaoRef buscarExecucaoAtual(Connection conn, int pedidoId) throws SQLException {
        String sql = "SELECT e.id AS entrega_id, "
                + "e.status::text AS entrega_status, "
                + "r.id AS rota_id, "
                + "r.status::text AS rota_status "
                + "FROM entregas e "
                + "JOIN rotas r ON r.id = e.rota_id "
                + "WHERE e.pedido_id = ? "
                + "ORDER BY "
                + "CASE "
                + "WHEN e.status::text = 'EM_EXECUCAO' THEN 0 "
                + "WHEN e.status::text = 'PENDENTE' THEN 1 "
                + "ELSE 2 "
                + "END, "
                + "CASE "
                + "WHEN r.status::text = 'EM_ANDAMENTO' THEN 0 "
                + "WHEN r.status::text = 'PLANEJADA' THEN 1 "
                + "ELSE 2 "
                + "END, "
                + "e.id DESC "
                + "LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ExecucaoRef(
                        rs.getInt("rota_id"),
                        rs.getString("rota_status"),
                        rs.getInt("entrega_id"),
                        rs.getString("entrega_status"));
            }
        }
    }

    private static String resolverCamada(String rotaStatus, String entregaStatus) {
        if ("EM_EXECUCAO".equals(entregaStatus)) {
            return "PRIMARIA_EM_EXECUCAO";
        }
        if ("PENDENTE".equals(entregaStatus) && "EM_ANDAMENTO".equals(rotaStatus)) {
            return "PRIMARIA_PENDENTE";
        }
        if ("PENDENTE".equals(entregaStatus) && "PLANEJADA".equals(rotaStatus)) {
            return "SECUNDARIA_CONFIRMADA";
        }
        return "FINALIZADA";
    }

    private record ExecucaoRef(int rotaId, String rotaStatus, int entregaId, String entregaStatus) {}

    public record PedidoExecucaoResultado(
            int pedidoId,
            Integer rotaPrimariaId,
            Integer entregaAtivaId,
            String statusPedido,
            String camada,
            Integer rotaId,
            Integer entregaId) {}
}
