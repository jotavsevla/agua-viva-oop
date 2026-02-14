package com.aguaviva.service;

import com.aguaviva.domain.pedido.PedidoStateMachine;
import com.aguaviva.domain.pedido.PedidoStatus;
import com.aguaviva.domain.pedido.PedidoTransitionResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * Porta unica de transicao de status de pedido no service layer.
 * Aplica lock pessimista do pedido e persiste efeitos da transicao.
 */
public class PedidoLifecycleService {

    public PedidoTransitionResult transicionar(Connection conn, int pedidoId, PedidoStatus statusDestino) throws SQLException {
        return transicionar(conn, pedidoId, statusDestino, TransitionContext.vazio());
    }

    public PedidoTransitionResult transicionar(Connection conn, int pedidoId, PedidoStatus statusDestino,
                                               TransitionContext context) throws SQLException {
        Objects.requireNonNull(conn, "Connection nao pode ser nula");
        Objects.requireNonNull(statusDestino, "Status destino nao pode ser nulo");

        if (pedidoId <= 0) {
            throw new IllegalArgumentException("PedidoId deve ser maior que zero");
        }

        TransitionContext safeContext = context == null ? TransitionContext.vazio() : context;

        PedidoStatus statusAtual = buscarStatusAtualComLock(conn, pedidoId);
        PedidoTransitionResult transition = PedidoStateMachine.transicionar(statusAtual, statusDestino);

        if (statusDestino == PedidoStatus.CANCELADO && hasCancelamentoColumns(conn)) {
            persistirCancelamento(conn, pedidoId, statusDestino, transition, safeContext);
            return transition;
        }

        persistirStatus(conn, pedidoId, statusDestino);
        return transition;
    }

    private PedidoStatus buscarStatusAtualComLock(Connection conn, int pedidoId) throws SQLException {
        String sql = "SELECT status::text FROM pedidos WHERE id = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Pedido nao encontrado com id: " + pedidoId);
                }
                return PedidoStatus.valueOf(rs.getString(1).toUpperCase());
            }
        }
    }

    private void persistirStatus(Connection conn, int pedidoId, PedidoStatus statusDestino) throws SQLException {
        String sql = "UPDATE pedidos SET status = ?, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, statusDestino.name(), Types.OTHER);
            stmt.setInt(2, pedidoId);
            int updated = stmt.executeUpdate();
            if (updated != 1) {
                throw new SQLException("Falha ao atualizar status do pedido " + pedidoId);
            }
        }
    }

    private void persistirCancelamento(Connection conn, int pedidoId, PedidoStatus statusDestino,
                                       PedidoTransitionResult transition, TransitionContext context) throws SQLException {
        CobrancaCancelamento cobranca = CobrancaCancelamento.from(transition, context);

        String sql = "UPDATE pedidos SET "
                + "status = ?, "
                + "atualizado_em = CURRENT_TIMESTAMP, "
                + "cancelado_em = CURRENT_TIMESTAMP, "
                + "motivo_cancelamento = ?, "
                + "cobranca_cancelamento_centavos = ?, "
                + "cobranca_status = ? "
                + "WHERE id = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, statusDestino.name(), Types.OTHER);
            stmt.setString(2, context.motivoCancelamento());
            stmt.setInt(3, cobranca.valorCentavos());
            stmt.setObject(4, cobranca.status(), Types.OTHER);
            stmt.setInt(5, pedidoId);
            int updated = stmt.executeUpdate();
            if (updated != 1) {
                throw new SQLException("Falha ao atualizar cancelamento do pedido " + pedidoId);
            }
        }
    }

    private boolean hasCancelamentoColumns(Connection conn) throws SQLException {
        return hasColumn(conn, "pedidos", "cancelado_em")
                && hasColumn(conn, "pedidos", "motivo_cancelamento")
                && hasColumn(conn, "pedidos", "cobranca_cancelamento_centavos")
                && hasColumn(conn, "pedidos", "cobranca_status");
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record CobrancaCancelamento(int valorCentavos, String status) {
        private static CobrancaCancelamento from(PedidoTransitionResult transition, TransitionContext context) {
            int valor = context.cobrancaCancelamentoCentavos() == null ? 0 : context.cobrancaCancelamentoCentavos();
            if (valor < 0) {
                throw new IllegalArgumentException("Cobranca de cancelamento nao pode ser negativa");
            }

            if (!transition.geraCobrancaCancelamento()) {
                return new CobrancaCancelamento(0, "NAO_APLICAVEL");
            }

            if (valor == 0) {
                return new CobrancaCancelamento(0, "NAO_APLICAVEL");
            }

            return new CobrancaCancelamento(valor, "PENDENTE");
        }
    }

    public record TransitionContext(String motivoCancelamento, Integer cobrancaCancelamentoCentavos) {
        public static TransitionContext vazio() {
            return new TransitionContext(null, null);
        }
    }
}
