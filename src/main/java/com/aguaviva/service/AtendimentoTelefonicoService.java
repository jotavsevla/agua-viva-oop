package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public class AtendimentoTelefonicoService {

    private static final String ENDERECO_PENDENTE = "Endereco pendente";
    private static final String METODO_PAGAMENTO_PADRAO = "NAO_INFORMADO";
    private static final String METODO_PAGAMENTO_VALE = "VALE";

    private final ConnectionFactory connectionFactory;
    private final DispatchEventService dispatchEventService;

    public AtendimentoTelefonicoService(ConnectionFactory connectionFactory) {
        this(connectionFactory, new DispatchEventService());
    }

    AtendimentoTelefonicoService(ConnectionFactory connectionFactory, DispatchEventService dispatchEventService) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.dispatchEventService =
                Objects.requireNonNull(dispatchEventService, "DispatchEventService nao pode ser nulo");
    }

    public AtendimentoTelefonicoResultado registrarPedido(
            String externalCallId, String telefoneInformado, int quantidadeGaloes, int atendenteId) {
        return registrarPedido(externalCallId, telefoneInformado, quantidadeGaloes, atendenteId, null);
    }

    public AtendimentoTelefonicoResultado registrarPedido(
            String externalCallId,
            String telefoneInformado,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento) {
        String externalCallIdNormalizado = normalizeExternalCallId(externalCallId);
        String telefoneNormalizado = normalizePhone(telefoneInformado);
        String metodoPagamentoNormalizado = normalizeMetodoPagamento(metodoPagamento);
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
                            pedido.pedidoId(), pedido.clienteId(), telefoneNormalizado, false, true);
                }

                lockPorTelefone(conn, telefoneNormalizado);
                assertAtendenteExiste(conn, atendenteId);

                int clienteId = obterClienteElegivelPorTelefone(conn, telefoneNormalizado);
                validarElegibilidadeVale(conn, clienteId, quantidadeGaloes, metodoPagamentoNormalizado);

                InsertPedidoResult insert = inserirPedidoPendenteIdempotente(
                        conn,
                        clienteId,
                        quantidadeGaloes,
                        atendenteId,
                        externalCallIdNormalizado,
                        metodoPagamentoNormalizado);

                if (!insert.idempotente()) {
                    dispatchEventService.publicar(
                            conn,
                            DispatchEventTypes.PEDIDO_CRIADO,
                            "PEDIDO",
                            (long) insert.pedidoId(),
                            new PedidoCriadoPayload(insert.pedidoId(), clienteId, externalCallIdNormalizado));
                }

                conn.commit();
                return new AtendimentoTelefonicoResultado(
                        insert.pedidoId(), insert.clienteId(), telefoneNormalizado, false, insert.idempotente());
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw mapearSqlException(e, "Falha ao registrar pedido via atendimento telefonico");
        }
    }

    public AtendimentoTelefonicoResultado registrarPedidoManual(
            String telefoneInformado, int quantidadeGaloes, int atendenteId) {
        return registrarPedidoManual(telefoneInformado, quantidadeGaloes, atendenteId, null);
    }

    public AtendimentoTelefonicoResultado registrarPedidoManual(
            String telefoneInformado, int quantidadeGaloes, int atendenteId, String metodoPagamento) {
        String telefoneNormalizado = normalizePhone(telefoneInformado);
        String metodoPagamentoNormalizado = normalizeMetodoPagamento(metodoPagamento);
        validateQuantidade(quantidadeGaloes);
        validateAtendenteId(atendenteId);

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                lockPorTelefone(conn, telefoneNormalizado);
                assertAtendenteExiste(conn, atendenteId);

                int clienteId = obterClienteElegivelPorTelefone(conn, telefoneNormalizado);
                Optional<PedidoExistente> pedidoAtivo = buscarPedidoAbertoPorClienteId(conn, clienteId);

                if (pedidoAtivo.isPresent()) {
                    conn.commit();
                    PedidoExistente existente = pedidoAtivo.get();
                    return new AtendimentoTelefonicoResultado(
                            existente.pedidoId(), existente.clienteId(), telefoneNormalizado, false, true);
                }

                validarElegibilidadeVale(conn, clienteId, quantidadeGaloes, metodoPagamentoNormalizado);

                InsertPedidoResult insert = inserirPedidoPendente(
                        conn, clienteId, quantidadeGaloes, atendenteId, metodoPagamentoNormalizado);

                dispatchEventService.publicar(
                        conn,
                        DispatchEventTypes.PEDIDO_CRIADO,
                        "PEDIDO",
                        (long) insert.pedidoId(),
                        new PedidoCriadoPayload(insert.pedidoId(), insert.clienteId(), null));

                conn.commit();
                return new AtendimentoTelefonicoResultado(
                        insert.pedidoId(), insert.clienteId(), telefoneNormalizado, false, false);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw mapearSqlException(e, "Falha ao registrar pedido via atendimento manual");
        }
    }

    private void assertIdempotencySchema(Connection conn) throws SQLException {
        if (!hasColumn(conn, "pedidos", "external_call_id")) {
            throw new IllegalStateException("Schema desatualizado: coluna pedidos.external_call_id ausente");
        }
        if (!hasUniqueConstraint(conn, "uk_pedidos_external_call_id")) {
            throw new IllegalStateException(
                    "Schema desatualizado: constraint unica uk_pedidos_external_call_id ausente");
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

    private Optional<PedidoExistente> buscarPedidoPorExternalCallId(Connection conn, String externalCallId)
            throws SQLException {
        String sql = "SELECT id, cliente_id FROM pedidos WHERE external_call_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalCallId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PedidoExistente(rs.getInt("id"), rs.getInt("cliente_id")));
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

    private Optional<ClienteCadastro> buscarClientePorTelefoneNormalizado(Connection conn, String telefoneNormalizado)
            throws SQLException {
        String sql = "SELECT id, endereco, latitude, longitude "
                + "FROM clientes "
                + "WHERE regexp_replace(telefone, '[^0-9]', '', 'g') = ? "
                + "ORDER BY id LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, telefoneNormalizado);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ClienteCadastro(
                            rs.getInt("id"),
                            rs.getString("endereco"),
                            toNullableDouble(rs, "latitude"),
                            toNullableDouble(rs, "longitude")));
                }
                return Optional.empty();
            }
        }
    }

    private int obterClienteElegivelPorTelefone(Connection conn, String telefoneNormalizado) throws SQLException {
        Optional<ClienteCadastro> clienteExistente = buscarClientePorTelefoneNormalizado(conn, telefoneNormalizado);
        if (clienteExistente.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cliente nao cadastrado para o telefone informado. Cadastre endereco valido antes de criar pedido");
        }
        ClienteCadastro cliente = clienteExistente.get();
        validarClienteElegivelParaPedido(cliente);
        return cliente.clienteId();
    }

    private void validarClienteElegivelParaPedido(ClienteCadastro cliente) {
        String enderecoNormalizado =
                cliente.endereco() == null ? "" : cliente.endereco().trim();
        if (enderecoNormalizado.isEmpty() || ENDERECO_PENDENTE.equalsIgnoreCase(enderecoNormalizado)) {
            throw new IllegalArgumentException("Cliente sem endereco valido. Atualize cadastro antes de criar pedido");
        }
        if (cliente.latitude() == null || cliente.longitude() == null) {
            throw new IllegalArgumentException(
                    "Cliente sem geolocalizacao valida. Atualize cadastro antes de criar pedido");
        }
    }

    private Optional<PedidoExistente> buscarPedidoAbertoPorClienteId(Connection conn, int clienteId)
            throws SQLException {
        String sql = "SELECT id, cliente_id FROM pedidos "
                + "WHERE cliente_id = ? "
                + "AND status::text IN ('PENDENTE', 'CONFIRMADO', 'EM_ROTA') "
                + "ORDER BY id DESC LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, clienteId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PedidoExistente(rs.getInt("id"), rs.getInt("cliente_id")));
                }
                return Optional.empty();
            }
        }
    }

    private void validarElegibilidadeVale(Connection conn, int clienteId, int quantidadeGaloes, String metodoPagamento)
            throws SQLException {
        if (!METODO_PAGAMENTO_VALE.equals(metodoPagamento)) {
            return;
        }

        int saldoDisponivel = buscarSaldoValeComLock(conn, clienteId);
        if (saldoDisponivel < quantidadeGaloes) {
            throw new IllegalArgumentException("cliente nao possui vale suficiente para checkout");
        }
    }

    private int buscarSaldoValeComLock(Connection conn, int clienteId) throws SQLException {
        String sql = "SELECT quantidade FROM saldo_vales WHERE cliente_id = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, clienteId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getInt("quantidade");
            }
        }
    }

    private void assertAtendenteExiste(Connection conn, int atendenteId) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE id = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, atendenteId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Atendente informado nao existe");
                }
            }
        }
    }

    private InsertPedidoResult inserirPedidoPendenteIdempotente(
            Connection conn,
            int clienteId,
            int quantidadeGaloes,
            int atendenteId,
            String externalCallId,
            String metodoPagamento)
            throws SQLException {
        String sql = "INSERT INTO pedidos "
                + "(cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, external_call_id, metodo_pagamento) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
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
            stmt.setObject(9, metodoPagamento, Types.OTHER);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new InsertPedidoResult(rs.getInt("id"), rs.getInt("cliente_id"), false);
                }
            }
        }

        PedidoExistente existente = buscarPedidoPorExternalCallId(conn, externalCallId)
                .orElseThrow(() -> new SQLException("Pedido idempotente nao encontrado para external_call_id"));
        return new InsertPedidoResult(existente.pedidoId(), existente.clienteId(), true);
    }

    private InsertPedidoResult inserirPedidoPendente(
            Connection conn, int clienteId, int quantidadeGaloes, int atendenteId, String metodoPagamento)
            throws SQLException {
        String sql = "INSERT INTO pedidos "
                + "(cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, metodo_pagamento) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "RETURNING id, cliente_id";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, clienteId);
            stmt.setInt(2, quantidadeGaloes);
            stmt.setObject(3, "ASAP", Types.OTHER);
            stmt.setNull(4, Types.TIME);
            stmt.setNull(5, Types.TIME);
            stmt.setObject(6, "PENDENTE", Types.OTHER);
            stmt.setInt(7, atendenteId);
            stmt.setObject(8, metodoPagamento, Types.OTHER);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new InsertPedidoResult(rs.getInt("id"), rs.getInt("cliente_id"), false);
                }
            }
        }
        throw new SQLException("Falha ao criar pedido pendente em atendimento manual");
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

    private static String normalizeMetodoPagamento(String value) {
        if (value == null || value.isBlank()) {
            return METODO_PAGAMENTO_PADRAO;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NAO_INFORMADO", "DINHEIRO", "PIX", "CARTAO", "VALE" -> normalized;
            default -> throw new IllegalArgumentException("metodoPagamento invalido");
        };
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

    private RuntimeException mapearSqlException(SQLException e, String mensagemPadrao) {
        if ("23503".equals(e.getSQLState())) {
            return new IllegalArgumentException("Atendente informado nao existe", e);
        }
        return new IllegalStateException(mensagemPadrao, e);
    }

    private record PedidoExistente(int pedidoId, int clienteId) {}

    private static Double toNullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return rs.getDouble(column);
    }

    private record ClienteCadastro(int clienteId, String endereco, Double latitude, Double longitude) {}

    private record InsertPedidoResult(int pedidoId, int clienteId, boolean idempotente) {}

    private record PedidoCriadoPayload(int pedidoId, int clienteId, String externalCallId) {}
}
