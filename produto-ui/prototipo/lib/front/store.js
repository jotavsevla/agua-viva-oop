(() => {
  function createInitialAppState(apiBaseUrl) {
    return {
      view: "pedidos",
      mode: "success",
      api: {
        baseUrl: String(apiBaseUrl || "http://localhost:8082"),
        connected: false,
        lastError: null,
        lastSyncAt: null
      },
      painel: null,
      eventosOperacionais: [],
      mapaOperacional: null,
      frotaRoteiros: [],
      apiResults: {
        atendimento: null,
        timeline: null,
        evento: null,
        iniciarRotaPronta: null
      },
      handoff: {
        atendimentosSessao: [],
        focoPedidoId: null,
        ultimoEntregadorId: null
      },
      atendente: {
        buscaTelefone: {
          telefone: "",
          telefoneNormalizado: "",
          atualizadaEm: null
        },
        trilhaSessao: []
      },
      examples: {
        atendimentoRequest: {
          externalCallId: "call-20260213-0001",
          sourceEventId: "",
          manualRequestId: "call-20260213-0001",
          origemCanal: "MANUAL",
          telefone: "(38) 99876-1234",
          quantidadeGaloes: 2,
          atendenteId: 1,
          metodoPagamento: "PIX",
          janelaTipo: "ASAP",
          janelaInicio: "",
          janelaFim: "",
          nomeCliente: "",
          endereco: "",
          latitude: "",
          longitude: ""
        },
        eventoRequest: {
          externalEventId: "",
          eventType: "PEDIDO_ENTREGUE",
          rotaId: "",
          entregaId: "",
          actorEntregadorId: "",
          motivo: "",
          cobrancaCancelamentoCentavos: ""
        },
        timelineRequest: {
          pedidoId: 1
        }
      }
    };
  }

  const storeApi = {
    createInitialAppState
  };

  if (typeof window !== "undefined") {
    window.AguaVivaStore = storeApi;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = storeApi;
  }
})();
