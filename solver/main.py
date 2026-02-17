import os
import threading
import uuid
from datetime import datetime, timezone

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse

from models import (
    AsyncSolveAccepted,
    AsyncSolveResult,
    Coordenada,
    Parada,
    Pedido,
    Rota,
    SolverRequest,
    SolverResponse,
)
from matrix import get_duration_matrix
from visualize import build_map
from vrp import MAX_TRIPS_PER_DRIVER, hhmm_to_seconds, seconds_to_hhmm, solve

app = FastAPI(title="Agua Viva Route Solver", version="1.1.0")

OSRM_URL = os.getenv("OSRM_URL", "http://osrm:5000")

# job_id -> estado do job async
JOBS: dict[str, dict] = {}
JOBS_LOCK = threading.Lock()


# --- Demo: pedidos ficticios espalhados por Montes Claros ---
DEMO_REQUEST = SolverRequest(
    deposito=Coordenada(lat=-16.734440968489228, lon=-43.877211192130325),
    capacidade_veiculo=5,
    entregadores=[1, 2, 3],
    pedidos=[
        # Centro
        Pedido(pedido_id=101, lat=-16.7210, lon=-43.8610, galoes=2,
               janela_tipo="HARD", janela_inicio="09:00", janela_fim="11:00", prioridade=1),
        Pedido(pedido_id=102, lat=-16.7265, lon=-43.8590, galoes=1),
        # Major Prates (norte)
        Pedido(pedido_id=103, lat=-16.7050, lon=-43.8600, galoes=3),
        Pedido(pedido_id=104, lat=-16.7080, lon=-43.8550, galoes=2,
               janela_tipo="HARD", janela_inicio="10:00", janela_fim="12:00", prioridade=1),
        # Todos os Santos (sul)
        Pedido(pedido_id=105, lat=-16.7400, lon=-43.8700, galoes=1),
        Pedido(pedido_id=106, lat=-16.7380, lon=-43.8650, galoes=2),
        # Sao Jose (leste)
        Pedido(pedido_id=107, lat=-16.7150, lon=-43.8450, galoes=1,
               janela_tipo="HARD", janela_inicio="08:30", janela_fim="10:00", prioridade=1),
        Pedido(pedido_id=108, lat=-16.7180, lon=-43.8480, galoes=2),
        # Ibituruna (oeste)
        Pedido(pedido_id=109, lat=-16.7200, lon=-43.8800, galoes=3),
        Pedido(pedido_id=110, lat=-16.7230, lon=-43.8750, galoes=1),
        # Jardim Panorama (norte)
        Pedido(pedido_id=111, lat=-16.6950, lon=-43.8550, galoes=2,
               janela_tipo="HARD", janela_inicio="14:00", janela_fim="16:00", prioridade=1),
        Pedido(pedido_id=112, lat=-16.6980, lon=-43.8520, galoes=1),
    ],
)


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _is_cancel_requested(job_id: str | None) -> bool:
    if not job_id:
        return False

    with JOBS_LOCK:
        state = JOBS.get(job_id)
        if not state:
            return False
        return bool(state.get("cancel_requested", False))


def _solve_internal(req: SolverRequest, cancel_checker) -> SolverResponse:
    if not req.pedidos:
        return SolverResponse(rotas=[], nao_atendidos=[])

    if cancel_checker():
        return SolverResponse(rotas=[], nao_atendidos=[p.pedido_id for p in req.pedidos])

    base = req.horario_inicio
    work_day_s = hhmm_to_seconds(req.horario_fim, base)

    # Montar pontos: index 0 = deposito, 1..N = pedidos
    points: list[tuple[float, float]] = [(req.deposito.lat, req.deposito.lon)]
    demands = [0]
    time_windows: list[tuple[int, int]] = [(0, work_day_s)]
    pedido_map: dict[int, Pedido] = {}

    for i, p in enumerate(req.pedidos):
        node = i + 1
        points.append((p.lat, p.lon))
        demands.append(p.galoes)
        pedido_map[node] = p

        if p.janela_tipo == "HARD" and p.janela_inicio and p.janela_fim:
            tw_start = hhmm_to_seconds(p.janela_inicio, base)
            tw_end = hhmm_to_seconds(p.janela_fim, base)
            time_windows.append((max(0, tw_start), min(work_day_s, tw_end)))
        else:
            time_windows.append((0, work_day_s))

    if cancel_checker():
        return SolverResponse(rotas=[], nao_atendidos=[p.pedido_id for p in req.pedidos])

    # Matriz de duracao via OSRM (fallback Haversine)
    matrix = get_duration_matrix(points, OSRM_URL)

    # Resolver
    raw_routes, dropped_nodes = solve(
        duration_matrix=matrix,
        demands=demands,
        time_windows=time_windows,
        num_drivers=len(req.entregadores),
        vehicle_capacity=req.capacidade_veiculo,
        vehicle_capacities=req.capacidades_entregadores,
        cancel_checker=cancel_checker,
    )

    if cancel_checker():
        return SolverResponse(rotas=[], nao_atendidos=[p.pedido_id for p in req.pedidos])

    # Mapear veiculos virtuais -> entregadores + numero da viagem
    driver_trips: dict[int, list[list[Parada]]] = {}

    for vehicle_id, stops in raw_routes:
        driver_index = vehicle_id // MAX_TRIPS_PER_DRIVER
        entregador_id = req.entregadores[driver_index]

        paradas = []
        for ordem, (node, arrival_s) in enumerate(stops, start=1):
            p = pedido_map[node]
            paradas.append(
                Parada(
                    ordem=ordem,
                    pedido_id=p.pedido_id,
                    lat=p.lat,
                    lon=p.lon,
                    hora_prevista=seconds_to_hhmm(arrival_s, base),
                )
            )

        if entregador_id not in driver_trips:
            driver_trips[entregador_id] = []
        driver_trips[entregador_id].append(paradas)

    # Montar resposta
    rotas = []
    for entregador_id, trips in driver_trips.items():
        for trip_num, paradas in enumerate(trips, start=1):
            rotas.append(
                Rota(
                    entregador_id=entregador_id,
                    numero_no_dia=trip_num,
                    paradas=paradas,
                )
            )

    nao_atendidos = [pedido_map[node].pedido_id for node in dropped_nodes]
    return SolverResponse(rotas=rotas, nao_atendidos=nao_atendidos)


def _run_async_job(job_id: str, req: SolverRequest) -> None:
    with JOBS_LOCK:
        state = JOBS.get(job_id)
        if not state:
            return
        if state.get("cancel_requested"):
            state["status"] = "CANCELADO"
            state["finalizado_em"] = _utc_now_iso()
            return

        state["status"] = "EM_EXECUCAO"
        state["iniciado_em"] = _utc_now_iso()

    try:
        resp = _solve_internal(req, lambda: _is_cancel_requested(job_id))

        with JOBS_LOCK:
            state = JOBS.get(job_id)
            if not state:
                return
            if state.get("cancel_requested"):
                state["status"] = "CANCELADO"
                state["response"] = None
            else:
                state["status"] = "CONCLUIDO"
                state["response"] = resp.model_dump()
            state["finalizado_em"] = _utc_now_iso()

    except Exception as ex:
        with JOBS_LOCK:
            state = JOBS.get(job_id)
            if not state:
                return
            state["status"] = "FALHOU"
            state["erro"] = str(ex)
            state["finalizado_em"] = _utc_now_iso()


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/solve", response_model=SolverResponse)
def solve_vrp(req: SolverRequest):
    return _solve_internal(req, lambda: _is_cancel_requested(req.job_id))


@app.post("/solve/async", response_model=AsyncSolveAccepted, status_code=202)
def solve_vrp_async(req: SolverRequest):
    job_id = req.job_id or f"job-{uuid.uuid4().hex}"

    with JOBS_LOCK:
        state = JOBS.get(job_id)
        if state and state.get("status") in {"PENDENTE", "EM_EXECUCAO"}:
            return AsyncSolveAccepted(job_id=job_id, status=state["status"])

        JOBS[job_id] = {
            "status": "PENDENTE",
            "cancel_requested": False,
            "erro": None,
            "response": None,
            "solicitado_em": _utc_now_iso(),
            "iniciado_em": None,
            "finalizado_em": None,
        }

    async_req = req.model_copy(deep=True)
    async_req.job_id = job_id

    thread = threading.Thread(target=_run_async_job, args=(job_id, async_req), daemon=True)
    thread.start()

    return AsyncSolveAccepted(job_id=job_id, status="PENDENTE")


@app.post("/cancel/{job_id}")
def cancel_job(job_id: str):
    with JOBS_LOCK:
        state = JOBS.get(job_id)
        if not state:
            # Tombstone: permite cancelar antes da execucao e descartar chamadas atrasadas.
            JOBS[job_id] = {
                "status": "CANCELADO",
                "cancel_requested": True,
                "erro": None,
                "response": None,
                "solicitado_em": _utc_now_iso(),
                "iniciado_em": None,
                "finalizado_em": _utc_now_iso(),
            }
            return {"job_id": job_id, "status": "CANCELADO"}

        state["cancel_requested"] = True
        if state.get("status") in {"PENDENTE", "EM_EXECUCAO"}:
            state["status"] = "CANCELADO"
            state["finalizado_em"] = _utc_now_iso()

    return {"job_id": job_id, "status": "CANCELADO"}


@app.get("/result/{job_id}", response_model=AsyncSolveResult)
def result_job(job_id: str):
    with JOBS_LOCK:
        state = JOBS.get(job_id)
        if not state:
            raise HTTPException(status_code=404, detail="job_id nao encontrado")

        response_payload = state.get("response")
        response = SolverResponse.model_validate(response_payload) if response_payload else None

        return AsyncSolveResult(
            job_id=job_id,
            status=state.get("status", "DESCONHECIDO"),
            response=response,
            erro=state.get("erro"),
        )


@app.post("/map", response_class=HTMLResponse)
def map_vrp(req: SolverRequest):
    """Resolve e devolve mapa HTML interativo com as rotas."""
    resp = solve_vrp(req)
    return build_map(req, resp)


@app.get("/demo", response_class=HTMLResponse)
def demo():
    """Mapa demo com pedidos ficticios em Montes Claros."""
    resp = solve_vrp(DEMO_REQUEST)
    return build_map(DEMO_REQUEST, resp)
