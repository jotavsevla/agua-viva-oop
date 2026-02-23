package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ClienteRepository;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class AtendimentoTelefonicoServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static AtendimentoTelefonicoService service;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        service = new AtendimentoTelefonicoService(factory);
        garantirSchemaIdempotenciaTelefonica();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparAntes() throws Exception {
        limparBanco();
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparBanco();
    }

    private static void garantirSchemaIdempotenciaTelefonica() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS external_call_id VARCHAR(64)");
            stmt.execute("ALTER TABLE pedidos DROP CONSTRAINT IF EXISTS uk_pedidos_external_call_id");
            stmt.execute("DROP INDEX IF EXISTS uk_pedidos_external_call_id");
            stmt.execute("ALTER TABLE pedidos ADD CONSTRAINT uk_pedidos_external_call_id UNIQUE (external_call_id)");
            stmt.execute("CREATE TABLE IF NOT EXISTS atendimentos_idempotencia ("
                    + "origem_canal VARCHAR(32) NOT NULL, "
                    + "source_event_id VARCHAR(128) NOT NULL, "
                    + "pedido_id INTEGER NOT NULL, "
                    + "cliente_id INTEGER NOT NULL, "
                    + "telefone_normalizado VARCHAR(15) NOT NULL, "
                    + "criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (origem_canal, source_event_id))");
            stmt.execute("ALTER TABLE atendimentos_idempotencia ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64)");
            stmt.execute("INSERT INTO configuracoes (chave, valor, descricao) VALUES ("
                    + "'cobertura_bbox', '-43.9600,-16.8200,-43.7800,-16.6200', "
                    + "'Cobertura operacional de atendimento em bbox') "
                    + "ON CONFLICT (chave) DO NOTHING");
            stmt.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dispatch_event_status') "
                    + "THEN CREATE TYPE dispatch_event_status AS ENUM ('PENDENTE', 'PROCESSADO'); "
                    + "END IF; "
                    + "END $$;");
            stmt.execute("CREATE TABLE IF NOT EXISTS dispatch_events ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "event_type VARCHAR(64) NOT NULL, "
                    + "aggregate_type VARCHAR(32) NOT NULL, "
                    + "aggregate_id BIGINT, "
                    + "payload JSONB NOT NULL DEFAULT '{}'::jsonb, "
                    + "status dispatch_event_status NOT NULL DEFAULT 'PENDENTE', "
                    + "created_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "available_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "processed_em TIMESTAMP)");
        }
    }

    private void limparBanco() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "TRUNCATE TABLE atendimentos_idempotencia, dispatch_events, sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private int criarAtendenteId(String email) throws Exception {
        User atendente = new User("Atendente", email, Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
        return userRepository.save(atendente).getId();
    }

    @Test
    void deveCriarCadastroPendenteQuandoTelefoneNaoPossuiClienteERejeitarPedidoAteCompletarCadastro() throws Exception {
        int atendenteId = criarAtendenteId("telefone1@teste.com");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedido("call-001", "+55 (38) 9 9876-1234", 2, atendenteId));

        assertTrue(ex.getMessage().contains("Cliente sem endereco valido"));
        assertEquals(1, contarLinhas("clientes"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveMitigarDuplicidadePorTelefoneNormalizadoPorUnicidadeOuPreferenciaPorCadastroElegivel() throws Exception {
        int atendenteId = criarAtendenteId("telefone-dup@teste.com");
        Cliente cadastroInvalido = clienteRepository.save(
                new Cliente("Cliente legado", "(38) 99876-9551", ClienteTipo.PF, "Endereco pendente"));

        try {
            Cliente cadastroElegivel = criarClienteComGeo("Cliente elegivel", "38 99876 9551", "Rua Resolvida, 99");
            AtendimentoTelefonicoResultado resultado = service.registrarPedidoManual("(38) 99876-9551", 1, atendenteId);
            assertEquals(cadastroElegivel.getId(), resultado.clienteId());
            assertEquals(1, contarLinhas("pedidos"));
            return;
        } catch (IllegalArgumentException ex) {
            // Se o indice de telefone normalizado estiver ativo, a duplicidade e bloqueada na gravacao.
            assertTrue(ex.getMessage().contains("Telefone ja cadastrado"));
        }

        IllegalArgumentException exAtendimento = assertThrows(
                IllegalArgumentException.class, () -> service.registrarPedidoManual("(38) 99876-9551", 1, atendenteId));
        assertTrue(exAtendimento.getMessage().contains("Cliente sem endereco valido"));
        assertEquals(cadastroInvalido.getId(), idClientePorTelefoneNormalizado("38998769551"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveCriarPedidoComMetodoValeQuandoClienteTemSaldoSuficiente() throws Exception {
        int atendenteId = criarAtendenteId("telefone1b@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente com vale", "(38) 99888-1101", "Rua Vale, 10");
        inserirSaldoVale(cliente.getId(), 5);

        AtendimentoTelefonicoResultado resultado =
                service.registrarPedido("call-vale-001", "(38) 99888-1101", 2, atendenteId, "VALE");

        assertFalse(resultado.idempotente());
        assertEquals(cliente.getId(), resultado.clienteId());
        assertEquals("VALE", metodoPagamentoPedido(resultado.pedidoId()));
    }

    @Test
    void deveCriarPedidoHardQuandoJanelaValidaForInformada() throws Exception {
        int atendenteId = criarAtendenteId("telefone-hard@teste.com");
        criarClienteComGeo("Cliente hard", "(38) 99888-1201", "Rua Janela, 10");

        AtendimentoTelefonicoResultado resultado = service.registrarPedido(
                "call-hard-001", "(38) 99888-1201", 1, atendenteId, "PIX", "HARD", "09:00", "11:00");

        assertEquals("HARD", janelaTipoPedido(resultado.pedidoId()));
        assertEquals("09:00:00", janelaInicioPedido(resultado.pedidoId()));
        assertEquals("11:00:00", janelaFimPedido(resultado.pedidoId()));
    }

    @Test
    void deveMapearJanelaFlexivelParaAsap() throws Exception {
        int atendenteId = criarAtendenteId("telefone-flex@teste.com");
        criarClienteComGeo("Cliente flexivel", "(38) 99888-1202", "Rua Janela, 11");

        AtendimentoTelefonicoResultado resultado = service.registrarPedido(
                "call-flex-001", "(38) 99888-1202", 1, atendenteId, "PIX", "FLEXIVEL", null, null);

        assertEquals("ASAP", janelaTipoPedido(resultado.pedidoId()));
        assertNull(janelaInicioPedido(resultado.pedidoId()));
        assertNull(janelaFimPedido(resultado.pedidoId()));
    }

    @Test
    void deveRejeitarJanelaHardQuandoHorarioNaoForCompleto() throws Exception {
        int atendenteId = criarAtendenteId("telefone-hard-invalido@teste.com");
        criarClienteComGeo("Cliente hard invalido", "(38) 99888-1203", "Rua Janela, 12");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedido(
                        "call-hard-002", "(38) 99888-1203", 1, atendenteId, "PIX", "HARD", "09:00", null));

        assertTrue(ex.getMessage().contains("janelaTipo=HARD"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveRejeitarPedidoComMetodoValeQuandoClienteNaoTemSaldoSuficiente() throws Exception {
        int atendenteId = criarAtendenteId("telefone1c@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente sem saldo", "(38) 99888-1102", "Rua Vale, 20");
        inserirSaldoVale(cliente.getId(), 1);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedido("call-vale-002", "(38) 99888-1102", 2, atendenteId, "VALE"));

        assertTrue(ex.getMessage().contains("cliente nao possui vale"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveRejeitarPedidoComMetodoValeQuandoClienteNaoPossuiSaldoCadastrado() throws Exception {
        int atendenteId = criarAtendenteId("telefone1d@teste.com");
        criarClienteComGeo("Cliente sem saldo", "(38) 99888-1103", "Rua Vale, 30");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedido("call-vale-003", "(38) 99888-1103", 1, atendenteId, "VALE"));

        assertTrue(ex.getMessage().contains("cliente nao possui vale"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveReaproveitarClienteExistentePorTelefoneNormalizado() throws Exception {
        int atendenteId = criarAtendenteId("telefone2@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente ja cadastrado", "(38) 99876-1234", "Rua A, 100");

        AtendimentoTelefonicoResultado resultado = service.registrarPedido("call-002", "38 99876 1234", 1, atendenteId);

        assertFalse(resultado.clienteCriado());
        assertFalse(resultado.idempotente());
        assertEquals(cliente.getId(), resultado.clienteId());
        assertEquals(1, contarLinhas("clientes"));
        assertEquals(1, contarLinhas("pedidos"));
    }

    @Test
    void deveSerIdempotenteQuandoExternalCallIdForRepetido() throws Exception {
        int atendenteId = criarAtendenteId("telefone3@teste.com");
        criarClienteComGeo("Cliente idempotente", "(38) 99999-5001", "Rua D, 10");

        AtendimentoTelefonicoResultado primeira =
                service.registrarPedido("call-003", "(38) 99999-5001", 1, atendenteId);
        AtendimentoTelefonicoResultado segunda = service.registrarPedido("call-003", "(38) 99999-5001", 1, atendenteId);

        assertFalse(primeira.idempotente());
        assertTrue(segunda.idempotente());
        assertEquals(primeira.pedidoId(), segunda.pedidoId());
        assertEquals(primeira.clienteId(), segunda.clienteId());
        assertEquals(1, contarLinhas("pedidos"));
        assertEquals(1, contarLinhas("clientes"));
    }

    @Test
    void deveRejeitarCanalAutomaticoSemSourceEventId() throws Exception {
        int atendenteId = criarAtendenteId("telefone-auto-sem-source@teste.com");
        criarClienteComGeo("Cliente auto", "(38) 99999-5010", "Rua Auto, 1");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedidoOmnichannel(
                        "WHATSAPP",
                        null,
                        null,
                        "(38) 99999-5010",
                        1,
                        atendenteId,
                        "PIX",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertTrue(ex.getMessage().contains("sourceEventId obrigatorio"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveRejeitarManualRequestIdEmCanalAutomatico() throws Exception {
        int atendenteId = criarAtendenteId("telefone-auto-manual-key@teste.com");
        criarClienteComGeo("Cliente auto", "(38) 99999-5011", "Rua Auto, 2");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedidoOmnichannel(
                        "BINA_FIXO",
                        "bina-evt-001",
                        "manual-req-nao-permitido",
                        "(38) 99999-5011",
                        1,
                        atendenteId,
                        "PIX",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertTrue(ex.getMessage().contains("manualRequestId so pode ser usado com origemCanal=MANUAL"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveRejeitarSourceEventIdNoCanalManual() throws Exception {
        int atendenteId = criarAtendenteId("telefone-manual-source-event@teste.com");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedidoOmnichannel(
                        "MANUAL",
                        "manual-source-event-001",
                        null,
                        "(38) 99999-5015",
                        1,
                        atendenteId,
                        "PIX",
                        null,
                        null,
                        null,
                        "Cliente Manual Fonte",
                        "Rua Manual, 15",
                        -16.7310,
                        -43.8710));

        assertTrue(ex.getMessage().contains("sourceEventId nao pode ser usado com origemCanal=MANUAL"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    @Test
    void deveGerarExternalCallIdLegacyDeterministicoQuandoSourceEventIdLongoEmCanalAutomatico() throws Exception {
        int atendenteId = criarAtendenteId("telefone-auto-source-longo@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente source longo", "(38) 99999-5012", "Rua Auto, 3");
        String sourceEventIdLongo = IntStream.range(0, 110).mapToObj(i -> "x").collect(Collectors.joining());

        AtendimentoTelefonicoResultado primeira = service.registrarPedidoOmnichannel(
                "WHATSAPP",
                sourceEventIdLongo,
                null,
                "(38) 99999-5012",
                1,
                atendenteId,
                "PIX",
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        AtendimentoTelefonicoResultado segunda = service.registrarPedidoOmnichannel(
                "WHATSAPP",
                sourceEventIdLongo,
                null,
                "(38) 99999-5012",
                1,
                atendenteId,
                "PIX",
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertEquals(cliente.getId(), primeira.clienteId());
        assertEquals(primeira.pedidoId(), segunda.pedidoId());
        assertFalse(primeira.idempotente());
        assertTrue(segunda.idempotente());
        assertEquals(1, contarLinhas("pedidos"));
        assertEquals(1, contarLinhas("atendimentos_idempotencia"));

        String externalCallId = externalCallIdPedido(primeira.pedidoId());
        assertTrue(externalCallId != null && externalCallId.length() == 64);
    }

    @Test
    void deveRejeitarReusoDeSourceEventIdComPayloadDivergenteNoMesmoCanal() throws Exception {
        int atendenteId = criarAtendenteId("telefone-auto-divergente@teste.com");
        criarClienteComGeo("Cliente diverge", "(38) 99999-5013", "Rua Auto, 4");

        AtendimentoTelefonicoResultado primeira = service.registrarPedidoOmnichannel(
                "WHATSAPP",
                "wa-div-001",
                null,
                "(38) 99999-5013",
                1,
                atendenteId,
                "PIX",
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.registrarPedidoOmnichannel(
                        "WHATSAPP",
                        "wa-div-001",
                        null,
                        "(38) 99999-5013",
                        2,
                        atendenteId,
                        "PIX",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertTrue(ex.getMessage().contains("payload divergente"));
        assertEquals(1, contarLinhas("pedidos"));
        assertEquals(1, contarLinhas("atendimentos_idempotencia"));
        assertFalse(primeira.idempotente());
    }

    @Test
    void deveRejeitarReusoDeManualRequestIdComPayloadDivergenteNoCanalManual() throws Exception {
        int atendenteId = criarAtendenteId("telefone-manual-divergente@teste.com");

        AtendimentoTelefonicoResultado primeira = service.registrarPedidoOmnichannel(
                "MANUAL",
                null,
                "manual-div-001",
                "(38) 99999-5014",
                1,
                atendenteId,
                "PIX",
                null,
                null,
                null,
                "Cliente Manual Divergente",
                "Rua Manual, 14",
                -16.7310,
                -43.8710);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.registrarPedidoOmnichannel(
                        "MANUAL",
                        null,
                        "manual-div-001",
                        "(38) 99999-5014",
                        2,
                        atendenteId,
                        "PIX",
                        null,
                        null,
                        null,
                        "Cliente Manual Divergente",
                        "Rua Manual, 14",
                        -16.7310,
                        -43.8710));

        assertTrue(ex.getMessage().contains("payload divergente"));
        assertEquals(1, contarLinhas("pedidos"));
        assertEquals(1, contarLinhas("atendimentos_idempotencia"));
        assertFalse(primeira.idempotente());
    }

    @Test
    void deveFazerRollbackQuandoAtendenteNaoExiste() throws Exception {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> service.registrarPedido("call-004", "(38) 99999-6001", 1, 999));

        assertEquals("Atendente informado nao existe", ex.getMessage());
        assertEquals(0, contarLinhas("pedidos"));
        assertEquals(0, contarLinhas("clientes"));
    }

    @Test
    void deveRejeitarExternalCallIdInvalido() throws Exception {
        int atendenteId = criarAtendenteId("telefone4@teste.com");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedido("   ", "(38) 99999-7001", 1, atendenteId));
    }

    @Test
    void deveRetornarPedidoAtivoQuandoAtendimentoManualEncontrarPedidoAberto() throws Exception {
        int atendenteId = criarAtendenteId("telefone5@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente manual", "(38) 99876-8001", "Rua B, 12");
        int pedidoExistenteId = inserirPedido(cliente.getId(), atendenteId, "PENDENTE", "call-manual-001");

        AtendimentoTelefonicoResultado resultado = service.registrarPedidoManual("(38) 99876-8001", 2, atendenteId);

        assertTrue(resultado.idempotente());
        assertFalse(resultado.clienteCriado());
        assertEquals(cliente.getId(), resultado.clienteId());
        assertEquals(pedidoExistenteId, resultado.pedidoId());
        assertEquals(1, contarLinhas("pedidos"));
    }

    @Test
    void deveRetornarPedidoAtivoNoManualMesmoQuandoCadastroFicarSemGeoOuEnderecoValido() throws Exception {
        int atendenteId = criarAtendenteId("telefone5b@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente degradado", "(38) 99876-8003", "Rua D, 42");
        int pedidoExistenteId = inserirPedido(cliente.getId(), atendenteId, "PENDENTE", "call-manual-003");

        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE clientes SET endereco = ?, latitude = NULL, longitude = NULL WHERE id = ?")) {
            stmt.setString(1, "Endereco pendente");
            stmt.setInt(2, cliente.getId());
            stmt.executeUpdate();
        }

        AtendimentoTelefonicoResultado resultado = service.registrarPedidoManual("(38) 99876-8003", 1, atendenteId);
        assertTrue(resultado.idempotente());
        assertEquals(pedidoExistenteId, resultado.pedidoId());
        assertEquals(cliente.getId(), resultado.clienteId());
    }

    @Test
    void deveCriarPedidoQuandoAtendimentoManualNaoEncontrarPedidoAtivo() throws Exception {
        int atendenteId = criarAtendenteId("telefone6@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente historico", "(38) 99876-8002", "Rua C, 30");
        inserirPedido(cliente.getId(), atendenteId, "ENTREGUE", "call-manual-002");

        AtendimentoTelefonicoResultado resultado = service.registrarPedidoManual("(38) 99876-8002", 1, atendenteId);

        assertFalse(resultado.idempotente());
        assertFalse(resultado.clienteCriado());
        assertEquals(cliente.getId(), resultado.clienteId());
        assertEquals(2, contarLinhas("pedidos"));
        assertEquals("PENDENTE", statusPedido(resultado.pedidoId()));
        assertNull(externalCallIdPedido(resultado.pedidoId()));
    }

    @Test
    void deveRejeitarAtendimentoManualComMetodoValeSemSaldoSuficiente() throws Exception {
        int atendenteId = criarAtendenteId("telefone7@teste.com");
        Cliente cliente = criarClienteComGeo("Cliente manual sem saldo", "(38) 99888-1104", "Rua Vale, 40");
        inserirSaldoVale(cliente.getId(), 0);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedidoManual("(38) 99888-1104", 1, atendenteId, "VALE"));

        assertTrue(ex.getMessage().contains("cliente nao possui vale"));
        assertEquals(0, contarLinhas("pedidos"));
    }

    private int contarLinhas(String tabela) throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int idClientePorTelefoneNormalizado(String telefoneNormalizado) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT id FROM clientes WHERE regexp_replace(telefone, '[^0-9]', '', 'g') = ? ORDER BY id DESC LIMIT 1")) {
            stmt.setString(1, telefoneNormalizado);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Cliente criarClienteComGeo(String nome, String telefone, String endereco) throws Exception {
        return clienteRepository.save(new Cliente(
                nome,
                telefone,
                ClienteTipo.PF,
                endereco,
                BigDecimal.valueOf(-16.72),
                BigDecimal.valueOf(-43.86),
                null));
    }

    private String statusPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT status::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String externalCallIdPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT external_call_id FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String metodoPagamentoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT metodo_pagamento::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String janelaTipoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT janela_tipo::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String janelaInicioPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT janela_inicio::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String janelaFimPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT janela_fim::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private void inserirSaldoVale(int clienteId, int quantidade) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO saldo_vales (cliente_id, quantidade) VALUES (?, ?) "
                                + "ON CONFLICT (cliente_id) DO UPDATE SET quantidade = EXCLUDED.quantidade, atualizado_em = CURRENT_TIMESTAMP")) {
            stmt.setInt(1, clienteId);
            stmt.setInt(2, quantidade);
            stmt.executeUpdate();
        }
    }

    private int inserirPedido(int clienteId, int atendenteId, String status, String externalCallId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO pedidos (cliente_id, quantidade_galoes, janela_tipo, janela_inicio, janela_fim, status, criado_por, external_call_id) "
                                + "VALUES (?, 1, 'ASAP', NULL, NULL, ?, ?, ?) RETURNING id")) {
            stmt.setInt(1, clienteId);
            stmt.setObject(2, status, java.sql.Types.OTHER);
            stmt.setInt(3, atendenteId);
            stmt.setString(4, externalCallId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
