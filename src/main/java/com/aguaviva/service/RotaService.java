package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.solver.Coordenada;
import com.aguaviva.solver.Parada;
import com.aguaviva.solver.PedidoSolver;
import com.aguaviva.solver.RotaSolver;
import com.aguaviva.solver.SolverClient;
import com.aguaviva.solver.SolverRequest;
import com.aguaviva.solver.SolverResponse;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
import java.util.Map;
import java.util.Objects;

public class RotaService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final SolverClient solverClient;
    private final ConnectionFactory connectionFactory;

    public RotaService(SolverClient solverClient, ConnectionFactory connectionFactory) {
        this.solverClient = Objects.requireNonNull(solverClient, "SolverClient nao pode ser nulo");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public PlanejamentoResultado planejarRotasPendentes() {
        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                ConfiguracaoRoteirizacao cfg = carregarConfiguracao(conn);
                List<Integer> entregadoresAtivos = buscarEntregadoresAtivos(conn);
                if (entregadoresAtivos.isEmpty()) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                List<PedidoSolver> pedidos = buscarPedidosParaSolver(conn);
                if (pedidos.isEmpty()) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                SolverRequest request = new SolverRequest(
                        new Coordenada(cfg.depositoLat(), cfg.depositoLon()),
                        cfg.capacidadeVeiculo(),
                        cfg.horarioInicio(),
                        cfg.horarioFim(),
                        entregadoresAtivos,
                        pedidos
                );

                SolverResponse solverResponse = solverClient.solve(request);

                int rotasCriadas = 0;
                int entregasCriadas = 0;

                for (RotaSolver rota : solverResponse.getRotas()) {
                    int rotaId = inserirRota(conn, rota.getEntregadorId(), rota.getNumeroNoDia());
                    rotasCriadas++;

                    for (Parada parada : rota.getParadas()) {
                        inserirEntrega(conn, parada, rotaId);
                        atualizarStatusPedido(conn, parada.getPedidoId(), "CONFIRMADO");
                        entregasCriadas++;
                    }
                }

                conn.commit();
                return new PlanejamentoResultado(rotasCriadas, entregasCriadas, solverResponse.getNaoAtendidos().size());
            } catch (InterruptedException e) {
                conn.rollback();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread interrompida ao chamar solver", e);
            } catch (Exception e) {
                conn.rollback();
                throw new IllegalStateException("Falha ao planejar rotas", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha de banco ao planejar rotas", e);
        }
    }

    private ConfiguracaoRoteirizacao carregarConfiguracao(Connection conn) throws SQLException {
        String sql = "SELECT chave, valor FROM configuracoes WHERE chave IN "
                + "('capacidade_veiculo', 'horario_inicio_expediente', 'horario_fim_expediente', "
                + "'deposito_latitude', 'deposito_longitude')";

        Map<String, String> configs = new HashMap<>();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                configs.put(rs.getString("chave"), rs.getString("valor"));
            }
        }

        return new ConfiguracaoRoteirizacao(
                Integer.parseInt(getObrigatorio(configs, "capacidade_veiculo")),
                getObrigatorio(configs, "horario_inicio_expediente"),
                getObrigatorio(configs, "horario_fim_expediente"),
                Double.parseDouble(getObrigatorio(configs, "deposito_latitude")),
                Double.parseDouble(getObrigatorio(configs, "deposito_longitude"))
        );
    }

    private List<Integer> buscarEntregadoresAtivos(Connection conn) throws SQLException {
        String sql = "SELECT id FROM users WHERE papel = 'entregador' AND ativo = true ORDER BY id";
        List<Integer> ids = new ArrayList<>();

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                ids.add(rs.getInt("id"));
            }
        }

        return ids;
    }

    private List<PedidoSolver> buscarPedidosParaSolver(Connection conn) throws SQLException {
        String sql = "SELECT pedido_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, latitude, longitude, prioridade "
                + "FROM vw_pedidos_para_solver ORDER BY prioridade, pedido_id";

        List<PedidoSolver> pedidos = new ArrayList<>();

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Double lat = toNullableDouble(rs, "latitude");
                Double lon = toNullableDouble(rs, "longitude");

                if (lat == null || lon == null) {
                    continue;
                }

                LocalTime janelaInicio = rs.getObject("janela_inicio", LocalTime.class);
                LocalTime janelaFim = rs.getObject("janela_fim", LocalTime.class);

                pedidos.add(new PedidoSolver(
                        rs.getInt("pedido_id"),
                        lat,
                        lon,
                        rs.getInt("quantidade_galoes"),
                        rs.getString("janela_tipo"),
                        formatTime(janelaInicio),
                        formatTime(janelaFim),
                        rs.getInt("prioridade")
                ));
            }
        }

        return pedidos;
    }

    private int inserirRota(Connection conn, int entregadorId, int numeroNoDia) throws SQLException {
        String sql = "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) "
                + "VALUES (?, CURRENT_DATE, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, entregadorId);
            stmt.setInt(2, numeroNoDia);
            stmt.setObject(3, "PLANEJADA", Types.OTHER);
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        throw new SQLException("Falha ao inserir rota, nenhum ID retornado");
    }

    private void inserirEntrega(Connection conn, Parada parada, int rotaId) throws SQLException {
        String sql = "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status) "
                + "VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, parada.getPedidoId());
            stmt.setInt(2, rotaId);
            stmt.setInt(3, parada.getOrdem());
            stmt.setObject(4, toLocalDateTime(parada.getHoraPrevista()));
            stmt.setObject(5, "PENDENTE", Types.OTHER);
            stmt.executeUpdate();
        }
    }

    private void atualizarStatusPedido(Connection conn, int pedidoId, String status) throws SQLException {
        String sql = "UPDATE pedidos SET status = ?, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, status, Types.OTHER);
            stmt.setInt(2, pedidoId);
            stmt.executeUpdate();
        }
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

    private record ConfiguracaoRoteirizacao(
            int capacidadeVeiculo,
            String horarioInicio,
            String horarioFim,
            double depositoLat,
            double depositoLon
    ) {
    }
}
