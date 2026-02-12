import os

import folium
import httpx

from models import SolverRequest, SolverResponse

COLORS = ["#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4", "#42d4f4"]


def _road_geometry(
    waypoints: list[tuple[float, float]], osrm_url: str
) -> list[tuple[float, float]] | None:
    """
    Pede ao OSRM a geometria real da rota (seguindo vias).
    Retorna lista de (lat, lon) detalhada ou None se OSRM indisponivel.
    """
    coords = ";".join(f"{lon},{lat}" for lat, lon in waypoints)
    try:
        r = httpx.get(
            f"{osrm_url}/route/v1/driving/{coords}",
            params={"overview": "full", "geometries": "geojson"},
            timeout=5.0,
        )
        data = r.json()
        if data.get("code") == "Ok":
            return [
                (lat, lon)
                for lon, lat in data["routes"][0]["geometry"]["coordinates"]
            ]
    except Exception:
        pass
    return None


def build_map(req: SolverRequest, resp: SolverResponse) -> str:
    """Gera HTML com mapa interativo Leaflet mostrando rotas otimizadas."""
    osrm_url = os.getenv("OSRM_URL", "http://osrm:5000")

    m = folium.Map(
        location=[req.deposito.lat, req.deposito.lon],
        zoom_start=14,
        tiles="OpenStreetMap",
    )

    # --- Deposito ---
    folium.Marker(
        [req.deposito.lat, req.deposito.lon],
        popup="<b>Deposito</b><br>Base dos entregadores",
        icon=folium.DivIcon(
            html=(
                '<div style="background:#000;color:#fff;border-radius:50%;'
                "width:32px;height:32px;display:flex;align-items:center;"
                "justify-content:center;font-size:16px;"
                'border:3px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,.4);">'
                "&#9751;</div>"
            ),
        ),
    ).add_to(m)

    # --- Rotas ---
    for i, rota in enumerate(resp.rotas):
        color = COLORS[i % len(COLORS)]

        # Waypoints: deposito → paradas em ordem → deposito
        waypoints = [(req.deposito.lat, req.deposito.lon)]
        for p in rota.paradas:
            waypoints.append((p.lat, p.lon))
        waypoints.append((req.deposito.lat, req.deposito.lon))

        # Geometria real via OSRM (ou linha reta como fallback)
        road = _road_geometry(waypoints, osrm_url)
        line_points = road if road else waypoints

        folium.PolyLine(
            line_points,
            color=color,
            weight=4,
            opacity=0.8,
            tooltip=(
                f"Entregador {rota.entregador_id} "
                f"- viagem {rota.numero_no_dia}"
            ),
        ).add_to(m)

        # Marcadores numerados por parada
        for p in rota.paradas:
            folium.Marker(
                [p.lat, p.lon],
                popup=(
                    f"<b>Pedido {p.pedido_id}</b><br>"
                    f"Ordem: {p.ordem}<br>"
                    f"Previsto: {p.hora_prevista}"
                ),
                icon=folium.DivIcon(
                    html=(
                        f'<div style="background:{color};color:#fff;'
                        f"border-radius:50%;width:28px;height:28px;"
                        f"display:flex;align-items:center;"
                        f"justify-content:center;font-weight:bold;"
                        f"font-size:13px;border:2px solid #fff;"
                        f'box-shadow:0 1px 3px rgba(0,0,0,.4);">'
                        f"{p.ordem}</div>"
                    ),
                ),
            ).add_to(m)

    # --- Nao atendidos ---
    pedido_lookup = {p.pedido_id: p for p in req.pedidos}
    for pid in resp.nao_atendidos:
        p = pedido_lookup.get(pid)
        if p:
            folium.Marker(
                [p.lat, p.lon],
                popup=f"<b>NAO ATENDIDO</b><br>Pedido {p.pedido_id}",
                icon=folium.Icon(color="red", icon="remove", prefix="glyphicon"),
            ).add_to(m)

    # --- Legenda ---
    legend = (
        '<div style="position:fixed;bottom:30px;left:30px;z-index:1000;'
        "background:#fff;padding:12px 16px;border-radius:8px;"
        'box-shadow:0 2px 8px rgba(0,0,0,.25);font:13px/1.8 sans-serif;">'
        "<b>Rotas</b><br>"
    )
    for i, rota in enumerate(resp.rotas):
        c = COLORS[i % len(COLORS)]
        n = len(rota.paradas)
        legend += (
            f'<span style="color:{c}">&#9679;</span> '
            f"Entregador {rota.entregador_id} "
            f"- viagem {rota.numero_no_dia} "
            f'({n} {"parada" if n == 1 else "paradas"})<br>'
        )
    if resp.nao_atendidos:
        legend += (
            f'<span style="color:red">&#10005;</span> '
            f"{len(resp.nao_atendidos)} nao atendido(s)<br>"
        )
    legend += "</div>"
    m.get_root().html.add_child(folium.Element(legend))

    return m._repr_html_()
