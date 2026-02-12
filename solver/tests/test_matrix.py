from matrix import _haversine_m, get_duration_matrix


def test_haversine_same_point():
    assert _haversine_m(-16.72, -43.86, -16.72, -43.86) == 0


def test_haversine_known_distance():
    # Deposito Montes Claros ate ponto proximo (~1.8km)
    dist = _haversine_m(-16.7244, -43.8636, -16.7100, -43.8500)
    assert 1000 < dist < 3000


def test_haversine_symmetry():
    d1 = _haversine_m(-16.72, -43.86, -16.71, -43.85)
    d2 = _haversine_m(-16.71, -43.85, -16.72, -43.86)
    assert d1 == d2


def test_fallback_matrix():
    # OSRM inalcancavel → cai no Haversine
    points = [(-16.7244, -43.8636), (-16.71, -43.85), (-16.73, -43.87)]
    matrix = get_duration_matrix(points, osrm_url="http://localhost:99999")
    assert len(matrix) == 3
    assert len(matrix[0]) == 3
    assert matrix[0][0] == 0
    assert matrix[1][1] == 0
    assert matrix[2][2] == 0
    assert matrix[0][1] > 0


def test_fallback_matrix_asymmetry_is_symmetric():
    # Haversine eh simetrico (OSRM nao seria, por causa de mao unica)
    points = [(-16.72, -43.86), (-16.71, -43.85)]
    matrix = get_duration_matrix(points, osrm_url="http://localhost:99999")
    assert matrix[0][1] == matrix[1][0]
