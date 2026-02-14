package com.aguaviva.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClienteRepositoryTest {

    private static ConnectionFactory factory;
    private static ClienteRepository repository;

    @BeforeAll
    static void setUp() {
        factory = new ConnectionFactory("localhost", "5435", "agua_viva_oop_test", "postgres", "postgres");
        repository = new ClienteRepository(factory);
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

    private void limparBanco() throws Exception {
        try (Connection conn = factory.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "TRUNCATE TABLE sessions, entregas, rotas, movimentacao_vales, saldo_vales, pedidos, clientes, users RESTART IDENTITY CASCADE");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Cliente criarCliente(String nome, String telefone, ClienteTipo tipo) {
        return new Cliente(
                nome,
                telefone,
                tipo,
                "Rua Principal, 100",
                new BigDecimal("-16.73444096"),
                new BigDecimal("-43.87721119"),
                "Portao azul");
    }

    // ========================================================================
    // save
    // ========================================================================

    @Test
    void deveSalvarClienteERetornarComIdGerado() throws Exception {
        Cliente salvo = repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));

        assertTrue(salvo.getId() > 0);
        assertEquals("Cliente 1", salvo.getNome());
        assertEquals("(38) 99999-0001", salvo.getTelefone());
        assertEquals(ClienteTipo.PF, salvo.getTipo());
    }

    @Test
    void deveSalvarClienteERecuperarPorId() throws Exception {
        Cliente salvo = repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));

        Optional<Cliente> encontrado = repository.findById(salvo.getId());

        assertTrue(encontrado.isPresent());
        Cliente cliente = encontrado.get();
        assertEquals(salvo.getId(), cliente.getId());
        assertEquals("Cliente 1", cliente.getNome());
        assertEquals("(38) 99999-0001", cliente.getTelefone());
        assertEquals(ClienteTipo.PF, cliente.getTipo());
        assertEquals(0, new BigDecimal("-16.73444096").compareTo(cliente.getLatitude()));
        assertEquals(0, new BigDecimal("-43.87721119").compareTo(cliente.getLongitude()));
    }

    @Test
    void deveSalvarClienteSemCoordenadasENotas() throws Exception {
        Cliente salvo = repository.save(new Cliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF, "Rua A, 10"));

        Optional<Cliente> encontrado = repository.findById(salvo.getId());

        assertTrue(encontrado.isPresent());
        assertNull(encontrado.get().getLatitude());
        assertNull(encontrado.get().getLongitude());
        assertNull(encontrado.get().getNotas());
    }

    @Test
    void deveRetornarOptionalVazioQuandoIdNaoExiste() throws Exception {
        Optional<Cliente> resultado = repository.findById(999);
        assertTrue(resultado.isEmpty());
    }

    // ========================================================================
    // findByTelefone
    // ========================================================================

    @Test
    void deveEncontrarClientePorTelefone() throws Exception {
        repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));

        Optional<Cliente> encontrado = repository.findByTelefone("(38) 99999-0001");

        assertTrue(encontrado.isPresent());
        assertEquals("Cliente 1", encontrado.get().getNome());
    }

    @Test
    void deveEncontrarClientePorTelefoneIgnorandoEspacosNasPontas() throws Exception {
        repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));

        Optional<Cliente> encontrado = repository.findByTelefone("   (38) 99999-0001   ");

        assertTrue(encontrado.isPresent());
        assertEquals("(38) 99999-0001", encontrado.get().getTelefone());
    }

    @Test
    void deveRetornarOptionalVazioQuandoTelefoneNaoExiste() throws Exception {
        Optional<Cliente> resultado = repository.findByTelefone("(38) 00000-0000");
        assertTrue(resultado.isEmpty());
    }

    @Test
    void deveLancarExcecaoAoSalvarTelefoneDuplicado() throws Exception {
        repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));

        Cliente duplicado = criarCliente("Cliente 2", "(38) 99999-0001", ClienteTipo.PJ);

        assertThrows(IllegalArgumentException.class, () -> repository.save(duplicado));
    }

    // ========================================================================
    // findAll
    // ========================================================================

    @Test
    void deveRetornarListaVaziaQuandoNaoHaClientes() throws Exception {
        List<Cliente> clientes = repository.findAll();
        assertTrue(clientes.isEmpty());
    }

    @Test
    void deveRetornarTodosOsClientesOrdenadosPorId() throws Exception {
        repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));
        repository.save(criarCliente("Cliente 2", "(38) 99999-0002", ClienteTipo.PJ));

        List<Cliente> clientes = repository.findAll();

        assertEquals(2, clientes.size());
        assertTrue(clientes.get(0).getId() < clientes.get(1).getId());
    }

    // ========================================================================
    // update
    // ========================================================================

    @Test
    void deveAtualizarDadosDoCliente() throws Exception {
        Cliente salvo = repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));

        Cliente atualizado = new Cliente(
                salvo.getId(),
                "Cliente Atualizado",
                "(38) 99999-0099",
                ClienteTipo.PJ,
                "Av. Nova, 123",
                new BigDecimal("-16.70000000"),
                new BigDecimal("-43.80000000"),
                "Ligar antes");

        repository.update(atualizado);

        Optional<Cliente> encontrado = repository.findById(salvo.getId());
        assertTrue(encontrado.isPresent());
        Cliente cliente = encontrado.get();
        assertEquals("Cliente Atualizado", cliente.getNome());
        assertEquals("(38) 99999-0099", cliente.getTelefone());
        assertEquals(ClienteTipo.PJ, cliente.getTipo());
        assertEquals("Av. Nova, 123", cliente.getEndereco());
        assertEquals(0, new BigDecimal("-16.70000000").compareTo(cliente.getLatitude()));
        assertEquals(0, new BigDecimal("-43.80000000").compareTo(cliente.getLongitude()));
        assertEquals("Ligar antes", cliente.getNotas());
    }

    @Test
    void deveLancarExcecaoAoAtualizarClienteInexistente() {
        Cliente fantasma = criarCliente("Fantasma", "(38) 99999-9999", ClienteTipo.PF);
        Cliente comIdInexistente = new Cliente(
                999,
                fantasma.getNome(),
                fantasma.getTelefone(),
                fantasma.getTipo(),
                fantasma.getEndereco(),
                fantasma.getLatitude(),
                fantasma.getLongitude(),
                fantasma.getNotas());

        assertThrows(SQLException.class, () -> repository.update(comIdInexistente));
    }

    @Test
    void deveLancarExcecaoAoAtualizarParaTelefoneDuplicado() throws Exception {
        repository.save(criarCliente("Cliente 1", "(38) 99999-0001", ClienteTipo.PF));
        Cliente cliente2 = repository.save(criarCliente("Cliente 2", "(38) 99999-0002", ClienteTipo.PJ));

        Cliente comTelefoneDuplicado = new Cliente(
                cliente2.getId(),
                cliente2.getNome(),
                "(38) 99999-0001",
                cliente2.getTipo(),
                cliente2.getEndereco(),
                cliente2.getLatitude(),
                cliente2.getLongitude(),
                cliente2.getNotas());

        assertThrows(IllegalArgumentException.class, () -> repository.update(comTelefoneDuplicado));
    }
}
