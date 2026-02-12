import math

import httpx

EARTH_RADIUS_M = 6_371_000
FALLBACK_SPEED_KMH = 30.0


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, [lat1, lon1, lat2, lon2])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def _haversine_duration_s(lat1: float, lon1: float, lat2: float, lon2: float) -> int:
    dist_m = _haversine_m(lat1, lon1, lat2, lon2)
    return int(dist_m / (FALLBACK_SPEED_KMH * 1000 / 3600))


def _build_haversine_matrix(points: list[tuple[float, float]]) -> list[list[int]]:
    n = len(points)
    return [
        [
            0 if i == j
            else _haversine_duration_s(
                points[i][0], points[i][1], points[j][0], points[j][1]
            )
            for j in range(n)
        ]
        for i in range(n)
    ]


def get_duration_matrix(
    points: list[tuple[float, float]], osrm_url: str = "http://osrm:5000"
) -> list[list[int]]:
    """
    Monta matriz de duracao (segundos) para lista de (lat, lon).
    Index 0 = deposito. Usa OSRM se disponivel, Haversine como fallback.
    """
    coords_str = ";".join(f"{lon},{lat}" for lat, lon in points)
    try:
        resp = httpx.get(
            f"{osrm_url}/table/v1/driving/{coords_str}",
            params={"annotations": "duration"},
            timeout=10.0,
        )
        data = resp.json()
        if data.get("code") != "Ok":
            raise ValueError(data.get("message", "OSRM error"))
        return [[int(cell) for cell in row] for row in data["durations"]]
    except Exception:
        return _build_haversine_matrix(points)
