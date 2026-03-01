package com.aguaviva.solver;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MockSolverClient implements SolverGateway {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DEFAULT_INTERVALO_MINUTOS = 15;
    private static final LocalTime DEFAULT_HORA_BASE = LocalTime.of(9, 0);

    @Override
    public SolverResponse solve(SolverRequest request) {
        Objects.requireNonNull(request, "request nao pode ser nulo");
        List<Integer> entregadores = request.getEntregadores();
        List<PedidoSolver> pedidos = request.getPedidos();
        if (entregadores == null || entregadores.isEmpty() || pedidos == null || pedidos.isEmpty()) {
            return new SolverResponse(List.of(), List.of());
        }

        Map<Integer, List<Parada>> paradasPorEntregador = new HashMap<>();
        for (Integer entregadorId : entregadores) {
            paradasPorEntregador.put(entregadorId, new ArrayList<>());
        }

        LocalTime horaBase = parseHoraBase(request.getHorarioInicio());
        int cursorEntregador = 0;
        int ordemGlobal = 1;
        for (PedidoSolver pedido : pedidos) {
            int entregadorId = entregadores.get(cursorEntregador % entregadores.size());
            LocalTime horaPrevista = horaBase.plusMinutes((long) (ordemGlobal - 1) * DEFAULT_INTERVALO_MINUTOS);
            Parada parada = new Parada(
                    paradasPorEntregador.get(entregadorId).size() + 1,
                    pedido.getPedidoId(),
                    pedido.getLat(),
                    pedido.getLon(),
                    horaPrevista.format(HH_MM));
            paradasPorEntregador.get(entregadorId).add(parada);
            cursorEntregador++;
            ordemGlobal++;
        }

        List<RotaSolver> rotas = new ArrayList<>();
        int numeroNoDia = 1;
        for (Integer entregadorId : entregadores) {
            List<Parada> paradas = paradasPorEntregador.get(entregadorId);
            if (paradas == null || paradas.isEmpty()) {
                continue;
            }
            rotas.add(new RotaSolver(entregadorId, numeroNoDia, List.copyOf(paradas)));
            numeroNoDia++;
        }
        return new SolverResponse(rotas, List.of());
    }

    @Override
    public void cancelBestEffort(String jobId) {
        // Mock local: sem estado remoto para cancelar.
    }

    private static LocalTime parseHoraBase(String horarioInicio) {
        if (horarioInicio == null || horarioInicio.isBlank()) {
            return DEFAULT_HORA_BASE;
        }
        try {
            return LocalTime.parse(horarioInicio, HH_MM);
        } catch (Exception ignored) {
            return DEFAULT_HORA_BASE;
        }
    }
}
