package com.aguaviva.service;

import com.aguaviva.domain.pedido.PedidoStatus;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class RotaService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    static final long PLANEJAMENTO_LOCK_KEY = 61001L;

    private final AtomicLong lastRequestedPlanVersion = new AtomicLong(0);
    private final AtomicReference<String> activeJobId = new AtomicReference<>();

    private final SolverClient solverClient;
    private final ConnectionFactory connectionFactory;
    private final PedidoLifecycleService pedidoLifecycleService;

    public RotaService(SolverClient solverClient, ConnectionFactory connectionFactory) {
        this(solverClient, connectionFactory, new PedidoLifecycleService());
    }

    RotaService(
            SolverClient solverClient,
            ConnectionFactory connectionFactory,
            PedidoLifecycleService pedidoLifecycleService) {
        this.solverClient = Objects.requireNonNull(solverClient, "SolverClient nao pode ser nulo");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.pedidoLifecycleService =
                Objects.requireNonNull(pedidoLifecycleService, "PedidoLifecycleService nao pode ser nulo");
    }

    public PlanejamentoResultado planejarRotasPendentes() {
        String currentJobId = null;

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!tentarAdquirirLockPlanejamento(conn)) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                long planVersion = lastRequestedPlanVersion.incrementAndGet();
                currentJobId = buildJobId(planVersion);
                String previousJobId = activeJobId.getAndSet(currentJobId);
                if (previousJobId != null && !previousJobId.equals(currentJobId)) {
                    solverClient.cancelBestEffort(previousJobId);
                }

                boolean planVersionEnabled = hasPlanVersionColumns(conn);
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

                InsercaoLocalResult insercaoLocal =
                        tentarInsercaoLocal(conn, pedidos, cfg.capacidadeVeiculo(), planVersion, planVersionEnabled);

                int rotasCriadas = 0;
                int entregasCriadas = insercaoLocal.entregasCriadas();

                List<PedidoSolver> pendentesParaSolver = insercaoLocal.pedidosRestantes();
                if (pendentesParaSolver.isEmpty()) {
                    conn.commit();
                    return new PlanejamentoResultado(rotasCriadas, entregasCriadas, 0);
                }

                SolverRequest request = new SolverRequest(
                        currentJobId,
                        planVersion,
                        new Coordenada(cfg.depositoLat(), cfg.depositoLon()),
                        cfg.capacidadeVeiculo(),
                        cfg.horarioInicio(),
                        cfg.horarioFim(),
                        entregadoresAtivos,
                        pendentesParaSolver);

                SolverResponse solverResponse = solverClient.solve(request);

                for (RotaSolver rota : solverResponse.getRotas()) {
                    int rotaId = inserirRota(
                            conn, rota.getEntregadorId(), rota.getNumeroNoDia(), planVersion, planVersionEnabled);
                    rotasCriadas++;

                    for (Parada parada : rota.getParadas()) {
                        inserirEntrega(conn, parada, rotaId, planVersion, planVersionEnabled);
                        pedidoLifecycleService.transicionar(conn, parada.getPedidoId(), PedidoStatus.CONFIRMADO);
                        entregasCriadas++;
                    }
                }

                conn.commit();
                return new PlanejamentoResultado(
                        rotasCriadas,
                        entregasCriadas,
                        solverResponse.getNaoAtendidos().size());
            } catch (InterruptedException e) {
                conn.rollback();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread interrompida ao chamar solver", e);
            } catch (Exception e) {
                conn.rollback();
                throw new IllegalStateException("Falha ao planejar rotas", e);
            } finally {
                if (currentJobId != null) {
                    clearActiveJob(currentJobId);
                }
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
                Double.parseDouble(getObrigatorio(configs, "deposito_longitude")));
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
        // FIFO com elegibilidade: respeita ordem temporal de entrada sem ignorar capacidade/saldo minimo.
        String sql = "SELECT "
                + "p.id AS pedido_id, "
                + "p.quantidade_galoes, "
                + "p.janela_tipo::text AS janela_tipo, "
                + "p.janela_inicio, "
                + "p.janela_fim, "
                + "c.latitude, "
                + "c.longitude, "
                + "CASE WHEN p.janela_tipo::text = 'HARD' THEN 1 ELSE 2 END AS prioridade "
                + "FROM pedidos p "
                + "JOIN clientes c ON c.id = p.cliente_id "
                + "JOIN saldo_vales sv ON sv.cliente_id = c.id "
                + "WHERE p.status::text = 'PENDENTE' "
                + "AND sv.quantidade > 0 "
                + "AND p.quantidade_galoes <= sv.quantidade "
                + "ORDER BY p.criado_em, p.id";

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
                        rs.getInt("prioridade")));
            }
        }

        return pedidos;
    }

    private int inserirRota(
            Connection conn, int entregadorId, int numeroNoDiaSugerido, long planVersion, boolean planVersionEnabled)
            throws SQLException {
        String sql = planVersionEnabled
                ? "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version) VALUES (?, CURRENT_DATE, ?, ?, ?)"
                : "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (?, CURRENT_DATE, ?, ?)";

        for (int tentativa = 1; tentativa <= 5; tentativa++) {
            int numeroNoDia = reservarNumeroNoDia(conn, entregadorId, numeroNoDiaSugerido);

            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, entregadorId);
                stmt.setInt(2, numeroNoDia);
                stmt.setObject(3, "PLANEJADA", Types.OTHER);
                if (planVersionEnabled) {
                    stmt.setLong(4, planVersion);
                }
                stmt.executeUpdate();

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                // Retry para corridas raras na unique (entregador_id, data, numero_no_dia).
                if ("23505".equals(e.getSQLState()) && tentativa < 5) {
                    continue;
                }
                throw e;
            }
        }

        throw new SQLException("Falha ao inserir rota apos tentativas");
    }

    private int reservarNumeroNoDia(Connection conn, int entregadorId, int numeroNoDiaSugerido) throws SQLException {
        String sql = "SELECT numero_no_dia FROM rotas "
                + "WHERE entregador_id = ? AND data = CURRENT_DATE AND numero_no_dia >= ? "
                + "ORDER BY numero_no_dia";

        int candidato = Math.max(1, numeroNoDiaSugerido);

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            stmt.setInt(2, candidato);

            try (ResultSet rs = stmt.executeQuery()) {
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

    private void inserirEntrega(
            Connection conn, Parada parada, int rotaId, long planVersion, boolean planVersionEnabled)
            throws SQLException {
        String sql = planVersionEnabled
                ? "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status, plan_version) VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, parada.getPedidoId());
            stmt.setInt(2, rotaId);
            stmt.setInt(3, parada.getOrdem());
            stmt.setObject(4, toLocalDateTime(parada.getHoraPrevista()));
            stmt.setObject(5, "PENDENTE", Types.OTHER);
            if (planVersionEnabled) {
                stmt.setLong(6, planVersion);
            }
            stmt.executeUpdate();
        }
    }

    private InsercaoLocalResult tentarInsercaoLocal(
            Connection conn,
            List<PedidoSolver> pendentes,
            int capacidadeVeiculo,
            long planVersion,
            boolean planVersionEnabled)
            throws SQLException {
        List<RotaCandidate> rotas = buscarRotasComSlotCanceladoOuFalho(conn);
        if (rotas.isEmpty()) {
            return new InsercaoLocalResult(0, pendentes);
        }

        List<PedidoSolver> restantes = new ArrayList<>();
        int inseridas = 0;

        for (PedidoSolver pedido : pendentes) {
            RotaCandidate candidata = escolherRotaComCapacidade(rotas, pedido.getGaloes(), capacidadeVeiculo);
            if (candidata == null) {
                restantes.add(pedido);
                continue;
            }

            inserirEntregaLocal(
                    conn,
                    pedido.getPedidoId(),
                    candidata.rotaId(),
                    candidata.nextOrder(),
                    planVersion,
                    planVersionEnabled);
            pedidoLifecycleService.transicionar(conn, pedido.getPedidoId(), PedidoStatus.CONFIRMADO);
            candidata.addCarga(pedido.getGaloes());
            inseridas++;
        }

        return new InsercaoLocalResult(inseridas, restantes);
    }

    private void inserirEntregaLocal(
            Connection conn, int pedidoId, int rotaId, int ordemNaRota, long planVersion, boolean planVersionEnabled)
            throws SQLException {
        String sql = planVersionEnabled
                ? "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status, plan_version) VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, hora_prevista, status) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setInt(3, ordemNaRota);
            stmt.setObject(4, null);
            stmt.setObject(5, "PENDENTE", Types.OTHER);
            if (planVersionEnabled) {
                stmt.setLong(6, planVersion);
            }
            stmt.executeUpdate();
        }
    }

    private List<RotaCandidate> buscarRotasComSlotCanceladoOuFalho(Connection conn) throws SQLException {
        String sql = "SELECT r.id, r.entregador_id, "
                + "COALESCE(SUM(p.quantidade_galoes) FILTER (WHERE e.status::text IN ('PENDENTE', 'EM_EXECUCAO')), 0) AS carga_ativa, "
                + "COALESCE(MAX(e.ordem_na_rota), 0) AS max_ordem, "
                + "COUNT(*) FILTER (WHERE e.status::text IN ('CANCELADA', 'FALHOU')) AS slots_recuperaveis, "
                + "r.status::text AS rota_status "
                + "FROM rotas r "
                + "LEFT JOIN entregas e ON e.rota_id = r.id "
                + "LEFT JOIN pedidos p ON p.id = e.pedido_id "
                + "WHERE r.data = CURRENT_DATE AND r.status::text IN ('PLANEJADA', 'EM_ANDAMENTO') "
                + "GROUP BY r.id, r.entregador_id, r.status "
                + "HAVING COUNT(*) FILTER (WHERE e.status::text IN ('CANCELADA', 'FALHOU')) > 0 "
                + "ORDER BY slots_recuperaveis DESC, CASE WHEN r.status::text = 'EM_ANDAMENTO' THEN 0 ELSE 1 END, r.id";

        List<RotaCandidate> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new RotaCandidate(
                        rs.getInt("id"), rs.getInt("entregador_id"), rs.getInt("carga_ativa"), rs.getInt("max_ordem")));
            }
        }
        return result;
    }

    private RotaCandidate escolherRotaComCapacidade(List<RotaCandidate> rotas, int demanda, int capacidadeVeiculo) {
        RotaCandidate melhor = null;
        int melhorFolga = Integer.MIN_VALUE;

        for (RotaCandidate rota : rotas) {
            int folga = capacidadeVeiculo - rota.cargaAtiva() - demanda;
            if (folga < 0) {
                continue;
            }
            if (folga > melhorFolga) {
                melhor = rota;
                melhorFolga = folga;
            }
        }

        return melhor;
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

    private boolean hasPlanVersionColumns(Connection conn) throws SQLException {
        return hasColumn(conn, "rotas", "plan_version") && hasColumn(conn, "entregas", "plan_version");
    }

    private boolean tentarAdquirirLockPlanejamento(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            stmt.setLong(1, PLANEJAMENTO_LOCK_KEY);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean(1);
            }
        }
    }

    private boolean hasColumn(Connection conn, String tabela, String coluna) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tabela);
            stmt.setString(2, coluna);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String buildJobId(long planVersion) {
        return "job-plan-" + planVersion + "-" + UUID.randomUUID();
    }

    private void clearActiveJob(String currentJobId) {
        activeJobId.compareAndSet(currentJobId, null);
    }

    private record ConfiguracaoRoteirizacao(
            int capacidadeVeiculo, String horarioInicio, String horarioFim, double depositoLat, double depositoLon) {}

    private record InsercaoLocalResult(int entregasCriadas, List<PedidoSolver> pedidosRestantes) {}

    private static final class RotaCandidate {
        private final int rotaId;
        private final int entregadorId;
        private int cargaAtiva;
        private int maxOrdem;

        private RotaCandidate(int rotaId, int entregadorId, int cargaAtiva, int maxOrdem) {
            this.rotaId = rotaId;
            this.entregadorId = entregadorId;
            this.cargaAtiva = cargaAtiva;
            this.maxOrdem = maxOrdem;
        }

        int rotaId() {
            return rotaId;
        }

        int cargaAtiva() {
            return cargaAtiva;
        }

        int nextOrder() {
            maxOrdem += 1;
            return maxOrdem;
        }

        void addCarga(int quantidade) {
            this.cargaAtiva += quantidade;
        }
    }
}
