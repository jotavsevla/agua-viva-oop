const test = require("node:test");
const assert = require("node:assert/strict");

const { createInitialAppState } = require("../lib/front/store");

test("deveCriarEstadoInicialComApiBaseInformada", () => {
  const state = createInitialAppState("http://localhost:8082");

  assert.equal(state.api.baseUrl, "http://localhost:8082");
  assert.equal(state.view, "pedidos");
  assert.equal(state.examples.atendimentoRequest.origemCanal, "MANUAL");
  assert.equal(state.examples.atendimentoRequest.janelaTipo, "ASAP");
  assert.deepEqual(state.handoff.atendimentosSessao, []);
  assert.equal(state.handoff.focoPedidoId, null);
});
