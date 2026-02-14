package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class PedidoTimelineService {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ConnectionFactory connectionFactory;

    public PedidoTimelineService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public PedidoTimelineResultado consultarTimeline(int pedidoId) {
        if (pedidoId <= 0) {
            throw new IllegalArgumentException("pedidoId deve ser maior que zero");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            PedidoAtual pedido = buscarPedido(conn, pedidoId);
            List<TimelineEvent> eventos = new ArrayList<>();
            boolean encontrouEventoCriacao = false;

            for (EventoDispatch evento : buscarEventosPedido(conn, pedidoId)) {
                switch (evento.eventType()) {
                    case DispatchEventTypes.PEDIDO_CRIADO -> {
                        eventos.add(new TimelineEvent(
                                evento.createdEm(),
                                "NOVO",
                                "PENDENTE",
                                "Atendimento",
                                null
                        ));
                        encontrouEventoCriacao = true;
                    }
                    case DispatchEventTypes.PEDIDO_ENTREGUE -> eventos.add(new TimelineEvent(
                            evento.createdEm(),
                            "EM_ROTA",
                            "ENTREGUE",
                            "Evento operacional",
                            null
                    ));
                    case DispatchEventTypes.PEDIDO_FALHOU, DispatchEventTypes.PEDIDO_CANCELADO -> eventos.add(new TimelineEvent(
                            evento.createdEm(),
                            "EM_ROTA",
                            "CANCELADO",
                            "Evento operacional",
                            normalizeText(evento.motivo())
                    ));
                    default -> {
                    }
                }
            }

            if (!encontrouEventoCriacao) {
                eventos.add(new TimelineEvent(
                        pedido.criadoEm(),
                        "NOVO",
                        "PENDENTE",
                        "Atendimento",
                        null
                ));
            }

            Integer rotaId = buscarRotaDoPedido(conn, pedidoId);
            if (rotaId != null) {
                LocalDateTime rotaIniciada = buscarRotaIniciada(conn, rotaId);
                if (rotaIniciada != null) {
                    eventos.add(new TimelineEvent(
                            rotaIniciada,
                            "CONFIRMADO",
                            "EM_ROTA",
                            "Despacho",
                            null
                    ));
                }
            }

            eventos.sort(Comparator
                    .comparing(TimelineEvent::timestamp)
                    .thenComparing(TimelineEvent::deStatus)
                    .thenComparing(TimelineEvent::paraStatus));

            List<EventoTimelineResultado> respostaEventos = eventos.stream()
                    .map(PedidoTimelineService::toResponseEvent)
                    .toList();

            return new PedidoTimelineResultado(
                    pedidoId,
                    pedido.statusAtual(),
                    respostaEventos
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar timeline do pedido", e);
        }
    }

    private PedidoAtual buscarPedido(Connection conn, int pedidoId) throws SQLException {
        String sql = "SELECT status::text, criado_em FROM pedidos WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Pedido nao encontrado com id: " + pedidoId);
                }
                return new PedidoAtual(
                        rs.getString("status"),
                        rs.getObject("criado_em", LocalDateTime.class)
                );
            }
        }
    }

    private List<EventoDispatch> buscarEventosPedido(Connection conn, int pedidoId) throws SQLException {
        String sql = "SELECT event_type, created_em, payload ->> 'motivo' AS motivo "
                + "FROM dispatch_events "
                + "WHERE aggregate_type = 'PEDIDO' "
                + "AND aggregate_id = ? "
                + "ORDER BY created_em, id";

        List<EventoDispatch> eventos = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    eventos.add(new EventoDispatch(
                            rs.getString("event_type"),
                            rs.getObject("created_em", LocalDateTime.class),
                            rs.getString("motivo")
                    ));
                }
            }
        }
        return eventos;
    }

    private Integer buscarRotaDoPedido(Connection conn, int pedidoId) throws SQLException {
        String sql = "SELECT rota_id FROM entregas WHERE pedido_id = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("rota_id");
                }
                return null;
            }
        }
    }

    private LocalDateTime buscarRotaIniciada(Connection conn, int rotaId) throws SQLException {
        String sql = "SELECT created_em FROM dispatch_events "
                + "WHERE aggregate_type = 'ROTA' "
                + "AND aggregate_id = ? "
                + "AND event_type = ? "
                + "ORDER BY created_em, id LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, rotaId);
            stmt.setString(2, DispatchEventTypes.ROTA_INICIADA);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("created_em", LocalDateTime.class);
                }
                return null;
            }
        }
    }

    private static EventoTimelineResultado toResponseEvent(TimelineEvent event) {
        return new EventoTimelineResultado(
                TIMESTAMP_FORMATTER.format(event.timestamp()),
                event.deStatus(),
                event.paraStatus(),
                event.origem(),
                event.observacao()
        );
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private record PedidoAtual(String statusAtual, LocalDateTime criadoEm) {
    }

    private record EventoDispatch(String eventType, LocalDateTime createdEm, String motivo) {
    }

    private record TimelineEvent(
            LocalDateTime timestamp,
            String deStatus,
            String paraStatus,
            String origem,
            String observacao
    ) {
    }

    public record PedidoTimelineResultado(int pedidoId, String statusAtual, List<EventoTimelineResultado> eventos) {
        public PedidoTimelineResultado {
            eventos = List.copyOf(eventos);
        }
    }

    public record EventoTimelineResultado(
            String timestamp,
            String deStatus,
            String paraStatus,
            String origem,
            String observacao
    ) {
    }
}
