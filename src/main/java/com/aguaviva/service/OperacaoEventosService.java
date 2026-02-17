package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OperacaoEventosService {

    private static final int LIMITE_PADRAO = 50;
    private static final int LIMITE_MAXIMO = 200;

    private final ConnectionFactory connectionFactory;
    private final Gson gson = new Gson();

    public OperacaoEventosService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public OperacaoEventosResultado listarEventos(Integer limiteSolicitado) {
        int limite = limiteSolicitado == null ? LIMITE_PADRAO : limiteSolicitado;
        if (limite <= 0) {
            throw new IllegalArgumentException("limite deve ser maior que zero");
        }
        if (limite > LIMITE_MAXIMO) {
            throw new IllegalArgumentException("limite maximo permitido e 200");
        }

        try (Connection conn = connectionFactory.getConnection()) {
            String sql = "SELECT id, event_type, status::text AS status, aggregate_type, aggregate_id, payload, created_em, processed_em "
                    + "FROM dispatch_events "
                    + "ORDER BY created_em DESC, id DESC "
                    + "LIMIT ?";
            List<EventoOperacional> eventos = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limite);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object payload = gson.fromJson(rs.getString("payload"), Object.class);
                        LocalDateTime processedEm = rs.getObject("processed_em", LocalDateTime.class);
                        Object aggregateIdRaw = rs.getObject("aggregate_id");
                        Long aggregateId = aggregateIdRaw == null ? null : ((Number) aggregateIdRaw).longValue();
                        eventos.add(new EventoOperacional(
                                rs.getLong("id"),
                                rs.getString("event_type"),
                                rs.getString("status"),
                                rs.getString("aggregate_type"),
                                aggregateId,
                                payload,
                                rs.getObject("created_em", LocalDateTime.class).toString(),
                                processedEm == null ? null : processedEm.toString()));
                    }
                }
            }
            return new OperacaoEventosResultado(eventos);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar feed operacional de eventos", e);
        }
    }

    public record OperacaoEventosResultado(List<EventoOperacional> eventos) {
        public OperacaoEventosResultado {
            eventos = List.copyOf(eventos);
        }
    }

    public record EventoOperacional(
            long id,
            String eventType,
            String status,
            String aggregateType,
            Long aggregateId,
            Object payload,
            String createdEm,
            String processedEm) {}
}
