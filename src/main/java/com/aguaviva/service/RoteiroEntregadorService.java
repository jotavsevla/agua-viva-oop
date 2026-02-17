package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RoteiroEntregadorService {

    private final ConnectionFactory connectionFactory;

    public RoteiroEntregadorService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public RoteiroEntregadorResultado consultarRoteiro(int entregadorId) {
        if (entregadorId <= 0) {
            throw new IllegalArgumentException("entregadorId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            RotaResumo rota = buscarRotaAtivaOuPlanejada(conn, entregadorId);
            if (rota == null) {
                return new RoteiroEntregadorResultado(entregadorId, null, 0, List.of(), List.of());
            }

            List<ParadaResumo> paradasPendentesExecucao = new ArrayList<>();
            List<ParadaResumo> paradasConcluidas = new ArrayList<>();
            int cargaRemanescente = 0;

            String sqlParadas = "SELECT e.id AS entrega_id, "
                    + "e.pedido_id, "
                    + "e.ordem_na_rota, "
                    + "e.status::text AS entrega_status, "
                    + "p.quantidade_galoes, "
                    + "COALESCE(c.nome, '') AS cliente_nome "
                    + "FROM entregas e "
                    + "JOIN pedidos p ON p.id = e.pedido_id "
                    + "LEFT JOIN clientes c ON c.id = p.cliente_id "
                    + "WHERE e.rota_id = ? "
                    + "ORDER BY e.ordem_na_rota, e.id";
            try (PreparedStatement stmt = conn.prepareStatement(sqlParadas)) {
                stmt.setInt(1, rota.rotaId());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        ParadaResumo parada = new ParadaResumo(
                                rs.getInt("entrega_id"),
                                rs.getInt("pedido_id"),
                                rs.getInt("ordem_na_rota"),
                                rs.getString("entrega_status"),
                                rs.getInt("quantidade_galoes"),
                                rs.getString("cliente_nome"));

                        if ("PENDENTE".equals(parada.status()) || "EM_EXECUCAO".equals(parada.status())) {
                            paradasPendentesExecucao.add(parada);
                            cargaRemanescente += parada.quantidadeGaloes();
                        } else {
                            paradasConcluidas.add(parada);
                        }
                    }
                }
            }

            return new RoteiroEntregadorResultado(
                    entregadorId,
                    rota,
                    cargaRemanescente,
                    List.copyOf(paradasPendentesExecucao),
                    List.copyOf(paradasConcluidas));
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar roteiro do entregador", e);
        }
    }

    private RotaResumo buscarRotaAtivaOuPlanejada(Connection conn, int entregadorId) throws SQLException {
        String sql = "SELECT id, status::text "
                + "FROM rotas "
                + "WHERE entregador_id = ? "
                + "AND data = CURRENT_DATE "
                + "AND status::text IN ('EM_ANDAMENTO', 'PLANEJADA') "
                + "ORDER BY CASE WHEN status::text = 'EM_ANDAMENTO' THEN 0 ELSE 1 END, numero_no_dia, id "
                + "LIMIT 1";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new RotaResumo(rs.getInt("id"), rs.getString("status"));
            }
        }
    }

    public record RoteiroEntregadorResultado(
            int entregadorId,
            RotaResumo rota,
            int cargaRemanescente,
            List<ParadaResumo> paradasPendentesExecucao,
            List<ParadaResumo> paradasConcluidas) {}

    public record RotaResumo(int rotaId, String status) {}

    public record ParadaResumo(
            int entregaId, int pedidoId, int ordemNaRota, String status, int quantidadeGaloes, String clienteNome) {}
}
