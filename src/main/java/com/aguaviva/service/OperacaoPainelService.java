package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OperacaoPainelService {

    private static final int LIMITE_LISTAS = 200;

    private final ConnectionFactory connectionFactory;

    public OperacaoPainelService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public OperacaoPainelResultado consultarPainel() {
        try (Connection conn = connectionFactory.getConnection()) {
            String ambiente = resolverAmbiente(conn);
            PedidosPorStatus pedidosPorStatus = consultarPedidosPorStatus(conn);
            RotasResumo rotas = new RotasResumo(consultarRotasEmAndamento(conn), consultarRotasPlanejadas(conn));
            FilasResumo filas = new FilasResumo(
                    consultarPendentesElegiveis(conn),
                    consultarConfirmadosSecundaria(conn),
                    consultarEmRotaPrimaria(conn));

            return new OperacaoPainelResultado(
                    LocalDateTime.now().toString(), ambiente, pedidosPorStatus, rotas, filas);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar painel operacional", e);
        }
    }

    private String resolverAmbiente(Connection conn) throws SQLException {
        String sql = "SELECT current_database()";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            rs.next();
            String db = rs.getString(1);
            if (db != null && db.contains("_test")) {
                return "test";
            }
            if (db != null && db.contains("_dev")) {
                return "dev";
            }
            return db == null || db.isBlank() ? "desconhecido" : db;
        }
    }

    private PedidosPorStatus consultarPedidosPorStatus(Connection conn) throws SQLException {
        String sql = "SELECT "
                + "SUM(CASE WHEN status::text = 'PENDENTE' THEN 1 ELSE 0 END) AS pendente, "
                + "SUM(CASE WHEN status::text = 'CONFIRMADO' THEN 1 ELSE 0 END) AS confirmado, "
                + "SUM(CASE WHEN status::text = 'EM_ROTA' THEN 1 ELSE 0 END) AS em_rota, "
                + "SUM(CASE WHEN status::text = 'ENTREGUE' THEN 1 ELSE 0 END) AS entregue, "
                + "SUM(CASE WHEN status::text = 'CANCELADO' THEN 1 ELSE 0 END) AS cancelado "
                + "FROM pedidos";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return new PedidosPorStatus(
                    rs.getInt("pendente"),
                    rs.getInt("confirmado"),
                    rs.getInt("em_rota"),
                    rs.getInt("entregue"),
                    rs.getInt("cancelado"));
        }
    }

    private List<RotaEmAndamentoResumo> consultarRotasEmAndamento(Connection conn) throws SQLException {
        String sql = "SELECT r.id AS rota_id, "
                + "r.entregador_id, "
                + "COALESCE(SUM(CASE WHEN e.status::text = 'PENDENTE' THEN 1 ELSE 0 END), 0) AS pendentes, "
                + "COALESCE(SUM(CASE WHEN e.status::text = 'EM_EXECUCAO' THEN 1 ELSE 0 END), 0) AS em_execucao "
                + "FROM rotas r "
                + "LEFT JOIN entregas e ON e.rota_id = r.id "
                + "WHERE r.data = CURRENT_DATE "
                + "AND r.status::text = 'EM_ANDAMENTO' "
                + "GROUP BY r.id, r.entregador_id "
                + "ORDER BY r.id";
        List<RotaEmAndamentoResumo> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new RotaEmAndamentoResumo(
                        rs.getInt("rota_id"),
                        rs.getInt("entregador_id"),
                        rs.getInt("pendentes"),
                        rs.getInt("em_execucao")));
            }
        }
        return result;
    }

    private List<RotaPlanejadaResumo> consultarRotasPlanejadas(Connection conn) throws SQLException {
        String sql = "SELECT r.id AS rota_id, "
                + "r.entregador_id, "
                + "COALESCE(SUM(CASE WHEN e.status::text = 'PENDENTE' THEN 1 ELSE 0 END), 0) AS pendentes "
                + "FROM rotas r "
                + "LEFT JOIN entregas e ON e.rota_id = r.id "
                + "WHERE r.data = CURRENT_DATE "
                + "AND r.status::text = 'PLANEJADA' "
                + "GROUP BY r.id, r.entregador_id "
                + "ORDER BY r.id";
        List<RotaPlanejadaResumo> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new RotaPlanejadaResumo(
                        rs.getInt("rota_id"), rs.getInt("entregador_id"), rs.getInt("pendentes")));
            }
        }
        return result;
    }

    private List<PendenteElegivelResumo> consultarPendentesElegiveis(Connection conn) throws SQLException {
        String sql = "SELECT p.id AS pedido_id, p.criado_em, p.quantidade_galoes, p.janela_tipo::text AS janela_tipo "
                + "FROM pedidos p "
                + "JOIN clientes c ON c.id = p.cliente_id "
                + "LEFT JOIN saldo_vales sv ON sv.cliente_id = c.id "
                + "WHERE p.status::text = 'PENDENTE' "
                + "AND (p.metodo_pagamento::text <> 'VALE' OR COALESCE(sv.quantidade, 0) >= p.quantidade_galoes) "
                + "AND NOT EXISTS ("
                + "    SELECT 1 FROM entregas e2 "
                + "    WHERE e2.pedido_id = p.id "
                + "    AND e2.status::text IN ('PENDENTE', 'EM_EXECUCAO')"
                + ") "
                + "ORDER BY p.criado_em, p.id "
                + "LIMIT ?";
        List<PendenteElegivelResumo> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, LIMITE_LISTAS);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new PendenteElegivelResumo(
                            rs.getInt("pedido_id"),
                            rs.getObject("criado_em", LocalDateTime.class).toString(),
                            rs.getInt("quantidade_galoes"),
                            rs.getString("janela_tipo")));
                }
            }
        }
        return result;
    }

    private List<ConfirmadoSecundariaResumo> consultarConfirmadosSecundaria(Connection conn) throws SQLException {
        String sql = "SELECT p.id AS pedido_id, r.id AS rota_id, e.ordem_na_rota, r.entregador_id, p.quantidade_galoes "
                + "FROM pedidos p "
                + "JOIN entregas e ON e.pedido_id = p.id "
                + "JOIN rotas r ON r.id = e.rota_id "
                + "WHERE p.status::text = 'CONFIRMADO' "
                + "AND r.status::text = 'PLANEJADA' "
                + "AND e.status::text = 'PENDENTE' "
                + "ORDER BY p.criado_em, p.id, e.ordem_na_rota "
                + "LIMIT ?";
        List<ConfirmadoSecundariaResumo> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, LIMITE_LISTAS);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new ConfirmadoSecundariaResumo(
                            rs.getInt("pedido_id"),
                            rs.getInt("rota_id"),
                            rs.getInt("ordem_na_rota"),
                            rs.getInt("entregador_id"),
                            rs.getInt("quantidade_galoes")));
                }
            }
        }
        return result;
    }

    private List<EmRotaPrimariaResumo> consultarEmRotaPrimaria(Connection conn) throws SQLException {
        String sql = "SELECT p.id AS pedido_id, "
                + "r.id AS rota_id, "
                + "e.id AS entrega_id, "
                + "r.entregador_id, "
                + "p.quantidade_galoes, "
                + "e.status::text AS status_entrega "
                + "FROM pedidos p "
                + "JOIN entregas e ON e.pedido_id = p.id "
                + "JOIN rotas r ON r.id = e.rota_id "
                + "WHERE p.status::text = 'EM_ROTA' "
                + "AND r.status::text = 'EM_ANDAMENTO' "
                + "AND e.status::text IN ('PENDENTE', 'EM_EXECUCAO') "
                + "ORDER BY r.id, e.ordem_na_rota, e.id "
                + "LIMIT ?";
        List<EmRotaPrimariaResumo> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, LIMITE_LISTAS);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new EmRotaPrimariaResumo(
                            rs.getInt("pedido_id"),
                            rs.getInt("rota_id"),
                            rs.getInt("entrega_id"),
                            rs.getInt("entregador_id"),
                            rs.getInt("quantidade_galoes"),
                            rs.getString("status_entrega")));
                }
            }
        }
        return result;
    }

    public record OperacaoPainelResultado(
            String atualizadoEm,
            String ambiente,
            PedidosPorStatus pedidosPorStatus,
            RotasResumo rotas,
            FilasResumo filas) {}

    public record PedidosPorStatus(int pendente, int confirmado, int emRota, int entregue, int cancelado) {}

    public record RotasResumo(List<RotaEmAndamentoResumo> emAndamento, List<RotaPlanejadaResumo> planejadas) {
        public RotasResumo {
            emAndamento = List.copyOf(emAndamento);
            planejadas = List.copyOf(planejadas);
        }
    }

    public record RotaEmAndamentoResumo(int rotaId, int entregadorId, int pendentes, int emExecucao) {}

    public record RotaPlanejadaResumo(int rotaId, int entregadorId, int pendentes) {}

    public record FilasResumo(
            List<PendenteElegivelResumo> pendentesElegiveis,
            List<ConfirmadoSecundariaResumo> confirmadosSecundaria,
            List<EmRotaPrimariaResumo> emRotaPrimaria) {
        public FilasResumo {
            pendentesElegiveis = List.copyOf(pendentesElegiveis);
            confirmadosSecundaria = List.copyOf(confirmadosSecundaria);
            emRotaPrimaria = List.copyOf(emRotaPrimaria);
        }
    }

    public record PendenteElegivelResumo(int pedidoId, String criadoEm, int quantidadeGaloes, String janelaTipo) {}

    public record ConfirmadoSecundariaResumo(
            int pedidoId, int rotaId, int ordemNaRota, int entregadorId, int quantidadeGaloes) {}

    public record EmRotaPrimariaResumo(
            int pedidoId, int rotaId, int entregaId, int entregadorId, int quantidadeGaloes, String statusEntrega) {}
}
