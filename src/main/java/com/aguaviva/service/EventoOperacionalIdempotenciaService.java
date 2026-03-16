package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.EventoOperacionalRepository;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class EventoOperacionalIdempotenciaService {

    private final ConnectionFactory connectionFactory;
    private final EventoOperacionalRepository repository;
    private final Gson gson = new Gson();

    public EventoOperacionalIdempotenciaService(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.repository = new EventoOperacionalRepository(connectionFactory);
    }

    public Resultado processar(
            String externalEventId,
            String requestHash,
            String eventType,
            String scopeType,
            long scopeId,
            Supplier<ExecucaoEntregaResultado> processamento) {
        validateText(externalEventId, "externalEventId");
        validateText(requestHash, "requestHash");
        validateText(eventType, "eventType");
        validateText(scopeType, "scopeType");
        if (externalEventId.length() > 128) {
            throw new IllegalArgumentException("externalEventId deve ter no maximo 128 caracteres");
        }
        if (requestHash.length() > 64) {
            throw new IllegalArgumentException("requestHash deve ter no maximo 64 caracteres");
        }
        if (scopeId <= 0) {
            throw new IllegalArgumentException("scopeId deve ser maior que zero");
        }
        Objects.requireNonNull(processamento, "Processamento nao pode ser nulo");

        try (var conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertSchema(conn);
                repository.lockPorExternalEventId(conn, externalEventId);

                Optional<EventoOperacionalRepository.RegistroExistente> existente =
                        repository.buscarPorExternalEventId(conn, externalEventId);
                if (existente.isPresent()) {
                    EventoOperacionalRepository.RegistroExistente registro = existente.get();
                    if (!requestHash.equals(registro.requestHash())) {
                        conn.commit();
                        return Resultado.conflito(
                                "externalEventId reutilizado com payload diferente: " + externalEventId);
                    }

                    ExecucaoEntregaResultado resposta = fromJson(registro.responseJson());
                    conn.commit();
                    return Resultado.sucesso(tornarIdempotente(resposta));
                }

                ExecucaoEntregaResultado resposta = processamento.get();
                repository.inserirRegistro(
                        conn, externalEventId, requestHash, eventType, scopeType, scopeId, gson.toJson(resposta), 200);
                conn.commit();
                return Resultado.sucesso(resposta);
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao processar idempotencia de evento operacional", e);
        }
    }

    public void assertSchema(Connection conn) throws SQLException {
        repository.assertSchema(conn);
    }

    private ExecucaoEntregaResultado fromJson(String json) {
        ExecucaoEntregaResultado payload = gson.fromJson(json, ExecucaoEntregaResultado.class);
        if (payload == null) {
            throw new IllegalStateException("response_json invalido para evento operacional idempotente");
        }
        return payload;
    }

    private ExecucaoEntregaResultado tornarIdempotente(ExecucaoEntregaResultado payload) {
        return new ExecucaoEntregaResultado(
                payload.evento(), payload.rotaId(), payload.entregaId(), payload.pedidoId(), true);
    }

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " obrigatorio");
        }
    }

    public record Resultado(ExecucaoEntregaResultado payload, boolean conflito, String erroConflito) {
        public static Resultado sucesso(ExecucaoEntregaResultado payload) {
            return new Resultado(Objects.requireNonNull(payload), false, null);
        }

        public static Resultado conflito(String erroConflito) {
            return new Resultado(null, true, Objects.requireNonNull(erroConflito));
        }
    }
}
