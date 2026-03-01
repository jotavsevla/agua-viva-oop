package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.pedido.JanelaTipo;
import com.aguaviva.domain.pedido.Pedido;
import com.aguaviva.domain.pedido.PedidoStatus;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ClienteRepository;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.PedidoRepository;
import com.aguaviva.repository.UserRepository;
import com.aguaviva.support.TestConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ExecucaoEntregaServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static PedidoRepository pedidoRepository;
    private static ExecucaoEntregaService execucaoService;

    @BeforeAll
    static void setUp() throws Exception {
        factory = TestConnectionFactory.newConnectionFactory();
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        pedidoRepository = new PedidoRepository(factory);
        execucaoService = new ExecucaoEntregaService(factory);
        garantirSchemaDispatch();
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

    private static void garantirSchemaDispatch() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TYPE entrega_status ADD VALUE IF NOT EXISTS 'EM_EXECUCAO'");
            stmt.execute("ALTER TYPE entrega_status ADD VALUE IF NOT EXISTS 'CANCELADA'");
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
                    "TRUNCATE TABLE dispatch_events, sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private int criarAtendenteId(String email) throws Exception {
        User atendente = new User("Atendente", email, Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
        return userRepository.save(atendente).getId();
    }

    private int criarEntregadorId(String email) throws Exception {
        User entregador = new User("Entregador", email, Password.fromPlainText("senha123"), UserPapel.ENTREGADOR);
        return userRepository.save(entregador).getId();
    }

    private int criarClienteId(String telefone) throws Exception {
        Cliente cliente = new Cliente("Cliente " + telefone, telefone, ClienteTipo.PF, "Rua A, 10");
        return clienteRepository.save(cliente).getId();
    }

    private int criarPedido(int clienteId, int atendenteId, PedidoStatus status) throws Exception {
        Pedido pedido = new Pedido(
                0, clienteId, 1, JanelaTipo.HARD, LocalTime.of(9, 0), LocalTime.of(11, 0), status, atendenteId);
        return pedidoRepository.save(pedido).getId();
    }

    private int criarRota(int entregadorId, String status) throws Exception {
        return criarRota(entregadorId, status, 1);
    }

    private int criarRota(int entregadorId, String status, int numeroNoDia) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO rotas (entregador_id, data, numero_no_dia, status) VALUES (?, CURRENT_DATE, ?, ?) RETURNING id")) {
            stmt.setInt(1, entregadorId);
            stmt.setInt(2, numeroNoDia);
            stmt.setObject(3, status, java.sql.Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int criarEntrega(int pedidoId, int rotaId, String status) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status) VALUES (?, ?, 1, ?) RETURNING id")) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setObject(3, status, java.sql.Types.OTHER);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void deveIniciarRotaEPromoverPedidosParaEmRota() throws Exception {
        int atendenteId = criarAtendenteId("exec1@teste.com");
        int entregadorId = criarEntregadorId("ent1@teste.com");
        int clienteId = criarClienteId("(38) 99999-9101");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.CONFIRMADO);
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        ExecucaoEntregaResultado resultado = execucaoService.registrarRotaIniciada(rotaId);

        assertFalse(resultado.idempotente());
        assertEquals(rotaId, resultado.rotaId());
        assertEquals("EM_ANDAMENTO", statusRota(rotaId));
        assertEquals("EM_EXECUCAO", statusEntrega(entregaId));
        assertEquals("EM_ROTA", statusPedido(pedidoId));
        assertEquals(1, contarEventos(DispatchEventTypes.ROTA_INICIADA));
    }

    @Test
    void naoDeveDuplicarEventoDeRotaIniciadaQuandoChamadaForIdempotenteSemPendencias() throws Exception {
        int atendenteId = criarAtendenteId("exec1b@teste.com");
        int entregadorId = criarEntregadorId("ent1b@teste.com");
        int clienteId = criarClienteId("(38) 99999-9111");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.CONFIRMADO);
        int rotaId = criarRota(entregadorId, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        ExecucaoEntregaResultado primeira = execucaoService.registrarRotaIniciada(rotaId);
        ExecucaoEntregaResultado segunda = execucaoService.registrarRotaIniciada(rotaId);

        assertFalse(primeira.idempotente());
        assertEquals("EM_ANDAMENTO", statusRota(rotaId));
        assertEquals("EM_EXECUCAO", statusEntrega(entregaId));
        assertEquals("EM_ROTA", statusPedido(pedidoId));

        assertEquals(rotaId, segunda.rotaId());
        assertEquals(0, segunda.entregaId());
        assertEquals(0, segunda.pedidoId());
        assertTrue(segunda.idempotente());
        assertEquals(1, contarEventos(DispatchEventTypes.ROTA_INICIADA));
    }

    @Test
    void deveConcluirEntregaEPedido() throws Exception {
        int atendenteId = criarAtendenteId("exec2@teste.com");
        int entregadorId = criarEntregadorId("ent2@teste.com");
        int clienteId = criarClienteId("(38) 99999-9102");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        ExecucaoEntregaResultado resultado = execucaoService.registrarPedidoEntregue(entregaId);

        assertFalse(resultado.idempotente());
        assertEquals("ENTREGUE", statusEntrega(entregaId));
        assertNotNull(horaRealEntrega(entregaId));
        assertEquals("ENTREGUE", statusPedido(pedidoId));
        assertEquals("CONCLUIDA", statusRota(rotaId));
        assertEquals(1, contarEventos(DispatchEventTypes.PEDIDO_ENTREGUE));
        assertEquals(1, contarEventos(DispatchEventTypes.ROTA_CONCLUIDA));
    }

    @Test
    void deveDebitarSaldoValeQuandoConcluirEntregaDePedidoPagoComVale() throws Exception {
        int atendenteId = criarAtendenteId("exec2b@teste.com");
        int entregadorId = criarEntregadorId("ent2b@teste.com");
        int clienteId = criarClienteId("(38) 99999-9105");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        inserirSaldoVale(clienteId, 3);
        atualizarMetodoPagamentoPedido(pedidoId, "VALE");

        ExecucaoEntregaResultado resultado = execucaoService.registrarPedidoEntregue(entregaId);

        assertFalse(resultado.idempotente());
        assertEquals("ENTREGUE", statusEntrega(entregaId));
        assertEquals("ENTREGUE", statusPedido(pedidoId));
        assertEquals(2, saldoValeCliente(clienteId));
        assertEquals(1, contarDebitoValePorPedido(pedidoId));
    }

    @Test
    void naoDeveDebitarSaldoValeQuandoConcluirEntregaDePedidoComOutroMetodoPagamento() throws Exception {
        int atendenteId = criarAtendenteId("exec2c@teste.com");
        int entregadorId = criarEntregadorId("ent2c@teste.com");
        int clienteId = criarClienteId("(38) 99999-9106");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        inserirSaldoVale(clienteId, 3);
        atualizarMetodoPagamentoPedido(pedidoId, "PIX");

        ExecucaoEntregaResultado resultado = execucaoService.registrarPedidoEntregue(entregaId);

        assertFalse(resultado.idempotente());
        assertEquals("ENTREGUE", statusEntrega(entregaId));
        assertEquals("ENTREGUE", statusPedido(pedidoId));
        assertEquals(3, saldoValeCliente(clienteId));
        assertEquals(0, contarDebitoValePorPedido(pedidoId));
    }

    @Test
    void deveCancelarEntregaComCobranca() throws Exception {
        int atendenteId = criarAtendenteId("exec3@teste.com");
        int entregadorId = criarEntregadorId("ent3@teste.com");
        int clienteId = criarClienteId("(38) 99999-9103");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        ExecucaoEntregaResultado resultado =
                execucaoService.registrarPedidoCancelado(entregaId, "cliente cancelou", 4200);

        assertFalse(resultado.idempotente());
        assertEquals("CANCELADA", statusEntrega(entregaId));
        assertEquals("CANCELADO", statusPedido(pedidoId));
        if (hasColumn("pedidos", "cobranca_cancelamento_centavos")) {
            assertEquals(4200, cobrancaCancelamentoPedido(pedidoId));
            assertEquals("PENDENTE", cobrancaStatusPedido(pedidoId));
        }
        assertEquals(1, contarEventos(DispatchEventTypes.PEDIDO_CANCELADO));
    }

    @Test
    void deveMarcarFalhaSemCobranca() throws Exception {
        int atendenteId = criarAtendenteId("exec4@teste.com");
        int entregadorId = criarEntregadorId("ent4@teste.com");
        int clienteId = criarClienteId("(38) 99999-9104");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        ExecucaoEntregaResultado resultado = execucaoService.registrarPedidoFalhou(entregaId, "cliente ausente");

        assertFalse(resultado.idempotente());
        assertEquals("FALHOU", statusEntrega(entregaId));
        assertEquals("CANCELADO", statusPedido(pedidoId));
        if (hasColumn("pedidos", "cobranca_cancelamento_centavos")) {
            assertEquals(0, cobrancaCancelamentoPedido(pedidoId));
            assertEquals("NAO_APLICAVEL", cobrancaStatusPedido(pedidoId));
        }
        assertEquals(1, contarEventos(DispatchEventTypes.PEDIDO_FALHOU));
    }

    @Test
    void deveBloquearEventoTerminalQuandoEntregaNaoEstaEmExecucao() throws Exception {
        int atendenteId = criarAtendenteId("exec5@teste.com");
        int entregadorId = criarEntregadorId("ent5@teste.com");
        int clienteId = criarClienteId("(38) 99999-9107");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> execucaoService.registrarPedidoEntregue(entregaId));

        assertTrue(ex.getMessage().contains("EM_EXECUCAO"));
        assertEquals("PENDENTE", statusEntrega(entregaId));
        assertEquals("EM_ROTA", statusPedido(pedidoId));
        assertEquals(0, contarEventos(DispatchEventTypes.PEDIDO_ENTREGUE));
    }

    @Test
    void deveRetornarIdempotenteSemDuplicarOutboxAoRepetirEventoTerminal() throws Exception {
        int atendenteId = criarAtendenteId("exec6@teste.com");
        int entregadorId = criarEntregadorId("ent6@teste.com");
        int clienteId = criarClienteId("(38) 99999-9108");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        ExecucaoEntregaResultado primeira = execucaoService.registrarPedidoEntregue(entregaId);
        ExecucaoEntregaResultado segunda = execucaoService.registrarPedidoEntregue(entregaId);

        assertFalse(primeira.idempotente());
        assertTrue(segunda.idempotente());
        assertEquals("ENTREGUE", statusEntrega(entregaId));
        assertEquals("ENTREGUE", statusPedido(pedidoId));
        assertEquals(1, contarEventos(DispatchEventTypes.PEDIDO_ENTREGUE));
        assertEquals(1, contarEventos(DispatchEventTypes.ROTA_CONCLUIDA));
    }

    @Test
    void deveBloquearEventoTerminalDivergenteQuandoEntregaJaFinalizada() throws Exception {
        int atendenteId = criarAtendenteId("exec6b@teste.com");
        int entregadorId = criarEntregadorId("ent6b@teste.com");
        int clienteId = criarClienteId("(38) 99999-9109");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorId, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        ExecucaoEntregaResultado primeira = execucaoService.registrarPedidoEntregue(entregaId);
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> execucaoService.registrarPedidoCancelado(entregaId, "cliente cancelou", 1000));

        assertFalse(primeira.idempotente());
        assertTrue(ex.getMessage().contains("ja finalizada"));
        assertEquals("ENTREGUE", statusEntrega(entregaId));
        assertEquals("ENTREGUE", statusPedido(pedidoId));
        assertEquals(1, contarEventos(DispatchEventTypes.PEDIDO_ENTREGUE));
        assertEquals(0, contarEventos(DispatchEventTypes.PEDIDO_CANCELADO));
    }

    @Test
    void deveBloquearRotaIniciadaQuandoActorEntregadorNaoForDonoDaRota() throws Exception {
        int atendenteId = criarAtendenteId("exec7@teste.com");
        int entregadorCorreto = criarEntregadorId("ent7-correto@teste.com");
        int outroEntregador = criarEntregadorId("ent7-incorreto@teste.com");
        int clienteId = criarClienteId("(38) 99999-9112");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.CONFIRMADO);
        int rotaId = criarRota(entregadorCorreto, "PLANEJADA");
        int entregaId = criarEntrega(pedidoId, rotaId, "PENDENTE");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> execucaoService.registrarRotaIniciada(rotaId, outroEntregador));

        assertTrue(ex.getMessage().toLowerCase().contains("entregador"));
        assertEquals("PLANEJADA", statusRota(rotaId));
        assertEquals("PENDENTE", statusEntrega(entregaId));
        assertEquals("CONFIRMADO", statusPedido(pedidoId));
    }

    @Test
    void deveBloquearEventoTerminalQuandoActorEntregadorNaoForDonoDaEntrega() throws Exception {
        int atendenteId = criarAtendenteId("exec8@teste.com");
        int entregadorCorreto = criarEntregadorId("ent8-correto@teste.com");
        int outroEntregador = criarEntregadorId("ent8-incorreto@teste.com");
        int clienteId = criarClienteId("(38) 99999-9113");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaId = criarRota(entregadorCorreto, "EM_ANDAMENTO");
        int entregaId = criarEntrega(pedidoId, rotaId, "EM_EXECUCAO");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> execucaoService.registrarPedidoEntregue(entregaId, outroEntregador));

        assertTrue(ex.getMessage().toLowerCase().contains("entregador"));
        assertEquals("EM_EXECUCAO", statusEntrega(entregaId));
        assertEquals("EM_ROTA", statusPedido(pedidoId));
        assertEquals(0, contarEventos(DispatchEventTypes.PEDIDO_ENTREGUE));
    }

    @Test
    void deveIniciarProximaRotaProntaDoEntregadorComUmClique() throws Exception {
        int atendenteId = criarAtendenteId("exec9@teste.com");
        int entregadorId = criarEntregadorId("ent9@teste.com");
        int clienteId1 = criarClienteId("(38) 99999-9114");
        int pedido1 = criarPedido(clienteId1, atendenteId, PedidoStatus.CONFIRMADO);
        int rotaPlanejada1 = criarRota(entregadorId, "PLANEJADA", 1);
        int entrega1 = criarEntrega(pedido1, rotaPlanejada1, "PENDENTE");

        ExecucaoEntregaResultado resultado = execucaoService.iniciarProximaRotaPronta(entregadorId);

        assertFalse(resultado.idempotente());
        assertEquals(rotaPlanejada1, resultado.rotaId());
        assertEquals("EM_ANDAMENTO", statusRota(rotaPlanejada1));
        assertEquals("EM_EXECUCAO", statusEntrega(entrega1));
        assertEquals("EM_ROTA", statusPedido(pedido1));
    }

    @Test
    void naoDeveAutoIniciarProximaRotaQuandoRotaAtualConcluir() throws Exception {
        int atendenteId = criarAtendenteId("exec9b@teste.com");
        int entregadorId = criarEntregadorId("ent9b@teste.com");
        int clienteExecucao = criarClienteId("(38) 99999-9115");
        int clientePlanejado = criarClienteId("(38) 99999-9116");

        int pedidoExecucao = criarPedido(clienteExecucao, atendenteId, PedidoStatus.EM_ROTA);
        int pedidoPlanejado = criarPedido(clientePlanejado, atendenteId, PedidoStatus.CONFIRMADO);
        int rotaEmAndamento = criarRota(entregadorId, "EM_ANDAMENTO", 1);
        int rotaPlanejada = criarRota(entregadorId, "PLANEJADA", 2);
        int entregaExecucao = criarEntrega(pedidoExecucao, rotaEmAndamento, "EM_EXECUCAO");
        int entregaPlanejada = criarEntrega(pedidoPlanejado, rotaPlanejada, "PENDENTE");

        ExecucaoEntregaResultado resultado = execucaoService.registrarPedidoEntregue(entregaExecucao);

        assertFalse(resultado.idempotente());
        assertEquals("CONCLUIDA", statusRota(rotaEmAndamento));
        assertEquals("PLANEJADA", statusRota(rotaPlanejada));
        assertEquals("PENDENTE", statusEntrega(entregaPlanejada));
        assertEquals("CONFIRMADO", statusPedido(pedidoPlanejado));
        assertEquals(0, contarEventos(DispatchEventTypes.ROTA_INICIADA));
        assertEquals(1, contarEventos(DispatchEventTypes.ROTA_CONCLUIDA));
    }

    @Test
    void deveBloquearIniciarProximaRotaProntaQuandoJaExisteEmAndamento() throws Exception {
        int atendenteId = criarAtendenteId("exec10@teste.com");
        int entregadorId = criarEntregadorId("ent10@teste.com");
        int clienteId = criarClienteId("(38) 99999-9116");
        int pedidoId = criarPedido(clienteId, atendenteId, PedidoStatus.EM_ROTA);
        int rotaEmAndamento = criarRota(entregadorId, "EM_ANDAMENTO", 1);
        criarEntrega(pedidoId, rotaEmAndamento, "EM_EXECUCAO");

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> execucaoService.iniciarProximaRotaPronta(entregadorId));

        assertTrue(ex.getMessage().contains("EM_ANDAMENTO"));
        assertEquals("EM_ANDAMENTO", statusRota(rotaEmAndamento));
    }

    @Test
    void deveBloquearIniciarProximaRotaProntaQuandoNaoExistePlanejada() throws Exception {
        int entregadorId = criarEntregadorId("ent11@teste.com");

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> execucaoService.iniciarProximaRotaPronta(entregadorId));

        assertTrue(ex.getMessage().contains("PLANEJADA"));
    }

    private boolean hasColumn(String tabela, String coluna) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
            stmt.setString(1, tabela);
            stmt.setString(2, coluna);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String statusRota(int rotaId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT status::text FROM rotas WHERE id = ?")) {
            stmt.setInt(1, rotaId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String statusEntrega(int entregaId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT status::text FROM entregas WHERE id = ?")) {
            stmt.setInt(1, entregaId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String horaRealEntrega(int entregaId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT TO_CHAR(hora_real, 'YYYY-MM-DD HH24:MI:SS') FROM entregas WHERE id = ?")) {
            stmt.setInt(1, entregaId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
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

    private int cobrancaCancelamentoPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT cobranca_cancelamento_centavos FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String cobrancaStatusPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT cobranca_status::text FROM pedidos WHERE id = ?")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int contarEventos(String eventType) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT COUNT(*) FROM dispatch_events WHERE event_type = ?")) {
            stmt.setString(1, eventType);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void atualizarMetodoPagamentoPedido(int pedidoId, String metodoPagamento) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("UPDATE pedidos SET metodo_pagamento = ? WHERE id = ?")) {
            stmt.setObject(1, metodoPagamento, java.sql.Types.OTHER);
            stmt.setInt(2, pedidoId);
            stmt.executeUpdate();
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

    private int saldoValeCliente(int clienteId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT quantidade FROM saldo_vales WHERE cliente_id = ?")) {
            stmt.setInt(1, clienteId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int contarDebitoValePorPedido(int pedidoId) throws Exception {
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM movimentacao_vales WHERE pedido_id = ? AND tipo::text = 'DEBITO'")) {
            stmt.setInt(1, pedidoId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
