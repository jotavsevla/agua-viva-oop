package com.aguaviva.repository;

import com.aguaviva.service.CapacidadePolicy;
import com.aguaviva.solver.Parada;
import com.aguaviva.solver.PedidoSolver;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RotaRepository {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final long PLANEJAMENTO_LOCK_KEY = 61001L;

    public RotaRepository(ConnectionFactory connectionFactory) {
        Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public ConfiguracaoRoteirizacao carregarConfiguracao(Connection conn) throws SQLException {
        String sql = """
                SELECT chave, valor FROM configuracoes WHERE chave IN
                ('capacidade_veiculo', 'horario_inicio_expediente', 'horario_fim_expediente',
                'deposito_latitude', 'deposito_longitude', 'frota_perfil_ativo',
                'capacidade_frota_moto', 'capacidade_frota_carro')
                """;

        Map<String, String> configs = new HashMap<>();

        try (Statement stmt = conn.createStatement();
                var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                configs.put(rs.getString("chave"), rs.getString("valor"));
            }
        }

        int capacidadeResolvida = resolverCapacidadeVeiculo(configs);
        return new ConfiguracaoRoteirizacao(
                capacidadeResolvida,
                getObrigatorio(configs, "horario_inicio_expediente"),
                getObrigatorio(configs, "horario_fim_expediente"),
                Double.parseDouble(getObrigatorio(configs, "deposito_latitude")),
                Double.parseDouble(getObrigatorio(configs, "deposito_longitude")));
    }

    public List<Integer> buscarEntregadoresAtivos(Connection conn) throws SQLException {
        String sql = "SELECT id FROM users WHERE papel = 'entregador' AND ativo = true ORDER BY id";
        List<Integer> ids = new ArrayList<>();

        try (var stmt = conn.prepareStatement(sql);
                var rs = stmt.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
        }

        return ids;
    }

    public List<PedidoPlanejavel> buscarPedidosParaSolver(Connection conn, int capacidadeLivreTotal)
            throws SQLException {
        String sql = """
                SELECT
                p.id AS pedido_id,
                p.status::text AS pedido_status,
                p.quantidade_galoes,
                p.janela_tipo::text AS janela_tipo,
                p.janela_inicio,
                p.janela_fim,
                c.latitude,
                c.longitude,
                CASE WHEN p.janela_tipo::text = 'HARD' THEN 1 ELSE 2 END AS prioridade
                FROM pedidos p
                JOIN clientes c ON c.id = p.cliente_id
                LEFT JOIN saldo_vales sv ON sv.cliente_id = c.id
                WHERE p.status::text IN ('PENDENTE', 'CONFIRMADO')
                AND (p.metodo_pagamento::text <> 'VALE' OR COALESCE(sv.quantidade, 0) >= p.quantidade_galoes)
                AND NOT EXISTS (
                    SELECT 1 FROM entregas e2
                    WHERE e2.pedido_id = p.id
                    AND e2.status::text IN ('PENDENTE', 'EM_EXECUCAO')
                )
                ORDER BY p.criado_em, p.id
                """;

        List<PedidoPlanejavel> elegiveis = new ArrayList<>();

        try (var stmt = conn.prepareStatement(sql);
                var rs = stmt.executeQuery()) {
            while (rs.next()) {
                Double lat = toNullableDouble(rs, "latitude");
                Double lon = toNullableDouble(rs, "longitude");

                if (lat == null || lon == null) {
                    continue;
                }

                LocalTime janelaInicio = rs.getObject("janela_inicio", LocalTime.class);
                LocalTime janelaFim = rs.getObject("janela_fim", LocalTime.class);

                PedidoSolver pedidoSolver = new PedidoSolver(
                        rs.getInt("pedido_id"),
                        lat,
                        lon,
                        rs.getInt("quantidade_galoes"),
                        rs.getString("janela_tipo"),
                        formatTime(janelaInicio),
                        formatTime(janelaFim),
                        rs.getInt("prioridade"));
                elegiveis.add(new PedidoPlanejavel(pedidoSolver, rs.getString("pedido_status")));
            }
        }

        return aplicarPromocaoConfirmadosPorFifo(elegiveis, capacidadeLivreTotal);
    }

    public boolean existePedidoSemEntregaAbertaParaPlanejar(Connection conn) throws SQLException {
        String sql = """
                SELECT 1
                FROM pedidos p
                JOIN clientes c ON c.id = p.cliente_id
                LEFT JOIN saldo_vales sv ON sv.cliente_id = c.id
                WHERE p.status::text IN ('PENDENTE', 'CONFIRMADO')
                AND (p.metodo_pagamento::text <> 'VALE' OR COALESCE(sv.quantidade, 0) >= p.quantidade_galoes)
                AND NOT EXISTS (
                    SELECT 1 FROM entregas e2
                    WHERE e2.pedido_id = p.id
                    AND e2.status::text IN ('PENDENTE', 'EM_EXECUCAO')
                )
                LIMIT 1
                """;
        try (var stmt = conn.prepareStatement(sql);
                var rs = stmt.executeQuery()) {
            return rs.next();
        }
    }

    public void limparCamadaSecundariaPlanejada(Connection conn) throws SQLException {
        String deleteEntregas = """
                DELETE FROM entregas e
                USING rotas r
                WHERE e.rota_id = r.id
                AND r.data = CURRENT_DATE
                AND r.status::text = 'PLANEJADA'
                """;
        try (var stmt = conn.prepareStatement(deleteEntregas)) {
            stmt.executeUpdate();
        }

        String deleteRotas = "DELETE FROM rotas WHERE data = CURRENT_DATE AND status::text = 'PLANEJADA'";
        try (var stmt = conn.prepareStatement(deleteRotas)) {
            stmt.executeUpdate();
        }
    }

    public int inserirRota(
            Connection conn,
            int entregadorId,
            int numeroNoDiaSugerido,
            long planVersion,
            boolean planVersionEnabled,
            String jobId,
            boolean jobIdEnabled)
            throws SQLException {
        String sql;
        if (planVersionEnabled && jobIdEnabled) {
            sql =
                    "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version, job_id) VALUES (?, CURRENT_DATE, ?, ?, ?, ?)";
        } else if (planVersionEnabled) {
            sql =
                    "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version) VALUES (?, CURRENT_DATE, ?, ?, ?)";
        } else if (jobIdEnabled) {
            sql =
                    "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, job_id) VALUES (?, CURRENT_DATE, ?, ?, ?)";
        } else {
            sql = "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (?, CURRENT_DATE, ?, ?)";
        }

        for (int tentativa = 1; tentativa <= 5; tentativa++) {
            int numeroNoDia = reservarNumeroNoDia(conn, entregadorId, numeroNoDiaSugerido);

            try (var stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, entregadorId);
                stmt.setInt(2, numeroNoDia);
                stmt.setObject(3, "PLANEJADA", Types.OTHER);
                if (planVersionEnabled && jobIdEnabled) {
                    stmt.setLong(4, planVersion);
                    stmt.setString(5, jobId);
                } else if (planVersionEnabled) {
                    stmt.setLong(4, planVersion);
                } else if (jobIdEnabled) {
                    stmt.setString(4, jobId);
                }
                stmt.executeUpdate();

                try (var rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState()) && tentativa < 5) {
                    continue;
                }
                throw e;
            }
        }

        throw new SQLException("Falha ao inserir rota apos tentativas");
    }

    private int reservarNumeroNoDia(Connection conn, int entregadorId, int numeroNoDiaSugerido) throws SQLException {
        String sql = """
                SELECT numero_no_dia FROM rotas
                WHERE entregador_id = ? AND data = CURRENT_DATE AND numero_no_dia >= ?
                ORDER BY numero_no_dia
                """;

        int candidato = Math.max(1, numeroNoDiaSugerido);

        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            stmt.setInt(2, candidato);

            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int existente = rs.getInt("numero_no_dia");
                    if (existente == candidato) {
                        candidato++;
                    } else if (existente > candidato) {
                        break;
                    }
                }
            }
        }

        return candidato;
    }

    public void inserirEntrega(
            Connection conn,
            Parada parada,
            int rotaId,
            long planVersion,
            boolean planVersionEnabled,
            String jobId,
            boolean jobIdEnabled)
            throws SQLException {
        String sql;
        if (planVersionEnabled && jobIdEnabled) {
            sql =
                    "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status, plan_version, job_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        } else if (planVersionEnabled) {
            sql =
                    "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status, plan_version) VALUES (?, ?, ?, ?, ?, ?)";
        } else if (jobIdEnabled) {
            sql =
                    "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status, job_id) VALUES (?, ?, ?, ?, ?, ?)";
        } else {
            sql =
                    "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status) VALUES (?, ?, ?, ?, ?)";
        }

        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, parada.pedidoId());
            stmt.setInt(2, rotaId);
            stmt.setInt(3, parada.ordem());
            stmt.setObject(4, toLocalDateTime(parada.horaPrevista()));
            stmt.setObject(5, "PENDENTE", Types.OTHER);
            if (planVersionEnabled && jobIdEnabled) {
                stmt.setLong(6, planVersion);
                stmt.setString(7, jobId);
            } else if (planVersionEnabled) {
                stmt.setLong(6, planVersion);
            } else if (jobIdEnabled) {
                stmt.setString(6, jobId);
            }
            stmt.executeUpdate();
        }
    }

    public List<Integer> calcularCapacidadesPorPolitica(
            Connection conn, List<Integer> entregadoresAtivos, int capacidadePadrao, CapacidadePolicy capacidadePolicy)
            throws SQLException {
        if (capacidadePolicy == CapacidadePolicy.CHEIA) {
            List<Integer> capacidades = new ArrayList<>(entregadoresAtivos.size());
            for (int i = 0; i < entregadoresAtivos.size(); i++) {
                capacidades.add(Math.max(0, capacidadePadrao));
            }
            return capacidades;
        }

        return calcularCapacidadesRemanescentesPorEntregador(conn, entregadoresAtivos, capacidadePadrao);
    }

    private List<Integer> calcularCapacidadesRemanescentesPorEntregador(
            Connection conn, List<Integer> entregadoresAtivos, int capacidadePadrao) throws SQLException {
        String sql = """
                SELECT r.entregador_id, COALESCE(SUM(p.quantidade_galoes), 0) AS carga_comprometida
                FROM rotas r
                JOIN entregas e ON e.rota_id = r.id
                JOIN pedidos p ON p.id = e.pedido_id
                WHERE r.data = CURRENT_DATE
                AND r.status::text = 'EM_ANDAMENTO'
                AND e.status::text IN ('PENDENTE', 'EM_EXECUCAO')
                GROUP BY r.entregador_id
                """;

        Map<Integer, Integer> cargaComprometidaPorEntregador = new HashMap<>();
        try (var stmt = conn.prepareStatement(sql);
                var rs = stmt.executeQuery()) {
            while (rs.next()) {
                cargaComprometidaPorEntregador.put(rs.getInt("entregador_id"), rs.getInt("carga_comprometida"));
            }
        }

        List<Integer> capacidades = new ArrayList<>(entregadoresAtivos.size());
        for (Integer entregadorId : entregadoresAtivos) {
            int cargaComprometida = cargaComprometidaPorEntregador.getOrDefault(entregadorId, 0);
            capacidades.add(Math.max(0, capacidadePadrao - cargaComprometida));
        }
        return capacidades;
    }

    public boolean tentarAdquirirLockPlanejamento(Connection conn) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            stmt.setLong(1, PLANEJAMENTO_LOCK_KEY);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean(1);
            }
        }
    }

    private List<PedidoPlanejavel> aplicarPromocaoConfirmadosPorFifo(
            List<PedidoPlanejavel> elegiveis, int capacidadeLivreTotal) {
        if (elegiveis.isEmpty()) {
            return List.of();
        }

        int capacidadeRestante = Math.max(0, capacidadeLivreTotal);
        List<PedidoPlanejavel> selecionados = new ArrayList<>(elegiveis.size());

        for (PedidoPlanejavel item : elegiveis) {
            if (!"CONFIRMADO".equals(item.statusPedido())) {
                selecionados.add(item);
                continue;
            }

            if (capacidadeRestante == 0) {
                continue;
            }

            int demanda = item.pedidoSolver().galoes();
            if (demanda > capacidadeRestante) {
                continue;
            }
            selecionados.add(item);
            capacidadeRestante -= demanda;
        }

        return selecionados;
    }

    private static Double toNullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return rs.getDouble(column);
    }

    private static LocalDateTime toLocalDateTime(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        LocalTime time = LocalTime.parse(hhmm, HH_MM);
        return LocalDate.now().atTime(time);
    }

    private static String formatTime(LocalTime time) {
        if (time == null) {
            return null;
        }
        return time.format(HH_MM);
    }

    private static String getObrigatorio(Map<String, String> configs, String chave) {
        String valor = configs.get(chave);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Configuracao obrigatoria ausente: " + chave);
        }
        return valor;
    }

    private static int resolverCapacidadeVeiculo(Map<String, String> configs) {
        int capacidadePadrao =
                parseCapacidadePositiva(getObrigatorio(configs, "capacidade_veiculo"), "capacidade_veiculo");
        String perfil = configs.getOrDefault("frota_perfil_ativo", "PADRAO");
        if (perfil == null) {
            return capacidadePadrao;
        }

        String perfilNormalizado = perfil.trim().toUpperCase(Locale.ROOT);
        return switch (perfilNormalizado) {
            case "MOTO" ->
                parseCapacidadePositiva(
                        configs.getOrDefault("capacidade_frota_moto", Integer.toString(capacidadePadrao)),
                        "capacidade_frota_moto");
            case "CARRO" ->
                parseCapacidadePositiva(
                        configs.getOrDefault("capacidade_frota_carro", Integer.toString(capacidadePadrao)),
                        "capacidade_frota_carro");
            case "", "PADRAO", "DEFAULT", "OFF" -> capacidadePadrao;
            default -> capacidadePadrao;
        };
    }

    private static int parseCapacidadePositiva(String valor, String chave) {
        try {
            int capacidade = Integer.parseInt(valor);
            if (capacidade <= 0) {
                throw new IllegalStateException("Configuracao invalida para " + chave + ": deve ser inteiro > 0");
            }
            return capacidade;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuracao invalida para " + chave + ": " + valor, e);
        }
    }

    public record ConfiguracaoRoteirizacao(
            int capacidadeVeiculo, String horarioInicio, String horarioFim, double depositoLat, double depositoLon) {}

    public record PedidoPlanejavel(PedidoSolver pedidoSolver, String statusPedido) {}
}
