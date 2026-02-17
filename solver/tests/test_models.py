import pytest
from pydantic import ValidationError

from models import SolverRequest, Pedido, Coordenada, JanelaTipo


def test_pedido_defaults():
    p = Pedido(pedido_id=1, lat=-16.72, lon=-43.86, galoes=2)
    assert p.janela_tipo == JanelaTipo.ASAP
    assert p.prioridade == 2
    assert p.janela_inicio is None
    assert p.janela_fim is None


def test_pedido_hard():
    p = Pedido(
        pedido_id=1, lat=-16.72, lon=-43.86, galoes=1,
        janela_tipo=JanelaTipo.HARD, janela_inicio="09:00", janela_fim="11:00",
        prioridade=1,
    )
    assert p.janela_tipo == JanelaTipo.HARD
    assert p.janela_inicio == "09:00"


def test_pedido_galoes_minimo():
    with pytest.raises(ValidationError):
        Pedido(pedido_id=1, lat=-16.72, lon=-43.86, galoes=0)


def test_pedido_galoes_negativo():
    with pytest.raises(ValidationError):
        Pedido(pedido_id=1, lat=-16.72, lon=-43.86, galoes=-1)


def test_solver_request_defaults():
    req = SolverRequest(
        deposito=Coordenada(lat=-16.7244, lon=-43.8636),
        entregadores=[1, 3],
        pedidos=[Pedido(pedido_id=10, lat=-16.71, lon=-43.85, galoes=2)],
    )
    assert req.capacidade_veiculo == 5
    assert req.horario_inicio == "08:00"
    assert req.horario_fim == "18:00"
    assert len(req.pedidos) == 1


def test_solver_request_custom():
    req = SolverRequest(
        deposito=Coordenada(lat=-16.7244, lon=-43.8636),
        capacidade_veiculo=10,
        capacidades_entregadores=[4],
        horario_inicio="07:00",
        horario_fim="17:00",
        entregadores=[1],
        pedidos=[],
    )
    assert req.capacidade_veiculo == 10
    assert req.capacidades_entregadores == [4]
    assert req.horario_inicio == "07:00"
