package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

public class AtendimentoTelefonicoService {

    private static final String ENDERECO_PENDENTE = "Endereco pendente";

    private final ConnectionFactory connectionFactory;
    private final DispatchEventService dispatchEventService;

    public AtendimentoTelefonicoService(ConnectionFactory connectionFactory) {
        this(connectionFactory, new DispatchEventService());
    }

    AtendimentoTelefonicoService(ConnectionFactory connectionFactory, DispatchEventService dispatchEventService) {
        this.connectionFactory = Objects.requireNonNull(
                connectionFactory, "ConnectionFactory nao pode ser nulo"
        );
        this.dispatchEventService = Objects.requireNonNull(
                dispatchEventService, "DispatchEventService nao pode ser nulo"
        );
    }

    public AtendimentoTelefonicoResultado registrarPedido(
            String externalCallId,
            String telefoneInformado,
            int quantidadeGaloes,
            int atendenteId
    ) {
        String externalCallIdNormalizado = normalizeExternalCallId(externalCallId);
        String telefoneNormalizado = normalizePhone(telefoneInformado);
        validateQuantidade(quantidadeGaloes);
        validateAtendenteId(atendenteId);

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertIdempotencySchema(conn);

                Optional<PedidoExistente> existente = buscarPedidoPorExternalCallId(conn, externalCallIdNormalizado);
                if (existente.isPresent()) {
                    conn.commit();
                    PedidoExistente pedido = existente.get();
                    return new AtendimentoTelefonicoResultado(
                            pedido.pedidoId(),
                            pedido.clienteId(),
                            telefoneNormalizado,
                            false,
                            true
                    );
                }

                lockPorTelefone(conn, telefoneNormalizado);

                int clienteId;
                boolean clienteCriado;
                Optional<Integer> clienteExistente = buscarClienteIdPorTelefoneNormalizado(conn, telefoneNormalizado);
                if (clienteExistente.isPresent()) {
                    clienteId = clienteExistente.get();
                    clienteCriado = false;
                } else {
                    clienteId = inserirClienteMinimo(conn, telefoneNormalizado);
                    clienteCriado = true;
                }

                InsertPedidoResult insert = inserirPedidoPendenteIdempotente(
                        conn,
                        clienteId,
                        quantidadeGaloes,
                        atendenteId,
                        externalCallIdNormalizado
                );

                if (!insert.idempotente()) {
                    dispatchEventService.publicar(
                            conn,
                            DispatchEventTypes.PEDIDO_CRIADO,
                            "PEDIDO",
                            (long) insert.pedidoId(),
                            new PedidoCriadoPayload(insert.pedidoId(), insert.clienteId(), externalCallIdNormalizado)
                    );
                }

                conn.commit();
                return new AtendimentoTelefonicoResultado(
                        insert.pedidoId(),
                        insert.clienteId(),
                        telefoneNormalizado,
                        clienteCriado && !insert.idempotente(),
                        insert.idempotente()
                );
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            if ("23503".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Atendente informado nao existe", e);
            }
            throw new IllegalStateException("Falha ao registrar pedido via atendimento telefonico", e);
        }
    }

    private void assertIdempotencySchema(Connection conn) throws SQLException {
        if (!hasColumn(conn, "pedidos", "external_call_id")) {
            throw new IllegalStateException("Schema desatualizado: coluna pedidos.external_call_id ausente");
        }
        if (!hasUniqueConstraint(conn, "uk_pedidos_external_call_id")) {
            throw new IllegalStateException("Schema desatualizado: constraint unica uk_pedidos_external_call_id ausente");
        }
    }

    private boolean hasColumn(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            stmt.setString(2, columnName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasUniqueConstraint(Connection conn, String constraintName) throws SQLException {
        String sql = "SELECT 1 FROM pg_constraint WHERE conname = ? AND contype = 'u'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, constraintName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Optional<PedidoExistente> buscarPedidoPorExternalCallId(Connection conn, String externalCallId) throws SQLException {
        String sql = "SELECT id, cliente_id FROM pedidos WHERE external_call_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalCallId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PedidoExistente(
                            rs.getInt("id"),
                            rs.getInt("cliente_id")
                    ));
                }
                return Optional.empty();
            }
        }
    }

    private void lockPorTelefone(Connection conn, String telefoneNormalizado) throws SQLException {
        String sql = "SELECT pg_advisory_xact_lock(hashtext(?))";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, telefoneNormalizado);
            stmt.executeQuery();
        }
    }

    private Optional<Integer> buscarClienteIdPorTelefoneNormalizado(Connection conn, String telefoneNormalizado)
            throws SQLException {
        String sql = "SELECT id FROM clientes WHERE regexp_replace(telefone, '[^0-9]', '', 'g') = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, telefoneNormalizado);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getInt("id"));
                }
                return Optional.empty();
            }
        }
    }

    private int inserirClienteMinimo(Connection conn, String telefoneNormalizado) throws SQLException {
        String nome = "Cliente " + telefoneNormalizado;
        String sql = "INSERT INTO clientes (nome, telefone, tipo, endereco) VALUES (?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, nome);
            stmt.setString(2, telefoneNormalizado);
            stmt.setObject(3, "PF", Types.OTHER);
            stmt.setString(4, ENDERECO_PENDENTE);
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        throw new SQLException("Falha ao criar cliente minimo para atendimento telefonico");
    }

    private InsertPedidoResult inserirPedidoPendenteIdempotente(
            Connection conn,
            int clienteId,
            int quantidadeGaloes,
            int atendenteId,
            String externalCallId
    ) throws SQLException {
        String sql = "INSERT INTO pedidos (cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, external_call_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (external_call_id) DO NOTHING "
                + "RETURNING id, cliente_id";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, clienteId);
            stmt.setInt(2, quantidadeGaloes);
            stmt.setObject(3, "ASAP", Types.OTHER);
            stmt.setNull(4, Types.TIME);
            stmt.setNull(5, Types.TIME);
            stmt.setObject(6, "PENDENTE", Types.OTHER);
            stmt.setInt(7, atendenteId);
            stmt.setString(8, externalCallId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new InsertPedidoResult(
                            rs.getInt("id"),
                            rs.getInt("cliente_id"),
                            false
                    );
                }
            }
        }

        PedidoExistente existente = buscarPedidoPorExternalCallId(conn, externalCallId)
                .orElseThrow(() -> new SQLException("Pedido idempotente nao encontrado para external_call_id"));
        return new InsertPedidoResult(existente.pedidoId(), existente.clienteId(), true);
    }

    private static String normalizeExternalCallId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("external_call_id nao pode ser nulo ou vazio");
        }

        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("external_call_id deve ter no maximo 64 caracteres");
        }
        return normalized;
    }

    private static String normalizePhone(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Telefone nao pode ser nulo ou vazio");
        }

        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15) {
            throw new IllegalArgumentException("Telefone deve ter entre 10 e 15 digitos apos normalizacao");
        }
        return digits;
    }

    private static void validateQuantidade(int quantidadeGaloes) {
        if (quantidadeGaloes <= 0) {
            throw new IllegalArgumentException("Quantidade de galoes deve ser maior que zero");
        }
    }

    private static void validateAtendenteId(int atendenteId) {
        if (atendenteId <= 0) {
            throw new IllegalArgumentException("AtendenteId deve ser maior que zero");
        }
    }

    private record PedidoExistente(int pedidoId, int clienteId) {
    }

    private record InsertPedidoResult(int pedidoId, int clienteId, boolean idempotente) {
    }

    private record PedidoCriadoPayload(int pedidoId, int clienteId, String externalCallId) {
    }
}
