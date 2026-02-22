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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                    "TRUNCATE TABLE sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private int criarAtendenteId(String email) throws Exception {
        User atendente = new User("Atendente", email, Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
        return userRepository.save(atendente).getId();
    }

    @Test
    void deveRejeitarPedidoQuandoTelefoneNaoPossuiClienteCadastradoComEnderecoValido() throws Exception {
        int atendenteId = criarAtendenteId("telefone1@teste.com");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registrarPedido("call-001", "+55 (38) 9 9876-1234", 2, atendenteId));

        assertTrue(ex.getMessage().contains("Cliente nao cadastrado"));
        assertEquals(0, contarLinhas("clientes"));
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
        AtendimentoTelefonicoResultado segunda = service.registrarPedido("call-003", "(38) 99999-5001", 3, atendenteId);

        assertFalse(primeira.idempotente());
        assertTrue(segunda.idempotente());
        assertEquals(primeira.pedidoId(), segunda.pedidoId());
        assertEquals(primeira.clienteId(), segunda.clienteId());
        assertEquals(1, contarLinhas("pedidos"));
        assertEquals(1, contarLinhas("clientes"));
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
