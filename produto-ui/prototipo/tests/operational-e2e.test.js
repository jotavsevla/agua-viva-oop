const test = require("node:test");
const assert = require("node:assert/strict");

const {
  expectedStatusForScenario,
  buildTerminalEventPayload,
  buildEntregaLookupSql,
  buildExecucaoPath
} = require("../lib/operational-e2e");

test("deveRetornarStatusEsperadoPorCenario", () => {
  assert.equal(expectedStatusForScenario("feliz"), "ENTREGUE");
  assert.equal(expectedStatusForScenario("falha"), "CANCELADO");
  assert.equal(expectedStatusForScenario("cancelamento"), "CANCELADO");
});

test("deveMontarPayloadTerminalParaCenarioFeliz", () => {
  const payload = buildTerminalEventPayload({
    scenario: "feliz",
    entregaId: 55
  });

  assert.deepEqual(payload, {
    eventType: "PEDIDO_ENTREGUE",
    entregaId: 55
  });
});

test("deveMontarPayloadTerminalParaCenarioFalhaComMotivo", () => {
  const payload = buildTerminalEventPayload({
    scenario: "falha",
    entregaId: 77,
    motivoFalha: "cliente ausente"
  });

  assert.deepEqual(payload, {
    eventType: "PEDIDO_FALHOU",
    entregaId: 77,
    motivo: "cliente ausente"
  });
});

test("deveMontarPayloadTerminalParaCenarioCancelamentoComCobranca", () => {
  const payload = buildTerminalEventPayload({
    scenario: "cancelamento",
    entregaId: 12,
    motivoCancelamento: "cliente cancelou",
    cobrancaCancelamentoCentavos: 2500
  });

  assert.deepEqual(payload, {
    eventType: "PEDIDO_CANCELADO",
    entregaId: 12,
    motivo: "cliente cancelou",
    cobrancaCancelamentoCentavos: 2500
  });
});

test("deveRejeitarCenarioOuEntregaInvalidaNoPayloadTerminal", () => {
  assert.throws(
    () => buildTerminalEventPayload({ scenario: "x", entregaId: 10 }),
    /cenario invalido/i
  );
  assert.throws(
    () => buildTerminalEventPayload({ scenario: "feliz", entregaId: 0 }),
    /entregaId invalido/i
  );
});

test("deveGerarSqlParaEncontrarEntregaERotaDoPedido", () => {
  assert.equal(
    buildEntregaLookupSql(42),
    "SELECT e.rota_id, e.id AS entrega_id FROM entregas e WHERE e.pedido_id = 42 ORDER BY e.id DESC LIMIT 1;"
  );
});

test("deveRejeitarPedidoIdInvalidoAoMontarSql", () => {
  assert.throws(() => buildEntregaLookupSql(0), /pedidoId invalido/i);
  assert.throws(() => buildEntregaLookupSql(-3), /pedidoId invalido/i);
});

test("deveGerarPathExecucaoDoPedido", () => {
  assert.equal(buildExecucaoPath(42), "/api/pedidos/42/execucao");
  assert.throws(() => buildExecucaoPath(0), /pedidoId invalido/i);
});
