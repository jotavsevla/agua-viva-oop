package com.aguaviva.service;

import com.aguaviva.repository.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public class AtendimentoTelefonicoService {

    private static final String ENDERECO_PENDENTE = "Endereco pendente";
    private static final String METODO_PAGAMENTO_PADRAO = "NAO_INFORMADO";
    private static final String METODO_PAGAMENTO_VALE = "VALE";
    private static final DateTimeFormatter WINDOW_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final String ORIGEM_CANAL_MANUAL = "MANUAL";
    private static final String ORIGEM_CANAL_WHATSAPP = "WHATSAPP";
    private static final String ORIGEM_CANAL_BINA_FIXO = "BINA_FIXO";
    private static final String ORIGEM_CANAL_TELEFONIA_FIXO = "TELEFONIA_FIXO";
    private static final String COBERTURA_BBOX_PADRAO = "-43.9600,-16.8200,-43.7800,-16.6200";

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
        return registrarPedido(
                externalCallId, telefoneInformado, quantidadeGaloes, atendenteId, metodoPagamento, null, null, null);
    }

    public AtendimentoTelefonicoResultado registrarPedido(
            String externalCallId,
            String telefoneInformado,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento,
            String janelaTipo,
            String janelaInicio,
            String janelaFim) {
        String externalCallIdNormalizado = normalizeExternalCallId(externalCallId);
        return registrarPedidoOmnichannel(
                ORIGEM_CANAL_TELEFONIA_FIXO,
                externalCallIdNormalizado,
                null,
                telefoneInformado,
                quantidadeGaloes,
                atendenteId,
                metodoPagamento,
                janelaTipo,
                janelaInicio,
                janelaFim,
                null,
                null,
                null,
                null);
    }

    public AtendimentoTelefonicoResultado registrarPedidoManual(
            String telefoneInformado, int quantidadeGaloes, int atendenteId) {
        return registrarPedidoManual(telefoneInformado, quantidadeGaloes, atendenteId, null);
    }

    public AtendimentoTelefonicoResultado registrarPedidoManual(
            String telefoneInformado, int quantidadeGaloes, int atendenteId, String metodoPagamento) {
        return registrarPedidoManual(
                telefoneInformado, quantidadeGaloes, atendenteId, metodoPagamento, null, null, null);
    }

    public AtendimentoTelefonicoResultado registrarPedidoManual(
            String telefoneInformado,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento,
            String janelaTipo,
            String janelaInicio,
            String janelaFim) {
        return registrarPedidoOmnichannel(
                ORIGEM_CANAL_MANUAL,
                null,
                null,
                telefoneInformado,
                quantidadeGaloes,
                atendenteId,
                metodoPagamento,
                janelaTipo,
                janelaInicio,
                janelaFim,
                null,
                null,
                null,
                null);
    }

    public AtendimentoTelefonicoResultado registrarPedidoOmnichannel(
            String origemCanal,
            String sourceEventId,
            String manualRequestId,
            String telefoneInformado,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento,
            String janelaTipo,
            String janelaInicio,
            String janelaFim,
            String nomeCliente,
            String endereco,
            Double latitude,
            Double longitude) {
        String telefoneNormalizado = normalizePhone(telefoneInformado);
        String metodoPagamentoNormalizado = normalizeMetodoPagamento(metodoPagamento);
        JanelaPedido janelaPedido = normalizeJanelaPedido(janelaTipo, janelaInicio, janelaFim);
        String origemCanalNormalizado = normalizeOrigemCanal(origemCanal, sourceEventId, manualRequestId);
        String sourceEventIdNormalizado = normalizeSourceEventIdOpcional(sourceEventId);
        String manualRequestIdNormalizado = normalizeSourceEventIdOpcional(manualRequestId);
        validarConsistenciaCanalEChaves(origemCanalNormalizado, sourceEventIdNormalizado, manualRequestIdNormalizado);
        String dedupeKey =
                resolveDedupeKey(origemCanalNormalizado, sourceEventIdNormalizado, manualRequestIdNormalizado);
        String externalCallIdLegacy = resolveExternalCallIdLegacy(origemCanalNormalizado, sourceEventIdNormalizado);
        CadastroClienteInput cadastroClienteInput =
                normalizeCadastroClienteInput(nomeCliente, endereco, latitude, longitude);
        String atendimentoRequestHash = dedupeKey == null
                ? null
                : buildAtendimentoRequestHash(
                        origemCanalNormalizado,
                        dedupeKey,
                        telefoneNormalizado,
                        quantidadeGaloes,
                        atendenteId,
                        metodoPagamentoNormalizado,
                        janelaPedido,
                        cadastroClienteInput);

        validateQuantidade(quantidadeGaloes);
        validateAtendenteId(atendenteId);

        try (Connection conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean transacaoFinalizada = false;
            try {
                lockPorTelefone(conn, telefoneNormalizado);
                assertAtendenteExiste(conn, atendenteId);

                if (dedupeKey != null) {
                    assertAtendimentoIdempotenciaSchema(conn);
                    lockPorIdempotenciaAtendimento(conn, origemCanalNormalizado, dedupeKey);
                    Optional<AtendimentoIdempotenteExistente> idempotenteExistente =
                            buscarAtendimentoIdempotente(conn, origemCanalNormalizado, dedupeKey);
                    if (idempotenteExistente.isPresent()) {
                        AtendimentoIdempotenteExistente replay = idempotenteExistente.get();
                        validarHashIdempotenciaCompativel(replay.requestHash(), atendimentoRequestHash);
                        conn.commit();
                        return new AtendimentoTelefonicoResultado(
                                replay.pedidoId(), replay.clienteId(), replay.telefoneNormalizado(), false, true);
                    }
                }

                ClienteResolucao clienteResolucao =
                        obterOuCriarClientePorTelefone(conn, telefoneNormalizado, cadastroClienteInput);
                int clienteId = clienteResolucao.clienteId();

                if (ORIGEM_CANAL_MANUAL.equals(origemCanalNormalizado)) {
                    Optional<PedidoExistente> pedidoAtivo = buscarPedidoAbertoPorClienteId(conn, clienteId);
                    if (pedidoAtivo.isPresent()) {
                        PedidoExistente existente = pedidoAtivo.get();
                        registrarAtendimentoIdempotenteSeNecessario(
                                conn,
                                origemCanalNormalizado,
                                dedupeKey,
                                existente.pedidoId(),
                                existente.clienteId(),
                                telefoneNormalizado,
                                atendimentoRequestHash);
                        conn.commit();
                        return new AtendimentoTelefonicoResultado(
                                existente.pedidoId(),
                                existente.clienteId(),
                                telefoneNormalizado,
                                clienteResolucao.clienteCriado(),
                                true);
                    }
                }

                try {
                    validarClienteElegivelParaPedido(clienteResolucao.cadastro());
                    validarCoberturaMoc(conn, clienteResolucao.cadastro());
                    validarElegibilidadeVale(conn, clienteId, quantidadeGaloes, metodoPagamentoNormalizado);
                } catch (IllegalArgumentException regraNegocioEx) {
                    // Mantem o cadastro capturado/atualizado mesmo quando a criacao do pedido e rejeitada.
                    conn.commit();
                    transacaoFinalizada = true;
                    throw regraNegocioEx;
                }

                InsertPedidoResult insert;
                if (externalCallIdLegacy != null) {
                    assertIdempotencySchema(conn);
                    insert = inserirPedidoPendenteIdempotente(
                            conn,
                            clienteId,
                            quantidadeGaloes,
                            atendenteId,
                            externalCallIdLegacy,
                            metodoPagamentoNormalizado,
                            janelaPedido);
                } else {
                    insert = inserirPedidoPendente(
                            conn, clienteId, quantidadeGaloes, atendenteId, metodoPagamentoNormalizado, janelaPedido);
                }

                if (!insert.idempotente()) {
                    dispatchEventService.publicar(
                            conn,
                            DispatchEventTypes.PEDIDO_CRIADO,
                            "PEDIDO",
                            (long) insert.pedidoId(),
                            new PedidoCriadoPayload(insert.pedidoId(), clienteId, externalCallIdLegacy));
                }

                registrarAtendimentoIdempotenteSeNecessario(
                        conn,
                        origemCanalNormalizado,
                        dedupeKey,
                        insert.pedidoId(),
                        insert.clienteId(),
                        telefoneNormalizado,
                        atendimentoRequestHash);

                conn.commit();
                transacaoFinalizada = true;
                return new AtendimentoTelefonicoResultado(
                        insert.pedidoId(),
                        insert.clienteId(),
                        telefoneNormalizado,
                        clienteResolucao.clienteCriado(),
                        insert.idempotente());
            } catch (Exception e) {
                if (!transacaoFinalizada) {
                    conn.rollback();
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw mapearSqlException(e, "Falha ao registrar pedido via atendimento omnichannel");
        }
    }

    private String resolveDedupeKey(String origemCanal, String sourceEventId, String manualRequestId) {
        if (sourceEventId != null) {
            return sourceEventId;
        }
        if (ORIGEM_CANAL_MANUAL.equals(origemCanal)) {
            return manualRequestId;
        }
        return null;
    }

    private void registrarAtendimentoIdempotenteSeNecessario(
            Connection conn,
            String origemCanal,
            String dedupeKey,
            int pedidoId,
            int clienteId,
            String telefoneNormalizado,
            String requestHash)
            throws SQLException {
        if (dedupeKey == null) {
            return;
        }

        String sql = "INSERT INTO atendimentos_idempotencia "
                + "(origem_canal, source_event_id, pedido_id, cliente_id, telefone_normalizado, request_hash) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (origem_canal, source_event_id) DO NOTHING";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
    }

    private Optional<AtendimentoIdempotenteExistente> buscarAtendimentoIdempotente(
            Connection conn, String origemCanal, String dedupeKey) throws SQLException {
        String sql = "SELECT pedido_id, cliente_id, telefone_normalizado, request_hash "
                + "FROM atendimentos_idempotencia "
                + "WHERE origem_canal = ? AND source_event_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, origemCanal);
            stmt.setString(2, dedupeKey);
            try (ResultSet rs = stmt.executeQuery()) {
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
    }

    private void lockPorIdempotenciaAtendimento(Connection conn, String origemCanal, String dedupeKey)
            throws SQLException {
        String sql = "SELECT pg_advisory_xact_lock(hashtext(?))";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, origemCanal + "|" + dedupeKey);
            stmt.executeQuery();
        }
    }

    private void assertAtendimentoIdempotenciaSchema(Connection conn) throws SQLException {
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

    private String buildAtendimentoRequestHash(
            String origemCanal,
            String dedupeKey,
            String telefoneNormalizado,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento,
            JanelaPedido janelaPedido,
            CadastroClienteInput cadastroClienteInput) {
        StringBuilder canonical = new StringBuilder(256);
        appendCanonicalField(canonical, "origemCanal", origemCanal);
        appendCanonicalField(canonical, "dedupeKey", dedupeKey);
        appendCanonicalField(canonical, "telefoneNormalizado", telefoneNormalizado);
        appendCanonicalField(canonical, "quantidadeGaloes", Integer.toString(quantidadeGaloes));
        appendCanonicalField(canonical, "atendenteId", Integer.toString(atendenteId));
        appendCanonicalField(canonical, "metodoPagamento", metodoPagamento);
        appendCanonicalField(canonical, "janelaTipo", janelaPedido.tipo());
        appendCanonicalField(
                canonical,
                "janelaInicio",
                janelaPedido.inicio() == null ? null : janelaPedido.inicio().format(WINDOW_TIME_FORMATTER));
        appendCanonicalField(
                canonical,
                "janelaFim",
                janelaPedido.fim() == null ? null : janelaPedido.fim().format(WINDOW_TIME_FORMATTER));
        appendCanonicalField(canonical, "nomeCliente", cadastroClienteInput.nomeCliente());
        appendCanonicalField(canonical, "endereco", cadastroClienteInput.endereco());
        appendCanonicalField(
                canonical,
                "latitude",
                cadastroClienteInput.latitude() == null ? null : Double.toString(cadastroClienteInput.latitude()));
        appendCanonicalField(
                canonical,
                "longitude",
                cadastroClienteInput.longitude() == null ? null : Double.toString(cadastroClienteInput.longitude()));
        return sha256Hex(canonical.toString());
    }

    private static void appendCanonicalField(StringBuilder canonical, String field, String value) {
        canonical.append(field).append('=');
        if (value == null) {
            canonical.append("<null>");
        } else {
            canonical.append(value.replace("\\", "\\\\").replace("|", "\\|"));
        }
        canonical.append('|');
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

    private String resolveExternalCallIdLegacy(String origemCanal, String sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        if (sourceEventId.length() <= 64 && ORIGEM_CANAL_TELEFONIA_FIXO.equals(origemCanal)) {
            return sourceEventId;
        }

        String canonical = origemCanal + ":" + sourceEventId;
        if (canonical.length() <= 64) {
            return canonical;
        }
        return sha256Hex(canonical).substring(0, 64);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
                sb.append(Character.forDigit(b & 0x0F, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel no runtime", e);
        }
    }

    private ClienteResolucao obterOuCriarClientePorTelefone(
            Connection conn, String telefoneNormalizado, CadastroClienteInput cadastroClienteInput)
            throws SQLException {
        Optional<ClienteCadastro> clienteExistente = buscarClientePorTelefoneNormalizado(conn, telefoneNormalizado);
        if (clienteExistente.isPresent()) {
            ClienteCadastro atualizado =
                    atualizarCadastroClienteSeInformado(conn, clienteExistente.get(), cadastroClienteInput);
            return new ClienteResolucao(atualizado.clienteId(), false, atualizado);
        }

        ClienteCadastro criado = criarClienteInicial(conn, telefoneNormalizado, cadastroClienteInput);
        return new ClienteResolucao(criado.clienteId(), true, criado);
    }

    private ClienteCadastro criarClienteInicial(
            Connection conn, String telefoneNormalizado, CadastroClienteInput cadastroClienteInput)
            throws SQLException {
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

        String sql = "INSERT INTO clientes (nome, telefone, tipo, endereco, latitude, longitude, notas) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "RETURNING id, nome, endereco, latitude, longitude";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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

            try (ResultSet rs = stmt.executeQuery()) {
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
    }

    private ClienteCadastro atualizarCadastroClienteSeInformado(
            Connection conn, ClienteCadastro clienteAtual, CadastroClienteInput cadastroClienteInput)
            throws SQLException {
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

        String sql = "UPDATE clientes "
                + "SET nome = ?, endereco = ?, latitude = ?, longitude = ?, atualizado_em = CURRENT_TIMESTAMP "
                + "WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
    }

    private void validarCoberturaMoc(Connection conn, ClienteCadastro cliente) throws SQLException {
        if (cliente.latitude() == null || cliente.longitude() == null) {
            throw new IllegalArgumentException(
                    "Cliente sem geolocalizacao valida. Atualize cadastro antes de criar pedido");
        }
        CoberturaBbox bbox = carregarCoberturaBbox(conn);
        if (cliente.latitude() < bbox.minLat()
                || cliente.latitude() > bbox.maxLat()
                || cliente.longitude() < bbox.minLon()
                || cliente.longitude() > bbox.maxLon()) {
            throw new IllegalArgumentException("Cliente fora da cobertura operacional de MOC");
        }
    }

    private CoberturaBbox carregarCoberturaBbox(Connection conn) throws SQLException {
        String valor = null;
        String sql = "SELECT valor FROM configuracoes WHERE chave = 'cobertura_bbox' LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                valor = rs.getString("valor");
            }
        }
        if (valor == null || valor.isBlank()) {
            valor = COBERTURA_BBOX_PADRAO;
        }
        return parseBbox(valor);
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

    private void assertIdempotencySchema(Connection conn) throws SQLException {
        if (!hasColumn(conn, "pedidos", "external_call_id")) {
            throw new IllegalStateException("Schema desatualizado: coluna pedidos.external_call_id ausente");
        }
        if (!hasUniqueConstraint(conn, "uk_pedidos_external_call_id")) {
            throw new IllegalStateException(
                    "Schema desatualizado: constraint unica uk_pedidos_external_call_id ausente");
        }
    }

    private boolean hasTable(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
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
        String sql = "SELECT id, nome, endereco, latitude, longitude "
                + "FROM clientes "
                + "WHERE regexp_replace(telefone, '[^0-9]', '', 'g') = ? "
                + "ORDER BY CASE "
                + "    WHEN btrim(COALESCE(endereco, '')) <> '' "
                + "         AND lower(btrim(COALESCE(endereco, ''))) <> 'endereco pendente' "
                + "         AND latitude IS NOT NULL "
                + "         AND longitude IS NOT NULL "
                + "    THEN 0 ELSE 1 END, "
                + "id DESC "
                + "LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, telefoneNormalizado);
            try (ResultSet rs = stmt.executeQuery()) {
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
            String metodoPagamento,
            JanelaPedido janelaPedido)
            throws SQLException {
        String sql = "INSERT INTO pedidos "
                + "(cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, external_call_id, metodo_pagamento) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (external_call_id) DO NOTHING "
                + "RETURNING id, cliente_id";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
            Connection conn,
            int clienteId,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento,
            JanelaPedido janelaPedido)
            throws SQLException {
        String sql = "INSERT INTO pedidos "
                + "(cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, metodo_pagamento) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "RETURNING id, cliente_id";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new InsertPedidoResult(rs.getInt("id"), rs.getInt("cliente_id"), false);
                }
            }
        }
        throw new SQLException("Falha ao criar pedido pendente em atendimento");
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

    private static JanelaPedido normalizeJanelaPedido(
            String janelaTipoRaw, String janelaInicioRaw, String janelaFimRaw) {
        String janelaTipo = normalizeJanelaTipo(janelaTipoRaw);
        LocalTime janelaInicio = parseOptionalWindowTime(janelaInicioRaw, "janelaInicio");
        LocalTime janelaFim = parseOptionalWindowTime(janelaFimRaw, "janelaFim");

        if ("ASAP".equals(janelaTipo)) {
            if (janelaInicio != null || janelaFim != null) {
                throw new IllegalArgumentException("janelaInicio/janelaFim so podem ser enviados com janelaTipo=HARD");
            }
            return new JanelaPedido("ASAP", null, null);
        }

        if (janelaInicio == null || janelaFim == null) {
            throw new IllegalArgumentException("janelaTipo=HARD exige janelaInicio e janelaFim no formato HH:mm");
        }
        if (!janelaFim.isAfter(janelaInicio)) {
            throw new IllegalArgumentException("janelaFim deve ser maior que janelaInicio para janelaTipo=HARD");
        }
        return new JanelaPedido("HARD", janelaInicio, janelaFim);
    }

    private static String normalizeJanelaTipo(String janelaTipoRaw) {
        if (janelaTipoRaw == null || janelaTipoRaw.isBlank()) {
            return "ASAP";
        }

        String normalized = janelaTipoRaw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ASAP", "FLEX", "FLEXIVEL", "LIVRE" -> "ASAP";
            case "HARD" -> "HARD";
            default -> throw new IllegalArgumentException("janelaTipo invalido");
        };
    }

    private static LocalTime parseOptionalWindowTime(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(trimmed, WINDOW_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " deve estar no formato HH:mm");
        }
    }

    private static String normalizeOrigemCanal(String origemCanal, String sourceEventId, String manualRequestId) {
        if (origemCanal == null || origemCanal.isBlank()) {
            if (sourceEventId != null && !sourceEventId.isBlank()) {
                return ORIGEM_CANAL_TELEFONIA_FIXO;
            }
            if (manualRequestId != null && !manualRequestId.isBlank()) {
                return ORIGEM_CANAL_MANUAL;
            }
            return ORIGEM_CANAL_MANUAL;
        }

        String normalized = origemCanal.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MANUAL" -> ORIGEM_CANAL_MANUAL;
            case "WHATSAPP", "WA" -> ORIGEM_CANAL_WHATSAPP;
            case "BINA", "BINA_FIXO" -> ORIGEM_CANAL_BINA_FIXO;
            case "TELEFONIA", "TELEFONIA_FIXO", "LIGACAO_FIXA" -> ORIGEM_CANAL_TELEFONIA_FIXO;
            default -> throw new IllegalArgumentException("origemCanal invalido");
        };
    }

    private static String normalizeSourceEventIdOpcional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("sourceEventId/manualRequestId deve ter no maximo 128 caracteres");
        }
        return trimmed;
    }

    private static void validarConsistenciaCanalEChaves(
            String origemCanal, String sourceEventId, String manualRequestId) {
        if (ORIGEM_CANAL_MANUAL.equals(origemCanal) && sourceEventId != null) {
            throw new IllegalArgumentException("sourceEventId nao pode ser usado com origemCanal=MANUAL");
        }
        if (!ORIGEM_CANAL_MANUAL.equals(origemCanal) && sourceEventId == null) {
            throw new IllegalArgumentException("sourceEventId obrigatorio para origemCanal automatica");
        }
        if (!ORIGEM_CANAL_MANUAL.equals(origemCanal) && manualRequestId != null) {
            throw new IllegalArgumentException("manualRequestId so pode ser usado com origemCanal=MANUAL");
        }
    }

    private static CadastroClienteInput normalizeCadastroClienteInput(
            String nomeCliente, String endereco, Double latitude, Double longitude) {
        String nomeNormalizado = normalizeOptionalText(nomeCliente);
        String enderecoNormalizado = normalizeOptionalText(endereco);
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("latitude e longitude devem ser informadas juntas");
        }
        return new CadastroClienteInput(nomeNormalizado, enderecoNormalizado, latitude, longitude);
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private record ClienteCadastro(int clienteId, String nome, String endereco, Double latitude, Double longitude) {}

    private record ClienteResolucao(int clienteId, boolean clienteCriado, ClienteCadastro cadastro) {}

    private record CadastroClienteInput(String nomeCliente, String endereco, Double latitude, Double longitude) {}

    private record AtendimentoIdempotenteExistente(
            int pedidoId, int clienteId, String telefoneNormalizado, String requestHash) {}

    private record InsertPedidoResult(int pedidoId, int clienteId, boolean idempotente) {}

    private record PedidoCriadoPayload(int pedidoId, int clienteId, String externalCallId) {}

    private record JanelaPedido(String tipo, LocalTime inicio, LocalTime fim) {}

    private record CoberturaBbox(double minLon, double minLat, double maxLon, double maxLat) {}
}
