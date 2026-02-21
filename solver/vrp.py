from collections.abc import Callable

from ortools.constraint_solver import routing_enums_pb2, pywrapcp

SERVICE_TIME_S = 120  # 2 min por parada (estacionar, entregar, receber)
MAX_TRIPS_PER_DRIVER = 1


def hhmm_to_seconds(hhmm: str, base: str = "08:00") -> int:
    h, m = map(int, hhmm.split(":"))
    bh, bm = map(int, base.split(":"))
    return (h * 3600 + m * 60) - (bh * 3600 + bm * 60)


def seconds_to_hhmm(seconds: int, base: str = "08:00") -> str:
    bh, bm = map(int, base.split(":"))
    total_min = bh * 60 + bm + seconds // 60
    return f"{total_min // 60:02d}:{total_min % 60:02d}"


def solve(
    duration_matrix: list[list[int]],
    demands: list[int],
    time_windows: list[tuple[int, int]],
    num_drivers: int,
    vehicle_capacity: int,
    vehicle_capacities: list[int] | None = None,
    priorities: list[int] | None = None,
    max_seconds: int = 5,
    cancel_checker: Callable[[], bool] | None = None,
) -> tuple[list[tuple[int, list[tuple[int, int]]]], list[int]]:
    """
    Resolve CVRPTW (Capacitated Vehicle Routing Problem with Time Windows).

    Args:
        duration_matrix: NxN segundos, index 0 = deposito
        demands: demanda por no, index 0 = 0
        time_windows: (inicio_s, fim_s) por no, relativo ao inicio do expediente
        num_drivers: numero de entregadores fisicos
        vehicle_capacity: carga maxima por viagem
        max_seconds: tempo limite do solver

    Returns:
        (rotas, nao_atendidos)
        rotas: [(vehicle_id, [(node, arrival_s), ...]), ...]
        nao_atendidos: [node_index, ...]
    """
    n = len(duration_matrix)
    if n <= 1:
        return [], []
    if cancel_checker and cancel_checker():
        return [], list(range(1, n))

    num_vehicles = num_drivers * MAX_TRIPS_PER_DRIVER
    work_day_s = time_windows[0][1]  # fim do expediente em segundos

    capacities_by_driver = vehicle_capacities
    if capacities_by_driver is not None and len(capacities_by_driver) != num_drivers:
        raise ValueError("vehicle_capacities deve ter o mesmo tamanho de num_drivers")
    if capacities_by_driver is None:
        capacities_by_driver = [vehicle_capacity] * num_drivers
    if priorities is not None and len(priorities) != n:
        raise ValueError("priorities deve ter o mesmo tamanho de duration_matrix")

    manager = pywrapcp.RoutingIndexManager(n, num_vehicles, 0)
    routing = pywrapcp.RoutingModel(manager)

    # --- Transito: tempo de viagem + tempo de servico na parada ---
    def time_callback(from_index, to_index):
        from_node = manager.IndexToNode(from_index)
        to_node = manager.IndexToNode(to_index)
        travel = duration_matrix[from_node][to_node]
        service = SERVICE_TIME_S if to_node != 0 else 0
        return travel + service

    time_cb = routing.RegisterTransitCallback(time_callback)
    routing.SetArcCostEvaluatorOfAllVehicles(time_cb)

    # --- Dimensao de tempo ---
    routing.AddDimension(time_cb, work_day_s, work_day_s, False, "Time")
    time_dim = routing.GetDimensionOrDie("Time")

    # Janelas de tempo por no de entrega (pula deposito)
    for i in range(1, n):
        idx = manager.NodeToIndex(i)
        lo, hi = time_windows[i]
        time_dim.CumulVar(idx).SetRange(lo, hi)

    # Janelas de tempo para saida/retorno dos veiculos
    for v in range(num_vehicles):
        time_dim.CumulVar(routing.Start(v)).SetRange(0, work_day_s)
        time_dim.CumulVar(routing.End(v)).SetRange(0, work_day_s)
        routing.AddVariableMinimizedByFinalizer(time_dim.CumulVar(routing.Start(v)))
        routing.AddVariableMinimizedByFinalizer(time_dim.CumulVar(routing.End(v)))

    # --- Dimensao de capacidade ---
    # A demanda e contabilizada na entrada do no de entrega (to_index),
    # evitando alocacoes inconsistentes em cenarios de capacidade limite.
    def demand_transit_callback(_, to_index):
        to_node = manager.IndexToNode(to_index)
        return demands[to_node] if to_node != 0 else 0

    demand_cb = routing.RegisterTransitCallback(demand_transit_callback)
    capacities_by_vehicle: list[int] = []
    for capacity in capacities_by_driver:
        capacities_by_vehicle.extend([capacity] * MAX_TRIPS_PER_DRIVER)
    routing.AddDimensionWithVehicleTransitAndCapacity(
        [demand_cb] * num_vehicles, 0, capacities_by_vehicle, True, "Capacity"
    )

    # --- Permitir dropar nos com penalidade (quando inviavel) ---
    for i in range(1, n):
        prioridade = priorities[i] if priorities is not None else 2
        penalty = 1_000_000 if prioridade <= 1 else 100_000
        routing.AddDisjunction([manager.NodeToIndex(i)], penalty)

    # --- Estrategia de busca ---
    params = pywrapcp.DefaultRoutingSearchParameters()
    params.first_solution_strategy = (
        routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    )
    params.local_search_metaheuristic = (
        routing_enums_pb2.LocalSearchMetaheuristic.GUIDED_LOCAL_SEARCH
    )
    params.time_limit.FromSeconds(max_seconds)

    solution = routing.SolveWithParameters(params)

    if cancel_checker and cancel_checker():
        return [], list(range(1, n))

    if not solution:
        return [], list(range(1, n))

    # --- Extrair rotas ---
    visited = set()
    routes = []

    for v in range(num_vehicles):
        stops = []
        index = routing.Start(v)
        while not routing.IsEnd(index):
            node = manager.IndexToNode(index)
            if node != 0:
                arrival = solution.Value(time_dim.CumulVar(index))
                stops.append((node, arrival))
                visited.add(node)
            index = solution.Value(routing.NextVar(index))
        if stops:
            routes.append((v, stops))

    dropped = [i for i in range(1, n) if i not in visited]
    return routes, dropped
