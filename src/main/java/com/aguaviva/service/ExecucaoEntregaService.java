package com.aguaviva.service;

import com.aguaviva.domain.pedido.PedidoStatus;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.ExecucaoEntregaRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public class ExecucaoEntregaService {

    private static final String METODO_PAGAMENTO_VALE = "VALE";

    private final ConnectionFactory connectionFactory;
    private final PedidoLifecycleService lifecycleService;
    private final DispatchEventService dispatchEventService;
    private final ExecucaoEntregaRepository repository;

    public ExecucaoEntregaService(ConnectionFactory connectionFactory) {
        this(connectionFactory, new PedidoLifecycleService(), new DispatchEventService());
    }

    ExecucaoEntregaService(
            ConnectionFactory connectionFactory,
            PedidoLifecycleService lifecycleService,
            DispatchEventService dispatchEventService) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "PedidoLifecycleService nao pode ser nulo");
        this.dispatchEventService =
                Objects.requireNonNull(dispatchEventService, "DispatchEventService nao pode ser nulo");
        this.repository = new ExecucaoEntregaRepository(connectionFactory);
    }

    public ExecucaoEntregaResultado registrarRotaIniciada(int rotaId) {
        return registrarRotaIniciada(rotaId, null);
    }

    public ExecucaoEntregaResultado registrarRotaIniciada(int rotaId, Integer actorEntregadorId) {
        if (rotaId <= 0) {
            throw new IllegalArgumentException("RotaId deve ser maior que zero");
        }
        if (actorEntregadorId != null && actorEntregadorId <= 0) {
            throw new IllegalArgumentException("actorEntregadorId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertOperationalSchema(conn);
                ExecucaoEntregaResultado resultado = iniciarRotaInterno(conn, rotaId, actorEntregadorId);
                conn.commit();
                return resultado;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao registrar inicio da rota", e);
        }
    }

    public ExecucaoEntregaResultado iniciarProximaRotaPronta(int entregadorId) {
        if (entregadorId <= 0) {
            throw new IllegalArgumentException("entregadorId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertOperationalSchema(conn);
                if (repository.existeRotaEmAndamento(conn, entregadorId)) {
                    throw new IllegalStateException("Entregador ja possui rota EM_ANDAMENTO");
                }

                Integer rotaId = repository.buscarProximaRotaPlanejada(conn, entregadorId);
                if (rotaId == null) {
                    throw new IllegalStateException("Entregador nao possui rota PLANEJADA pronta para iniciar");
                }

                ExecucaoEntregaResultado resultado = iniciarRotaInterno(conn, rotaId, entregadorId);
                conn.commit();
                return resultado;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao iniciar proxima rota pronta", e);
        }
    }

    public ExecucaoEntregaResultado registrarPedidoEntregue(int entregaId) {
        return registrarPedidoEntregue(entregaId, null);
    }

    public ExecucaoEntregaResultado registrarPedidoEntregue(int entregaId, Integer actorEntregadorId) {
        return finalizarEntrega(
                entregaId, "ENTREGUE", DispatchEventTypes.PEDIDO_ENTREGUE, null, null, actorEntregadorId);
    }

    public ExecucaoEntregaResultado registrarPedidoFalhou(int entregaId, String motivo) {
        return registrarPedidoFalhou(entregaId, motivo, null);
    }

    public ExecucaoEntregaResultado registrarPedidoFalhou(int entregaId, String motivo, Integer actorEntregadorId) {
        return finalizarEntrega(
                entregaId,
                "FALHOU",
                DispatchEventTypes.PEDIDO_FALHOU,
                new PedidoLifecycleService.TransitionContext(motivo, 0),
                motivo,
                actorEntregadorId);
    }

    public ExecucaoEntregaResultado registrarPedidoCancelado(int entregaId, String motivo, Integer cobrancaCentavos) {
        return registrarPedidoCancelado(entregaId, motivo, cobrancaCentavos, null);
    }

    public ExecucaoEntregaResultado registrarPedidoCancelado(
            int entregaId, String motivo, Integer cobrancaCentavos, Integer actorEntregadorId) {
        return finalizarEntrega(
                entregaId,
                "CANCELADA",
                DispatchEventTypes.PEDIDO_CANCELADO,
                new PedidoLifecycleService.TransitionContext(motivo, cobrancaCentavos),
                motivo,
                actorEntregadorId);
    }

    private ExecucaoEntregaResultado finalizarEntrega(
            int entregaId,
            String entregaStatusDestino,
            String eventType,
            PedidoLifecycleService.TransitionContext transitionContext,
            String motivo,
            Integer actorEntregadorId) {
        if (entregaId <= 0) {
            throw new IllegalArgumentException("EntregaId deve ser maior que zero");
        }
        if (actorEntregadorId != null && actorEntregadorId <= 0) {
            throw new IllegalArgumentException("actorEntregadorId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertOperationalSchema(conn);
                ExecucaoEntregaRepository.EntregaComPedido entrega = repository.buscarEntregaComLock(conn, entregaId);
                validarActorEntregador(actorEntregadorId, entrega.entregadorId());

                if (isTerminalStatus(entrega.statusEntrega())) {
                    String statusEsperadoParaEvento = statusFinalEsperadoParaEvento(eventType);
                    if (!statusEsperadoParaEvento.equals(entrega.statusEntrega())) {
                        throw new IllegalStateException("Entrega ja finalizada com status "
                                + entrega.statusEntrega()
                                + " e nao aceita evento "
                                + eventType);
                    }
                    conn.commit();
                    return new ExecucaoEntregaResultado(
                            eventType, entrega.rotaId(), entrega.idEntrega(), entrega.pedidoId(), true);
                }
                if (!"EM_EXECUCAO".equals(entrega.statusEntrega())) {
                    throw new IllegalStateException("Evento terminal exige entrega em status EM_EXECUCAO");
                }

                repository.atualizarStatusEntrega(conn, entregaId, entregaStatusDestino, true);
                int actorEntregadorAuditoria = actorEntregadorId != null ? actorEntregadorId : entrega.entregadorId();

                if ("ENTREGUE".equals(entregaStatusDestino)) {
                    lifecycleService.transicionar(conn, entrega.pedidoId(), PedidoStatus.ENTREGUE);
                    debitarValeSeNecessario(conn, entrega);
                } else {
                    lifecycleService.transicionar(
                            conn,
                            entrega.pedidoId(),
                            PedidoStatus.CANCELADO,
                            transitionContext == null
                                    ? PedidoLifecycleService.TransitionContext.vazio()
                                    : transitionContext);
                }

                boolean rotaConcluida = repository.atualizarRotaParaConcluidaSeCabivel(conn, entrega.rotaId());

                dispatchEventService.publicar(
                        conn,
                        eventType,
                        "PEDIDO",
                        (long) entrega.pedidoId(),
                        new EntregaAtualizadaPayload(
                                entrega.rotaId(),
                                entrega.idEntrega(),
                                entrega.pedidoId(),
                                entregaStatusDestino,
                                motivo,
                                actorEntregadorAuditoria));
                if (rotaConcluida) {
                    dispatchEventService.publicar(
                            conn,
                            DispatchEventTypes.ROTA_CONCLUIDA,
                            "ROTA",
                            (long) entrega.rotaId(),
                            new RotaConcluidaPayload(entrega.rotaId()));
                }

                conn.commit();
                return new ExecucaoEntregaResultado(
                        eventType, entrega.rotaId(), entrega.idEntrega(), entrega.pedidoId(), false);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao registrar evento de entrega", e);
        }
    }

    private ExecucaoEntregaResultado iniciarRotaInterno(Connection conn, int rotaId, Integer actorEntregadorId)
            throws SQLException {
        ExecucaoEntregaRepository.RotaStatus rota = repository.buscarRotaComLock(conn, rotaId);
        validarActorEntregador(actorEntregadorId, rota.entregadorId());
        boolean idempotente = "EM_ANDAMENTO".equals(rota.status());

        if ("CONCLUIDA".equals(rota.status())) {
            throw new IllegalStateException("Rota ja concluida e nao pode ser reiniciada");
        }

        if (!idempotente) {
            repository.atualizarRotaParaEmAndamento(conn, rotaId);
        }

        List<ExecucaoEntregaRepository.EntregaPedidoRef> entregas = repository.buscarEntregasPendentesDaRota(conn, rotaId);
        int pedidoIdReferencia = 0;
        int entregaIdReferencia = 0;
        for (ExecucaoEntregaRepository.EntregaPedidoRef ref : entregas) {
            repository.atualizarStatusEntrega(conn, ref.entregaId(), "EM_EXECUCAO", false);
            lifecycleService.transicionar(conn, ref.pedidoId(), PedidoStatus.EM_ROTA);
            pedidoIdReferencia = ref.pedidoId();
            entregaIdReferencia = ref.entregaId();
        }

        // Em chamadas idempotentes sem novas entregas pendentes, evita duplicar evento no outbox.
        if (!idempotente || !entregas.isEmpty()) {
            dispatchEventService.publicar(
                    conn,
                    DispatchEventTypes.ROTA_INICIADA,
                    "ROTA",
                    (long) rotaId,
                    new RotaIniciadaPayload(rotaId, entregas.size()));
        }

        return new ExecucaoEntregaResultado(
                DispatchEventTypes.ROTA_INICIADA, rotaId, entregaIdReferencia, pedidoIdReferencia, idempotente);
    }

    private void validarActorEntregador(Integer actorEntregadorId, int entregadorEsperado) {
        if (actorEntregadorId == null) {
            return;
        }
        if (actorEntregadorId != entregadorEsperado) {
            throw new IllegalStateException(
                    "Evento operacional invalido: actorEntregadorId nao corresponde ao entregador da rota");
        }
    }

    private void debitarValeSeNecessario(Connection conn, ExecucaoEntregaRepository.EntregaComPedido entrega)
            throws SQLException {
        if (!METODO_PAGAMENTO_VALE.equals(entrega.metodoPagamento())) {
            return;
        }

        if (!repository.registrarDebitoValeSeAusente(conn, entrega)) {
            return;
        }

        int saldoAtualizado = repository.debitarSaldoVale(conn, entrega.clienteId(), entrega.quantidadeGaloes());
        if (saldoAtualizado == 0) {
            throw new IllegalStateException("cliente nao possui vale suficiente para concluir a entrega");
        }
    }

    private void assertOperationalSchema(Connection conn) throws SQLException {
        dispatchEventService.assertSchema(conn);
        if (!repository.hasEnumValue(conn, "entrega_status", "EM_EXECUCAO")) {
            throw new IllegalStateException("Schema desatualizado: entrega_status sem valor EM_EXECUCAO");
        }
        if (!repository.hasEnumValue(conn, "entrega_status", "CANCELADA")) {
            throw new IllegalStateException("Schema desatualizado: entrega_status sem valor CANCELADA");
        }
    }

    private boolean isTerminalStatus(String statusEntrega) {
        return "ENTREGUE".equals(statusEntrega) || "FALHOU".equals(statusEntrega) || "CANCELADA".equals(statusEntrega);
    }

    private String statusFinalEsperadoParaEvento(String eventType) {
        return switch (eventType) {
            case DispatchEventTypes.PEDIDO_ENTREGUE -> "ENTREGUE";
            case DispatchEventTypes.PEDIDO_FALHOU -> "FALHOU";
            case DispatchEventTypes.PEDIDO_CANCELADO -> "CANCELADA";
            default -> throw new IllegalArgumentException("eventType terminal invalido: " + eventType);
        };
    }

    private record RotaIniciadaPayload(int rotaId, int entregasEmExecucao) {}

    private record RotaConcluidaPayload(int rotaId) {}

    private record EntregaAtualizadaPayload(
            int rotaId, int entregaId, int pedidoId, String statusEntrega, String motivo, int actorEntregadorId) {}
}
