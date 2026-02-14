const test = require("node:test");
const assert = require("node:assert/strict");

const { fetchTimelineForPedido } = require("../lib/timeline-flow");

test("deveBuscarTimelineNormalizadaQuandoApiRetornarSucesso", async () => {
  const chamadas = [];
  const requestApi = async (path, options, fallbackFactory) => {
    chamadas.push({ path, options, fallbackFactory });
    return {
      fallback: false,
      payload: {
        pedidoId: 8421,
        statusAtual: "EM_ROTA",
        eventos: [
          {
            timestamp: "2026-02-13T09:02:00Z",
            deStatus: "CONFIRMADO",
            paraStatus: "EM_ROTA",
            origem: "Despacho"
          }
        ]
      }
    };
  };

  const resultado = await fetchTimelineForPedido({
    pedidoId: 8421,
    requestApi,
    buildTimelinePath: (id) => `/api/pedidos/${id}/timeline`,
    normalizeTimelinePayload: (payload) => ({
      pedidoId: payload.pedidoId,
      status: payload.statusAtual,
      eventos: [{ hora: "09:02", de: "CONFIRMADO", para: "EM_ROTA", origem: "Despacho" }]
    }),
    buildFallbackTimelinePayload: (id) => ({ pedidoId: id, statusAtual: "PENDENTE", eventos: [] })
  });

  assert.equal(chamadas.length, 1);
  assert.equal(chamadas[0].path, "/api/pedidos/8421/timeline");
  assert.equal(chamadas[0].options.method, "GET");
  assert.equal(typeof chamadas[0].fallbackFactory, "function");
  assert.equal(resultado.fallback, false);
  assert.equal(resultado.payload.pedidoId, 8421);
  assert.equal(resultado.payload.status, "EM_ROTA");
});

test("devePropagarErroQuandoDependenciasAusentes", async () => {
  await assert.rejects(
    () => fetchTimelineForPedido({ pedidoId: 8421 }),
    /dependencia obrigatoria/i
  );
});

test("deveUsarFallbackFactoryComPedidoIdQuandoApiFalhar", async () => {
  let fallbackRecebido = null;
  const requestApi = async (_path, _options, fallbackFactory) => {
    fallbackRecebido = fallbackFactory();
    return { fallback: true, payload: fallbackRecebido };
  };

  const resultado = await fetchTimelineForPedido({
    pedidoId: 9000,
    requestApi,
    buildTimelinePath: (id) => `/api/pedidos/${id}/timeline`,
    normalizeTimelinePayload: (payload) => ({
      pedidoId: payload.pedidoId,
      status: payload.statusAtual,
      eventos: []
    }),
    buildFallbackTimelinePayload: (id) => ({ pedidoId: id, statusAtual: "PENDENTE", eventos: [] })
  });

  assert.deepEqual(fallbackRecebido, { pedidoId: 9000, statusAtual: "PENDENTE", eventos: [] });
  assert.equal(resultado.fallback, true);
  assert.equal(resultado.payload.pedidoId, 9000);
});
