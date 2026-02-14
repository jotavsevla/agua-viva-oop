from pydantic import BaseModel, Field
from enum import Enum


class Coordenada(BaseModel):
    lat: float
    lon: float


class JanelaTipo(str, Enum):
    HARD = "HARD"
    ASAP = "ASAP"


class Pedido(BaseModel):
    pedido_id: int
    lat: float
    lon: float
    galoes: int = Field(ge=1)
    janela_tipo: JanelaTipo = JanelaTipo.ASAP
    janela_inicio: str | None = None  # "HH:MM"
    janela_fim: str | None = None     # "HH:MM"
    prioridade: int = 2               # 1=HARD, 2=ASAP


class SolverRequest(BaseModel):
    job_id: str | None = None
    plan_version: int | None = None
    deposito: Coordenada
    capacidade_veiculo: int = 5
    horario_inicio: str = "08:00"    # "HH:MM"
    horario_fim: str = "18:00"       # "HH:MM"
    entregadores: list[int]          # user IDs dos entregadores ativos
    pedidos: list[Pedido]


class Parada(BaseModel):
    ordem: int
    pedido_id: int
    lat: float
    lon: float
    hora_prevista: str  # "HH:MM"


class Rota(BaseModel):
    entregador_id: int
    numero_no_dia: int
    paradas: list[Parada]


class SolverResponse(BaseModel):
    rotas: list[Rota]
    nao_atendidos: list[int]  # pedido_ids sem rota viavel


class AsyncSolveAccepted(BaseModel):
    job_id: str
    status: str


class AsyncSolveResult(BaseModel):
    job_id: str
    status: str
    response: SolverResponse | None = None
    erro: str | None = None
