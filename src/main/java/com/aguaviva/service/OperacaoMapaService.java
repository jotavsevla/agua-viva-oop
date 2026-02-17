package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class OperacaoMapaService {

    private final ConnectionFactory connectionFactory;

    public OperacaoMapaService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public OperacaoMapaResultado consultarMapa() {
        try (Connection conn = connectionFactory.getConnection()) {
            String ambiente = resolverAmbiente(conn);
            DepositoResumo deposito = consultarDeposito(conn);
            List<RotaMapaResumo> rotas = consultarRotasComParadas(conn);
            return new OperacaoMapaResultado(LocalDateTime.now().toString(), ambiente, deposito, rotas);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar mapa operacional", e);
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

    private DepositoResumo consultarDeposito(Connection conn) throws SQLException {
        String sql = "SELECT chave, valor FROM configuracoes WHERE chave IN ('deposito_latitude', 'deposito_longitude')";
        Map<String, String> valores = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                valores.put(rs.getString("chave"), rs.getString("valor"));
            }
        }

        String latRaw = valores.get("deposito_latitude");
        String lonRaw = valores.get("deposito_longitude");
        if (latRaw == null || lonRaw == null) {
            throw new IllegalStateException("Configuracoes de deposito nao encontradas");
        }

        try {
            return new DepositoResumo(Double.parseDouble(latRaw), Double.parseDouble(lonRaw));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuracoes de deposito invalidas", e);
        }
    }

    private List<RotaMapaResumo> consultarRotasComParadas(Connection conn) throws SQLException {
        String sql = "SELECT r.id AS rota_id, "
                + "r.entregador_id, "
                + "r.status::text AS rota_status, "
                + "e.id AS entrega_id, "
                + "e.ordem_na_rota, "
                + "e.status::text AS status_entrega, "
                + "p.id AS pedido_id, "
                + "p.quantidade_galoes, "
                + "c.latitude, "
                + "c.longitude "
                + "FROM rotas r "
                + "JOIN entregas e ON e.rota_id = r.id "
                + "JOIN pedidos p ON p.id = e.pedido_id "
                + "JOIN clientes c ON c.id = p.cliente_id "
                + "WHERE r.data = CURRENT_DATE "
                + "AND r.status::text IN ('EM_ANDAMENTO', 'PLANEJADA') "
                + "ORDER BY "
                + "CASE WHEN r.status::text = 'EM_ANDAMENTO' THEN 0 ELSE 1 END, "
                + "r.id, "
                + "e.ordem_na_rota, "
                + "e.id";

        Map<Integer, RotaAccumulator> rotas = new LinkedHashMap<>();

        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                int rotaId = rs.getInt("rota_id");
                int entregadorId = rs.getInt("entregador_id");
                String statusRota = rs.getString("rota_status");

                RotaAccumulator rota = rotas.computeIfAbsent(
                        rotaId,
                        ignored -> new RotaAccumulator(
                                rotaId,
                                entregadorId,
                                statusRota,
                                "EM_ANDAMENTO".equals(statusRota) ? "PRIMARIA" : "SECUNDARIA"));

                Object latRaw = rs.getObject("latitude");
                Object lonRaw = rs.getObject("longitude");
                if (!(latRaw instanceof Number latNum) || !(lonRaw instanceof Number lonNum)) {
                    continue;
                }

                rota.paradas.add(new ParadaMapaResumo(
                        rs.getInt("pedido_id"),
                        rs.getInt("entrega_id"),
                        rs.getInt("ordem_na_rota"),
                        rs.getString("status_entrega"),
                        rs.getInt("quantidade_galoes"),
                        latNum.doubleValue(),
                        lonNum.doubleValue()));
            }
        }

        List<RotaMapaResumo> resultado = new ArrayList<>();
        for (RotaAccumulator rota : rotas.values()) {
            resultado.add(new RotaMapaResumo(
                    rota.rotaId, rota.entregadorId, rota.statusRota, rota.camada, rota.paradas));
        }
        return resultado;
    }

    public record OperacaoMapaResultado(String atualizadoEm, String ambiente, DepositoResumo deposito, List<RotaMapaResumo> rotas) {
        public OperacaoMapaResultado {
            rotas = List.copyOf(rotas);
        }
    }

    public record DepositoResumo(double lat, double lon) {}

    public record RotaMapaResumo(
            int rotaId, int entregadorId, String statusRota, String camada, List<ParadaMapaResumo> paradas) {
        public RotaMapaResumo {
            paradas = List.copyOf(paradas);
        }
    }

    public record ParadaMapaResumo(
            int pedidoId,
            int entregaId,
            int ordemNaRota,
            String statusEntrega,
            int quantidadeGaloes,
            double lat,
            double lon) {}

    private static final class RotaAccumulator {
        private final int rotaId;
        private final int entregadorId;
        private final String statusRota;
        private final String camada;
        private final List<ParadaMapaResumo> paradas = new ArrayList<>();

        private RotaAccumulator(int rotaId, int entregadorId, String statusRota, String camada) {
            this.rotaId = rotaId;
            this.entregadorId = entregadorId;
            this.statusRota = statusRota;
            this.camada = camada;
        }
    }
}
