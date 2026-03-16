package com.aguaviva.service;

import com.aguaviva.repository.AtendimentoTelefonicoRepository;
import com.aguaviva.repository.AtendimentoTelefonicoRepository.AtendimentoIdempotenteExistente;
import com.aguaviva.repository.AtendimentoTelefonicoRepository.ClienteCadastro;
import com.aguaviva.repository.AtendimentoTelefonicoRepository.CoberturaBbox;
import com.aguaviva.repository.AtendimentoTelefonicoRepository.InsertPedidoResult;
import com.aguaviva.repository.AtendimentoTelefonicoRepository.PedidoExistente;
import com.aguaviva.repository.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public class AtendimentoTelefonicoService {

    private static final String ENDERECO_PENDENTE = "Endereco pendente";
    private static final String METODO_PAGAMENTO_VALE = "VALE";

    private final ConnectionFactory connectionFactory;
    private final DispatchEventService dispatchEventService;
    private final AtendimentoTelefonicoRepository repository;

    public AtendimentoTelefonicoService(ConnectionFactory connectionFactory) {
        this(connectionFactory, new DispatchEventService());
    }

    AtendimentoTelefonicoService(ConnectionFactory connectionFactory, DispatchEventService dispatchEventService) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory nao pode ser nulo");
        this.dispatchEventService =
                Objects.requireNonNull(dispatchEventService, "DispatchEventService nao pode ser nulo");
        this.repository = new AtendimentoTelefonicoRepository(connectionFactory);
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
        String externalCallIdNormalizado = AtendimentoRequestNormalizer.normalizeExternalCallId(externalCallId);
        return registrarPedidoOmnichannel(
                AtendimentoRequestNormalizer.ORIGEM_CANAL_TELEFONIA_FIXO,
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
                AtendimentoRequestNormalizer.ORIGEM_CANAL_MANUAL,
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
        String telefoneNormalizado = AtendimentoRequestNormalizer.normalizePhone(telefoneInformado);
        String metodoPagamentoNormalizado = AtendimentoRequestNormalizer.normalizeMetodoPagamento(metodoPagamento);
        AtendimentoRequestNormalizer.JanelaPedidoInput janelaPedido =
                AtendimentoRequestNormalizer.normalizeJanelaPedido(janelaTipo, janelaInicio, janelaFim);
        String origemCanalNormalizado =
                AtendimentoRequestNormalizer.normalizeOrigemCanal(origemCanal, sourceEventId, manualRequestId);
        String sourceEventIdNormalizado = AtendimentoRequestNormalizer.normalizeSourceEventIdOpcional(sourceEventId);
        String manualRequestIdNormalizado =
                AtendimentoRequestNormalizer.normalizeSourceEventIdOpcional(manualRequestId);
        AtendimentoRequestNormalizer.validarConsistenciaCanalEChaves(
                origemCanalNormalizado, sourceEventIdNormalizado, manualRequestIdNormalizado);
        String dedupeKey =
                resolveDedupeKey(origemCanalNormalizado, sourceEventIdNormalizado, manualRequestIdNormalizado);
        String externalCallIdLegacy = resolveExternalCallIdLegacy(origemCanalNormalizado, sourceEventIdNormalizado);
        AtendimentoRequestNormalizer.CadastroClienteInput cadastroClienteInput =
                AtendimentoRequestNormalizer.normalizeCadastroClienteInput(nomeCliente, endereco, latitude, longitude);
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

        AtendimentoRequestNormalizer.validateQuantidade(quantidadeGaloes);
        AtendimentoRequestNormalizer.validateAtendenteId(atendenteId);

        try (var conn = connectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean transacaoFinalizada = false;
            try {
                repository.lockPorTelefone(conn, telefoneNormalizado);
                repository.assertAtendenteExiste(conn, atendenteId);

                if (dedupeKey != null) {
                    repository.assertAtendimentoIdempotenciaSchema(conn);
                    repository.lockPorIdempotenciaAtendimento(conn, origemCanalNormalizado, dedupeKey);
                    Optional<AtendimentoIdempotenteExistente> idempotenteExistente =
                            repository.buscarAtendimentoIdempotente(conn, origemCanalNormalizado, dedupeKey);
                    if (idempotenteExistente.isPresent()) {
                        AtendimentoIdempotenteExistente replay = idempotenteExistente.get();
                        repository.registrarAtendimentoIdempotenteSeNecessario(
                                conn,
                                origemCanalNormalizado,
                                dedupeKey,
                                replay.pedidoId(),
                                replay.clienteId(),
                                replay.telefoneNormalizado(),
                                atendimentoRequestHash);
                        conn.commit();
                        return new AtendimentoTelefonicoResultado(
                                replay.pedidoId(), replay.clienteId(), replay.telefoneNormalizado(), false, true);
                    }
                }

                ClienteResolucao clienteResolucao =
                        obterOuCriarClientePorTelefone(conn, telefoneNormalizado, cadastroClienteInput);
                int clienteId = clienteResolucao.clienteId();

                if (AtendimentoRequestNormalizer.ORIGEM_CANAL_MANUAL.equals(origemCanalNormalizado)) {
                    Optional<PedidoExistente> pedidoAtivo = repository.buscarPedidoAbertoPorClienteId(conn, clienteId);
                    if (pedidoAtivo.isPresent()) {
                        PedidoExistente existente = pedidoAtivo.get();
                        repository.registrarAtendimentoIdempotenteSeNecessario(
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
                    repository.assertIdempotencySchema(conn);
                    AtendimentoTelefonicoRepository.JanelaPedidoInput repoJanela =
                            new AtendimentoTelefonicoRepository.JanelaPedidoInput(
                                    janelaPedido.tipo(), janelaPedido.inicio(), janelaPedido.fim());
                    insert = repository.inserirPedidoPendenteIdempotente(
                            conn,
                            clienteId,
                            quantidadeGaloes,
                            atendenteId,
                            externalCallIdLegacy,
                            metodoPagamentoNormalizado,
                            repoJanela);
                } else {
                    AtendimentoTelefonicoRepository.JanelaPedidoInput repoJanela =
                            new AtendimentoTelefonicoRepository.JanelaPedidoInput(
                                    janelaPedido.tipo(), janelaPedido.inicio(), janelaPedido.fim());
                    insert = repository.inserirPedidoPendente(
                            conn, clienteId, quantidadeGaloes, atendenteId, metodoPagamentoNormalizado, repoJanela);
                }

                if (!insert.idempotente()) {
                    dispatchEventService.publicar(
                            conn,
                            DispatchEventTypes.PEDIDO_CRIADO,
                            "PEDIDO",
                            (long) insert.pedidoId(),
                            new PedidoCriadoPayload(insert.pedidoId(), clienteId, externalCallIdLegacy));
                }

                repository.registrarAtendimentoIdempotenteSeNecessario(
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
        if (AtendimentoRequestNormalizer.ORIGEM_CANAL_MANUAL.equals(origemCanal)) {
            return manualRequestId;
        }
        return null;
    }

    private String buildAtendimentoRequestHash(
            String origemCanal,
            String dedupeKey,
            String telefoneNormalizado,
            int quantidadeGaloes,
            int atendenteId,
            String metodoPagamento,
            AtendimentoRequestNormalizer.JanelaPedidoInput janelaPedido,
            AtendimentoRequestNormalizer.CadastroClienteInput cadastroClienteInput) {
        StringBuilder canonical = new StringBuilder(256);
        appendCanonicalField(canonical, "origemCanal", origemCanal);
        appendCanonicalField(canonical, "dedupeKey", dedupeKey);
        appendCanonicalField(canonical, "telefoneNormalizado", telefoneNormalizado);
        appendCanonicalField(canonical, "quantidadeGaloes", Integer.toString(quantidadeGaloes));
        appendCanonicalField(canonical, "atendenteId", Integer.toString(atendenteId));
        appendCanonicalField(canonical, "metodoPagamento", metodoPagamento);
        appendCanonicalField(canonical, "janelaTipo", janelaPedido.tipo());
        appendCanonicalField(
                canonical, "janelaInicio", AtendimentoRequestNormalizer.formatWindowTime(janelaPedido.inicio()));
        appendCanonicalField(canonical, "janelaFim", AtendimentoRequestNormalizer.formatWindowTime(janelaPedido.fim()));
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

    private String resolveExternalCallIdLegacy(String origemCanal, String sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        if (sourceEventId.length() <= 64
                && AtendimentoRequestNormalizer.ORIGEM_CANAL_TELEFONIA_FIXO.equals(origemCanal)) {
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
            Connection conn,
            String telefoneNormalizado,
            AtendimentoRequestNormalizer.CadastroClienteInput cadastroClienteInput)
            throws SQLException {
        Optional<ClienteCadastro> clienteExistente =
                repository.buscarClientePorTelefoneNormalizado(conn, telefoneNormalizado);
        AtendimentoTelefonicoRepository.CadastroClienteInput repoCadastro =
                new AtendimentoTelefonicoRepository.CadastroClienteInput(
                        cadastroClienteInput.nomeCliente(),
                        cadastroClienteInput.endereco(),
                        cadastroClienteInput.latitude(),
                        cadastroClienteInput.longitude());
        if (clienteExistente.isPresent()) {
            ClienteCadastro atualizado =
                    repository.atualizarCadastroClienteSeInformado(conn, clienteExistente.get(), repoCadastro);
            return new ClienteResolucao(atualizado.clienteId(), false, atualizado);
        }

        ClienteCadastro criado = repository.criarClienteInicial(conn, telefoneNormalizado, repoCadastro);
        return new ClienteResolucao(criado.clienteId(), true, criado);
    }

    private void validarCoberturaMoc(Connection conn, ClienteCadastro cliente) throws SQLException {
        if (cliente.latitude() == null || cliente.longitude() == null) {
            throw new IllegalArgumentException(
                    "Cliente sem geolocalizacao valida. Atualize cadastro antes de criar pedido");
        }
        CoberturaBbox bbox = repository.carregarCoberturaBbox(conn);
        if (cliente.latitude() < bbox.minLat()
                || cliente.latitude() > bbox.maxLat()
                || cliente.longitude() < bbox.minLon()
                || cliente.longitude() > bbox.maxLon()) {
            throw new IllegalArgumentException("Cliente fora da cobertura operacional de MOC");
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

    private void validarElegibilidadeVale(Connection conn, int clienteId, int quantidadeGaloes, String metodoPagamento)
            throws SQLException {
        if (!METODO_PAGAMENTO_VALE.equals(metodoPagamento)) {
            return;
        }

        int saldoDisponivel = repository.buscarSaldoValeComLock(conn, clienteId);
        if (saldoDisponivel < quantidadeGaloes) {
            throw new IllegalArgumentException("cliente nao possui vale suficiente para checkout");
        }
    }

    private RuntimeException mapearSqlException(SQLException e, String mensagemPadrao) {
        if ("23503".equals(e.getSQLState())) {
            return new IllegalArgumentException("Atendente informado nao existe", e);
        }
        return new IllegalStateException(mensagemPadrao, e);
    }

    private record ClienteResolucao(int clienteId, boolean clienteCriado, ClienteCadastro cadastro) {}

    private record PedidoCriadoPayload(int pedidoId, int clienteId, String externalCallId) {}
}
