const test = require("node:test");
const assert = require("node:assert/strict");

const {
  buildTimelinePath,
  normalizeTimelinePayload,
  mergeTimelineIntoPedido
} = require("../lib/timeline-utils");

test("deveGerarPathTimelineQuandoPedidoIdValido", () => {
  assert.equal(buildTimelinePath(8421), "/api/pedidos/8421/timeline");
});

test("deveRejeitarPathTimelineQuandoPedidoIdInvalido", () => {
  assert.throws(() => buildTimelinePath(0), /pedidoId invalido/i);
  assert.throws(() => buildTimelinePath(-1), /pedidoId invalido/i);
  assert.throws(() => buildTimelinePath("x"), /pedidoId invalido/i);
});

test("deveNormalizarPayloadTimelineQuandoValido", () => {
  const payload = {
    pedidoId: 8421,
    statusAtual: "EM_ROTA",
    eventos: [
      {
        timestamp: "2026-02-13T09:02:00Z",
        deStatus: "CONFIRMADO",
        paraStatus: "EM_ROTA",
        origem: "Despacho"
      },
      {
        timestamp: "2026-02-13T07:58:00Z",
        deStatus: "PENDENTE",
        paraStatus: "CONFIRMADO",
        origem: "Atendimento"
      }
    ]
  };

  const normalizado = normalizeTimelinePayload(payload);
  assert.equal(normalizado.pedidoId, 8421);
  assert.equal(normalizado.status, "EM_ROTA");
  assert.equal(normalizado.eventos.length, 2);
  assert.deepEqual(normalizado.eventos[0].de, "PENDENTE");
  assert.deepEqual(normalizado.eventos[1].para, "EM_ROTA");
  assert.match(normalizado.eventos[0].hora, /^\d{2}:\d{2}$/);
});

test("deveMesclarTimelineNoPedidoQuandoPayloadValido", () => {
  const pedido = {
    pedidoId: 8421,
    clienteId: 392,
    cliente: "Condominio Horizonte",
    status: "PENDENTE",
    idempotente: false,
    eventos: []
  };
  const timeline = {
    pedidoId: 8421,
    status: "EM_ROTA",
    eventos: [
      { hora: "07:58", de: "PENDENTE", para: "CONFIRMADO", origem: "Atendimento" },
      { hora: "09:02", de: "CONFIRMADO", para: "EM_ROTA", origem: "Despacho" }
    ]
  };

  const mesclado = mergeTimelineIntoPedido(pedido, timeline);
  assert.equal(mesclado.status, "EM_ROTA");
  assert.equal(mesclado.eventos.length, 2);
  assert.equal(mesclado.cliente, "Condominio Horizonte");
});
