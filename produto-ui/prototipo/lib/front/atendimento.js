(() => {
  const ORIGENS_CANAL = new Set(["MANUAL", "WHATSAPP", "BINA_FIXO", "TELEFONIA_FIXO"]);
  const JANELAS = new Set(["ASAP", "HARD", "FLEXIVEL"]);
  const METODOS_PAGAMENTO = new Set(["NAO_INFORMADO", "DINHEIRO", "PIX", "CARTAO", "VALE"]);

  function normalizeOptionalText(value) {
    if (value == null) {
      return "";
    }
    return String(value).trim();
  }

  function normalizeUpperOptionalText(value) {
    const normalized = normalizeOptionalText(value);
    return normalized ? normalized.toUpperCase() : "";
  }

  function parsePositiveInteger(value, fieldName) {
    const numeric = Number(value);
    if (!Number.isInteger(numeric) || numeric <= 0) {
      throw new Error(`${fieldName} invalido`);
    }
    return numeric;
  }

  function parseCoordinate(value, fieldName) {
    const normalized = normalizeOptionalText(value).replace(",", ".");
    if (!normalized) {
      return null;
    }
    const numeric = Number(normalized);
    if (!Number.isFinite(numeric)) {
      throw new Error(`${fieldName} invalida`);
    }
    return numeric;
  }

  function normalizeOrigemCanal(value) {
    const normalized = normalizeUpperOptionalText(value);
    if (!normalized) {
      return "";
    }
    if (!ORIGENS_CANAL.has(normalized)) {
      throw new Error("origemCanal invalido");
    }
    return normalized;
  }

  function normalizeJanelaTipo(value) {
    const normalized = normalizeUpperOptionalText(value);
    if (!normalized) {
      return "";
    }
    if (normalized === "FLEX") {
      return "FLEXIVEL";
    }
    if (!JANELAS.has(normalized)) {
      throw new Error("janelaTipo invalido");
    }
    return normalized;
  }

  function normalizeMetodoPagamento(value) {
    const normalized = normalizeUpperOptionalText(value);
    if (!normalized) {
      return "";
    }
    if (!METODOS_PAGAMENTO.has(normalized)) {
      throw new Error("metodoPagamento invalido");
    }
    return normalized;
  }

  function validateOmnichannelConsistency(payload) {
    const origemCanal = payload.origemCanal || "";
    if (origemCanal === "MANUAL" && payload.sourceEventId) {
      throw new Error("sourceEventId nao pode ser usado com origemCanal=MANUAL");
    }
    if (origemCanal && origemCanal !== "MANUAL" && !payload.sourceEventId) {
      throw new Error(`sourceEventId obrigatorio para origemCanal=${origemCanal}`);
    }
    if (origemCanal && origemCanal !== "MANUAL" && payload.manualRequestId) {
      throw new Error(`manualRequestId so pode ser usado com origemCanal=MANUAL`);
    }
    if (!origemCanal && payload.sourceEventId && payload.manualRequestId) {
      throw new Error("manualRequestId nao pode ser combinado com sourceEventId quando origemCanal estiver vazio");
    }

    if (payload.sourceEventId && payload.externalCallId && payload.sourceEventId !== payload.externalCallId) {
      throw new Error("sourceEventId diverge de externalCallId");
    }
    if (payload.manualRequestId && payload.externalCallId && payload.manualRequestId !== payload.externalCallId) {
      throw new Error("manualRequestId diverge de externalCallId");
    }
  }

  function validateJanela(payload) {
    const janelaTipo = payload.janelaTipo || "";
    if (janelaTipo === "HARD") {
      if (!payload.janelaInicio || !payload.janelaFim) {
        throw new Error("janelaTipo=HARD exige janelaInicio e janelaFim");
      }
      return;
    }
    if (payload.janelaInicio || payload.janelaFim) {
      throw new Error("janelaInicio/janelaFim so podem ser enviados com janelaTipo=HARD");
    }
  }

  function validateCoordinates(payload, hasLatitude, hasLongitude) {
    if (hasLatitude !== hasLongitude) {
      throw new Error("latitude e longitude devem ser informadas juntas");
    }
    if (!hasLatitude) {
      return;
    }
    payload.latitude = parseCoordinate(payload.latitude, "latitude");
    payload.longitude = parseCoordinate(payload.longitude, "longitude");
  }

  function readAtendimentoFormState(formData) {
    return {
      telefone: normalizeOptionalText(formData.get("telefone")),
      quantidadeGaloes: normalizeOptionalText(formData.get("quantidadeGaloes")),
      atendenteId: normalizeOptionalText(formData.get("atendenteId")),
      origemCanal: normalizeUpperOptionalText(formData.get("origemCanal")),
      sourceEventId: normalizeOptionalText(formData.get("sourceEventId")),
      manualRequestId: normalizeOptionalText(formData.get("manualRequestId")),
      externalCallId: normalizeOptionalText(formData.get("externalCallId")),
      metodoPagamento: normalizeUpperOptionalText(formData.get("metodoPagamento")),
      janelaTipo: normalizeUpperOptionalText(formData.get("janelaTipo")),
      janelaInicio: normalizeOptionalText(formData.get("janelaInicio")),
      janelaFim: normalizeOptionalText(formData.get("janelaFim")),
      nomeCliente: normalizeOptionalText(formData.get("nomeCliente")),
      endereco: normalizeOptionalText(formData.get("endereco")),
      latitude: normalizeOptionalText(formData.get("latitude")),
      longitude: normalizeOptionalText(formData.get("longitude"))
    };
  }

  function buildAtendimentoPayloadFromFormData(formData) {
    const state = readAtendimentoFormState(formData);

    const payload = {
      telefone: state.telefone,
      quantidadeGaloes: parsePositiveInteger(state.quantidadeGaloes, "quantidadeGaloes"),
      atendenteId: parsePositiveInteger(state.atendenteId, "atendenteId")
    };

    const origemCanal = normalizeOrigemCanal(state.origemCanal);
    if (origemCanal) {
      payload.origemCanal = origemCanal;
    }

    const method = normalizeMetodoPagamento(state.metodoPagamento);
    if (method) {
      payload.metodoPagamento = method;
    }

    const janelaTipo = normalizeJanelaTipo(state.janelaTipo);
    if (janelaTipo) {
      payload.janelaTipo = janelaTipo;
    }

    const optionalTextFields = [
      ["sourceEventId", state.sourceEventId],
      ["manualRequestId", state.manualRequestId],
      ["externalCallId", state.externalCallId],
      ["janelaInicio", state.janelaInicio],
      ["janelaFim", state.janelaFim],
      ["nomeCliente", state.nomeCliente],
      ["endereco", state.endereco]
    ];
    optionalTextFields.forEach(([field, value]) => {
      if (value) {
        payload[field] = value;
      }
    });

    const hasLatitude = Boolean(state.latitude);
    const hasLongitude = Boolean(state.longitude);
    if (hasLatitude) {
      payload.latitude = state.latitude;
    }
    if (hasLongitude) {
      payload.longitude = state.longitude;
    }

    validateOmnichannelConsistency(payload);
    validateJanela(payload);
    validateCoordinates(payload, hasLatitude, hasLongitude);

    if (!payload.telefone) {
      throw new Error("telefone obrigatorio");
    }

    return payload;
  }

  const atendimentoApi = {
    ORIGENS_CANAL: [...ORIGENS_CANAL],
    JANELAS: [...JANELAS],
    METODOS_PAGAMENTO: [...METODOS_PAGAMENTO],
    readAtendimentoFormState,
    buildAtendimentoPayloadFromFormData
  };

  if (typeof window !== "undefined") {
    window.AguaVivaAtendimento = atendimentoApi;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = atendimentoApi;
  }
})();
