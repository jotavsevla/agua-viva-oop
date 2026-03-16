package com.aguaviva.repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ExecucaoEntregaRepository {

    private static final String TIPO_MOVIMENTACAO_DEBITO = "DEBITO";

    public ExecucaoEntregaRepository(ConnectionFactory connectionFactory) {
        Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public RotaStatus buscarRotaComLock(Connection conn, int rotaId) throws SQLException {
        String sql = "SELECT id, status::text, entregador_id FROM rotas WHERE id = ? FOR UPDATE";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rotaId);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Rota nao encontrada com id: " + rotaId);
                }
                return new RotaStatus(rs.getInt("id"), rs.getString("status"), rs.getInt("entregador_id"));
            }
        }
    }

    public boolean existeRotaEmAndamento(Connection conn, int entregadorId) throws SQLException {
        String sql = """
                SELECT 1 FROM rotas
                WHERE entregador_id = ?
                AND data = CURRENT_DATE
                AND status::text = 'EM_ANDAMENTO'
                LIMIT 1 FOR UPDATE
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Integer buscarProximaRotaPlanejada(Connection conn, int entregadorId) throws SQLException {
        String sql = """
                SELECT id FROM rotas
                WHERE entregador_id = ?
                AND data = CURRENT_DATE
                AND status::text = 'PLANEJADA'
                ORDER BY numero_no_dia, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getInt("id");
            }
        }
    }

    public void atualizarRotaParaEmAndamento(Connection conn, int rotaId) throws SQLException {
        String sql = "UPDATE rotas SET status = ?, inicio = COALESCE(inicio, CURRENT_TIMESTAMP) WHERE id = ?";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, "EM_ANDAMENTO", Types.OTHER);
            stmt.setInt(2, rotaId);
            stmt.executeUpdate();
        }
    }

    public List<EntregaPedidoRef> buscarEntregasPendentesDaRota(Connection conn, int rotaId) throws SQLException {
        String sql =
                "SELECT id, pedido_id FROM entregas WHERE rota_id = ? AND status::text = 'PENDENTE' ORDER BY ordem_na_rota";
        List<EntregaPedidoRef> result = new ArrayList<>();
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rotaId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new EntregaPedidoRef(rs.getInt("id"), rs.getInt("pedido_id")));
                }
            }
        }
        return result;
    }

    public EntregaComPedido buscarEntregaComLock(Connection conn, int entregaId) throws SQLException {
        String sql = """
                SELECT e.id AS entrega_id,
                e.status::text AS entrega_status,
                e.rota_id,
                p.id AS pedido_id,
                p.cliente_id,
                p.quantidade_galoes,
                p.metodo_pagamento::text AS metodo_pagamento,
                r.entregador_id
                FROM entregas e
                JOIN pedidos p ON p.id = e.pedido_id
                JOIN rotas r ON r.id = e.rota_id
                WHERE e.id = ?
                FOR UPDATE OF e, p, r
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregaId);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entrega nao encontrada com id: " + entregaId);
                }
                return new EntregaComPedido(
                        rs.getInt("entrega_id"),
                        rs.getString("entrega_status"),
                        rs.getInt("rota_id"),
                        rs.getInt("pedido_id"),
                        rs.getInt("cliente_id"),
                        rs.getInt("quantidade_galoes"),
                        rs.getString("metodo_pagamento"),
                        rs.getInt("entregador_id"));
            }
        }
    }

    public void atualizarStatusEntrega(Connection conn, int entregaId, String status, boolean setHoraReal)
            throws SQLException {
        String sql = setHoraReal
                ? "UPDATE entregas SET status = ?, hora_real = CURRENT_TIMESTAMP, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?"
                : "UPDATE entregas SET status = ?, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, status, Types.OTHER);
            stmt.setInt(2, entregaId);
            stmt.executeUpdate();
        }
    }

    public boolean atualizarRotaParaConcluidaSeCabivel(Connection conn, int rotaId) throws SQLException {
        String sql = """
                SELECT COUNT(*) FILTER (WHERE status::text IN ('PENDENTE', 'EM_EXECUCAO')) AS abertas
                FROM entregas WHERE rota_id = ?
                """;
        int abertas;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rotaId);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                abertas = rs.getInt("abertas");
            }
        }

        if (abertas != 0) {
            return false;
        }

        try (var stmt = conn.prepareStatement(
                "UPDATE rotas SET status = ?, fim = COALESCE(fim, CURRENT_TIMESTAMP) WHERE id = ?")) {
            stmt.setObject(1, "CONCLUIDA", Types.OTHER);
            stmt.setInt(2, rotaId);
            stmt.executeUpdate();
        }
        return true;
    }

    public boolean registrarDebitoValeSeAusente(Connection conn, EntregaComPedido entrega) throws SQLException {
        String sql = """
                INSERT INTO movimentacao_vales
                (cliente_id, tipo, quantidade, pedido_id, registrado_por, observacao)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entrega.clienteId());
            stmt.setObject(2, TIPO_MOVIMENTACAO_DEBITO, Types.OTHER);
            stmt.setInt(3, entrega.quantidadeGaloes());
            stmt.setInt(4, entrega.pedidoId());
            stmt.setInt(5, entrega.entregadorId());
            stmt.setString(6, "Debito automatico na entrega do pedido " + entrega.pedidoId());
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int debitarSaldoVale(Connection conn, int clienteId, int quantidade) throws SQLException {
        String sql = """
                UPDATE saldo_vales
                SET quantidade = quantidade - ?, atualizado_em = CURRENT_TIMESTAMP
                WHERE cliente_id = ? AND quantidade >= ?
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, quantidade);
            stmt.setInt(2, clienteId);
            stmt.setInt(3, quantidade);
            return stmt.executeUpdate();
        }
    }

    public boolean hasEnumValue(Connection conn, String typeName, String enumLabel) throws SQLException {
        String sql = """
                SELECT 1
                FROM pg_type t
                JOIN pg_enum e ON e.enumtypid = t.oid
                WHERE t.typname = ? AND e.enumlabel = ?
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, typeName);
            stmt.setString(2, enumLabel);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public record RotaStatus(int id, String status, int entregadorId) {}

    public record EntregaPedidoRef(int entregaId, int pedidoId) {}

    public record EntregaComPedido(
            int idEntrega,
            String statusEntrega,
            int rotaId,
            int pedidoId,
            int clienteId,
            int quantidadeGaloes,
            String metodoPagamento,
            int entregadorId) {}
}
