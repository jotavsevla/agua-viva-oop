(() => {
  function toPositiveInteger(value) {
    if (typeof value === "string" && value.trim() === "") {
      return null;
    }

    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      return null;
    }
    return parsed;
  }

  function requirePedidoId(value) {
    const pedidoId = toPositiveInteger(value);
    if (pedidoId === null) {
      throw new Error("pedidoId invalido");
    }
    return pedidoId;
  }

  function formatHour(timestamp) {
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) {
      return "--:--";
    }

    return date.toLocaleTimeString("pt-BR", {
      hour: "2-digit",
      minute: "2-digit"
    });
  }

  function getEventTimestamp(evento) {
    const value = evento?.timestamp || evento?.data || null;
    const time = new Date(value).getTime();
    return Number.isNaN(time) ? Number.POSITIVE_INFINITY : time;
  }

  function normalizeTimelinePayload(payload) {
    const pedidoId = requirePedidoId(payload?.pedidoId ?? payload?.pedido_id);
    const status = String(payload?.statusAtual ?? payload?.status_atual ?? payload?.status ?? "").trim();
    const eventosOriginais = Array.isArray(payload?.eventos) ? payload.eventos : [];

    const eventos = [...eventosOriginais]
      .sort((a, b) => getEventTimestamp(a) - getEventTimestamp(b))
      .map((evento) => {
        return {
          hora: formatHour(evento?.timestamp ?? evento?.data ?? null),
          de: String(evento?.deStatus ?? evento?.de_status ?? evento?.de ?? "").trim(),
          para: String(evento?.paraStatus ?? evento?.para_status ?? evento?.para ?? "").trim(),
          origem: String(evento?.origem ?? "").trim() || "Sistema",
          observacao: String(evento?.observacao ?? "").trim()
        };
      });

    return {
      pedidoId,
      status,
      eventos
    };
  }

  function mergeTimelineIntoPedido(pedido, timeline) {
    const pedidoId = requirePedidoId(pedido?.pedidoId);
    const timelineId = requirePedidoId(timeline?.pedidoId);
    if (pedidoId !== timelineId) {
      throw new Error("pedidoId divergente");
    }

    return {
      ...pedido,
      status: timeline.status || pedido.status,
      eventos: Array.isArray(timeline.eventos) ? timeline.eventos : pedido.eventos
    };
  }

  function buildTimelinePath(pedidoId) {
    return `/api/pedidos/${requirePedidoId(pedidoId)}/timeline`;
  }

  const timelineUtilsApi = {
    buildTimelinePath,
    normalizeTimelinePayload,
    mergeTimelineIntoPedido
  };

  if (typeof window !== "undefined") {
    window.TimelineUtils = timelineUtilsApi;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = timelineUtilsApi;
  }
})();
