package com.aguaviva.support;

import com.aguaviva.domain.cliente.Cliente;
import com.aguaviva.domain.cliente.ClienteTipo;
import com.aguaviva.domain.pedido.JanelaTipo;
import com.aguaviva.domain.pedido.Pedido;
import com.aguaviva.domain.user.Password;
import com.aguaviva.domain.user.User;
import com.aguaviva.domain.user.UserPapel;
import com.aguaviva.repository.ClienteRepository;
import com.aguaviva.repository.PedidoRepository;
import com.aguaviva.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestDataFactory {

    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    private TestDataFactory() {}

    public static Cliente aCliente() {
        int n = SEQ.incrementAndGet();
        return new Cliente(
                "Cliente " + n,
                "(38) 99999-" + String.format("%04d", n % 10000),
                ClienteTipo.PF,
                "Rua Teste " + n,
                new BigDecimal("-16.73444096"),
                new BigDecimal("-43.87721119"),
                "referencia " + n);
    }

    public static User aUser() {
        int n = SEQ.incrementAndGet();
        return new User("Usuario " + n, "user" + n + "@aguaviva.test", Password.fromPlainText("senha123"), UserPapel.ATENDENTE);
    }

    public static Pedido aPedido(int clienteId, int userId) {
        return new Pedido(clienteId, 2, JanelaTipo.ASAP, null, null, userId);
    }

    public static Cliente insertCliente(ClienteRepository repository) {
        return repository.save(aCliente());
    }

    public static User insertUser(UserRepository repository) {
        return repository.save(aUser());
    }

    public static Pedido insertPedido(PedidoRepository repository, int clienteId, int userId) {
        return repository.save(aPedido(clienteId, userId));
    }

    public static Pedido insertPedido(PedidoRepository repository, ClienteRepository clienteRepository, UserRepository userRepository) {
        Cliente cliente = insertCliente(clienteRepository);
        User user = insertUser(userRepository);
        return insertPedido(repository, cliente.getId(), user.getId());
    }

    public static Pedido aPedidoHard(int clienteId, int userId) {
        return new Pedido(clienteId, 1, JanelaTipo.HARD, LocalTime.of(9, 0), LocalTime.of(11, 0), userId);
    }
}
