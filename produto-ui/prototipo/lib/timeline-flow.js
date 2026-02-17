(() => {
  function assertDependency(value, name) {
    if (typeof value !== "function") {
      throw new Error(`dependencia obrigatoria ausente: ${name}`);
    }
  }

  async function fetchTimelineForPedido(params) {
    const {
      pedidoId,
      requestApi,
      buildTimelinePath,
      normalizeTimelinePayload,
      buildFallbackTimelinePayload
    } = params || {};

    assertDependency(requestApi, "requestApi");
    assertDependency(buildTimelinePath, "buildTimelinePath");
    assertDependency(normalizeTimelinePayload, "normalizeTimelinePayload");
    assertDependency(buildFallbackTimelinePayload, "buildFallbackTimelinePayload");

    const path = buildTimelinePath(pedidoId);
    const result = await requestApi(
      path,
      { method: "GET" },
      () => buildFallbackTimelinePayload(pedidoId)
    );

    return {
      ...result,
      payload: normalizeTimelinePayload(result.payload)
    };
  }

  const timelineFlowApi = {
    fetchTimelineForPedido
  };

  if (typeof window !== "undefined") {
    window.TimelineFlow = timelineFlowApi;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = timelineFlowApi;
  }
})();
