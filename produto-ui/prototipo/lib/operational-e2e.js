(() => {
  function normalizeScenario(value) {
    const normalized = String(value || "").trim().toLowerCase();
    if (normalized !== "feliz" && normalized !== "falha" && normalized !== "cancelamento") {
      throw new Error("cenario invalido");
    }
    return normalized;
  }

  function requirePositiveInt(value, fieldName) {
    const n = Number(value);
    if (!Number.isInteger(n) || n <= 0) {
      throw new Error(`${fieldName} invalido`);
    }
    return n;
  }

  function expectedStatusForScenario(scenario) {
    const normalized = normalizeScenario(scenario);
    if (normalized === "feliz") {
      return "ENTREGUE";
    }
    return "CANCELADO";
  }

  function buildTerminalEventPayload(params) {
    const scenario = normalizeScenario(params?.scenario);
    const entregaId = requirePositiveInt(params?.entregaId, "entregaId");

    if (scenario === "feliz") {
      return {
        eventType: "PEDIDO_ENTREGUE",
        entregaId
      };
    }

    if (scenario === "falha") {
      const motivoFalha = String(params?.motivoFalha || "cliente ausente").trim() || "cliente ausente";
      return {
        eventType: "PEDIDO_FALHOU",
        entregaId,
        motivo: motivoFalha
      };
    }

    const motivoCancelamento =
      String(params?.motivoCancelamento || "cliente cancelou").trim() || "cliente cancelou";
    const cobranca = Number(params?.cobrancaCancelamentoCentavos);
    const payload = {
      eventType: "PEDIDO_CANCELADO",
      entregaId,
      motivo: motivoCancelamento
    };
    if (Number.isInteger(cobranca) && cobranca >= 0) {
      payload.cobrancaCancelamentoCentavos = cobranca;
    }
    return payload;
  }

  function buildEntregaLookupSql(pedidoId) {
    const pid = requirePositiveInt(pedidoId, "pedidoId");
    return `SELECT e.rota_id, e.id AS entrega_id FROM entregas e WHERE e.pedido_id = ${pid} ORDER BY e.id DESC LIMIT 1;`;
  }

  function buildExecucaoPath(pedidoId) {
    const pid = requirePositiveInt(pedidoId, "pedidoId");
    return `/api/pedidos/${pid}/execucao`;
  }

  const operationalE2EApi = {
    expectedStatusForScenario,
    buildTerminalEventPayload,
    buildEntregaLookupSql,
    buildExecucaoPath
  };

  if (typeof window !== "undefined") {
    window.OperationalE2E = operationalE2EApi;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = operationalE2EApi;
  }
})();
