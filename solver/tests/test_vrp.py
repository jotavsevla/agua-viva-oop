from vrp import solve, hhmm_to_seconds, seconds_to_hhmm


# ---------- conversao de tempo ----------

def test_hhmm_to_seconds_same():
    assert hhmm_to_seconds("08:00", "08:00") == 0


def test_hhmm_to_seconds_one_hour():
    assert hhmm_to_seconds("09:00", "08:00") == 3600


def test_hhmm_to_seconds_half_hour():
    assert hhmm_to_seconds("08:30", "08:00") == 1800


def test_hhmm_to_seconds_full_day():
    assert hhmm_to_seconds("18:00", "08:00") == 36000


def test_seconds_to_hhmm_zero():
    assert seconds_to_hhmm(0, "08:00") == "08:00"


def test_seconds_to_hhmm_one_hour():
    assert seconds_to_hhmm(3600, "08:00") == "09:00"


def test_seconds_to_hhmm_end_of_day():
    assert seconds_to_hhmm(36000, "08:00") == "18:00"


# ---------- solver ----------

def test_empty_input():
    routes, dropped = solve(
        duration_matrix=[[0]],
        demands=[0],
        time_windows=[(0, 36000)],
        num_drivers=1,
        vehicle_capacity=5,
    )
    assert routes == []
    assert dropped == []


def test_single_delivery():
    # Deposito (0) e uma entrega (1), 10 min de distancia
    matrix = [
        [0, 600],
        [600, 0],
    ]
    routes, dropped = solve(
        duration_matrix=matrix,
        demands=[0, 2],
        time_windows=[(0, 36000), (0, 36000)],
        num_drivers=1,
        vehicle_capacity=5,
    )
    assert len(routes) == 1
    assert len(dropped) == 0
    _, stops = routes[0]
    assert len(stops) == 1
    node, _ = stops[0]
    assert node == 1


def test_capacity_forces_multiple_trips():
    # 3 entregas de 3 galoes cada, veiculo carrega 5
    # Precisa de pelo menos 2 viagens (3+3 > 5)
    matrix = [
        [0, 300, 300, 300],
        [300, 0, 300, 300],
        [300, 300, 0, 300],
        [300, 300, 300, 0],
    ]
    routes, dropped = solve(
        duration_matrix=matrix,
        demands=[0, 3, 3, 3],
        time_windows=[(0, 36000)] * 4,
        num_drivers=1,
        vehicle_capacity=5,
    )
    assert len(dropped) == 0
    assert len(routes) >= 2
    for _, stops in routes:
        total_demand = sum([0, 3, 3, 3][node] for node, _ in stops)
        assert total_demand <= 5


def test_time_window_respected():
    # Entrega 1: janela 0-1800s (08:00-08:30)
    # Entrega 2: janela 7200-10800s (10:00-11:00)
    matrix = [
        [0, 600, 600],
        [600, 0, 600],
        [600, 600, 0],
    ]
    routes, dropped = solve(
        duration_matrix=matrix,
        demands=[0, 1, 1],
        time_windows=[(0, 36000), (0, 1800), (7200, 10800)],
        num_drivers=1,
        vehicle_capacity=5,
    )
    assert len(dropped) == 0
    all_stops = []
    for _, stops in routes:
        all_stops.extend(stops)
    for node, arrival in all_stops:
        if node == 1:
            assert arrival <= 1800
        if node == 2:
            assert 7200 <= arrival <= 10800


def test_multiple_drivers():
    # 6 entregas, 2 entregadores, capacidade 5, demanda 1 cada
    n = 7
    matrix = [[300 if i != j else 0 for j in range(n)] for i in range(n)]
    routes, dropped = solve(
        duration_matrix=matrix,
        demands=[0] + [1] * 6,
        time_windows=[(0, 36000)] * n,
        num_drivers=2,
        vehicle_capacity=5,
    )
    assert len(dropped) == 0
    all_nodes = set()
    for _, stops in routes:
        for node, _ in stops:
            all_nodes.add(node)
    assert all_nodes == {1, 2, 3, 4, 5, 6}


def test_infeasible_drops_node():
    # Entrega 1: janela impossivel (ja passou antes de chegar)
    # Viagem de 2h, mas janela fecha em 30min
    matrix = [
        [0, 7200],
        [7200, 0],
    ]
    routes, dropped = solve(
        duration_matrix=matrix,
        demands=[0, 1],
        time_windows=[(0, 36000), (0, 1800)],
        num_drivers=1,
        vehicle_capacity=5,
    )
    assert 1 in dropped
