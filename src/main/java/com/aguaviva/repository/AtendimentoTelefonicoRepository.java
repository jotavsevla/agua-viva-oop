package com.aguaviva.repository;

import com.aguaviva.domain.exception.DatabaseException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

public final class AtendimentoTelefonicoRepository {

    private static final String ENDERECO_PENDENTE = "Endereco pendente";
    private static final String COBERTURA_BBOX_PADRAO = "-43.9600,-16.8200,-43.7800,-16.6200";

    public AtendimentoTelefonicoRepository(ConnectionFactory connectionFactory) {
        Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
    }

    public void lockPorTelefone(Connection conn, String telefoneNormalizado) {
        try {
            String sql = "SELECT pg_advisory_xact_lock(hashtext(?))";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, telefoneNormalizado);
                stmt.executeQuery();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao aplicar lock por telefone", e);
        }
    }

    public void assertAtendenteExiste(Connection conn, int atendenteId) {
        try {
            String sql = "SELECT 1 FROM users WHERE id = ? LIMIT 1";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, atendenteId);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Atendente informado nao existe");
                    }
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao validar atendente", e);
        }
    }

    public void assertAtendimentoIdempotenciaSchema(Connection conn) {
        if (!hasTable(conn, "atendimentos_idempotencia")) {
            throw new IllegalStateException("Schema desatualizado: tabela atendimentos_idempotencia ausente");
        }
        if (!hasColumn(conn, "atendimentos_idempotencia", "origem_canal")) {
            throw new IllegalStateException(
                    "Schema desatualizado: coluna atendimentos_idempotencia.origem_canal ausente");
        }
        if (!hasColumn(conn, "atendimentos_idempotencia", "source_event_id")) {
            throw new IllegalStateException(
                    "Schema desatualizado: coluna atendimentos_idempotencia.source_event_id ausente");
        }
        if (!hasColumn(conn, "atendimentos_idempotencia", "pedido_id")) {
            throw new IllegalStateException("Schema desatualizado: coluna atendimentos_idempotencia.pedido_id ausente");
        }
        if (!hasColumn(conn, "atendimentos_idempotencia", "request_hash")) {
            throw new IllegalStateException(
                    "Schema desatualizado: coluna atendimentos_idempotencia.request_hash ausente");
        }
    }

    public void lockPorIdempotenciaAtendimento(Connection conn, String origemCanal, String dedupeKey) {
        try {
            String sql = "SELECT pg_advisory_xact_lock(hashtext(?))";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, origemCanal + "|" + dedupeKey);
                stmt.executeQuery();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao aplicar lock por idempotencia de atendimento", e);
        }
    }

    public Optional<AtendimentoIdempotenteExistente> buscarAtendimentoIdempotente(
            Connection conn, String origemCanal, String dedupeKey) {
        try {
            String sql = """
                    SELECT pedido_id, cliente_id, telefone_normalizado, request_hash
                    FROM atendimentos_idempotencia
                    WHERE origem_canal = ? AND source_event_id = ?
                    """;
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, origemCanal);
                stmt.setString(2, dedupeKey);
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new AtendimentoIdempotenteExistente(
                                rs.getInt("pedido_id"),
                                rs.getInt("cliente_id"),
                                rs.getString("telefone_normalizado"),
                                rs.getString("request_hash")));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao buscar atendimento idempotente", e);
        }
    }

    public void registrarAtendimentoIdempotenteSeNecessario(
            Connection conn,
            String origemCanal,
            String dedupeKey,
            int pedidoId,
            int clienteId,
            String telefoneNormalizado,
            String requestHash) {
        try {
            if (dedupeKey == null) {
                return;
            }

            String sql = """
                    INSERT INTO atendimentos_idempotencia
                    (origem_canal, source_event_id, pedido_id, cliente_id, telefone_normalizado, request_hash)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (origem_canal, source_event_id) DO NOTHING
                    """;
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, origemCanal);
                stmt.setString(2, dedupeKey);
                stmt.setInt(3, pedidoId);
                stmt.setInt(4, clienteId);
                stmt.setString(5, telefoneNormalizado);
                stmt.setString(6, requestHash);
                int inseridos = stmt.executeUpdate();
                if (inseridos > 0) {
                    return;
                }
            }

            AtendimentoIdempotenteExistente existente = buscarAtendimentoIdempotente(conn, origemCanal, dedupeKey)
                    .orElseThrow(
                            () -> new SQLException("Registro idempotente de atendimento nao encontrado apos conflito"));
            validarHashIdempotenciaCompativel(existente.requestHash(), requestHash);
            if (existente.pedidoId() != pedidoId || existente.clienteId() != clienteId) {
                throw new IllegalStateException(
                        "source_event_id/manual_request_id reutilizado com pedido diferente para o mesmo canal");
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao registrar atendimento idempotente", e);
        }
    }

    public Optional<ClienteCadastro> buscarClientePorTelefoneNormalizado(Connection conn, String telefoneNormalizado) {
        try {
            String sql = """
                    SELECT id, nome, endereco, latitude, longitude
                    FROM clientes
                    WHERE regexp_replace(telefone, '[^0-9]', '', 'g') = ?
                    ORDER BY CASE
                        WHEN btrim(COALESCE(endereco, '')) <> ''
                             AND lower(btrim(COALESCE(endereco, ''))) <> 'endereco pendente'
                             AND latitude IS NOT NULL
                             AND longitude IS NOT NULL
                        THEN 0 ELSE 1 END,
                    id DESC
                    LIMIT 1
                    """;
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, telefoneNormalizado);
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new ClienteCadastro(
                                rs.getInt("id"),
                                rs.getString("nome"),
                                rs.getString("endereco"),
                                toNullableDouble(rs, "latitude"),
                                toNullableDouble(rs, "longitude")));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao buscar cliente por telefone normalizado", e);
        }
    }

    public ClienteCadastro criarClienteInicial(
            Connection conn, String telefoneNormalizado, CadastroClienteInput cadastroClienteInput) {
        try {
            String nome = cadastroClienteInput.nomeCliente();
            if (nome == null) {
                String sufixo = telefoneNormalizado.length() <= 4
                        ? telefoneNormalizado
                        : telefoneNormalizado.substring(telefoneNormalizado.length() - 4);
                nome = "Cliente " + sufixo;
            }
            String endereco = cadastroClienteInput.endereco();
            if (endereco == null) {
                endereco = ENDERECO_PENDENTE;
            }

            String sql = """
                    INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude, notas)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, nome, endereco, latitude, longitude
                    """;
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, nome);
                stmt.setString(2, telefoneNormalizado);
                stmt.setObject(3, "PF", Types.OTHER);
                stmt.setString(4, endereco);
                if (cadastroClienteInput.latitude() == null) {
                    stmt.setNull(5, Types.DOUBLE);
                } else {
                    stmt.setDouble(5, cadastroClienteInput.latitude());
                }
                if (cadastroClienteInput.longitude() == null) {
                    stmt.setNull(6, Types.DOUBLE);
                } else {
                    stmt.setDouble(6, cadastroClienteInput.longitude());
                }
                stmt.setNull(7, Types.VARCHAR);

                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new ClienteCadastro(
                                rs.getInt("id"),
                                rs.getString("nome"),
                                rs.getString("endereco"),
                                toNullableDouble(rs, "latitude"),
                                toNullableDouble(rs, "longitude"));
                    }
                }
            }
            throw new SQLException("Falha ao criar cadastro inicial do cliente para atendimento omnichannel");
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao criar cadastro inicial do cliente", e);
        }
    }

    public ClienteCadastro atualizarCadastroClienteSeInformado(
            Connection conn, ClienteCadastro clienteAtual, CadastroClienteInput cadastroClienteInput) {
        try {
            boolean temNome = cadastroClienteInput.nomeCliente() != null;
            boolean temEndereco = cadastroClienteInput.endereco() != null;
            boolean temLatitude = cadastroClienteInput.latitude() != null;
            boolean temLongitude = cadastroClienteInput.longitude() != null;

            if (!temNome && !temEndereco && !temLatitude && !temLongitude) {
                return clienteAtual;
            }

            String novoNome = temNome ? cadastroClienteInput.nomeCliente() : clienteAtual.nome();
            String novoEndereco = temEndereco ? cadastroClienteInput.endereco() : clienteAtual.endereco();
            Double novaLatitude = temLatitude ? cadastroClienteInput.latitude() : clienteAtual.latitude();
            Double novaLongitude = temLongitude ? cadastroClienteInput.longitude() : clienteAtual.longitude();

            String sql = """
                    UPDATE clientes
                    SET nome = ?, endereco = ?, latitude = ?, longitude = ?, atualizado_em = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """;
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, novoNome);
                stmt.setString(2, novoEndereco);
                if (novaLatitude == null) {
                    stmt.setNull(3, Types.DOUBLE);
                } else {
                    stmt.setDouble(3, novaLatitude);
                }
                if (novaLongitude == null) {
                    stmt.setNull(4, Types.DOUBLE);
                } else {
                    stmt.setDouble(4, novaLongitude);
                }
                stmt.setInt(5, clienteAtual.clienteId());
                stmt.executeUpdate();
            }

            return new ClienteCadastro(clienteAtual.clienteId(), novoNome, novoEndereco, novaLatitude, novaLongitude);
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao atualizar cadastro do cliente", e);
        }
    }

    public Optional<PedidoExistente> buscarPedidoAbertoPorClienteId(Connection conn, int clienteId) {
        try {
            String sql = """
                    SELECT id, cliente_id FROM pedidos
                    WHERE cliente_id = ?
                    AND status::text IN ('PENDENTE', 'CONFIRMADO', 'EM_ROTA')
                    ORDER BY id DESC LIMIT 1
                    """;
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, clienteId);
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new PedidoExistente(rs.getInt("id"), rs.getInt("cliente_id")));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao buscar pedido aberto por cliente", e);
        }
    }

    public Optional<PedidoExistente> buscarPedidoPorExternalCallId(Connection conn, String externalCallId) {
        try {
            String sql = "SELECT id, cliente_id FROM pedidos WHERE external_call_id = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, externalCallId);
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new PedidoExistente(rs.getInt("id"), rs.getInt("cliente_id")));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao buscar pedido por external_call_id", e);
        }
    }

    public InsertPedidoResult inserirPedidoPendenteIdempotente(
            Connection conn,
            int clienteId,
            int quantidadeGaloes,
            int atendenteId,
            String externalCallId,
            String metodoPagamento,
            JanelaPedidoInput janelaPedido) {
        try {
            String sql = """
                    INSERT INTO pedidos
                    (cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, external_call_id, metodo_pagamento)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (external_call_id) DO NOTHING
                    RETURNING id, cliente_id
                    """;

            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, clienteId);
                stmt.setInt(2, quantidadeGaloes);
                stmt.setObject(3, janelaPedido.tipo(), Types.OTHER);
                if (janelaPedido.inicio() == null) {
                    stmt.setNull(4, Types.TIME);
                } else {
                    stmt.setObject(4, janelaPedido.inicio(), Types.TIME);
                }
                if (janelaPedido.fim() == null) {
                    stmt.setNull(5, Types.TIME);
                } else {
                    stmt.setObject(5, janelaPedido.fim(), Types.TIME);
                }
                stmt.setObject(6, "PENDENTE", Types.OTHER);
                stmt.setInt(7, atendenteId);
                stmt.setString(8, externalCallId);
                stmt.setObject(9, metodoPagamento, Types.OTHER);

                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new InsertPedidoResult(rs.getInt("id"), rs.getInt("cliente_id"), false);
                    }
                }
            }

            PedidoExistente existente = buscarPedidoPorExternalCallId(conn, externalCallId)
                    .orElseThrow(() -> new SQLException("Pedido idempotente nao encontrado para external_call_id"));
            return new InsertPedidoResult(existente.pedidoId(), existente.clienteId(), true);
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao inserir pedido pendente idempotente", e);
        }
    }

    public InsertPedidoResult inserirPedidoPendente(
            Connection conn,
            int clienteId,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento,
            JanelaPedidoInput janelaPedido) {
        try {
            String sql = """
                    INSERT INTO pedidos
                    (cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, metodo_pagamento)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, cliente_id
                    """;

            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, clienteId);
                stmt.setInt(2, quantidadeGaloes);
                stmt.setObject(3, janelaPedido.tipo(), Types.OTHER);
                if (janelaPedido.inicio() == null) {
                    stmt.setNull(4, Types.TIME);
                } else {
                    stmt.setObject(4, janelaPedido.inicio(), Types.TIME);
                }
                if (janelaPedido.fim() == null) {
                    stmt.setNull(5, Types.TIME);
                } else {
                    stmt.setObject(5, janelaPedido.fim(), Types.TIME);
                }
                stmt.setObject(6, "PENDENTE", Types.OTHER);
                stmt.setInt(7, atendenteId);
                stmt.setObject(8, metodoPagamento, Types.OTHER);

                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new InsertPedidoResult(rs.getInt("id"), rs.getInt("cliente_id"), false);
                    }
                }
            }

            throw new SQLException("Falha ao criar pedido pendente em atendimento");
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao inserir pedido pendente", e);
        }
    }

    public void assertIdempotencySchema(Connection conn) {
        if (!hasColumn(conn, "pedidos", "external_call_id")) {
            throw new IllegalStateException("Schema desatualizado: coluna pedidos.external_call_id ausente");
        }
        if (!hasUniqueConstraint(conn, "uk_pedidos_external_call_id")) {
            throw new IllegalStateException(
                    "Schema desatualizado: constraint unica uk_pedidos_external_call_id ausente");
        }
    }

    public boolean hasTable(Connection conn, String tableName) {
        try {
            String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, tableName);
                try (var rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao consultar tabela", e);
        }
    }

    public boolean hasColumn(Connection conn, String tableName, String columnName) {
        try {
            String sql = "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, tableName);
                stmt.setString(2, columnName);
                try (var rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao consultar coluna", e);
        }
    }

    public boolean hasUniqueConstraint(Connection conn, String constraintName) {
        try {
            String sql = "SELECT 1 FROM pg_constraint WHERE conname = ? AND contype = 'u'";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, constraintName);
                try (var rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao consultar constraint unica", e);
        }
    }

    public CoberturaBbox carregarCoberturaBbox(Connection conn) {
        try {
            String valor = null;
            String sql = "SELECT valor FROM configuracoes WHERE chave = 'cobertura_bbox' LIMIT 1";
            try (var stmt = conn.prepareStatement(sql);
                    var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    valor = rs.getString("valor");
                }
            }
            if (valor == null || valor.isBlank()) {
                valor = COBERTURA_BBOX_PADRAO;
            }
            return parseBbox(valor);
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao carregar cobertura bbox", e);
        }
    }

    public int buscarSaldoValeComLock(Connection conn, int clienteId) {
        try {
            String sql = "SELECT quantidade FROM saldo_vales WHERE cliente_id = ? FOR UPDATE";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, clienteId);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return 0;
                    }
                    return rs.getInt("quantidade");
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Falha ao buscar saldo de vale com lock", e);
        }
    }

    private void validarHashIdempotenciaCompativel(String requestHashPersistido, String requestHashAtual) {
        if (requestHashAtual == null || requestHashPersistido == null || requestHashPersistido.isBlank()) {
            return;
        }
        if (!requestHashPersistido.equals(requestHashAtual)) {
            throw new IllegalStateException(
                    "source_event_id/manual_request_id reutilizado com payload divergente para o mesmo canal");
        }
    }

    private CoberturaBbox parseBbox(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            throw new IllegalStateException(
                    "Configuracao cobertura_bbox invalida. Esperado min_lon,min_lat,max_lon,max_lat");
        }
        try {
            double minLon = Double.parseDouble(parts[0].trim());
            double minLat = Double.parseDouble(parts[1].trim());
            double maxLon = Double.parseDouble(parts[2].trim());
            double maxLat = Double.parseDouble(parts[3].trim());
            if (minLon >= maxLon || minLat >= maxLat) {
                throw new IllegalStateException(
                        "Configuracao cobertura_bbox invalida. min/max devem respeitar ordem crescente");
            }
            return new CoberturaBbox(minLon, minLat, maxLon, maxLat);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuracao cobertura_bbox invalida. Valores devem ser numericos", e);
        }
    }

    private static Double toNullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return rs.getDouble(column);
    }

    public record AtendimentoIdempotenteExistente(
            int pedidoId, int clienteId, String telefoneNormalizado, String requestHash) {}

    public record PedidoExistente(int pedidoId, int clienteId) {}

    public record ClienteCadastro(int clienteId, String nome, String endereco, Double latitude, Double longitude) {}

    public record CadastroClienteInput(String nomeCliente, String endereco, Double latitude, Double longitude) {}

    public record JanelaPedidoInput(String tipo, java.time.LocalTime inicio, java.time.LocalTime fim) {}

    public record InsertPedidoResult(int pedidoId, int clienteId, boolean idempotente) {}

    public record CoberturaBbox(double minLon, double minLat, double maxLon, double maxLat) {}
}
