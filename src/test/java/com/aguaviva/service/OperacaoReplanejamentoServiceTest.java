package com.aguaviva.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.pedido.JanelaTipo;
import com.aguaviva.domain.pedido.Pedido;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ClienteRepository;
import com.aguaviva.repository.ConnectionFactory;
import com.aguaviva.repository.PedidoRepository;
import com.aguaviva.repository.UserRepository;
import com.aguaviva.support.TestConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class OperacaoReplanejamentoServiceTest {

    private static ConnectionFactory factory;
    private static UserRepository userRepository;
    private static ClienteRepository clienteRepository;
    private static PedidoRepository pedidoRepository;
    private static OperacaoReplanejamentoService service;

    @BeforeAll
    static void setUp() throws Exception {
        factory = TestConnectionFactory.newConnectionFactory();
        userRepository = new UserRepository(factory);
        clienteRepository = new ClienteRepository(factory);
        pedidoRepository = new PedidoRepository(factory);
        service = new OperacaoReplanejamentoService(factory);
        garantirSchemaReplanejamento();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void limparAntes() throws Exception {
        limparBase();
    }

    @AfterEach
    void limparDepois() throws Exception {
        limparBase();
    }

    @Test
    void deveListarJobsEDetalharImpactoPorJobId() throws Exception {
        int atendenteId = criarUsuario("atendente-replan@teste.com", UserPapel.ATENDENTE);
        int entregadorId = criarUsuario("entregador-replan@teste.com", UserPapel.ENTREGADOR);
        int clienteId = criarCliente("(38) 99711-1001");
        int pedidoId = criarPedido(clienteId, atendenteId);

        String jobId = "job-ops-001";
        long planVersion = 42L;
        inserirJobSolver(jobId, planVersion, "CONCLUIDO", "{\"input\":true}", "{\"rotas\":1}");

        int rotaId = criarRota(entregadorId, planVersion, jobId);
        int entregaId = criarEntrega(pedidoId, rotaId, planVersion, jobId);

        OperacaoReplanejamentoService.OperacaoReplanejamentoResultado jobs = service.listarJobs(10);
        assertTrue(jobs.habilitado());
        assertEquals(1, jobs.jobs().size());
        OperacaoReplanejamentoService.SolverJobResumo resumo = jobs.jobs().getFirst();
        assertEquals(jobId, resumo.jobId());
        assertEquals(planVersion, resumo.planVersion());
        assertEquals("CONCLUIDO", resumo.status());
        assertTrue(resumo.hasRequestPayload());
        assertTrue(resumo.hasResponsePayload());

        OperacaoReplanejamentoService.OperacaoReplanejamentoJobDetalheResultado detalheResultado =
                service.detalharJob(jobId);
        assertTrue(detalheResultado.habilitado());
        OperacaoReplanejamentoService.SolverJobDetalhe detalhe = detalheResultado.job();
        assertEquals(jobId, detalhe.jobId());
        assertEquals(planVersion, detalhe.planVersion());
        assertEquals(1, detalhe.rotasImpactadas().size());
        assertEquals(rotaId, detalhe.rotasImpactadas().getFirst().rotaId());
        assertEquals(1, detalhe.pedidosImpactados().size());
        assertEquals(pedidoId, detalhe.pedidosImpactados().getFirst().pedidoId());
        assertEquals(entregaId, detalhe.pedidosImpactados().getFirst().entregaId());
    }

    @Test
    void deveValidarLimiteDeListagem() {
        IllegalArgumentException limiteZero =
                assertThrows(IllegalArgumentException.class, () -> service.listarJobs(0));
        assertTrue(limiteZero.getMessage().contains("maior que zero"));

        IllegalArgumentException limiteAcimaMaximo =
                assertThrows(IllegalArgumentException.class, () -> service.listarJobs(201));
        assertTrue(limiteAcimaMaximo.getMessage().contains("200"));
    }

    private static void garantirSchemaReplanejamento() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'solver_job_status') "
                    + "THEN CREATE TYPE solver_job_status AS ENUM ('PENDENTE', 'EM_EXECUCAO', 'CONCLUIDO', 'CANCELADO', 'FALHOU'); "
                    + "END IF; "
                    + "END $$;");
            stmt.execute("CREATE TABLE IF NOT EXISTS solver_jobs ("
                    + "job_id VARCHAR(64) PRIMARY KEY, "
                    + "plan_version BIGINT NOT NULL, "
                    + "status solver_job_status NOT NULL DEFAULT 'PENDENTE', "
                    + "cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "solicitado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "iniciado_em TIMESTAMP, "
                    + "finalizado_em TIMESTAMP, "
                    + "erro TEXT, "
                    + "request_payload JSONB, "
                    + "response_payload JSONB)");
            stmt.execute("ALTER TABLE rotas ADD COLUMN IF NOT EXISTS plan_version BIGINT NOT NULL DEFAULT 1");
            stmt.execute("ALTER TABLE rotas ADD COLUMN IF NOT EXISTS job_id VARCHAR(64)");
            stmt.execute("ALTER TABLE entregas ADD COLUMN IF NOT EXISTS plan_version BIGINT NOT NULL DEFAULT 1");
            stmt.execute("ALTER TABLE entregas ADD COLUMN IF NOT EXISTS job_id VARCHAR(64)");
        }
    }

    private void limparBase() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "TRUNCATE TABLE solver_jobs, sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    private int criarUsuario(String email, UserPapel papel) throws Exception {
        User user = new User("Usuario Replanejamento", email, Password.fromPlainText("senha123"), papel);
        return userRepository.save(user).getId();
    }

    private int criarCliente(String telefone) throws Exception {
        Cliente cliente = new Cliente(
                "Cliente Replan " + telefone,
                telefone,
                ClienteTipo.PF,
                "Rua Replanejamento, 90",
                BigDecimal.valueOf(-16.721),
                BigDecimal.valueOf(-43.861),
                null);
        return clienteRepository.save(cliente).getId();
    }

    private int criarPedido(int clienteId, int atendenteId) throws Exception {
        Pedido pedido = pedidoRepository.save(new Pedido(clienteId, 2, JanelaTipo.ASAP, null, null, atendenteId));
        return pedido.getId();
    }

    private void inserirJobSolver(String jobId, long planVersion, String status, String requestPayload, String responsePayload)
            throws Exception {
        String sql = "INSERT INTO solver_jobs (job_id, plan_version, status, cancel_requested, request_payload, response_payload) "
                + "VALUES (?, ?, ?, false, CAST(? AS jsonb), CAST(? AS jsonb))";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, jobId);
            stmt.setLong(2, planVersion);
            stmt.setObject(3, status, Types.OTHER);
            stmt.setString(4, requestPayload);
            stmt.setString(5, responsePayload);
            stmt.executeUpdate();
        }
    }

    private int criarRota(int entregadorId, long planVersion, String jobId) throws Exception {
        String sql = "INSERT INTO rotas (entregador_id, data, numero_no_dia, status, plan_version, job_id) "
                + "VALUES (?, CURRENT_DATE, 1, ?, ?, ?) RETURNING id";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, entregadorId);
            stmt.setObject(2, "PLANEJADA", Types.OTHER);
            stmt.setLong(3, planVersion);
            stmt.setString(4, jobId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int criarEntrega(int pedidoId, int rotaId, long planVersion, String jobId) throws Exception {
        String sql = "INSERT INTO entregas (pedido_id, rota_id, ordem_na_rota, status, plan_version, job_id) "
                + "VALUES (?, ?, 1, ?, ?, ?) RETURNING id";
        try (Connection conn = factory.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pedidoId);
            stmt.setInt(2, rotaId);
            stmt.setObject(3, "PENDENTE", Types.OTHER);
            stmt.setLong(4, planVersion);
            stmt.setString(5, jobId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
