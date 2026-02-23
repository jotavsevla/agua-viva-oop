import time
import uuid

import pytest
from fastapi.testclient import TestClient

import main


client = TestClient(main.app)


@pytest.fixture(autouse=True)
def _isolar_estado_jobs():
    with main.JOBS_LOCK:
        main.JOBS.clear()
    yield
    with main.JOBS_LOCK:
        main.JOBS.clear()


@pytest.fixture(autouse=True)
def _mock_duration_matrix(monkeypatch):
    def fake_duration_matrix(points, osrm_url):
        size = len(points)
        matrix = [[0 for _ in range(size)] for _ in range(size)]
        for i in range(size):
            for j in range(size):
                if i != j:
                    matrix[i][j] = 60
        return matrix

    monkeypatch.setattr(main, "get_duration_matrix", fake_duration_matrix)


def _payload(job_id: str | None = None) -> dict:
    payload = {
        "deposito": {"lat": -16.7344, "lon": -43.8772},
        "capacidade_veiculo": 5,
        "horario_inicio": "08:00",
        "horario_fim": "18:00",
        "entregadores": [1],
        "pedidos": [
            {
                "pedido_id": 1,
                "lat": -16.7210,
                "lon": -43.8610,
                "galoes": 1,
                "janela_tipo": "ASAP",
                "prioridade": 2,
            }
        ],
    }
    if job_id is not None:
        payload["job_id"] = job_id
    return payload


def test_cancelamento_previo_descarta_resposta_sincrona():
    job_id = f"job-cancel-{uuid.uuid4().hex}"

    cancel_resp = client.post(f"/cancel/{job_id}")
    assert cancel_resp.status_code == 200

    solve_resp = client.post("/solve", json=_payload(job_id=job_id))
    assert solve_resp.status_code == 200

    body = solve_resp.json()
    assert body["rotas"] == []
    assert body["nao_atendidos"] == [1]


def test_solve_async_deve_retornar_resultado_consultavel():
    job_id = f"job-async-{uuid.uuid4().hex}"

    accepted = client.post("/solve/async", json=_payload(job_id=job_id))
    assert accepted.status_code == 202
    assert accepted.json()["job_id"] == job_id

    status = None
    result_body = None

    for _ in range(50):
        result = client.get(f"/result/{job_id}")
        assert result.status_code == 200
        result_body = result.json()
        status = result_body["status"]
        if status in {"CONCLUIDO", "CANCELADO", "FALHOU"}:
            break
        time.sleep(0.05)

    assert status == "CONCLUIDO"
    assert result_body["response"] is not None
    assert result_body["response"]["nao_atendidos"] == []
