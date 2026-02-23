package com.aguaviva.service;

import com.aguaviva.domain.pedido.PedidoStatus;
import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ExecucaoEntregaService {

    private static final String METODO_PAGAMENTO_VALE = "VALE";
    private static final String TIPO_MOVIMENTACAO_DEBITO = "DEBITO";

    private final ConnectionFactory connectionFactory;
    private final PedidoLifecycleService lifecycleService;
    private final DispatchEventService dispatchEventService;

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
                if (existeRotaEmAndamento(conn, entregadorId)) {
                    throw new IllegalStateException("Entregador ja possui rota EM_ANDAMENTO");
                }

                Integer rotaId = buscarProximaRotaPlanejada(conn, entregadorId);
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
                EntregaComPedido entrega = buscarEntregaComLock(conn, entregaId);
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

                atualizarStatusEntrega(conn, entregaId, entregaStatusDestino, true);

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

                boolean rotaConcluida = atualizarRotaParaConcluidaSeCabivel(conn, entrega.rotaId());

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
                                motivo));
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

    private RotaStatus buscarRotaComLock(Connection conn, int rotaId) throws SQLException {
        String sql = "SELECT id, status::text, entregador_id FROM rotas WHERE id = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rotaId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Rota nao encontrada com id: " + rotaId);
                }
                return new RotaStatus(rs.getInt("id"), rs.getString("status"), rs.getInt("entregador_id"));
            }
        }
    }

    private ExecucaoEntregaResultado iniciarRotaInterno(Connection conn, int rotaId, Integer actorEntregadorId)
            throws SQLException {
        RotaStatus rota = buscarRotaComLock(conn, rotaId);
        validarActorEntregador(actorEntregadorId, rota.entregadorId());
        boolean idempotente = "EM_ANDAMENTO".equals(rota.status());

        if ("CONCLUIDA".equals(rota.status())) {
            throw new IllegalStateException("Rota ja concluida e nao pode ser reiniciada");
        }

        if (!idempotente) {
            atualizarRotaParaEmAndamento(conn, rotaId);
        }

        List<EntregaPedidoRef> entregas = buscarEntregasPendentesDaRota(conn, rotaId);
        int pedidoIdReferencia = 0;
        int entregaIdReferencia = 0;
        for (EntregaPedidoRef ref : entregas) {
            atualizarStatusEntrega(conn, ref.entregaId(), "EM_EXECUCAO", false);
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

    private boolean existeRotaEmAndamento(Connection conn, int entregadorId) throws SQLException {
        String sql = "SELECT 1 FROM rotas "
                + "WHERE entregador_id = ? "
                + "AND data = CURRENT_DATE "
                + "AND status::text = 'EM_ANDAMENTO' "
                + "LIMIT 1 FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Integer buscarProximaRotaPlanejada(Connection conn, int entregadorId) throws SQLException {
        String sql = "SELECT id FROM rotas "
                + "WHERE entregador_id = ? "
                + "AND data = CURRENT_DATE "
                + "AND status::text = 'PLANEJADA' "
                + "ORDER BY numero_no_dia, id "
                + "LIMIT 1 FOR UPDATE SKIP LOCKED";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getInt("id");
            }
        }
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

    private void atualizarRotaParaEmAndamento(Connection conn, int rotaId) throws SQLException {
        String sql = "UPDATE rotas SET status = ?, inicio = COALESCE(inicio, CURRENT_TIMESTAMP) WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, "EM_ANDAMENTO", Types.OTHER);
            stmt.setInt(2, rotaId);
            stmt.executeUpdate();
        }
    }

    private List<EntregaPedidoRef> buscarEntregasPendentesDaRota(Connection conn, int rotaId) throws SQLException {
        String sql =
                "SELECT id, pedido_id FROM entregas WHERE rota_id = ? AND status::text = 'PENDENTE' ORDER BY ordem_na_rota";
        List<EntregaPedidoRef> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rotaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new EntregaPedidoRef(rs.getInt("id"), rs.getInt("pedido_id")));
                }
            }
        }
        return result;
    }

    private EntregaComPedido buscarEntregaComLock(Connection conn, int entregaId) throws SQLException {
        String sql = "SELECT e.id AS entrega_id, "
                + "e.status::text AS entrega_status, "
                + "e.rota_id, "
                + "p.id AS pedido_id, "
                + "p.cliente_id, "
                + "p.quantidade_galoes, "
                + "p.metodo_pagamento::text AS metodo_pagamento, "
                + "r.entregador_id "
                + "FROM entregas e "
                + "JOIN pedidos p ON p.id = e.pedido_id "
                + "JOIN rotas r ON r.id = e.rota_id "
                + "WHERE e.id = ? "
                + "FOR UPDATE OF e, p, r";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregaId);
            try (ResultSet rs = stmt.executeQuery()) {
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

    private void debitarValeSeNecessario(Connection conn, EntregaComPedido entrega) throws SQLException {
        if (!METODO_PAGAMENTO_VALE.equals(entrega.metodoPagamento())) {
            return;
        }

        if (!registrarDebitoValeSeAusente(conn, entrega)) {
            return;
        }

        int saldoAtualizado = debitarSaldoVale(conn, entrega.clienteId(), entrega.quantidadeGaloes());
        if (saldoAtualizado == 0) {
            throw new IllegalStateException("cliente nao possui vale suficiente para concluir a entrega");
        }
    }

    private boolean registrarDebitoValeSeAusente(Connection conn, EntregaComPedido entrega) throws SQLException {
        String sql = "INSERT INTO movimentacao_vales "
                + "(cliente_id, tipo, quantidade, pedido_id, registrado_por, observacao) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING "
                + "RETURNING id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entrega.clienteId());
            stmt.setObject(2, TIPO_MOVIMENTACAO_DEBITO, Types.OTHER);
            stmt.setInt(3, entrega.quantidadeGaloes());
            stmt.setInt(4, entrega.pedidoId());
            stmt.setInt(5, entrega.entregadorId());
            stmt.setString(6, "Debito automatico na entrega do pedido " + entrega.pedidoId());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int debitarSaldoVale(Connection conn, int clienteId, int quantidade) throws SQLException {
        String sql = "UPDATE saldo_vales "
                + "SET quantidade = quantidade - ?, atualizado_em = CURRENT_TIMESTAMP "
                + "WHERE cliente_id = ? AND quantidade >= ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, quantidade);
            stmt.setInt(2, clienteId);
            stmt.setInt(3, quantidade);
            return stmt.executeUpdate();
        }
    }

    private void atualizarStatusEntrega(Connection conn, int entregaId, String status, boolean setHoraReal)
            throws SQLException {
        String sql = setHoraReal
                ? "UPDATE entregas SET status = ?, hora_real = CURRENT_TIMESTAMP, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?"
                : "UPDATE entregas SET status = ?, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, status, Types.OTHER);
            stmt.setInt(2, entregaId);
            stmt.executeUpdate();
        }
    }

    private boolean atualizarRotaParaConcluidaSeCabivel(Connection conn, int rotaId) throws SQLException {
        String sql = "SELECT COUNT(*) FILTER (WHERE status::text IN ('PENDENTE', 'EM_EXECUCAO')) AS abertas "
                + "FROM entregas WHERE rota_id = ?";
        int abertas;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rotaId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                abertas = rs.getInt("abertas");
            }
        }

        if (abertas != 0) {
            return false;
        }

        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE rotas SET status = ?, fim = COALESCE(fim, CURRENT_TIMESTAMP) WHERE id = ?")) {
            stmt.setObject(1, "CONCLUIDA", Types.OTHER);
            stmt.setInt(2, rotaId);
            stmt.executeUpdate();
        }
        return true;
    }

    private void assertOperationalSchema(Connection conn) throws SQLException {
        dispatchEventService.assertSchema(conn);
        if (!hasEnumValue(conn, "entrega_status", "EM_EXECUCAO")) {
            throw new IllegalStateException("Schema desatualizado: entrega_status sem valor EM_EXECUCAO");
        }
        if (!hasEnumValue(conn, "entrega_status", "CANCELADA")) {
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

    private boolean hasEnumValue(Connection conn, String typeName, String enumLabel) throws SQLException {
        String sql = "SELECT 1 "
                + "FROM pg_type t "
                + "JOIN pg_enum e ON e.enumtypid = t.oid "
                + "WHERE t.typname = ? AND e.enumlabel = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, typeName);
            stmt.setString(2, enumLabel);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record RotaStatus(int id, String status, int entregadorId) {}

    private record EntregaPedidoRef(int entregaId, int pedidoId) {}

    private record EntregaComPedido(
            int idEntrega,
            String statusEntrega,
            int rotaId,
            int pedidoId,
            int clienteId,
            int quantidadeGaloes,
            String metodoPagamento,
            int entregadorId) {}

    private record RotaIniciadaPayload(int rotaId, int entregasEmExecucao) {}

    private record RotaConcluidaPayload(int rotaId) {}

    private record EntregaAtualizadaPayload(
            int rotaId, int entregaId, int pedidoId, String statusEntrega, String motivo) {}
}
