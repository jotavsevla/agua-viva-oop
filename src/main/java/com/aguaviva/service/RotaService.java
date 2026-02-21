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
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class RotaService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    static final long PLANEJAMENTO_LOCK_KEY = 61001L;
    private static final int MAX_SOLVER_JOBS_CANCELAMENTO = 8;

    private final AtomicLong lastRequestedPlanVersion = new AtomicLong(0);
    private final AtomicReference<String> activeJobId = new AtomicReference<>();

    private final SolverClient solverClient;
    private final ConnectionFactory connectionFactory;
    private final PedidoLifecycleService pedidoLifecycleService;
    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .create();

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

    public void cancelarPlanejamentosAtivosBestEffort() {
        Set<String> jobIds = new LinkedHashSet<>();

        String jobAtivoLocal = activeJobId.getAndSet(null);
        if (jobAtivoLocal != null && !jobAtivoLocal.isBlank()) {
            jobIds.add(jobAtivoLocal);
        }

        try (Connection conn = connectionFactory.getConnection()) {
            if (hasSolverJobsSchema(conn)) {
                jobIds.addAll(marcarCancelamentoSolicitadoEmJobsAtivos(conn, MAX_SOLVER_JOBS_CANCELAMENTO));
            }
        } catch (Exception ignored) {
            // Melhor esforco: cancelamento remoto nao deve interromper o fluxo principal.
        }

        for (String jobId : jobIds) {
            solverClient.cancelBestEffort(jobId);
        }
    }

    public PlanejamentoResultado planejarRotasPendentes() {
        return planejarRotasPendentes(CapacidadePolicy.REMANESCENTE);
    }

    public PlanejamentoResultado planejarRotasPendentes(CapacidadePolicy capacidadePolicy) {
        CapacidadePolicy capacidadeResolvida =
                Objects.requireNonNull(capacidadePolicy, "capacidadePolicy nao pode ser nulo");
        String currentJobId = null;
        boolean solverJobsEnabled = false;

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

                solverJobsEnabled = hasSolverJobsSchema(conn);
                boolean planVersionEnabled = hasPlanVersionColumns(conn);
                ConfiguracaoRoteirizacao cfg = carregarConfiguracao(conn);
                List<Integer> entregadoresAtivos = buscarEntregadoresAtivos(conn);
                if (entregadoresAtivos.isEmpty()) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                List<Integer> capacidadesEntregadores = calcularCapacidadesPorPolitica(
                        conn, entregadoresAtivos, cfg.capacidadeVeiculo(), capacidadeResolvida);
                int capacidadeLivreTotal = capacidadesEntregadores.stream()
                        .mapToInt(Integer::intValue)
                        .sum();

                if (!existePedidoSemEntregaAbertaParaPlanejar(conn)) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                limparCamadaSecundariaPlanejada(conn);
                List<PedidoPlanejavel> pedidosPlanejaveis = buscarPedidosParaSolver(conn, capacidadeLivreTotal);
                if (pedidosPlanejaveis.isEmpty()) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                int rotasCriadas = 0;
                int entregasCriadas = 0;

                List<PedidoSolver> pedidosParaSolver = pedidosPlanejaveis.stream()
                        .map(PedidoPlanejavel::pedidoSolver)
                        .toList();

                SolverRequest request = new SolverRequest(
                        currentJobId,
                        planVersion,
                        new Coordenada(cfg.depositoLat(), cfg.depositoLon()),
                        cfg.capacidadeVeiculo(),
                        capacidadesEntregadores,
                        cfg.horarioInicio(),
                        cfg.horarioFim(),
                        entregadoresAtivos,
                        pedidosParaSolver);

                if (solverJobsEnabled) {
                    registrarSolverJobEmExecucao(currentJobId, planVersion, request);
                }

                SolverResponse solverResponse = solverClient.solve(request);
                if (isPlanejamentoPreemptado(conn, currentJobId, solverJobsEnabled)) {
                    throw new PlanejamentoPreemptadoException();
                }
                validarPoliticaTemporariaCamadaSecundaria(solverResponse);
                Map<Integer, PedidoPlanejavel> pedidosPorId = indexarPedidosPorId(pedidosPlanejaveis);

                for (RotaSolver rota : solverResponse.getRotas()) {
                    int rotaId = inserirRota(
                            conn, rota.getEntregadorId(), rota.getNumeroNoDia(), planVersion, planVersionEnabled);
                    rotasCriadas++;

                    for (Parada parada : rota.getParadas()) {
                        inserirEntrega(conn, parada, rotaId, planVersion, planVersionEnabled);
                        confirmarPedidoSeNecessario(conn, pedidosPorId.get(parada.getPedidoId()));
                        entregasCriadas++;
                    }
                }

                conn.commit();
                if (solverJobsEnabled) {
                    finalizarSolverJob(currentJobId, "CONCLUIDO", null, solverResponse);
                }
                return new PlanejamentoResultado(
                        rotasCriadas,
                        entregasCriadas,
                        solverResponse.getNaoAtendidos().size());
            } catch (PlanejamentoPreemptadoException e) {
                conn.rollback();
                if (solverJobsEnabled && currentJobId != null) {
                    finalizarSolverJob(currentJobId, "CANCELADO", "Job preemptado por solicitacao mais recente", null);
                }
                return new PlanejamentoResultado(0, 0, 0);
            } catch (InterruptedException e) {
                conn.rollback();
                if (solverJobsEnabled && currentJobId != null) {
                    finalizarSolverJob(currentJobId, "FALHOU", "Thread interrompida ao chamar solver", null);
                }
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread interrompida ao chamar solver", e);
            } catch (Exception e) {
                conn.rollback();
                if (solverJobsEnabled && currentJobId != null) {
                    finalizarSolverJob(currentJobId, "FALHOU", e.getMessage(), null);
                }
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
                + "'deposito_latitude', 'deposito_longitude', 'frota_perfil_ativo', "
                + "'capacidade_frota_moto', 'capacidade_frota_carro')";

        Map<String, String> configs = new HashMap<>();

        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

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

    private List<PedidoPlanejavel> buscarPedidosParaSolver(Connection conn, int capacidadeLivreTotal)
            throws SQLException {
        // FIFO global (criado_em, id): PENDENTE elegivel + CONFIRMADO sem entrega aberta.
        String sql = "SELECT "
                + "p.id AS pedido_id, "
                + "p.status::text AS pedido_status, "
                + "p.quantidade_galoes, "
                + "p.janela_tipo::text AS janela_tipo, "
                + "p.janela_inicio, "
                + "p.janela_fim, "
                + "c.latitude, "
                + "c.longitude, "
                + "CASE WHEN p.janela_tipo::text = 'HARD' THEN 1 ELSE 2 END AS prioridade "
                + "FROM pedidos p "
                + "JOIN clientes c ON c.id = p.cliente_id "
                + "LEFT JOIN saldo_vales sv ON sv.cliente_id = c.id "
                + "WHERE p.status::text IN ('PENDENTE', 'CONFIRMADO') "
                + "AND (p.metodo_pagamento::text <> 'VALE' OR COALESCE(sv.quantidade, 0) >= p.quantidade_galoes) "
                + "AND NOT EXISTS ("
                + "    SELECT 1 FROM entregas e2 "
                + "    WHERE e2.pedido_id = p.id "
                + "    AND e2.status::text IN ('PENDENTE', 'EM_EXECUCAO')"
                + ") "
                + "ORDER BY p.criado_em, p.id";

        List<PedidoPlanejavel> elegiveis = new ArrayList<>();

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

    private boolean existePedidoSemEntregaAbertaParaPlanejar(Connection conn) throws SQLException {
        String sql = "SELECT 1 "
                + "FROM pedidos p "
                + "JOIN clientes c ON c.id = p.cliente_id "
                + "LEFT JOIN saldo_vales sv ON sv.cliente_id = c.id "
                + "WHERE p.status::text IN ('PENDENTE', 'CONFIRMADO') "
                + "AND (p.metodo_pagamento::text <> 'VALE' OR COALESCE(sv.quantidade, 0) >= p.quantidade_galoes) "
                + "AND NOT EXISTS ("
                + "    SELECT 1 FROM entregas e2 "
                + "    WHERE e2.pedido_id = p.id "
                + "    AND e2.status::text IN ('PENDENTE', 'EM_EXECUCAO')"
                + ") "
                + "LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            return rs.next();
        }
    }

    private void limparCamadaSecundariaPlanejada(Connection conn) throws SQLException {
        String deleteEntregas = "DELETE FROM entregas e "
                + "USING rotas r "
                + "WHERE e.rota_id = r.id "
                + "AND r.data = CURRENT_DATE "
                + "AND r.status::text = 'PLANEJADA'";
        try (PreparedStatement stmt = conn.prepareStatement(deleteEntregas)) {
            stmt.executeUpdate();
        }

        String deleteRotas = "DELETE FROM rotas WHERE data = CURRENT_DATE AND status::text = 'PLANEJADA'";
        try (PreparedStatement stmt = conn.prepareStatement(deleteRotas)) {
            stmt.executeUpdate();
        }
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

    private void validarPoliticaTemporariaCamadaSecundaria(SolverResponse solverResponse) {
        Map<Integer, Integer> rotasPorEntregador = new HashMap<>();
        for (RotaSolver rota : solverResponse.getRotas()) {
            int quantidade = rotasPorEntregador.merge(rota.getEntregadorId(), 1, Integer::sum);
            if (quantidade > 1) {
                throw new IllegalStateException(
                        "Solver violou politica temporaria: mais de uma rota PLANEJADA para entregador "
                                + rota.getEntregadorId());
            }
        }
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

    private List<Integer> calcularCapacidadesPorPolitica(
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
        String sql = "SELECT r.entregador_id, COALESCE(SUM(p.quantidade_galoes), 0) AS carga_comprometida "
                + "FROM rotas r "
                + "JOIN entregas e ON e.rota_id = r.id "
                + "JOIN pedidos p ON p.id = e.pedido_id "
                + "WHERE r.data = CURRENT_DATE "
                + "AND r.status::text = 'EM_ANDAMENTO' "
                + "AND e.status::text IN ('PENDENTE', 'EM_EXECUCAO') "
                + "GROUP BY r.entregador_id";

        Map<Integer, Integer> cargaComprometidaPorEntregador = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
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

            int demanda = item.pedidoSolver().getGaloes();
            if (demanda > capacidadeRestante) {
                // Mantem determinismo por ordem de chegada e permite pular item que nao cabe.
                continue;
            }
            selecionados.add(item);
            capacidadeRestante -= demanda;
        }

        return selecionados;
    }

    private Map<Integer, PedidoPlanejavel> indexarPedidosPorId(List<PedidoPlanejavel> pedidosPlanejaveis) {
        Map<Integer, PedidoPlanejavel> index = new HashMap<>();
        for (PedidoPlanejavel item : pedidosPlanejaveis) {
            index.put(item.pedidoSolver().getPedidoId(), item);
        }
        return index;
    }

    private void confirmarPedidoSeNecessario(Connection conn, PedidoPlanejavel pedidoPlanejavel) throws SQLException {
        if (pedidoPlanejavel == null) {
            throw new IllegalStateException("Solver retornou pedido nao elegivel para o ciclo atual");
        }
        if ("PENDENTE".equals(pedidoPlanejavel.statusPedido())) {
            pedidoLifecycleService.transicionar(
                    conn, pedidoPlanejavel.pedidoSolver().getPedidoId(), PedidoStatus.CONFIRMADO);
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

    private static int resolverCapacidadeVeiculo(Map<String, String> configs) {
        int capacidadePadrao = parseCapacidadePositiva(getObrigatorio(configs, "capacidade_veiculo"), "capacidade_veiculo");
        String perfil = configs.getOrDefault("frota_perfil_ativo", "PADRAO");
        if (perfil == null) {
            return capacidadePadrao;
        }

        String perfilNormalizado = perfil.trim().toUpperCase(Locale.ROOT);
        return switch (perfilNormalizado) {
            case "MOTO" -> parseCapacidadePositiva(
                    configs.getOrDefault("capacidade_frota_moto", Integer.toString(capacidadePadrao)),
                    "capacidade_frota_moto");
            case "CARRO" -> parseCapacidadePositiva(
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

    private boolean hasPlanVersionColumns(Connection conn) throws SQLException {
        return hasColumn(conn, "rotas", "plan_version") && hasColumn(conn, "entregas", "plan_version");
    }

    private boolean hasSolverJobsSchema(Connection conn) throws SQLException {
        return hasTable(conn, "solver_jobs")
                && hasColumn(conn, "solver_jobs", "job_id")
                && hasColumn(conn, "solver_jobs", "status")
                && hasColumn(conn, "solver_jobs", "cancel_requested");
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

    private boolean hasTable(Connection conn, String tabela) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tabela);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
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

    private boolean isPlanejamentoPreemptado(Connection conn, String jobId, boolean solverJobsEnabled)
            throws SQLException {
        if (!isCurrentJobActive(jobId)) {
            return true;
        }
        if (!solverJobsEnabled) {
            return false;
        }
        return isCancelamentoSolicitadoNoBanco(conn, jobId);
    }

    private boolean isCurrentJobActive(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        return jobId.equals(activeJobId.get());
    }

    private boolean isCancelamentoSolicitadoNoBanco(Connection conn, String jobId) throws SQLException {
        String sql = "SELECT cancel_requested, status::text FROM solver_jobs WHERE job_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, jobId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                boolean cancelRequested = rs.getBoolean("cancel_requested");
                String status = rs.getString("status");
                return cancelRequested || "CANCELADO".equals(status);
            }
        }
    }

    private List<String> marcarCancelamentoSolicitadoEmJobsAtivos(Connection conn, int limite) throws SQLException {
        String selectSql = "SELECT job_id FROM solver_jobs "
                + "WHERE status::text IN ('PENDENTE', 'EM_EXECUCAO') "
                + "AND cancel_requested = false "
                + "ORDER BY solicitado_em DESC "
                + "LIMIT ?";

        List<String> jobIds = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setInt(1, Math.max(1, limite));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    jobIds.add(rs.getString("job_id"));
                }
            }
        }

        if (jobIds.isEmpty()) {
            return jobIds;
        }

        String updateSql = "UPDATE solver_jobs "
                + "SET cancel_requested = true, status = ?, finalizado_em = CURRENT_TIMESTAMP "
                + "WHERE job_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            for (String jobId : jobIds) {
                stmt.setObject(1, "CANCELADO", Types.OTHER);
                stmt.setString(2, jobId);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }

        return jobIds;
    }

    private void registrarSolverJobEmExecucao(String jobId, long planVersion, SolverRequest request)
            throws SQLException {
        try (Connection conn = connectionFactory.getConnection()) {
            if (!hasSolverJobsSchema(conn)) {
                return;
            }
            boolean hasRequestPayload = hasColumn(conn, "solver_jobs", "request_payload");
            String requestPayload = gson.toJson(request);
            String sql =
                    "INSERT INTO solver_jobs (job_id, plan_version, status, cancel_requested, solicitado_em, iniciado_em, finalizado_em, erro) "
                            + "VALUES (?, ?, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL) "
                            + "ON CONFLICT (job_id) DO UPDATE SET "
                            + "plan_version = EXCLUDED.plan_version, "
                            + "status = EXCLUDED.status, "
                            + "cancel_requested = false, "
                            + "solicitado_em = CURRENT_TIMESTAMP, "
                            + "iniciado_em = CURRENT_TIMESTAMP, "
                            + "finalizado_em = NULL, "
                            + "erro = NULL";
            if (hasRequestPayload) {
                sql =
                        "INSERT INTO solver_jobs (job_id, plan_version, status, cancel_requested, solicitado_em, iniciado_em, finalizado_em, erro, request_payload) "
                                + "VALUES (?, ?, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, CAST(? AS jsonb)) "
                                + "ON CONFLICT (job_id) DO UPDATE SET "
                                + "plan_version = EXCLUDED.plan_version, "
                                + "status = EXCLUDED.status, "
                                + "cancel_requested = false, "
                                + "solicitado_em = CURRENT_TIMESTAMP, "
                                + "iniciado_em = CURRENT_TIMESTAMP, "
                                + "finalizado_em = NULL, "
                                + "erro = NULL, "
                                + "request_payload = EXCLUDED.request_payload";
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, jobId);
                stmt.setLong(2, planVersion);
                stmt.setObject(3, "EM_EXECUCAO", Types.OTHER);
                if (hasRequestPayload) {
                    stmt.setString(4, requestPayload);
                }
                stmt.executeUpdate();
            }
        }
    }

    private void finalizarSolverJob(String jobId, String status, String erro, SolverResponse response) {
        try (Connection conn = connectionFactory.getConnection()) {
            if (!hasSolverJobsSchema(conn)) {
                return;
            }
            boolean hasResponsePayload = hasColumn(conn, "solver_jobs", "response_payload");
            String responsePayload = response == null ? null : gson.toJson(response);
            String sql = "UPDATE solver_jobs "
                    + "SET status = ?, finalizado_em = CURRENT_TIMESTAMP, erro = ?, "
                    + "cancel_requested = CASE WHEN ? = 'CANCELADO' THEN true ELSE cancel_requested END "
                    + "WHERE job_id = ?";
            if (hasResponsePayload) {
                sql = "UPDATE solver_jobs "
                        + "SET status = ?, finalizado_em = CURRENT_TIMESTAMP, erro = ?, "
                        + "cancel_requested = CASE WHEN ? = 'CANCELADO' THEN true ELSE cancel_requested END, "
                        + "response_payload = CASE WHEN ? IS NULL THEN NULL ELSE CAST(? AS jsonb) END "
                        + "WHERE job_id = ?";
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, status, Types.OTHER);
                stmt.setString(2, erro);
                stmt.setString(3, status);
                if (hasResponsePayload) {
                    stmt.setString(4, responsePayload);
                    stmt.setString(5, responsePayload);
                    stmt.setString(6, jobId);
                } else {
                    stmt.setString(4, jobId);
                }
                stmt.executeUpdate();
            }
        } catch (Exception ignored) {
            // Melhor esforco para trilha operacional de jobs.
        }
    }

    private void clearActiveJob(String currentJobId) {
        activeJobId.compareAndSet(currentJobId, null);
    }

    private static final class PlanejamentoPreemptadoException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record ConfiguracaoRoteirizacao(
            int capacidadeVeiculo, String horarioInicio, String horarioFim, double depositoLat, double depositoLon) {}

    private record PedidoPlanejavel(PedidoSolver pedidoSolver, String statusPedido) {}
}
