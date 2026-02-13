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

    private final ConnectionFactory connectionFactory;
    private final PedidoLifecycleService lifecycleService;
    private final DispatchEventService dispatchEventService;

    public ExecucaoEntregaService(ConnectionFactory connectionFactory) {
        this(connectionFactory, new PedidoLifecycleService(), new DispatchEventService());
    }

    ExecucaoEntregaService(ConnectionFactory connectionFactory,
                           PedidoLifecycleService lifecycleService,
                           DispatchEventService dispatchEventService) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "PedidoLifecycleService nao pode ser nulo");
        this.dispatchEventService = Objects.requireNonNull(
                dispatchEventService, "DispatchEventService nao pode ser nulo"
        );
    }

    public ExecucaoEntregaResultado registrarRotaIniciada(int rotaId) {
        if (rotaId <= 0) {
            throw new IllegalArgumentException("RotaId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertOperationalSchema(conn);
                RotaStatus rota = buscarRotaComLock(conn, rotaId);
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

                dispatchEventService.publicar(
                        conn,
                        DispatchEventTypes.ROTA_INICIADA,
                        "ROTA",
                        (long) rotaId,
                        new RotaIniciadaPayload(rotaId, entregas.size())
                );

                conn.commit();
                return new ExecucaoEntregaResultado(
                        DispatchEventTypes.ROTA_INICIADA,
                        rotaId,
                        entregaIdReferencia,
                        pedidoIdReferencia,
                        idempotente
                );
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

    public ExecucaoEntregaResultado registrarPedidoEntregue(int entregaId) {
        return finalizarEntrega(entregaId, "ENTREGUE", DispatchEventTypes.PEDIDO_ENTREGUE, null, null);
    }

    public ExecucaoEntregaResultado registrarPedidoFalhou(int entregaId, String motivo) {
        return finalizarEntrega(
                entregaId,
                "FALHOU",
                DispatchEventTypes.PEDIDO_FALHOU,
                new PedidoLifecycleService.TransitionContext(motivo, 0),
                motivo
        );
    }

    public ExecucaoEntregaResultado registrarPedidoCancelado(int entregaId, String motivo, Integer cobrancaCentavos) {
        return finalizarEntrega(
                entregaId,
                "CANCELADA",
                DispatchEventTypes.PEDIDO_CANCELADO,
                new PedidoLifecycleService.TransitionContext(motivo, cobrancaCentavos),
                motivo
        );
    }

    private ExecucaoEntregaResultado finalizarEntrega(
            int entregaId,
            String entregaStatusDestino,
            String eventType,
            PedidoLifecycleService.TransitionContext transitionContext,
            String motivo
    ) {
        if (entregaId <= 0) {
            throw new IllegalArgumentException("EntregaId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertOperationalSchema(conn);
                EntregaComPedido entrega = buscarEntregaComLock(conn, entregaId);

                if ("ENTREGUE".equals(entrega.statusEntrega())
                        || "FALHOU".equals(entrega.statusEntrega())
                        || "CANCELADA".equals(entrega.statusEntrega())) {
                    conn.commit();
                    return new ExecucaoEntregaResultado(
                            eventType,
                            entrega.rotaId(),
                            entrega.idEntrega(),
                            entrega.pedidoId(),
                            true
                    );
                }

                atualizarStatusEntrega(conn, entregaId, entregaStatusDestino, true);

                if ("ENTREGUE".equals(entregaStatusDestino)) {
                    lifecycleService.transicionar(conn, entrega.pedidoId(), PedidoStatus.ENTREGUE);
                } else {
                    lifecycleService.transicionar(
                            conn,
                            entrega.pedidoId(),
                            PedidoStatus.CANCELADO,
                            transitionContext == null ? PedidoLifecycleService.TransitionContext.vazio() : transitionContext
                    );
                }

                atualizarRotaParaConcluidaSeCabivel(conn, entrega.rotaId());

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
                                motivo
                        )
                );

                conn.commit();
                return new ExecucaoEntregaResultado(
                        eventType,
                        entrega.rotaId(),
                        entrega.idEntrega(),
                        entrega.pedidoId(),
                        false
                );
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
        String sql = "SELECT id, status::text FROM rotas WHERE id = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rotaId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Rota nao encontrada com id: " + rotaId);
                }
                return new RotaStatus(rs.getInt("id"), rs.getString("status"));
            }
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
        String sql = "SELECT id, pedido_id FROM entregas WHERE rota_id = ? AND status::text = 'PENDENTE' ORDER BY ordem_na_rota";
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
        String sql = "SELECT e.id AS entrega_id, e.status::text AS entrega_status, e.rota_id, p.id AS pedido_id "
                + "FROM entregas e "
                + "JOIN pedidos p ON p.id = e.pedido_id "
                + "WHERE e.id = ? "
                + "FOR UPDATE OF e, p";
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
                        rs.getInt("pedido_id")
                );
            }
        }
    }

    private void atualizarStatusEntrega(Connection conn, int entregaId, String status, boolean setHoraReal) throws SQLException {
        String sql = setHoraReal
                ? "UPDATE entregas SET status = ?, hora_real = CURRENT_TIMESTAMP, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?"
                : "UPDATE entregas SET status = ?, atualizado_em = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, status, Types.OTHER);
            stmt.setInt(2, entregaId);
            stmt.executeUpdate();
        }
    }

    private void atualizarRotaParaConcluidaSeCabivel(Connection conn, int rotaId) throws SQLException {
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

        if (abertas == 0) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE rotas SET status = ?, fim = COALESCE(fim, CURRENT_TIMESTAMP) WHERE id = ?")) {
                stmt.setObject(1, "CONCLUIDA", Types.OTHER);
                stmt.setInt(2, rotaId);
                stmt.executeUpdate();
            }
        }
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

    private record RotaStatus(int id, String status) {
    }

    private record EntregaPedidoRef(int entregaId, int pedidoId) {
    }

    private record EntregaComPedido(int idEntrega, String statusEntrega, int rotaId, int pedidoId) {
    }

    private record RotaIniciadaPayload(int rotaId, int entregasEmExecucao) {
    }

    private record EntregaAtualizadaPayload(int rotaId, int entregaId, int pedidoId, String statusEntrega, String motivo) {
    }
}
