package com.aguaviva.service;

import com.aguaviva.domain.pedido.PedidoStatus;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.RotaRepository;
import com.aguaviva.solver.Coordenada;
import com.aguaviva.solver.Parada;
import com.aguaviva.solver.PedidoSolver;
import com.aguaviva.solver.RotaSolver;
import com.aguaviva.solver.SolverClient;
import com.aguaviva.solver.SolverGateway;
import com.aguaviva.solver.SolverRequest;
import com.aguaviva.solver.SolverResponse;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RotaService {

    static final long PLANEJAMENTO_LOCK_KEY = 61001L;
    private static final int MAX_SOLVER_JOBS_CANCELAMENTO = 8;
    private static final Logger LOGGER = Logger.getLogger(RotaService.class.getName());
    private static final LongAdder CANCELAMENTO_DISCOVERY_FAILURES = new LongAdder();

    private final AtomicReference<String> activeJobId = new AtomicReference<>();

    private final SolverGateway solverClient;
    private final ConnectionFactory connectionFactory;
    private final PedidoLifecycleService pedidoLifecycleService;
    private final RotaRepository repository;
    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .create();

    public RotaService(SolverClient solverClient, ConnectionFactory connectionFactory) {
        this(solverClient, connectionFactory, new PedidoLifecycleService());
    }

    public RotaService(SolverGateway solverClient, ConnectionFactory connectionFactory) {
        this(solverClient, connectionFactory, new PedidoLifecycleService());
    }

    RotaService(
            SolverGateway solverClient,
            ConnectionFactory connectionFactory,
            PedidoLifecycleService pedidoLifecycleService) {
        this.solverClient = Objects.requireNonNull(solverClient, "SolverClient nao pode ser nulo");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.pedidoLifecycleService =
                Objects.requireNonNull(pedidoLifecycleService, "PedidoLifecycleService nao pode ser nulo");
        this.repository = new RotaRepository(connectionFactory);
    }

    public void cancelarPlanejamentosAtivosBestEffort() {
        Set<String> jobIds = new LinkedHashSet<>();

        String jobAtivoLocal = activeJobId.getAndSet(null);
        if (jobAtivoLocal != null && !jobAtivoLocal.isBlank()) {
            jobIds.add(jobAtivoLocal);
        }

        try (var conn = connectionFactory.getConnection()) {
            if (RotaSolverJobSupport.hasSolverJobsSchema(conn)) {
                jobIds.addAll(RotaSolverJobSupport.marcarCancelamentoSolicitadoEmJobsAtivos(
                        conn, MAX_SOLVER_JOBS_CANCELAMENTO));
            }
        } catch (SQLException e) {
            CANCELAMENTO_DISCOVERY_FAILURES.increment();
            LOGGER.log(
                    Level.FINE,
                    "event=planejamento_cancel_discovery_failed sql_state={0} message={1}",
                    new Object[] {e.getSQLState(), e.getMessage()});
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

        try (var conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!repository.tentarAdquirirLockPlanejamento(conn)) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                solverJobsEnabled = RotaSolverJobSupport.hasSolverJobsSchema(conn);
                boolean planVersionEnabled = RotaSolverJobSupport.hasPlanVersionColumns(conn);
                boolean jobIdEnabled = RotaSolverJobSupport.hasJobIdColumns(conn);
                long planVersion =
                        (solverJobsEnabled || planVersionEnabled) ? RotaSolverJobSupport.nextPlanVersion(conn) : 1L;
                currentJobId = buildJobId(planVersion);
                String previousJobId = activeJobId.getAndSet(currentJobId);
                if (previousJobId != null && !previousJobId.equals(currentJobId)) {
                    solverClient.cancelBestEffort(previousJobId);
                }

                RotaRepository.ConfiguracaoRoteirizacao cfg = repository.carregarConfiguracao(conn);
                List<Integer> entregadoresAtivos = repository.buscarEntregadoresAtivos(conn);
                if (entregadoresAtivos.isEmpty()) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                List<Integer> capacidadesEntregadores = repository.calcularCapacidadesPorPolitica(
                        conn, entregadoresAtivos, cfg.capacidadeVeiculo(), capacidadeResolvida);
                int capacidadeLivreTotal = capacidadesEntregadores.stream()
                        .mapToInt(Integer::intValue)
                        .sum();

                if (!repository.existePedidoSemEntregaAbertaParaPlanejar(conn)) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                repository.limparCamadaSecundariaPlanejada(conn);
                List<RotaRepository.PedidoPlanejavel> pedidosPlanejaveis =
                        repository.buscarPedidosParaSolver(conn, capacidadeLivreTotal);
                if (pedidosPlanejaveis.isEmpty()) {
                    conn.commit();
                    return new PlanejamentoResultado(0, 0, 0);
                }

                int rotasCriadas = 0;
                int entregasCriadas = 0;

                List<PedidoSolver> pedidosParaSolver = pedidosPlanejaveis.stream()
                        .map(RotaRepository.PedidoPlanejavel::pedidoSolver)
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
                    RotaSolverJobSupport.registrarSolverJobEmExecucao(
                            connectionFactory, gson, currentJobId, planVersion, request);
                }

                SolverResponse solverResponse = solverClient.solve(request);
                if (isPlanejamentoPreemptado(conn, currentJobId, solverJobsEnabled)) {
                    throw new PlanejamentoPreemptadoException();
                }
                Map<Integer, RotaRepository.PedidoPlanejavel> pedidosPorId = indexarPedidosPorId(pedidosPlanejaveis);
                validarRespostaSolver(solverResponse, entregadoresAtivos, capacidadesEntregadores, pedidosPorId);

                for (RotaSolver rota : solverResponse.rotas()) {
                    int rotaId = repository.inserirRota(
                            conn,
                            rota.entregadorId(),
                            rota.numeroNoDia(),
                            planVersion,
                            planVersionEnabled,
                            currentJobId,
                            jobIdEnabled);
                    rotasCriadas++;

                    for (Parada parada : rota.paradas()) {
                        repository.inserirEntrega(
                                conn, parada, rotaId, planVersion, planVersionEnabled, currentJobId, jobIdEnabled);
                        confirmarPedidoSeNecessario(conn, pedidosPorId.get(parada.pedidoId()));
                        entregasCriadas++;
                    }
                }

                conn.commit();
                if (solverJobsEnabled) {
                    RotaSolverJobSupport.finalizarSolverJob(
                            connectionFactory, gson, currentJobId, "CONCLUIDO", null, solverResponse);
                }
                return new PlanejamentoResultado(
                        rotasCriadas,
                        entregasCriadas,
                        solverResponse.naoAtendidos().size());
            } catch (PlanejamentoPreemptadoException e) {
                conn.rollback();
                if (solverJobsEnabled && currentJobId != null) {
                    RotaSolverJobSupport.finalizarSolverJob(
                            connectionFactory,
                            gson,
                            currentJobId,
                            "CANCELADO",
                            "Job preemptado por solicitacao mais recente",
                            null);
                }
                return new PlanejamentoResultado(0, 0, 0);
            } catch (InterruptedException e) {
                conn.rollback();
                if (solverJobsEnabled && currentJobId != null) {
                    RotaSolverJobSupport.finalizarSolverJob(
                            connectionFactory,
                            gson,
                            currentJobId,
                            "FALHOU",
                            "Thread interrompida ao chamar solver",
                            null);
                }
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread interrompida ao chamar solver", e);
            } catch (Exception e) {
                conn.rollback();
                if (solverJobsEnabled && currentJobId != null) {
                    RotaSolverJobSupport.finalizarSolverJob(
                            connectionFactory, gson, currentJobId, "FALHOU", e.getMessage(), null);
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

    private void validarRespostaSolver(
            SolverResponse solverResponse,
            List<Integer> entregadoresAtivos,
            List<Integer> capacidadesEntregadores,
            Map<Integer, RotaRepository.PedidoPlanejavel> pedidosPorId) {
        Set<Integer> entregadoresNoCiclo = new HashSet<>(entregadoresAtivos);
        Map<Integer, Integer> capacidadePorEntregador =
                mapearCapacidadePorEntregador(entregadoresAtivos, capacidadesEntregadores);
        Set<Integer> pedidosJaPlanejados = new HashSet<>();
        Map<Integer, Integer> rotasPorEntregador = new HashMap<>();

        for (RotaSolver rota : solverResponse.rotas()) {
            int entregadorId = rota.entregadorId();
            if (!entregadoresNoCiclo.contains(entregadorId)) {
                throw new IllegalStateException(
                        "Solver retornou rota para entregador que nao pertence ao ciclo atual: " + entregadorId);
            }

            int quantidade = rotasPorEntregador.merge(rota.entregadorId(), 1, Integer::sum);
            if (quantidade > 1) {
                throw new IllegalStateException(
                        "Solver violou politica temporaria: mais de uma rota PLANEJADA para entregador "
                                + entregadorId);
            }

            int cargaPlanejada = 0;
            for (Parada parada : rota.paradas()) {
                int pedidoId = parada.pedidoId();
                RotaRepository.PedidoPlanejavel pedidoPlanejavel = pedidosPorId.get(pedidoId);
                if (pedidoPlanejavel == null) {
                    throw new IllegalStateException(
                            "Solver retornou pedido nao elegivel para o ciclo atual: " + pedidoId);
                }
                if (!pedidosJaPlanejados.add(pedidoId)) {
                    throw new IllegalStateException("Solver retornou pedido duplicado no ciclo atual: " + pedidoId);
                }
                cargaPlanejada += pedidoPlanejavel.pedidoSolver().galoes();
            }

            int capacidadeEntregador = capacidadePorEntregador.getOrDefault(entregadorId, 0);
            if (cargaPlanejada > capacidadeEntregador) {
                throw new IllegalStateException("Solver retornou carga acima da capacidade para entregador "
                        + entregadorId
                        + " (carga="
                        + cargaPlanejada
                        + ", capacidade="
                        + capacidadeEntregador
                        + ")");
            }
        }
    }

    private Map<Integer, Integer> mapearCapacidadePorEntregador(
            List<Integer> entregadoresAtivos, List<Integer> capacidadesEntregadores) {
        if (entregadoresAtivos.size() != capacidadesEntregadores.size()) {
            throw new IllegalStateException("Configuracao invalida: capacidades por entregador inconsistente");
        }
        Map<Integer, Integer> capacidades = new HashMap<>(entregadoresAtivos.size());
        for (int i = 0; i < entregadoresAtivos.size(); i++) {
            capacidades.put(entregadoresAtivos.get(i), Math.max(0, capacidadesEntregadores.get(i)));
        }
        return capacidades;
    }

    private Map<Integer, RotaRepository.PedidoPlanejavel> indexarPedidosPorId(
            List<RotaRepository.PedidoPlanejavel> pedidosPlanejaveis) {
        Map<Integer, RotaRepository.PedidoPlanejavel> index = new HashMap<>();
        for (RotaRepository.PedidoPlanejavel item : pedidosPlanejaveis) {
            index.put(item.pedidoSolver().pedidoId(), item);
        }
        return index;
    }

    private void confirmarPedidoSeNecessario(Connection conn, RotaRepository.PedidoPlanejavel pedidoPlanejavel)
            throws SQLException {
        if (pedidoPlanejavel == null) {
            throw new IllegalStateException("Solver retornou pedido nao elegivel para o ciclo atual");
        }
        if ("PENDENTE".equals(pedidoPlanejavel.statusPedido())) {
            pedidoLifecycleService.transicionar(
                    conn, pedidoPlanejavel.pedidoSolver().pedidoId(), PedidoStatus.CONFIRMADO);
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
        return RotaSolverJobSupport.isCancelamentoSolicitadoNoBanco(conn, jobId);
    }

    private boolean isCurrentJobActive(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        return jobId.equals(activeJobId.get());
    }

    private void clearActiveJob(String currentJobId) {
        activeJobId.compareAndSet(currentJobId, null);
    }

    private static final class PlanejamentoPreemptadoException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
