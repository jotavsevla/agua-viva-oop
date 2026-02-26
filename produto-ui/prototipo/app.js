const StorageModule = window.AguaVivaStorage || {};
const ApiModule = window.AguaVivaApi || {};
const AtendimentoModule = window.AguaVivaAtendimento || {};
const StoreModule = window.AguaVivaStore || {};
const DEFAULT_API_BASE = StorageModule.DEFAULT_API_BASE || "http://localhost:8082";
const OPERATIONAL_AUTO_REFRESH_MS = 5000;
let operationalRefreshTimerId = null;
let operationalRefreshInFlight = false;
let deferredViewRender = false;
let cityMapInstances = [];

function readStoredApiBase() {
  if (typeof StorageModule.readStoredApiBase === "function") {
    return StorageModule.readStoredApiBase();
  }
  try {
    const stored = window.localStorage.getItem("aguaVivaApiBaseUrl");
    return stored || DEFAULT_API_BASE;
  } catch (_) {
    return DEFAULT_API_BASE;
  }
}

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function escapeAttr(text) {
  return escapeHtml(text).replace(/`/g, "&#96;");
}

function formatNow() {
  return new Date().toLocaleTimeString("pt-BR");
}

function readAtendimentoFormState(formData) {
  if (typeof AtendimentoModule.readAtendimentoFormState === "function") {
    return AtendimentoModule.readAtendimentoFormState(formData);
  }
  return {
    telefone: String(formData.get("telefone") || "").trim(),
    quantidadeGaloes: String(formData.get("quantidadeGaloes") || "").trim(),
    atendenteId: String(formData.get("atendenteId") || "").trim(),
    origemCanal: String(formData.get("origemCanal") || "").trim().toUpperCase(),
    sourceEventId: String(formData.get("sourceEventId") || "").trim(),
    manualRequestId: String(formData.get("manualRequestId") || "").trim(),
    externalCallId: String(formData.get("externalCallId") || "").trim(),
    metodoPagamento: String(formData.get("metodoPagamento") || "").trim().toUpperCase(),
    janelaTipo: String(formData.get("janelaTipo") || "").trim().toUpperCase(),
    janelaInicio: String(formData.get("janelaInicio") || "").trim(),
    janelaFim: String(formData.get("janelaFim") || "").trim(),
    nomeCliente: String(formData.get("nomeCliente") || "").trim(),
    endereco: String(formData.get("endereco") || "").trim(),
    latitude: String(formData.get("latitude") || "").trim(),
    longitude: String(formData.get("longitude") || "").trim()
  };
}

function buildAtendimentoPayloadFromFormData(formData) {
  if (typeof AtendimentoModule.buildAtendimentoPayloadFromFormData === "function") {
    return AtendimentoModule.buildAtendimentoPayloadFromFormData(formData);
  }

  const state = readAtendimentoFormState(formData);
  const payload = {
    telefone: state.telefone,
    quantidadeGaloes: Number(state.quantidadeGaloes),
    atendenteId: Number(state.atendenteId)
  };
  [
    "origemCanal",
    "sourceEventId",
    "manualRequestId",
    "externalCallId",
    "metodoPagamento",
    "janelaTipo",
    "janelaInicio",
    "janelaFim",
    "nomeCliente",
    "endereco"
  ].forEach((field) => {
    if (state[field]) {
      payload[field] = state[field];
    }
  });

  const latitude = state.latitude ? Number(state.latitude.replace(",", ".")) : null;
  const longitude = state.longitude ? Number(state.longitude.replace(",", ".")) : null;
  if (Number.isFinite(latitude) && Number.isFinite(longitude)) {
    payload.latitude = latitude;
    payload.longitude = longitude;
  }
  return payload;
}

function normalizeTimelinePayload(payload) {
  const pedidoId = Number(payload?.pedidoId || 0);
  if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
    throw new Error("timeline invalida: pedidoId ausente");
  }

  const eventos = Array.isArray(payload?.eventos)
    ? payload.eventos.map((ev) => ({
        hora: String(ev?.timestamp || "").slice(11, 16) || "00:00",
        de: String(ev?.deStatus || ""),
        para: String(ev?.paraStatus || ""),
        origem: String(ev?.origem || ""),
        observacao: String(ev?.observacao || "")
      }))
    : [];

  return {
    pedidoId,
    status: String(payload?.statusAtual || payload?.status || "PENDENTE"),
    eventos
  };
}

function buildExecucaoPath(pedidoId) {
  const numeric = Number(pedidoId);
  if (!Number.isInteger(numeric) || numeric <= 0) {
    throw new Error("pedidoId invalido");
  }
  return `/api/pedidos/${numeric}/execucao`;
}

function buildTimelinePath(pedidoId) {
  const numeric = Number(pedidoId);
  if (!Number.isInteger(numeric) || numeric <= 0) {
    throw new Error("pedidoId invalido");
  }
  return `/api/pedidos/${numeric}/timeline`;
}

function createFallbackAppState(apiBaseUrl) {
  return {
    view: "pedidos",
    mode: "success",
    api: {
      baseUrl: apiBaseUrl,
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

const initialApiBase = readStoredApiBase();
const appState = typeof StoreModule.createInitialAppState === "function"
  ? StoreModule.createInitialAppState(initialApiBase)
  : createFallbackAppState(initialApiBase);

const viewTitle = document.getElementById("view-title");
const viewRoot = document.getElementById("view-root");
const metricsRoot = document.getElementById("metrics");
const clock = document.getElementById("clock");
const apiInput = document.getElementById("api-base");
const apiConnectButton = document.getElementById("api-connect");
const apiResetButton = document.getElementById("api-reset");
const apiStatus = document.getElementById("api-status");
const sidebarPhoneSearchRoot = document.getElementById("sidebar-phone-search");

const OPEN_PEDIDO_STATUSES = new Set(["PENDENTE", "CONFIRMADO", "EM_ROTA"]);
const CLOSED_PEDIDO_STATUSES = new Set(["ENTREGUE", "CANCELADO"]);

apiInput.value = appState.api.baseUrl;

function isEditableField(element) {
  if (!element || !(element instanceof HTMLElement)) {
    return false;
  }
  if (element.isContentEditable) {
    return true;
  }
  if (element instanceof HTMLInputElement
    || element instanceof HTMLTextAreaElement
    || element instanceof HTMLSelectElement) {
    return !element.disabled && !element.readOnly;
  }
  return false;
}

function isViewEditingActive() {
  const active = document.activeElement;
  if (!isEditableField(active)) {
    return false;
  }
  return viewRoot.contains(active);
}

function requestViewRender(options = {}) {
  if (options.force !== true && isViewEditingActive()) {
    deferredViewRender = true;
    return;
  }
  deferredViewRender = false;
  render();
}

function flushDeferredViewRender() {
  if (!deferredViewRender) {
    return;
  }
  if (isViewEditingActive()) {
    return;
  }
  deferredViewRender = false;
  render();
}

function tonePill(label, tone) {
  return `<span class="pill ${tone}">${escapeHtml(label)}</span>`;
}

function statusPill(status) {
  const normalized = String(status || "").toUpperCase();
  const tone = normalized === "ENTREGUE"
    ? "ok"
    : normalized === "CANCELADO" || normalized === "FALHOU"
      ? "danger"
      : normalized === "EM_ROTA" || normalized === "PENDENTE" || normalized === "CONFIRMADO"
        ? "warn"
        : "info";
  return `<span class="pill ${tone}">${escapeHtml(normalized || "-")}</span>`;
}

function atendenteActionHintByPedidoStatus(status) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "PENDENTE") {
    return "Conferir dados e priorizar despacho";
  }
  if (normalized === "CONFIRMADO") {
    return "Encaminhar para rota planejada";
  }
  if (normalized === "EM_ROTA") {
    return "Acompanhar execucao e evitar duplicidade";
  }
  if (normalized === "ENTREGUE" || normalized === "CANCELADO") {
    return "Sem acao pendente";
  }
  return "Validar timeline e estado atual";
}

function rotaActionHintByStatus(status) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "PLANEJADA") {
    return "Pode iniciar rota pronta";
  }
  if (normalized === "EM_ANDAMENTO") {
    return "Monitorar entregas em execucao";
  }
  if (normalized === "CONCLUIDA") {
    return "Sem acao pendente";
  }
  return "Atualizar painel antes de operar";
}

function renderResultBox(title, result) {
  if (!result) {
    return "";
  }

  const source = result.source || "api real";
  const payload = result.payload || {};
  const hasError = Boolean(payload?.erro || payload?.error);
  return `
    <details class="result-box"${hasError ? " open" : ""}>
      <summary class="diag-summary">
        <strong>${escapeHtml(title)}</strong> · ${tonePill(source, hasError ? "danger" : "ok")}
      </summary>
      <pre class="mono">${escapeHtml(JSON.stringify(result.payload, null, 2))}</pre>
    </details>
  `;
}

function normalizePhoneDigits(value) {
  return String(value || "").replace(/\D+/g, "");
}

function formatPhoneForDisplay(value) {
  const digits = normalizePhoneDigits(value);
  if (digits.length === 11) {
    return `(${digits.slice(0, 2)}) ${digits.slice(2, 7)}-${digits.slice(7)}`;
  }
  if (digits.length === 10) {
    return `(${digits.slice(0, 2)}) ${digits.slice(2, 6)}-${digits.slice(6)}`;
  }
  const trimmed = String(value || "").trim();
  return trimmed || "-";
}

function ensureHandoffState() {
  if (!appState.handoff || typeof appState.handoff !== "object") {
    appState.handoff = {
      atendimentosSessao: [],
      focoPedidoId: null,
      ultimoEntregadorId: null
    };
  }
  if (!Array.isArray(appState.handoff.atendimentosSessao)) {
    appState.handoff.atendimentosSessao = [];
  }
  if (!Object.prototype.hasOwnProperty.call(appState.handoff, "focoPedidoId")) {
    appState.handoff.focoPedidoId = null;
  }
  if (!Object.prototype.hasOwnProperty.call(appState.handoff, "ultimoEntregadorId")) {
    appState.handoff.ultimoEntregadorId = null;
  }
  return appState.handoff;
}

function ensureAtendenteState() {
  if (!appState.atendente || typeof appState.atendente !== "object") {
    appState.atendente = {
      buscaTelefone: {
        telefone: "",
        telefoneNormalizado: "",
        atualizadaEm: null
      },
      trilhaSessao: []
    };
  }
  if (!appState.atendente.buscaTelefone || typeof appState.atendente.buscaTelefone !== "object") {
    appState.atendente.buscaTelefone = {
      telefone: "",
      telefoneNormalizado: "",
      atualizadaEm: null
    };
  }
  if (!Array.isArray(appState.atendente.trilhaSessao)) {
    appState.atendente.trilhaSessao = [];
  }
  return appState.atendente;
}

function pushTrilhaSessao(tipo, acao, detalhe, contexto) {
  const atendente = ensureAtendenteState();
  const tone = ["ok", "warn", "danger", "info"].includes(tipo) ? tipo : "info";
  const item = {
    quando: new Date().toISOString(),
    tipo: tone,
    acao: String(acao || "acao"),
    detalhe: String(detalhe || ""),
    contexto: contexto || null
  };
  atendente.trilhaSessao = [item, ...atendente.trilhaSessao].slice(0, 40);
}

function moveHandoffOrder(pedidoId, direction) {
  const numericPedidoId = Number(pedidoId);
  if (!Number.isInteger(numericPedidoId) || numericPedidoId <= 0) {
    return false;
  }

  const handoff = ensureHandoffState();
  const currentIndex = handoff.atendimentosSessao.findIndex(
    (item) => Number(item?.pedidoId || 0) === numericPedidoId
  );
  if (currentIndex < 0) {
    return false;
  }

  const delta = direction === "up" ? -1 : direction === "down" ? 1 : 0;
  if (delta === 0) {
    return false;
  }

  const nextIndex = currentIndex + delta;
  if (nextIndex < 0 || nextIndex >= handoff.atendimentosSessao.length) {
    return false;
  }

  const rows = [...handoff.atendimentosSessao];
  const [selected] = rows.splice(currentIndex, 1);
  rows.splice(nextIndex, 0, selected);
  handoff.atendimentosSessao = rows;
  handoff.focoPedidoId = numericPedidoId;
  pushTrilhaSessao(
    "info",
    "reorganizacao da fila",
    `Pedido #${numericPedidoId} movido para posicao ${nextIndex + 1}.`
  );
  return true;
}

function buildPedidoStatusIndex() {
  const mapa = new Map();
  buildPedidosRowsFromPainel(painelOrDefault()).forEach((item) => {
    const pedidoId = Number(item?.pedidoId || 0);
    if (Number.isInteger(pedidoId) && pedidoId > 0) {
      mapa.set(pedidoId, String(item?.status || "").toUpperCase());
    }
  });

  const timelinePayload = appState.apiResults?.timeline?.payload;
  const timelinePedidoId = Number(timelinePayload?.pedidoId || 0);
  if (Number.isInteger(timelinePedidoId) && timelinePedidoId > 0) {
    mapa.set(timelinePedidoId, String(timelinePayload?.status || "").toUpperCase());
  }

  return mapa;
}

function resolvePedidoStatus(pedidoId, pedidoStatusIndex) {
  const numeric = Number(pedidoId);
  if (!Number.isInteger(numeric) || numeric <= 0) {
    return "INVALIDO";
  }
  const map = pedidoStatusIndex || buildPedidoStatusIndex();
  return String(map.get(numeric) || "SEM_VISIBILIDADE");
}

function isPedidoStatusOpen(status) {
  return OPEN_PEDIDO_STATUSES.has(String(status || "").toUpperCase());
}

function findOpenPedidoForPhone(telefoneNormalizado, pedidoStatusIndex) {
  const normalized = normalizePhoneDigits(telefoneNormalizado);
  if (!normalized) {
    return null;
  }
  const handoff = ensureHandoffState();
  const map = pedidoStatusIndex || buildPedidoStatusIndex();
  const rows = handoff.atendimentosSessao.filter((item) => item?.telefoneNormalizado === normalized);
  for (const item of rows) {
    const pedidoId = Number(item?.pedidoId || 0);
    if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
      continue;
    }
    const status = resolvePedidoStatus(pedidoId, map);
    if (isPedidoStatusOpen(status)) {
      return {
        pedidoId,
        status,
        item
      };
    }
  }
  return null;
}

function validateAtendimentoPreflight(payload) {
  const telefoneNormalizado = normalizePhoneDigits(payload?.telefone);
  if (!telefoneNormalizado) {
    return {
      ok: false,
      motivo: "Telefone invalido para atendimento."
    };
  }

  const pedidoStatusIndex = buildPedidoStatusIndex();
  const aberto = findOpenPedidoForPhone(telefoneNormalizado, pedidoStatusIndex);
  if (!aberto) {
    return { ok: true };
  }

  const externalCallId = String(payload?.externalCallId || payload?.manualRequestId || payload?.sourceEventId || "");
  const externalOpen = String(aberto.item?.externalCallId || "");
  if (externalCallId && externalOpen && externalCallId === externalOpen) {
    return { ok: true };
  }

  return {
    ok: false,
    motivo: `Cliente ja possui pedido aberto #${aberto.pedidoId} (${aberto.status}). Concluir ou evoluir esse pedido antes de criar outro.`,
    pedidoId: aberto.pedidoId
  };
}

function validateEventoManualPreflight(payload) {
  const eventType = String(payload?.eventType || "").toUpperCase();
  const painel = painelOrDefault();
  const layerSnapshot = buildFleetLayerSnapshot(painel);
  const allowedEventTypes = new Set(["ROTA_INICIADA", "PEDIDO_ENTREGUE", "PEDIDO_FALHOU", "PEDIDO_CANCELADO"]);
  if (!eventType) {
    return { ok: false, motivo: "Tipo de evento obrigatorio." };
  }
  if (!allowedEventTypes.has(eventType)) {
    return { ok: false, motivo: `Tipo de evento ${eventType} nao permitido para operacao manual.` };
  }

  if (eventType === "ROTA_INICIADA") {
    if (layerSnapshot.hasAnomaly) {
      return {
        ok: false,
        motivo: `Camadas inconsistentes no painel (${layerSnapshot.anomalyMessages.join(" ")}). Corrija antes de iniciar rota manualmente.`
      };
    }

    if (!layerSnapshot.secundaria) {
      return {
        ok: false,
        motivo: "Nao existe rota da frota SECUNDARIA pronta para iniciar neste painel."
      };
    }

    const rotaId = Number(payload?.rotaId || 0);
    if (!Number.isInteger(rotaId) || rotaId <= 0) {
      return { ok: false, motivo: "ROTA_INICIADA exige rotaId valido." };
    }
    const rotaSecundariaId = Number(layerSnapshot.secundaria?.rotaId || 0);
    if (rotaId !== rotaSecundariaId) {
      return {
        ok: false,
        motivo: `ROTA_INICIADA so pode atuar na frota SECUNDARIA ativa (rota ${rotaSecundariaId}).`
      };
    }
    return { ok: true };
  }

  const entregaId = Number(payload?.entregaId || 0);
  if (!Number.isInteger(entregaId) || entregaId <= 0) {
    return { ok: false, motivo: `${eventType} exige entregaId valido.` };
  }

  const entregaAtiva = (Array.isArray(painel.filas?.emRotaPrimaria) ? painel.filas.emRotaPrimaria : [])
    .find((item) => Number(item?.entregaId) === entregaId);
  if (!entregaAtiva) {
    return {
      ok: false,
      motivo: `Entrega ${entregaId} nao aparece em rota primaria ativa. Operacao bloqueada para evitar quebra de regra.`
    };
  }

  if (layerSnapshot.hasAnomaly) {
    return {
      ok: false,
      motivo: `Camadas inconsistentes no painel (${layerSnapshot.anomalyMessages.join(" ")}). Evento terminal bloqueado para evitar quebra de regra.`
    };
  }

  const rotaPrimariaId = Number(layerSnapshot.primaria?.rotaId || 0);
  const rotaDaEntrega = Number(entregaAtiva?.rotaId || 0);
  if (rotaPrimariaId > 0 && rotaDaEntrega > 0 && rotaDaEntrega !== rotaPrimariaId) {
    return {
      ok: false,
      motivo: `Entrega ${entregaId} pertence a rota ${rotaDaEntrega}, mas a frota PRIMARIA ativa e rota ${rotaPrimariaId}. Operacao bloqueada.`
    };
  }

  const statusEntrega = String(entregaAtiva?.statusEntrega || "").toUpperCase();
  if (eventType !== "ROTA_INICIADA" && statusEntrega !== "EM_EXECUCAO") {
    return {
      ok: false,
      motivo: `Entrega ${entregaId} esta em ${statusEntrega || "estado desconhecido"}; evento terminal so permitido em EM_EXECUCAO.`
    };
  }

  return { ok: true };
}

function formatHandoffTime(isoTimestamp) {
  if (!isoTimestamp) {
    return "--:--:--";
  }
  const date = new Date(isoTimestamp);
  if (Number.isNaN(date.getTime())) {
    return "--:--:--";
  }
  return date.toLocaleTimeString("pt-BR");
}

function pushAtendimentoHandoff(resultPayload, requestPayload) {
  const handoff = ensureHandoffState();
  const pedidoId = Number(resultPayload?.pedidoId || 0);
  if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
    return;
  }

  const clienteId = Number(resultPayload?.clienteId || 0);
  const metodoPagamento = String(requestPayload?.metodoPagamento || "NAO_INFORMADO");
  const telefoneRaw = String(requestPayload?.telefone || "");
  const entry = {
    pedidoId,
    clienteId: Number.isInteger(clienteId) && clienteId > 0 ? clienteId : null,
    telefone: telefoneRaw,
    telefoneNormalizado: normalizePhoneDigits(telefoneRaw),
    externalCallId: String(
      requestPayload?.externalCallId || requestPayload?.manualRequestId || requestPayload?.sourceEventId || ""
    ),
    origemCanal: String(requestPayload?.origemCanal || "MANUAL"),
    quantidadeGaloes: Number(requestPayload?.quantidadeGaloes || 0),
    janelaTipo: String(requestPayload?.janelaTipo || "ASAP"),
    metodoPagamento,
    idempotente: Boolean(resultPayload?.idempotente),
    criadoEm: new Date().toISOString()
  };

  const withoutSamePedido = handoff.atendimentosSessao.filter((item) => Number(item?.pedidoId) !== pedidoId);
  handoff.atendimentosSessao = [entry, ...withoutSamePedido].slice(0, 20);
  handoff.focoPedidoId = pedidoId;
}

function renderAtendimentoHandoffSection() {
  const handoff = ensureHandoffState();
  const items = handoff.atendimentosSessao;
  if (items.length === 0) {
    return `
      <div class="result-box" style="margin-top: 0.75rem;">
        <p><strong>Handoff Atendimento -> Despacho</strong></p>
        <p>Nenhum atendimento confirmado nesta sessao.</p>
      </div>
    `;
  }

  const pedidoStatusIndex = buildPedidoStatusIndex();
  const rows = items.map((item, index) => {
    const pedidoId = Number(item?.pedidoId || 0);
    const status = resolvePedidoStatus(pedidoId, pedidoStatusIndex);
    const focus = Number(handoff.focoPedidoId || 0) === pedidoId;
    const focusTag = focus ? tonePill("em foco", "info") : "";
    const isTop = index === 0;
    const isBottom = index === items.length - 1;
    return `
      <tr>
        <td class="mono">${escapeHtml(String(index + 1))}</td>
        <td class="mono">#${escapeHtml(String(pedidoId))}</td>
        <td>${statusPill(status)}</td>
        <td class="mono">${escapeHtml(formatPhoneForDisplay(item?.telefone || ""))}</td>
        <td>${escapeHtml(atendenteActionHintByPedidoStatus(status))}</td>
        <td>
          <div class="reorder-actions">
            <button
              class="btn btn-tiny"
              type="button"
              data-action="handoff-move"
              data-direction="up"
              data-pedido-id="${escapeAttr(String(pedidoId))}"
              ${isTop ? "disabled" : ""}
            >
              Subir
            </button>
            <button
              class="btn btn-tiny"
              type="button"
              data-action="handoff-move"
              data-direction="down"
              data-pedido-id="${escapeAttr(String(pedidoId))}"
              ${isBottom ? "disabled" : ""}
            >
              Descer
            </button>
          </div>
        </td>
        <td>
          <button
            class="btn"
            type="button"
            data-action="go-to-despacho"
            data-pedido-id="${escapeAttr(String(pedidoId))}"
          >
            Despacho
          </button>
          <button
            class="btn"
            type="button"
            data-action="handoff-open-timeline"
            data-pedido-id="${escapeAttr(String(pedidoId))}"
          >
            Timeline
          </button>
          ${focusTag}
        </td>
      </tr>
    `;
  }).join("");

  return `
    <div class="result-box" style="margin-top: 0.75rem;">
      <p><strong>Handoff Atendimento -> Despacho</strong></p>
      <p class="mono">A reorganizacao da fila e local da sessao; nao quebra regra de negocio no backend.</p>
      <div class="table-scroll table-scroll-vertical" style="margin-top: 0.5rem;">
        <table class="entity-table compact">
          <thead>
            <tr>
              <th>Pos</th>
              <th>Pedido</th>
              <th>Status</th>
              <th>Telefone</th>
              <th>Proximo passo</th>
              <th>Reordenar</th>
              <th>Atalhos</th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </div>
  `;
}

function renderDespachoHandoffSection() {
  const handoff = ensureHandoffState();
  if (!Array.isArray(handoff.atendimentosSessao) || handoff.atendimentosSessao.length === 0) {
    return "";
  }

  const focoPedidoId = Number(handoff.focoPedidoId || 0);
  const foco = handoff.atendimentosSessao.find((item) => Number(item?.pedidoId || 0) === focoPedidoId)
    || handoff.atendimentosSessao[0];
  const pedido = Number(foco?.pedidoId || 0);
  const cliente = Number(foco?.clienteId || 0);
  const pagamento = String(foco?.metodoPagamento || "-");

  return `
    <section class="panel" style="margin-top: 1rem;">
      <div class="panel-header">
        <h3>Handoff ativo de atendimento</h3>
        <span class="pill info">sessao atual</span>
      </div>
      <p class="mono">
        Pedido #${escapeHtml(String(pedido > 0 ? pedido : "-"))}
        · Cliente ${escapeHtml(String(cliente > 0 ? cliente : "-"))}
        · Pagamento ${escapeHtml(pagamento)}
        · ${escapeHtml(formatHandoffTime(foco?.criadoEm))}
      </p>
      <div class="form-grid" style="margin-top: 0.5rem;">
        <button
          class="btn"
          type="button"
          data-action="go-to-pedidos"
          data-pedido-id="${escapeAttr(String(pedido > 0 ? pedido : ""))}"
        >
          Voltar para atendimento
        </button>
      </div>
    </section>
  `;
}

function buildHistoricoPorTelefone(telefoneNormalizado) {
  const normalized = normalizePhoneDigits(telefoneNormalizado);
  if (!normalized) {
    return {
      telefoneNormalizado: "",
      historico: [],
      pedidoAberto: null,
      ultimoPedido: null
    };
  }

  const handoff = ensureHandoffState();
  const pedidoStatusIndex = buildPedidoStatusIndex();
  const historico = handoff.atendimentosSessao
    .filter((item) => item?.telefoneNormalizado === normalized)
    .map((item) => {
      const pedidoId = Number(item?.pedidoId || 0);
      const status = resolvePedidoStatus(pedidoId, pedidoStatusIndex);
      return {
        ...item,
        pedidoId,
        statusAtual: status
      };
    })
    .sort((a, b) => new Date(b?.criadoEm || 0).getTime() - new Date(a?.criadoEm || 0).getTime());

  const pedidoAberto = historico.find((item) => isPedidoStatusOpen(item?.statusAtual || ""));
  const pedidosConcluidos = historico.filter((item) => CLOSED_PEDIDO_STATUSES.has(String(item?.statusAtual || ""))).length;
  return {
    telefoneNormalizado: normalized,
    historico,
    pedidoAberto: pedidoAberto || null,
    ultimoPedido: historico[0] || null,
    pedidosConcluidos
  };
}

function renderSidebarTools() {
  if (!sidebarPhoneSearchRoot) {
    return;
  }

  const atendente = ensureAtendenteState();
  const busca = atendente.buscaTelefone || {};
  const historicoTelefone = buildHistoricoPorTelefone(busca.telefoneNormalizado || "");
  const temBusca = Boolean(busca.telefoneNormalizado);
  const historico = historicoTelefone.historico || [];
  const pedidoAberto = historicoTelefone.pedidoAberto;
  const ultimoPedido = historicoTelefone.ultimoPedido;

  const historicoRows = historico.map((item) => `
    <tr>
      <td class="mono">#${escapeHtml(String(item?.pedidoId || "-"))}</td>
      <td>${statusPill(item?.statusAtual || "-")}</td>
      <td class="mono">${escapeHtml(formatHandoffTime(item?.criadoEm))}</td>
      <td>${escapeHtml(String(item?.metodoPagamento || "-"))}</td>
      <td>
        <button
          type="button"
          class="btn"
          data-action="usar-pedido-timeline"
          data-pedido-id="${escapeAttr(String(item?.pedidoId || ""))}"
        >
          Timeline
        </button>
      </td>
    </tr>
  `).join("");

  const trilhaRows = atendente.trilhaSessao
    .slice(0, 10)
    .map((item) => `
      <li class="sidebar-log-item">
        <p class="mono">${escapeHtml(formatHandoffTime(item?.quando))} · ${tonePill(String(item?.acao || "-"), String(item?.tipo || "info"))}</p>
        <p>${escapeHtml(String(item?.detalhe || "-"))}</p>
      </li>
    `)
    .join("");

  sidebarPhoneSearchRoot.innerHTML = `
    <div class="sidebar-tools-box">
      <p class="legend-title">Busca por telefone</p>
      <form id="sidebar-phone-search-form" class="form-grid">
        <div class="form-row">
          <label for="sidebar-phone-input">Telefone cliente</label>
          <input
            id="sidebar-phone-input"
            name="telefone"
            placeholder="(38) 99876-8001"
            value="${escapeAttr(String(busca.telefone || ""))}"
          />
        </div>
        <div class="sidebar-actions">
          <button class="btn" type="submit">Pesquisar</button>
          <button class="btn" type="button" data-action="limpar-busca-telefone">Limpar</button>
        </div>
      </form>
      ${temBusca
        ? `
          <div class="result-box" style="margin-top: 0.5rem;">
            <p><strong>Resultado da busca</strong></p>
            <p class="mono">Telefone: ${escapeHtml(formatPhoneForDisplay(busca.telefoneNormalizado || busca.telefone || ""))}</p>
            <p class="mono">Historico na sessao: ${escapeHtml(String(historico.length))}</p>
            <p class="mono">Pedidos concluidos na sessao: ${escapeHtml(String(historicoTelefone.pedidosConcluidos || 0))}</p>
            <p class="mono">
              Ultimo pedido: ${ultimoPedido ? `#${escapeHtml(String(ultimoPedido.pedidoId))} · ${escapeHtml(String(ultimoPedido.statusAtual || "-"))}` : "-"}
            </p>
            <p class="mono">
              Pedido em aberto: ${pedidoAberto ? `SIM (#${escapeHtml(String(pedidoAberto.pedidoId))} · ${escapeHtml(String(pedidoAberto.statusAtual || "-"))})` : "NAO"}
            </p>
            ${historico.length > 0
              ? `
                <div class="table-scroll" style="margin-top: 0.5rem;">
                  <table class="entity-table compact">
                    <thead>
                      <tr>
                        <th>Pedido</th>
                        <th>Status</th>
                        <th>Hora</th>
                        <th>Pgto</th>
                        <th>Acao</th>
                      </tr>
                    </thead>
                    <tbody>${historicoRows}</tbody>
                  </table>
                </div>
              `
              : "<p class=\"mono\">Nenhum pedido desse telefone registrado nesta sessao.</p>"}
          </div>
        `
        : "<p class=\"ops-empty\">Pesquisa local por historico da sessao atual da atendente.</p>"}
    </div>
    <div class="sidebar-tools-box" style="margin-top: 0.75rem;">
      <p class="legend-title">Trilha de validacao manual</p>
      <p class="ops-empty">Operacoes invalidas sao bloqueadas antes da API.</p>
      <ul class="sidebar-log-list">${trilhaRows || "<li class=\"ops-empty\">Sem ocorrencias na sessao.</li>"}</ul>
    </div>
  `;
}

function handleSidebarPhoneSearchSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const telefone = String(formData.get("telefone") || "").trim();
  const normalized = normalizePhoneDigits(telefone);
  const atendente = ensureAtendenteState();
  atendente.buscaTelefone.telefone = telefone;
  atendente.buscaTelefone.telefoneNormalizado = normalized;
  atendente.buscaTelefone.atualizadaEm = new Date().toISOString();
  if (!normalized) {
    pushTrilhaSessao("warn", "busca telefone", "Telefone vazio ou invalido para pesquisa.");
  } else {
    pushTrilhaSessao("info", "busca telefone", `Pesquisa por telefone ${formatPhoneForDisplay(normalized)}.`);
  }
  render();
}

function bindSidebarEvents() {
  if (!sidebarPhoneSearchRoot) {
    return;
  }

  const form = sidebarPhoneSearchRoot.querySelector("#sidebar-phone-search-form");
  if (form) {
    form.addEventListener("submit", handleSidebarPhoneSearchSubmit);
  }

  const clearButton = sidebarPhoneSearchRoot.querySelector('[data-action="limpar-busca-telefone"]');
  if (clearButton) {
    clearButton.addEventListener("click", () => {
      const atendente = ensureAtendenteState();
      atendente.buscaTelefone = {
        telefone: "",
        telefoneNormalizado: "",
        atualizadaEm: new Date().toISOString()
      };
      render();
    });
  }

  sidebarPhoneSearchRoot.querySelectorAll('[data-action="usar-pedido-timeline"]').forEach((button) => {
    button.addEventListener("click", () => {
      const pedidoId = Number(button.dataset.pedidoId || 0);
      if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
        return;
      }
      appState.examples.timelineRequest = { pedidoId };
      ensureHandoffState().focoPedidoId = pedidoId;
      setView("pedidos");
    });
  });
}

function updateApiStatus() {
  if (appState.api.connected) {
    if (appState.api.lastError) {
      apiStatus.className = "pill warn";
      apiStatus.textContent = `API: conectada (parcial) · auto ${Math.round(OPERATIONAL_AUTO_REFRESH_MS / 1000)}s`;
      return;
    }
    apiStatus.className = "pill ok";
    apiStatus.textContent = `API: conectada · auto ${Math.round(OPERATIONAL_AUTO_REFRESH_MS / 1000)}s`;
    return;
  }

  if (appState.api.lastError) {
    apiStatus.className = "pill danger";
    apiStatus.textContent = "API: offline";
    return;
  }

  apiStatus.className = "pill warn";
  apiStatus.textContent = "API: pendente";
}

function applyApiBaseFromInput() {
  const nextBase = typeof StorageModule.sanitizeBaseUrl === "function"
    ? StorageModule.sanitizeBaseUrl(apiInput.value)
    : apiInput.value.trim().replace(/\/+$/, "");
  if (!nextBase) {
    return;
  }
  appState.api.baseUrl = nextBase;
  persistApiBase(nextBase);
}

function persistApiBase(baseUrl) {
  if (typeof StorageModule.persistApiBase === "function") {
    StorageModule.persistApiBase(baseUrl);
    return;
  }
  try {
    window.localStorage.setItem("aguaVivaApiBaseUrl", baseUrl);
  } catch (_) {
    // Sem persistencia local.
  }
}

function clearStoredApiBase() {
  if (typeof StorageModule.clearStoredApiBase === "function") {
    StorageModule.clearStoredApiBase();
    return;
  }
  try {
    window.localStorage.removeItem("aguaVivaApiBaseUrl");
  } catch (_) {
    // Sem persistencia local.
  }
}

async function requestApi(path, options = {}) {
  if (typeof ApiModule.requestJson === "function") {
    return ApiModule.requestJson(appState.api.baseUrl, path, options);
  }
  const url = `${appState.api.baseUrl}${path}`;
  const response = await fetch(url, {
    method: options.method || "GET",
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  });

  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = payload.erro || payload.message || `HTTP ${response.status}`;
    throw new Error(message);
  }

  return { payload };
}

async function checkHealth() {
  applyApiBaseFromInput();
  const preferredBase = appState.api.baseUrl;
  const candidateBases = [preferredBase];
  if (preferredBase !== DEFAULT_API_BASE) {
    candidateBases.push(DEFAULT_API_BASE);
  }
  const attempts = [];

  try {
    for (const base of candidateBases) {
      appState.api.baseUrl = base;
      apiInput.value = base;
      try {
        await requestApi("/health");
        await refreshOperationalReadModels();
        persistApiBase(base);
        return;
      } catch (error) {
        attempts.push(`${base}: ${error?.message || "falha desconhecida"}`);
      }
    }
    appState.api.baseUrl = preferredBase;
    apiInput.value = preferredBase;
    appState.api.connected = false;
    appState.api.lastError = attempts.length > 0
      ? `Falha ao conectar API (${attempts.join(" | ")})`
      : "Falha ao conectar API";
  } finally {
    appState.api.lastSyncAt = new Date().toISOString();
    updateApiStatus();
    requestViewRender();
  }
}

function collectEntregadorIdsFromReadModels(painelPayload, mapaPayload) {
  const ids = new Set();
  const rotasPainel = painelPayload?.rotas || {};
  const rotasMapa = Array.isArray(mapaPayload?.rotas) ? mapaPayload.rotas : [];

  [rotasPainel.emAndamento, rotasPainel.planejadas].forEach((list) => {
    (Array.isArray(list) ? list : []).forEach((item) => {
      const id = Number(item?.entregadorId || 0);
      if (Number.isInteger(id) && id > 0) {
        ids.add(id);
      }
    });
  });

  rotasMapa.forEach((rota) => {
    const id = Number(rota?.entregadorId || 0);
    if (Number.isInteger(id) && id > 0) {
      ids.add(id);
    }
  });

  return [...ids].sort((a, b) => a - b);
}

async function fetchFrotaRoteiros(entregadorIds) {
  if (!Array.isArray(entregadorIds) || entregadorIds.length === 0) {
    return [];
  }

  const roteiros = await Promise.all(
    entregadorIds.map(async (entregadorId) => {
      try {
        const response = await requestApi(`/api/entregadores/${entregadorId}/roteiro`);
        return response.payload;
      } catch (error) {
        return {
          entregadorId,
          rota: null,
          cargaRemanescente: 0,
          paradasPendentesExecucao: [],
          paradasConcluidas: [],
          erro: error?.message || "Falha ao consultar roteiro"
        };
      }
    })
  );

  return roteiros.sort((a, b) => Number(a?.entregadorId || 0) - Number(b?.entregadorId || 0));
}

async function refreshOperationalReadModels() {
  const [painelResult, eventosResult, mapaResult] = await Promise.allSettled([
    requestApi("/api/operacao/painel"),
    requestApi("/api/operacao/eventos?limite=50"),
    requestApi("/api/operacao/mapa")
  ]);

  const partialErrors = [];
  let loadedReadModels = 0;

  if (painelResult.status === "fulfilled") {
    appState.painel = painelResult.value.payload;
    loadedReadModels += 1;
  } else {
    partialErrors.push(`painel: ${painelResult.reason?.message || "falha"}`);
  }

  if (eventosResult.status === "fulfilled") {
    appState.eventosOperacionais = Array.isArray(eventosResult.value.payload?.eventos)
      ? eventosResult.value.payload.eventos
      : [];
    loadedReadModels += 1;
  } else {
    partialErrors.push(`eventos: ${eventosResult.reason?.message || "falha"}`);
  }

  if (mapaResult.status === "fulfilled") {
    appState.mapaOperacional = mapaResult.value.payload || null;
    loadedReadModels += 1;
  } else {
    partialErrors.push(`mapa: ${mapaResult.reason?.message || "falha"}`);
  }

  if (loadedReadModels === 0) {
    throw new Error(`Falha ao sincronizar read models (${partialErrors.join(" | ")})`);
  }

  const entregadorIds = collectEntregadorIdsFromReadModels(appState.painel, appState.mapaOperacional);
  appState.frotaRoteiros = await fetchFrotaRoteiros(entregadorIds);
  appState.api.connected = true;
  appState.api.lastError = partialErrors.length > 0
    ? `Read models parciais (${partialErrors.join(" | ")})`
    : null;
  appState.api.lastSyncAt = new Date().toISOString();
}

async function refreshOperationalReadModelsSafe() {
  if (operationalRefreshInFlight) {
    return;
  }

  operationalRefreshInFlight = true;
  try {
    await refreshOperationalReadModels();
  } catch (error) {
    appState.api.connected = false;
    appState.api.lastError = error?.message || "Falha ao sincronizar read models operacionais";
    appState.api.lastSyncAt = new Date().toISOString();
  } finally {
    updateApiStatus();
    requestViewRender();
    operationalRefreshInFlight = false;
  }
}

function painelOrDefault() {
  return appState.painel || {
    ambiente: "-",
    pedidosPorStatus: {
      pendente: 0,
      confirmado: 0,
      emRota: 0,
      entregue: 0,
      cancelado: 0
    },
    rotas: {
      emAndamento: [],
      planejadas: []
    },
    filas: {
      pendentesElegiveis: [],
      confirmadosSecundaria: [],
      emRotaPrimaria: []
    }
  };
}

function mapaOrDefault() {
  return appState.mapaOperacional || {
    ambiente: "-",
    deposito: null,
    rotas: []
  };
}

function toFiniteCoordinate(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function clampPercent(value) {
  return Math.min(97, Math.max(3, value));
}

function buildMapBounds(points) {
  let minLat = points[0].lat;
  let maxLat = points[0].lat;
  let minLon = points[0].lon;
  let maxLon = points[0].lon;

  for (const point of points) {
    minLat = Math.min(minLat, point.lat);
    maxLat = Math.max(maxLat, point.lat);
    minLon = Math.min(minLon, point.lon);
    maxLon = Math.max(maxLon, point.lon);
  }

  const latPadding = Math.max((maxLat - minLat) * 0.14, 0.0002);
  const lonPadding = Math.max((maxLon - minLon) * 0.14, 0.0002);

  minLat -= latPadding;
  maxLat += latPadding;
  minLon -= lonPadding;
  maxLon += lonPadding;

  const spanLat = Math.max(maxLat - minLat, 0.0001);
  const spanLon = Math.max(maxLon - minLon, 0.0001);

  return { minLat, minLon, spanLat, spanLon };
}

function projectMapPoint(bounds, lat, lon) {
  const x = ((lon - bounds.minLon) / bounds.spanLon) * 100;
  const y = 100 - ((lat - bounds.minLat) / bounds.spanLat) * 100;
  return {
    x: clampPercent(x),
    y: clampPercent(y)
  };
}

function toneForEntregaStatus(statusEntrega) {
  const normalized = String(statusEntrega || "").toUpperCase();
  if (normalized === "ENTREGUE") {
    return "ok";
  }
  if (normalized === "PENDENTE") {
    return "warn";
  }
  if (normalized === "CANCELADA" || normalized === "FALHOU") {
    return "danger";
  }
  return "info";
}

function colorForRota(index) {
  const palette = ["#4fc9dd", "#52d39c", "#f4b740", "#ef6d62", "#9ebdff", "#f5b5df"];
  return palette[index % palette.length];
}

function buildFallbackTrajeto(depositoLat, depositoLon, paradas) {
  const trajeto = [{ lat: depositoLat, lon: depositoLon }];
  for (const parada of paradas) {
    if (parada.lat === null || parada.lon === null) {
      continue;
    }
    trajeto.push({ lat: parada.lat, lon: parada.lon });
  }
  if (paradas.length > 0) {
    trajeto.push({ lat: depositoLat, lon: depositoLon });
  }
  return trajeto;
}

function destroyCityMaps() {
  if (!Array.isArray(cityMapInstances) || cityMapInstances.length === 0) {
    return;
  }
  cityMapInstances.forEach((map) => {
    try {
      map.remove();
    } catch (_) {
      // Ignora erro de cleanup.
    }
  });
  cityMapInstances = [];
}

function initCityMaps() {
  const L = window.L;
  if (!L || !(viewRoot instanceof HTMLElement)) {
    return;
  }

  const mapNodes = viewRoot.querySelectorAll(".city-map[data-city-map]");
  mapNodes.forEach((node) => {
    const payloadRaw = node.getAttribute("data-city-map");
    if (!payloadRaw) {
      return;
    }

    let payload;
    try {
      payload = JSON.parse(payloadRaw);
    } catch (_) {
      return;
    }

    const depositoLat = toFiniteCoordinate(payload?.deposito?.lat);
    const depositoLon = toFiniteCoordinate(payload?.deposito?.lon);
    if (depositoLat === null || depositoLon === null) {
      return;
    }

    const map = L.map(node, {
      zoomControl: true,
      attributionControl: true
    });
    cityMapInstances.push(map);

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap"
    }).addTo(map);

    const depositoLatLng = [depositoLat, depositoLon];
    const allLatLngs = [depositoLatLng];

    L.circleMarker(depositoLatLng, {
      radius: 8,
      color: "#4fc9dd",
      fillColor: "#4fc9dd",
      fillOpacity: 0.9,
      weight: 2
    })
      .addTo(map)
      .bindTooltip("DEP");

    const rotas = Array.isArray(payload?.rotas) ? payload.rotas : [];
    rotas.forEach((rota, index) => {
      const color = colorForRota(index);
      const paradas = Array.isArray(rota?.paradas) ? rota.paradas : [];
      const stopLatLngs = [];

      paradas.forEach((parada) => {
        const lat = toFiniteCoordinate(parada?.lat);
        const lon = toFiniteCoordinate(parada?.lon);
        if (lat === null || lon === null) {
          return;
        }
        const latLng = [lat, lon];
        allLatLngs.push(latLng);
        stopLatLngs.push(latLng);

        const statusTone = toneForEntregaStatus(parada?.statusEntrega);
        const markerColor = statusTone === "ok"
          ? "#52d39c"
          : statusTone === "danger"
            ? "#ef6d62"
            : statusTone === "warn"
              ? "#f4b740"
              : color;
        const pedidoId = Number(parada?.pedidoId || 0);
        const entregaId = Number(parada?.entregaId || 0);
        const statusEntrega = String(parada?.statusEntrega || "-");
        const rotaId = Number(rota?.rotaId || 0);

        L.circleMarker(latLng, {
          radius: 7,
          color: markerColor,
          fillColor: markerColor,
          fillOpacity: 0.92,
          weight: 2
        })
          .addTo(map)
          .bindTooltip(`#${pedidoId > 0 ? pedidoId : "?"}`)
          .bindPopup(
            `Pedido #${pedidoId > 0 ? pedidoId : "?"}<br>` +
            `Entrega ${entregaId > 0 ? entregaId : "-"}<br>` +
            `Status: ${statusEntrega}<br>` +
            `Rota: R${rotaId > 0 ? rotaId : "?"}`
          );
      });

      let polylinePoints = [];
      const trajeto = Array.isArray(rota?.trajeto) ? rota.trajeto : [];
      trajeto.forEach((ponto) => {
        const lat = toFiniteCoordinate(ponto?.lat);
        const lon = toFiniteCoordinate(ponto?.lon);
        if (lat === null || lon === null) {
          return;
        }
        const latLng = [lat, lon];
        polylinePoints.push(latLng);
      });

      if (polylinePoints.length < 2) {
        polylinePoints = [depositoLatLng, ...stopLatLngs];
        if (stopLatLngs.length > 0) {
          polylinePoints.push(depositoLatLng);
        }
      }

      if (polylinePoints.length > 1) {
        allLatLngs.push(...polylinePoints);
        L.polyline(polylinePoints, {
          color,
          weight: 4,
          opacity: 0.78
        }).addTo(map);
      }
    });

    if (allLatLngs.length > 1) {
      map.fitBounds(allLatLngs, { padding: [24, 24], maxZoom: 15 });
    } else {
      map.setView(depositoLatLng, 13);
    }
    window.setTimeout(() => map.invalidateSize(), 0);
  });
}

function stableHashSeed(value) {
  const text = String(value || "");
  let hash = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function offsetMapPoint(basePoint, key) {
  const seed = stableHashSeed(key);
  const angle = (seed % 360) * (Math.PI / 180);
  const radius = 0.85 + ((seed >>> 5) % 30) / 100;
  const shiftedX = clampPercent(basePoint.x + Math.cos(angle) * radius);
  const shiftedY = clampPercent(basePoint.y + Math.sin(angle) * radius);
  return { x: shiftedX, y: shiftedY };
}

function renderMapaOperacional() {
  if (!appState.api.connected && !appState.mapaOperacional) {
    return `
      <section class="notice error">
        <h3>Mapa operacional indisponivel</h3>
        <p>A API nao respondeu em <span class="mono">${escapeHtml(appState.api.baseUrl)}</span>.</p>
        <p>Confirme a API base (normalmente <span class="mono">${escapeHtml(DEFAULT_API_BASE)}</span>) e clique em "Testar conexao".</p>
        ${appState.api.lastError ? `<p class="mono">${escapeHtml(appState.api.lastError)}</p>` : ""}
      </section>
    `;
  }

  const mapa = mapaOrDefault();
  const depositoLat = toFiniteCoordinate(mapa?.deposito?.lat);
  const depositoLon = toFiniteCoordinate(mapa?.deposito?.lon);
  if (depositoLat === null || depositoLon === null) {
    return `
      <section class="notice">
        <p>Sem coordenadas de deposito para montar o mapa.</p>
      </section>
    `;
  }

  const rotasRaw = Array.isArray(mapa?.rotas) ? mapa.rotas : [];
  const points = [{ lat: depositoLat, lon: depositoLon }];
  const rotas = rotasRaw
    .map((rota) => {
      const paradasRaw = Array.isArray(rota?.paradas) ? rota.paradas : [];
      const paradas = paradasRaw
        .map((parada) => ({
          pedidoId: Number(parada?.pedidoId),
          entregaId: Number(parada?.entregaId),
          ordemNaRota: Number(parada?.ordemNaRota),
          statusEntrega: String(parada?.statusEntrega || ""),
          quantidadeGaloes: Number(parada?.quantidadeGaloes),
          lat: toFiniteCoordinate(parada?.lat),
          lon: toFiniteCoordinate(parada?.lon)
        }))
        .filter((parada) => parada.lat !== null && parada.lon !== null)
        .sort((a, b) => a.ordemNaRota - b.ordemNaRota);
      const trajetoRaw = Array.isArray(rota?.trajeto) ? rota.trajeto : [];
      let trajeto = trajetoRaw
        .map((ponto) => ({
          lat: toFiniteCoordinate(ponto?.lat),
          lon: toFiniteCoordinate(ponto?.lon)
        }))
        .filter((ponto) => ponto.lat !== null && ponto.lon !== null);
      if (trajeto.length < 2) {
        trajeto = buildFallbackTrajeto(depositoLat, depositoLon, paradas);
      }
      for (const ponto of trajeto) {
        points.push({ lat: ponto.lat, lon: ponto.lon });
      }
      return {
        rotaId: Number(rota?.rotaId || 0),
        entregadorId: Number(rota?.entregadorId || 0),
        statusRota: String(rota?.statusRota || ""),
        camada: String(rota?.camada || ""),
        paradas,
        trajeto
      };
    })
    .filter((rota) => Number.isInteger(rota.rotaId) && rota.rotaId > 0);

  const bounds = buildMapBounds(points);
  const depositoPoint = projectMapPoint(bounds, depositoLat, depositoLon);
  const cityMapPayload = escapeAttr(JSON.stringify({
    deposito: { lat: depositoLat, lon: depositoLon },
    rotas
  }));

  const renderedRotas = rotas.map((rota, index) => {
    const color = colorForRota(index);
    const polylinePoints = rota.trajeto.map((ponto) => {
      const point = projectMapPoint(bounds, ponto.lat, ponto.lon);
      return `${point.x.toFixed(2)},${point.y.toFixed(2)}`;
    });
    const polylinePointsValue = polylinePoints.join(" ");

    const markers = rota.paradas
      .map((parada) => {
        const basePoint = projectMapPoint(bounds, parada.lat, parada.lon);
        const point = offsetMapPoint(
          basePoint,
          `${rota.rotaId}-${rota.entregadorId}-${parada.pedidoId}-${parada.entregaId}-${parada.ordemNaRota}`
        );
        const tone = toneForEntregaStatus(parada.statusEntrega);
        const title = `Pedido ${parada.pedidoId} · Entrega ${parada.entregaId} · ${parada.statusEntrega} · Rota ${rota.rotaId}`;
        const markerLabel = Number.isInteger(parada.pedidoId) && parada.pedidoId > 0
          ? `#${parada.pedidoId}`
          : `P${parada.ordemNaRota}`;
        return `
          <div
            class="stop ${tone} pedido-stop"
            style="left:${point.x.toFixed(2)}%;top:${point.y.toFixed(2)}%;"
            title="${escapeAttr(title)}"
          >
            ${escapeHtml(markerLabel)}
          </div>
        `;
      })
      .join("");

    const legendTone = rota.statusRota === "EM_ANDAMENTO" ? "info" : "warn";
    const legendText = `R${rota.rotaId} · E${rota.entregadorId} · ${rota.camada}`;
    const hasAnyParada = rota.paradas.length > 0;
    const hasRoutePath = polylinePoints.length > 1;

    return {
      path: hasRoutePath
        ? `<polyline points="${polylinePointsValue}" fill="none" stroke="${color}" stroke-width="2.8" opacity="0.92" stroke-linecap="round" stroke-linejoin="round"></polyline>`
        : "",
      markers,
      legend: `<span class="pill ${legendTone} mono">${escapeHtml(legendText)}</span>`
    };
  });

  const pedidosNoMapa = rotas
    .flatMap((rota) => rota.paradas.map((parada) => ({
      pedidoId: parada.pedidoId,
      rotaId: rota.rotaId,
      ordemNaRota: parada.ordemNaRota,
      statusEntrega: parada.statusEntrega
    })))
    .sort((a, b) => Number(a.pedidoId) - Number(b.pedidoId));

  const resumoPontos = pedidosNoMapa
    .map((item) => {
      const tone = toneForEntregaStatus(item.statusEntrega);
      const pedidoLabel = Number.isInteger(item.pedidoId) && item.pedidoId > 0 ? `#${item.pedidoId}` : "#?";
      const resumo = `${pedidoLabel} · R${item.rotaId} · P${item.ordemNaRota}`;
      return `<span class="pill ${tone} mono">${escapeHtml(resumo)}</span>`;
    })
    .join("");

  const hasStops = pedidosNoMapa.length > 0;
  const legendHtml = renderedRotas.length > 0
    ? renderedRotas.map((rota) => rota.legend).join("")
    : `<span class="pill warn mono">Sem rotas mapeadas no momento</span>`;
  const mapOrdersHtml = hasStops
    ? `<div class="map-orders-pills">${resumoPontos}</div>`
    : `<p class="ops-empty">Sem pontos de pedidos com coordenadas no momento.</p>`;

  return `
    <div class="city-map-box">
      <div class="city-map-head">
        <p class="mono">Mapa da cidade (OpenStreetMap)</p>
        <span class="pill info mono">Camada real</span>
      </div>
      <div class="city-map" data-city-map="${cityMapPayload}"></div>
    </div>
    <div class="city-map-head" style="margin-top: 0.75rem;">
      <p class="mono">Mapa esquematico operacional</p>
      <span class="pill warn mono">Camada de apoio</span>
    </div>
    <div class="map-box">
      <div class="map-grid"></div>
      <svg class="map-route" viewBox="0 0 100 100" preserveAspectRatio="none" aria-label="rotas operacionais">
        ${renderedRotas.map((rota) => rota.path).join("")}
      </svg>
      <div
        class="stop info dep"
        style="left:${depositoPoint.x.toFixed(2)}%;top:${depositoPoint.y.toFixed(2)}%;"
        title="Deposito"
      >
        DEP
      </div>
      ${renderedRotas.map((rota) => rota.markers).join("")}
      ${!hasStops ? '<div class="map-empty-overlay"><span class="pill warn mono">Sem paradas com coordenadas</span></div>' : ""}
    </div>
    <div class="map-legend">${legendHtml}</div>
    <div class="map-orders-list">
      <p class="mono">Pontos de pedidos no mapa: ${escapeHtml(String(pedidosNoMapa.length))}</p>
      ${mapOrdersHtml}
    </div>
  `;
}

function buildPedidosRowsFromPainel(painel) {
  const rows = [];

  (painel.filas?.emRotaPrimaria || []).forEach((item) => {
    rows.push({
      pedidoId: item.pedidoId,
      cliente: "-",
      status: "EM_ROTA",
      idempotente: false,
      eventos: [
        {
          hora: "--:--",
          de: "CONFIRMADO",
          para: item.statusEntrega || "EM_EXECUCAO",
          origem: `Rota ${item.rotaId} / Entregador ${item.entregadorId}`
        }
      ]
    });
  });

  (painel.filas?.confirmadosSecundaria || []).forEach((item) => {
    rows.push({
      pedidoId: item.pedidoId,
      cliente: "-",
      status: "CONFIRMADO",
      idempotente: false,
      eventos: [
        {
          hora: "--:--",
          de: "PENDENTE",
          para: "CONFIRMADO",
          origem: `Secundaria rota ${item.rotaId} / ordem ${item.ordemNaRota}`
        }
      ]
    });
  });

  (painel.filas?.pendentesElegiveis || []).forEach((item) => {
    rows.push({
      pedidoId: item.pedidoId,
      cliente: "-",
      status: "PENDENTE",
      idempotente: false,
      eventos: [
        {
          hora: String(item.criadoEm || "").slice(11, 16) || "--:--",
          de: "NOVO",
          para: "PENDENTE",
          origem: `Fila ${item.janelaTipo || "ASAP"}`
        }
      ]
    });
  });

  rows.sort((a, b) => Number(a.pedidoId) - Number(b.pedidoId));
  return rows;
}

function toCountFromValues(...values) {
  for (const value of values) {
    const numeric = Number(value);
    if (Number.isFinite(numeric)) {
      return Math.max(0, Math.trunc(numeric));
    }
  }
  return 0;
}

function buildRotasRowsFromPainel(painel) {
  const normalizeRota = (item, statusRota) => ({
    rotaId: Number(item?.rotaId || 0),
    entregadorId: Number(item?.entregadorId || 0),
    camada: String(item?.camada || (statusRota === "EM_ANDAMENTO" ? "PRIMARIA" : "SECUNDARIA")),
    statusRota,
    pendentes: toCountFromValues(
      item?.pendentes,
      item?.pendentesExecucao,
      item?.entregasPendentes,
      item?.pendentesEntrega
    ),
    emExecucao: toCountFromValues(item?.emExecucao, item?.entregasEmExecucao, item?.em_andamento)
  });

  const byRotaThenEntregador = (a, b) => {
    const rotaDiff = Number(a?.rotaId || 0) - Number(b?.rotaId || 0);
    if (rotaDiff !== 0) {
      return rotaDiff;
    }
    return Number(a?.entregadorId || 0) - Number(b?.entregadorId || 0);
  };

  const emAndamento = (Array.isArray(painel?.rotas?.emAndamento) ? painel.rotas.emAndamento : [])
    .map((item) => normalizeRota(item, "EM_ANDAMENTO"))
    .sort(byRotaThenEntregador);

  const planejadas = (Array.isArray(painel?.rotas?.planejadas) ? painel.rotas.planejadas : [])
    .map((item) => normalizeRota(item, "PLANEJADA"))
    .sort(byRotaThenEntregador);

  return { emAndamento, planejadas };
}

function resolveCamadaFromRota(rota) {
  const camada = String(rota?.camada || "").toUpperCase();
  if (camada.includes("PRIMARIA")) {
    return "PRIMARIA";
  }
  if (camada.includes("SECUNDARIA")) {
    return "SECUNDARIA";
  }

  const statusRota = String(rota?.statusRota || "").toUpperCase();
  if (statusRota === "EM_ANDAMENTO") {
    return "PRIMARIA";
  }
  if (statusRota === "PLANEJADA") {
    return "SECUNDARIA";
  }
  return "DESCONHECIDA";
}

function buildFleetLayerSnapshotFromRotas(rotasRows) {
  const rows = (Array.isArray(rotasRows) ? rotasRows : [])
    .filter((rota) => Number.isInteger(Number(rota?.rotaId || 0)) && Number(rota?.rotaId || 0) > 0)
    .slice()
    .sort((a, b) => Number(a?.rotaId || 0) - Number(b?.rotaId || 0));

  const primarias = [];
  const secundarias = [];
  const desconhecidas = [];
  rows.forEach((rota) => {
    const camada = resolveCamadaFromRota(rota);
    if (camada === "PRIMARIA") {
      primarias.push(rota);
      return;
    }
    if (camada === "SECUNDARIA") {
      secundarias.push(rota);
      return;
    }
    desconhecidas.push(rota);
  });

  const anomalyMessages = [];
  if (primarias.length > 1) {
    anomalyMessages.push(`Foram detectadas ${primarias.length} rotas na camada PRIMARIA (esperado: 1).`);
  }
  if (secundarias.length > 1) {
    anomalyMessages.push(`Foram detectadas ${secundarias.length} rotas na camada SECUNDARIA (esperado: 1).`);
  }
  if (desconhecidas.length > 0) {
    anomalyMessages.push(`${desconhecidas.length} rota(s) sem camada reconhecida (PRIMARIA/SECUNDARIA).`);
  }

  return {
    primaria: primarias[0] || null,
    secundaria: secundarias[0] || null,
    primarias,
    secundarias,
    desconhecidas,
    hasAnomaly: anomalyMessages.length > 0,
    anomalyMessages
  };
}

function buildFleetLayerSnapshot(painel) {
  const rotas = buildRotasRowsFromPainel(painel);
  return buildFleetLayerSnapshotFromRotas([...rotas.emAndamento, ...rotas.planejadas]);
}

function renderFleetLayerCard(title, tone, rota, emptyMessage) {
  if (!rota) {
    return `
      <article class="frota-layer-card">
        <p class="entity-title">${escapeHtml(title)}</p>
        <p class="ops-empty">${escapeHtml(emptyMessage)}</p>
      </article>
    `;
  }

  const pedidosAssoc = (Array.isArray(rota?.pedidos) ? rota.pedidos : [])
    .map((pedido) => {
      const pedidoId = Number(pedido?.pedidoId || 0);
      const ordem = Number(pedido?.ordemNaRota || 0);
      return `<span class="pill ${tone} mono">#${escapeHtml(String(pedidoId > 0 ? pedidoId : "?"))}${ordem > 0 ? ` · O${escapeHtml(String(ordem))}` : ""}</span>`;
    })
    .join("");

  return `
    <article class="frota-layer-card">
      <p class="entity-title">${escapeHtml(title)}</p>
      <p class="mono">Rota: <strong>R${escapeHtml(String(rota?.rotaId || "-"))}</strong> · Entregador: <strong>${escapeHtml(String(rota?.entregadorId || "-"))}</strong></p>
      <p class="mono">
        Status: ${escapeHtml(String(rota?.statusRota || "-"))}
        · Pendentes: ${escapeHtml(String(rota?.pendentes || 0))}
        · Em execucao: ${escapeHtml(String(rota?.emExecucao || 0))}
      </p>
      <p class="mono">Proximo passo: ${escapeHtml(rotaActionHintByStatus(rota?.statusRota || "-"))}</p>
      <div class="entity-pills">${pedidosAssoc || "<span class=\"mono\">Sem pedidos associados.</span>"}</div>
    </article>
  `;
}

function buildAtendenteOrientacaoPedidos(painel) {
  const rows = [];
  const filas = painel?.filas || {};

  (Array.isArray(filas.pendentesElegiveis) ? filas.pendentesElegiveis : []).forEach((item) => {
    rows.push({
      pedidoId: Number(item?.pedidoId || 0),
      statusPedido: "PENDENTE",
      rotaId: null,
      entregaId: null,
      ordemNaRota: null,
      entregadorId: null,
      quantidadeGaloes: Number(item?.quantidadeGaloes || 0),
      janelaTipo: String(item?.janelaTipo || "ASAP"),
      origem: "FILA_PENDENTE"
    });
  });

  (Array.isArray(filas.confirmadosSecundaria) ? filas.confirmadosSecundaria : []).forEach((item) => {
    rows.push({
      pedidoId: Number(item?.pedidoId || 0),
      statusPedido: "CONFIRMADO",
      rotaId: Number(item?.rotaId || 0),
      entregaId: null,
      ordemNaRota: Number(item?.ordemNaRota || 0),
      entregadorId: Number(item?.entregadorId || 0),
      quantidadeGaloes: Number(item?.quantidadeGaloes || 0),
      janelaTipo: "-",
      origem: "SECUNDARIA"
    });
  });

  (Array.isArray(filas.emRotaPrimaria) ? filas.emRotaPrimaria : []).forEach((item) => {
    rows.push({
      pedidoId: Number(item?.pedidoId || 0),
      statusPedido: "EM_ROTA",
      rotaId: Number(item?.rotaId || 0),
      entregaId: Number(item?.entregaId || 0),
      ordemNaRota: null,
      entregadorId: Number(item?.entregadorId || 0),
      quantidadeGaloes: Number(item?.quantidadeGaloes || 0),
      janelaTipo: "-",
      origem: String(item?.statusEntrega || "PRIMARIA")
    });
  });

  rows.sort((a, b) => Number(a.pedidoId || 0) - Number(b.pedidoId || 0));
  return rows;
}

function buildAtendenteOrientacaoRotas(painel, pedidosRows) {
  const rotas = buildRotasRowsFromPainel(painel);
  const rows = [];
  const pedidosByRota = new Map();
  (Array.isArray(pedidosRows) ? pedidosRows : []).forEach((pedido) => {
    const rotaId = Number(pedido?.rotaId || 0);
    if (!Number.isInteger(rotaId) || rotaId <= 0) {
      return;
    }
    if (!pedidosByRota.has(rotaId)) {
      pedidosByRota.set(rotaId, []);
    }
    pedidosByRota.get(rotaId).push(pedido);
  });

  const allRotas = [...rotas.emAndamento, ...rotas.planejadas];
  const seen = new Set();
  allRotas.forEach((rota) => {
    const rotaId = Number(rota?.rotaId || 0);
    if (!Number.isInteger(rotaId) || rotaId <= 0 || seen.has(rotaId)) {
      return;
    }
    seen.add(rotaId);
    rows.push({
      rotaId,
      statusRota: String(rota?.statusRota || "-"),
      entregadorId: Number(rota?.entregadorId || 0),
      camada: String(rota?.camada || "-"),
      pendentes: Number(rota?.pendentes || 0),
      emExecucao: Number(rota?.emExecucao || 0),
      pedidos: pedidosByRota.get(rotaId) || []
    });
  });

  rows.sort((a, b) => a.rotaId - b.rotaId);
  return rows;
}

function renderAtendenteSessionOverview(painel, historicoBusca) {
  const handoff = ensureHandoffState();
  const atendente = ensureAtendenteState();
  const pedidoStatusIndex = buildPedidoStatusIndex();
  const bloqueios = atendente.trilhaSessao.filter((item) => item?.tipo === "danger").length;
  const abertosNaSessao = handoff.atendimentosSessao
    .filter((item) => isPedidoStatusOpen(resolvePedidoStatus(item?.pedidoId, pedidoStatusIndex)))
    .length;
  const pedidosNoPainel = Number(painel?.pedidosPorStatus?.pendente || 0)
    + Number(painel?.pedidosPorStatus?.confirmado || 0)
    + Number(painel?.pedidosPorStatus?.emRota || 0);

  const cards = [
    {
      label: "Pedidos no painel",
      value: String(pedidosNoPainel),
      detail: "pendente + confirmado + em rota",
      tone: "info"
    },
    {
      label: "Abertos na sessao",
      value: String(abertosNaSessao),
      detail: "validado por status atual",
      tone: abertosNaSessao > 0 ? "warn" : "ok"
    },
    {
      label: "Bloqueios de regra",
      value: String(bloqueios),
      detail: "acoes manuais barradas no front",
      tone: bloqueios > 0 ? "danger" : "ok"
    },
    {
      label: "Busca por telefone",
      value: historicoBusca?.telefoneNormalizado
        ? formatPhoneForDisplay(historicoBusca.telefoneNormalizado)
        : "Sem busca",
      detail: historicoBusca?.pedidoAberto
        ? `pedido aberto #${historicoBusca.pedidoAberto.pedidoId}`
        : "sem pedido aberto no telefone atual",
      tone: historicoBusca?.pedidoAberto ? "warn" : "info"
    }
  ];

  return `
    <section class="panel">
      <div class="panel-header">
        <h3>Resumo operacional da atendente</h3>
        <span class="pill info">visao da sessao</span>
      </div>
      <div class="atendente-overview-grid">
        ${cards.map((card) => `
          <article class="overview-card ${card.tone}">
            <p class="overview-label">${escapeHtml(card.label)}</p>
            <p class="overview-value">${escapeHtml(card.value)}</p>
            <p class="overview-detail mono">${escapeHtml(card.detail)}</p>
          </article>
        `).join("")}
      </div>
    </section>
  `;
}

function renderAtendenteFlowBoard(historicoBusca) {
  const handoff = ensureHandoffState();
  const atendente = ensureAtendenteState();
  const trilha = atendente.trilhaSessao;
  const buscaAtiva = Boolean(atendente.buscaTelefone?.telefoneNormalizado);
  const pedidoAberto = historicoBusca?.pedidoAberto || null;
  const ultimoAtendimento = trilha.find(
    (item) => item?.tipo === "ok" && String(item?.acao || "").includes("atendimento registrado")
  );
  const focoPedidoId = Number(handoff.focoPedidoId || 0);

  const steps = [
    {
      title: "1) Buscar cliente",
      detail: buscaAtiva
        ? `Telefone ativo: ${formatPhoneForDisplay(atendente.buscaTelefone.telefoneNormalizado)}`
        : "Use a barra lateral para localizar cliente por telefone.",
      status: buscaAtiva ? "done" : "active"
    },
    {
      title: "2) Validar historico",
      detail: !buscaAtiva
        ? "Sem busca ativa."
        : pedidoAberto
          ? `Cliente com pedido aberto #${pedidoAberto.pedidoId}. Reaproveite o fluxo existente.`
          : "Sem pedido aberto para o telefone pesquisado.",
      status: !buscaAtiva ? "pending" : pedidoAberto ? "blocked" : "done"
    },
    {
      title: "3) Registrar pedido",
      detail: ultimoAtendimento
        ? `Ultimo registro em ${formatHandoffTime(ultimoAtendimento.quando)}.`
        : "Preencha os campos obrigatorios e registre o pedido.",
      status: ultimoAtendimento ? "done" : buscaAtiva && !pedidoAberto ? "active" : "pending"
    },
    {
      title: "4) Encaminhar despacho",
      detail: focoPedidoId > 0
        ? `Pedido em foco para despacho: #${focoPedidoId}.`
        : "Selecione um pedido da fila para seguir ao despacho.",
      status: focoPedidoId > 0 ? "done" : ultimoAtendimento ? "active" : "pending"
    }
  ];

  const statusMeta = {
    done: { label: "concluido", tone: "ok" },
    active: { label: "em curso", tone: "info" },
    blocked: { label: "bloqueado", tone: "danger" },
    pending: { label: "pendente", tone: "warn" }
  };

  return `
    <section class="panel">
      <div class="panel-header">
        <h3>Fluxo guiado da atendente</h3>
        <span class="pill info">operacao assistida</span>
      </div>
      <div class="flow-grid">
        ${steps.map((step) => {
          const meta = statusMeta[step.status] || statusMeta.pending;
          return `
            <article class="flow-step ${escapeAttr(step.status)}">
              <div class="flow-step-head">
                <p class="flow-step-title">${escapeHtml(step.title)}</p>
                ${tonePill(meta.label, meta.tone)}
              </div>
              <p class="flow-step-detail mono">${escapeHtml(step.detail)}</p>
            </article>
          `;
        }).join("")}
      </div>
    </section>
  `;
}

function renderAtendenteOrientacaoBoard(painel) {
  const pedidos = buildAtendenteOrientacaoPedidos(painel);
  const rotas = buildAtendenteOrientacaoRotas(painel, pedidos);
  const layerSnapshot = buildFleetLayerSnapshotFromRotas(rotas);
  const bloqueios = ensureAtendenteState().trilhaSessao.filter((item) => item?.tipo === "danger").length;
  const pedidosAbertos = pedidos.filter((item) => isPedidoStatusOpen(item?.statusPedido)).length;
  const primariaId = Number(layerSnapshot.primaria?.rotaId || 0);
  const secundariaId = Number(layerSnapshot.secundaria?.rotaId || 0);

  const pedidoRows = pedidos.map((item) => `
    <tr>
      <td class="mono">#${escapeHtml(String(item?.pedidoId || "-"))}</td>
      <td>${statusPill(item?.statusPedido || "-")}</td>
      <td class="mono">${escapeHtml(String(item?.rotaId > 0 ? item.rotaId : "-"))}</td>
      <td class="mono">${escapeHtml(String(item?.ordemNaRota > 0 ? item.ordemNaRota : "-"))}</td>
      <td class="mono">${escapeHtml(String(item?.entregadorId > 0 ? item.entregadorId : "-"))}</td>
      <td>${escapeHtml(atendenteActionHintByPedidoStatus(item?.statusPedido || "-"))}</td>
    </tr>
  `).join("");

  const camadaAlert = layerSnapshot.hasAnomaly
    ? `
      <div class="atendente-callout danger" style="margin-top: 0.65rem;">
        <p><strong>Inconsistencia de camadas detectada</strong></p>
        <ul class="layer-alert-list">
          ${layerSnapshot.anomalyMessages.map((message) => `<li class="mono">${escapeHtml(message)}</li>`).join("")}
        </ul>
        <p class="mono">Enquanto essa inconsistencia existir, a iniciacao manual de rota fica bloqueada.</p>
      </div>
    `
    : `
      <div class="atendente-callout ok" style="margin-top: 0.65rem;">
        <p><strong>Modelo de frota consistente</strong></p>
        <p class="mono">
          Primaria: ${primariaId > 0 ? `R${escapeHtml(String(primariaId))}` : "sem rota"}
          · Secundaria: ${secundariaId > 0 ? `R${escapeHtml(String(secundariaId))}` : "sem rota"}
        </p>
      </div>
    `;

  return `
    <section class="panel">
      <div class="panel-header">
        <h3>Quadro operacional</h3>
        <span class="pill info">visao compacta</span>
      </div>
      <p class="mono">
        Bloqueios de operacao na sessao: ${escapeHtml(String(bloqueios))}
        · Regras invalidas sao barradas antes da API.
      </p>
      <div class="entity-summary">
        ${tonePill(`pedidos ${pedidos.length}`, "info")}
        ${tonePill(`abertos ${pedidosAbertos}`, pedidosAbertos > 0 ? "warn" : "ok")}
        ${tonePill(`frota primaria ${primariaId > 0 ? `R${primariaId}` : "vazia"}`, "info")}
        ${tonePill(`frota secundaria ${secundariaId > 0 ? `R${secundariaId}` : "vazia"}`, "warn")}
      </div>
      ${camadaAlert}
      <div class="entity-grid">
        <article class="entity-card">
          <p class="entity-title">Pedidos</p>
          <div class="table-scroll table-scroll-vertical">
            <table class="entity-table compact">
              <thead>
                <tr>
                  <th>Pedido</th>
                  <th>Status</th>
                  <th>Rota</th>
                  <th>Ordem</th>
                  <th>Entregador</th>
                  <th>Proximo passo</th>
                </tr>
              </thead>
              <tbody>${pedidoRows || "<tr><td colspan=\"6\">Sem pedidos no quadro atual.</td></tr>"}</tbody>
            </table>
          </div>
        </article>
        <article class="entity-card">
          <p class="entity-title">Frotas fixas (1 primaria + 1 secundaria)</p>
          <div class="frota-layer-grid">
            ${renderFleetLayerCard(
              "Frota primaria (execucao)",
              "info",
              layerSnapshot.primaria,
              "Sem rota primaria ativa no momento."
            )}
            ${renderFleetLayerCard(
              "Frota secundaria (planejamento)",
              "warn",
              layerSnapshot.secundaria,
              "Sem rota secundaria planejada no momento."
            )}
          </div>
        </article>
      </div>
    </section>
  `;
}

function splitPedidosForVisualBoard(pedidos) {
  const buckets = {
    emRota: [],
    confirmados: [],
    pendentes: []
  };

  (Array.isArray(pedidos) ? pedidos : []).forEach((pedido) => {
    const status = String(pedido?.status || "").toUpperCase();
    if (status === "EM_ROTA") {
      buckets.emRota.push(pedido);
      return;
    }
    if (status === "CONFIRMADO") {
      buckets.confirmados.push(pedido);
      return;
    }
    buckets.pendentes.push(pedido);
  });

  return buckets;
}

function renderRotaCardForVisualBoard(rota) {
  const rotaLabel = Number.isInteger(rota?.rotaId) && rota.rotaId > 0
    ? `R${rota.rotaId}`
    : "R?";
  const entregadorLabel = Number.isInteger(rota?.entregadorId) && rota.entregadorId > 0
    ? `E${rota.entregadorId}`
    : "E?";
  const camada = String(rota?.camada || "-");
  const pendentes = toCountFromValues(rota?.pendentes);
  const emExecucao = toCountFromValues(rota?.emExecucao);

  return `
    <article class="ops-card">
      <div class="ops-card-head">
        <p class="ops-card-title mono">${escapeHtml(`${rotaLabel} · ${entregadorLabel}`)}</p>
        ${statusPill(rota?.statusRota || "-")}
      </div>
      <p class="ops-card-meta mono">Camada: ${escapeHtml(camada)}</p>
      <p class="ops-card-context mono">Pendentes: ${escapeHtml(String(pendentes))} · Em execucao: ${escapeHtml(String(emExecucao))}</p>
    </article>
  `;
}

function renderPedidoCardForVisualBoard(pedido) {
  const pedidoId = Number(pedido?.pedidoId || 0);
  const firstEvent = Array.isArray(pedido?.eventos) && pedido.eventos.length > 0 ? pedido.eventos[0] : null;
  const origem = firstEvent?.origem ? String(firstEvent.origem) : "-";
  const transicao = firstEvent
    ? `${String(firstEvent.de || "-")} -> ${String(firstEvent.para || "-")}`
    : "Sem evento";

  return `
    <article class="ops-card">
      <div class="ops-card-head">
        <p class="ops-card-title mono">#${escapeHtml(String(pedidoId > 0 ? pedidoId : "?"))}</p>
        ${statusPill(pedido?.status || "-")}
      </div>
      <p class="ops-card-meta mono">${escapeHtml(transicao)}</p>
      <p class="ops-card-context mono">${escapeHtml(origem)}</p>
    </article>
  `;
}

function renderOpsBucket(title, tone, items, renderItem, emptyMessage) {
  const rows = Array.isArray(items) ? items : [];
  return `
    <section class="ops-bucket">
      <div class="ops-bucket-header">
        <p>${escapeHtml(title)}</p>
        ${tonePill(String(rows.length), tone)}
      </div>
      ${rows.length > 0
        ? `<div class="ops-cards">${rows.map((item) => renderItem(item)).join("")}</div>`
        : `<p class="ops-empty">${escapeHtml(emptyMessage)}</p>`}
    </section>
  `;
}

function renderOperationalSplitBoard(painel) {
  const rotas = buildRotasRowsFromPainel(painel);
  const layerSnapshot = buildFleetLayerSnapshotFromRotas([...rotas.emAndamento, ...rotas.planejadas]);
  const pedidosBuckets = splitPedidosForVisualBoard(buildPedidosRowsFromPainel(painel));

  const totalRotas = (layerSnapshot.primaria ? 1 : 0) + (layerSnapshot.secundaria ? 1 : 0);
  const totalPedidos = pedidosBuckets.emRota.length + pedidosBuckets.confirmados.length + pedidosBuckets.pendentes.length;
  const camadaAlert = layerSnapshot.hasAnomaly
    ? `
      <div class="atendente-callout danger" style="margin-top: 0.75rem;">
        <p><strong>Anomalia de camadas</strong></p>
        <ul class="layer-alert-list">
          ${layerSnapshot.anomalyMessages.map((message) => `<li class="mono">${escapeHtml(message)}</li>`).join("")}
        </ul>
      </div>
    `
    : "";

  return `
    <section class="panel">
      <div class="panel-header">
        <h3>Separacao visual operacional: Frotas fixas x Pedidos</h3>
        <span class="pill info">/api/operacao/painel</span>
      </div>
      <div class="ops-split-grid">
        <article class="ops-lane ops-lane-rotas">
          <div class="ops-lane-header">
            <h4>Frotas</h4>
            ${tonePill(`total ${totalRotas}`, "info")}
          </div>
          ${renderOpsBucket(
            "Frota primaria (execucao)",
            "info",
            layerSnapshot.primaria ? [layerSnapshot.primaria] : [],
            renderRotaCardForVisualBoard,
            "Nenhuma rota ativa na frota primaria."
          )}
          ${renderOpsBucket(
            "Frota secundaria (planejada)",
            "warn",
            layerSnapshot.secundaria ? [layerSnapshot.secundaria] : [],
            renderRotaCardForVisualBoard,
            "Nenhuma rota ativa na frota secundaria."
          )}
          ${camadaAlert}
        </article>
        <article class="ops-lane ops-lane-pedidos">
          <div class="ops-lane-header">
            <h4>Pedidos</h4>
            ${tonePill(`total ${totalPedidos}`, "warn")}
          </div>
          ${renderOpsBucket(
            "Em rota",
            "info",
            pedidosBuckets.emRota,
            renderPedidoCardForVisualBoard,
            "Nenhum pedido em rota."
          )}
          ${renderOpsBucket(
            "Confirmados",
            "warn",
            pedidosBuckets.confirmados,
            renderPedidoCardForVisualBoard,
            "Nenhum pedido confirmado."
          )}
          ${renderOpsBucket(
            "Pendentes",
            "ok",
            pedidosBuckets.pendentes,
            renderPedidoCardForVisualBoard,
            "Nenhum pedido pendente."
          )}
        </article>
      </div>
    </section>
  `;
}

function renderMetrics() {
  const painel = painelOrDefault();
  const connectionValue = appState.api.connected ? "online" : "offline";
  const cards = appState.view === "pedidos"
    ? [
        { label: "Pendentes", value: painel.pedidosPorStatus?.pendente ?? 0 },
        { label: "Em rota", value: painel.pedidosPorStatus?.emRota ?? 0 },
        { label: "API", value: connectionValue }
      ]
    : [
        { label: "Pendentes", value: painel.pedidosPorStatus?.pendente ?? 0 },
        { label: "Confirmados", value: painel.pedidosPorStatus?.confirmado ?? 0 },
        { label: "Em rota", value: painel.pedidosPorStatus?.emRota ?? 0 },
        { label: "API", value: connectionValue },
        { label: "Ambiente", value: painel.ambiente || "-" }
      ];

  metricsRoot.innerHTML = cards
    .map((item, idx) => {
      return `
        <article class="metric-card" style="animation-delay:${idx * 70}ms">
          <p class="metric-label">${escapeHtml(item.label)}</p>
          <p class="metric-value">${escapeHtml(String(item.value))}</p>
        </article>
      `;
    })
    .join("");
}

function renderStateShell(content) {
  // Empty/Error sao modos de demo para simulacao visual.
  // Nas views operacionais (despacho/frota) sempre priorizamos dados reais.
  if (appState.view === "despacho" || appState.view === "frota") {
    return content;
  }

  if (appState.mode === "empty") {
    return `
      <section class="empty-state">
        <h3>Sem dados no momento</h3>
        <p>Nenhum registro foi encontrado para os filtros ativos.</p>
        <button class="btn" data-mode-restore>Voltar para success</button>
      </section>
    `;
  }

  if (appState.mode === "error") {
    const detalhe = appState.api.lastError ? `<p class="mono">${escapeHtml(appState.api.lastError)}</p>` : "";
    return `
      <section class="notice error">
        <h3>Falha ao carregar dados reais</h3>
        <p>O painel operacional usa somente dados da API. Nao ha fallback/mock.</p>
        ${detalhe}
        <button class="btn" data-mode-restore>Tentar novamente</button>
      </section>
    `;
  }

  return content;
}

function renderPedidos() {
  const painel = painelOrDefault();
  const atendimentoExample = appState.examples.atendimentoRequest;
  const timelineExample = appState.examples.timelineRequest;
  const atendente = ensureAtendenteState();
  const buscaTelefone = atendente.buscaTelefone || {};
  const historicoBusca = buildHistoricoPorTelefone(buscaTelefone.telefoneNormalizado || "");
  const ultimoBloqueio = atendente.trilhaSessao.find((item) => item?.tipo === "danger");
  const ultimaAcao = atendente.trilhaSessao[0] || null;

  const blocoBusca = buscaTelefone.telefoneNormalizado
    ? `
      <div class="atendente-callout info">
        <p><strong>Cliente pesquisado</strong></p>
        <p class="mono">
          ${escapeHtml(formatPhoneForDisplay(buscaTelefone.telefoneNormalizado))}
          · historico(sessao): ${escapeHtml(String(historicoBusca.historico.length))}
        </p>
        <p class="mono">
          Pedido em aberto: ${historicoBusca.pedidoAberto ? `SIM (#${escapeHtml(String(historicoBusca.pedidoAberto.pedidoId))} · ${escapeHtml(String(historicoBusca.pedidoAberto.statusAtual || "-"))})` : "NAO"}
        </p>
      </div>
    `
    : `
      <div class="atendente-callout warn">
        <p><strong>Sem cliente focado</strong></p>
        <p class="mono">Use a barra lateral para pesquisar telefone e abrir historico da sessao.</p>
      </div>
    `;

  const blocoAberto = historicoBusca.pedidoAberto
    ? `
      <div class="atendente-callout danger">
        <p><strong>Atencao: pedido em aberto do cliente</strong></p>
        <p class="mono">
          Pedido #${escapeHtml(String(historicoBusca.pedidoAberto.pedidoId))}
          · status ${escapeHtml(String(historicoBusca.pedidoAberto.statusAtual || "-"))}
        </p>
        <button
          class="btn"
          type="button"
          data-action="go-to-despacho"
          data-pedido-id="${escapeAttr(String(historicoBusca.pedidoAberto.pedidoId || ""))}"
        >
          Ir para despacho desse pedido
        </button>
      </div>
    `
    : "";

  const blocoBloqueio = ultimoBloqueio
    ? `
      <div class="atendente-callout danger">
        <p><strong>Ultimo bloqueio de regra</strong></p>
        <p class="mono">${escapeHtml(formatHandoffTime(ultimoBloqueio.quando))} · ${escapeHtml(String(ultimoBloqueio.acao || "-"))}</p>
        <p>${escapeHtml(String(ultimoBloqueio.detalhe || "-"))}</p>
      </div>
    `
    : `
      <div class="atendente-callout ok">
        <p><strong>Sessao sem bloqueios recentes</strong></p>
        <p class="mono">Operacoes manuais recentes nao violaram regras de negocio no front.</p>
      </div>
    `;

  const blocoUltimaAcao = ultimaAcao
    ? `
      <div class="atendente-callout info">
        <p><strong>Ultima acao registrada</strong></p>
        <p class="mono">${escapeHtml(formatHandoffTime(ultimaAcao.quando))} · ${escapeHtml(String(ultimaAcao.acao || "-"))}</p>
        <p>${escapeHtml(String(ultimaAcao.detalhe || "-"))}</p>
      </div>
    `
    : "";

  return renderStateShell(`
    <div class="panel-grid atendente-main-grid">
      <section class="panel">
        <div class="panel-header">
          <h3>Operacao da atendente</h3>
          <span class="pill info">/api/atendimento/pedidos</span>
        </div>
        <p class="mono">Fluxo recomendado: buscar cliente -> validar historico -> registrar pedido -> encaminhar despacho.</p>
        <form id="atendimento-form" class="form-grid">
          <div class="form-row two">
            <div class="form-row">
              <label for="telefone">Telefone</label>
              <input
                id="telefone"
                name="telefone"
                placeholder="(38) 99876-8001"
                value="${escapeAttr(atendimentoExample.telefone)}"
                required
              />
            </div>
            <div class="form-row">
              <label for="atendenteId">Atendente ID</label>
              <input
                id="atendenteId"
                name="atendenteId"
                type="number"
                min="1"
                value="${atendimentoExample.atendenteId}"
                required
              />
            </div>
          </div>
          <div class="form-row two">
            <div class="form-row">
              <label for="quantidadeGaloes">Quantidade de galoes</label>
              <input
                id="quantidadeGaloes"
                name="quantidadeGaloes"
                type="number"
                min="1"
                value="${atendimentoExample.quantidadeGaloes}"
                required
              />
            </div>
            <div class="form-row">
              <label for="metodoPagamento">Metodo de pagamento</label>
              <select id="metodoPagamento" name="metodoPagamento">
                <option value="NAO_INFORMADO" ${atendimentoExample.metodoPagamento === "NAO_INFORMADO" ? "selected" : ""}>NAO_INFORMADO</option>
                <option value="DINHEIRO" ${atendimentoExample.metodoPagamento === "DINHEIRO" ? "selected" : ""}>DINHEIRO</option>
                <option value="PIX" ${atendimentoExample.metodoPagamento === "PIX" ? "selected" : ""}>PIX</option>
                <option value="CARTAO" ${atendimentoExample.metodoPagamento === "CARTAO" ? "selected" : ""}>CARTAO</option>
                <option value="VALE" ${atendimentoExample.metodoPagamento === "VALE" ? "selected" : ""}>VALE</option>
              </select>
            </div>
          </div>
          <div class="form-row two">
            <div class="form-row">
              <label for="origemCanal">Origem canal</label>
              <select id="origemCanal" name="origemCanal">
                <option value="" ${!atendimentoExample.origemCanal ? "selected" : ""}>AUTO (backend decide)</option>
                <option value="MANUAL" ${atendimentoExample.origemCanal === "MANUAL" ? "selected" : ""}>MANUAL</option>
                <option value="WHATSAPP" ${atendimentoExample.origemCanal === "WHATSAPP" ? "selected" : ""}>WHATSAPP</option>
                <option value="BINA_FIXO" ${atendimentoExample.origemCanal === "BINA_FIXO" ? "selected" : ""}>BINA_FIXO</option>
                <option value="TELEFONIA_FIXO" ${atendimentoExample.origemCanal === "TELEFONIA_FIXO" ? "selected" : ""}>TELEFONIA_FIXO</option>
              </select>
            </div>
            <div class="form-row">
              <label for="janelaTipo">Janela tipo</label>
              <select id="janelaTipo" name="janelaTipo">
                <option value="ASAP" ${atendimentoExample.janelaTipo === "ASAP" ? "selected" : ""}>ASAP</option>
                <option value="FLEXIVEL" ${atendimentoExample.janelaTipo === "FLEXIVEL" ? "selected" : ""}>FLEXIVEL</option>
                <option value="HARD" ${atendimentoExample.janelaTipo === "HARD" ? "selected" : ""}>HARD</option>
              </select>
            </div>
            <div class="form-row">
              <label for="janelaInicio">Janela inicio (HH:mm)</label>
              <input id="janelaInicio" name="janelaInicio" placeholder="09:00" value="${escapeAttr(atendimentoExample.janelaInicio || "")}" />
            </div>
            <div class="form-row">
              <label for="janelaFim">Janela fim (HH:mm)</label>
              <input id="janelaFim" name="janelaFim" placeholder="10:30" value="${escapeAttr(atendimentoExample.janelaFim || "")}" />
            </div>
          </div>
          <details class="form-collapsible">
            <summary class="diag-summary">IDs operacionais e dados opcionais</summary>
            <div class="form-grid" style="margin-top: 0.75rem;">
              <div class="form-row two">
                <div class="form-row">
                  <label for="sourceEventId">sourceEventId (canal automatico)</label>
                  <input
                    id="sourceEventId"
                    name="sourceEventId"
                    placeholder="src-20260224-001"
                    value="${escapeAttr(atendimentoExample.sourceEventId || "")}"
                  />
                </div>
                <div class="form-row">
                  <label for="manualRequestId">manualRequestId (canal manual)</label>
                  <input
                    id="manualRequestId"
                    name="manualRequestId"
                    placeholder="manual-20260224-001"
                    value="${escapeAttr(atendimentoExample.manualRequestId || "")}"
                  />
                </div>
              </div>
              <div class="form-row">
                <label for="externalCallId">externalCallId (legado/idempotencia)</label>
                <input
                  id="externalCallId"
                  name="externalCallId"
                  placeholder="call-20260213-0001"
                  value="${escapeAttr(atendimentoExample.externalCallId || "")}"
                />
              </div>
              <div class="form-row">
                <label for="nomeCliente">Nome cliente (opcional)</label>
                <input
                  id="nomeCliente"
                  name="nomeCliente"
                  placeholder="Maria Clara"
                  value="${escapeAttr(atendimentoExample.nomeCliente || "")}"
                />
              </div>
              <div class="form-row">
                <label for="endereco">Endereco (opcional)</label>
                <input
                  id="endereco"
                  name="endereco"
                  placeholder="Rua A, 10 - Montes Claros"
                  value="${escapeAttr(atendimentoExample.endereco || "")}"
                />
              </div>
              <div class="form-row two">
                <div class="form-row">
                  <label for="latitude">Latitude (opcional)</label>
                  <input
                    id="latitude"
                    name="latitude"
                    placeholder="-16.7310"
                    value="${escapeAttr(atendimentoExample.latitude || "")}"
                  />
                </div>
                <div class="form-row">
                  <label for="longitude">Longitude (opcional)</label>
                  <input
                    id="longitude"
                    name="longitude"
                    placeholder="-43.8710"
                    value="${escapeAttr(atendimentoExample.longitude || "")}"
                  />
                </div>
              </div>
            </div>
          </details>
          <div class="rules-list">
            <p class="mono">Regra 1: canal automatico exige sourceEventId; canal manual usa manualRequestId.</p>
            <p class="mono">Regra 2: cliente com pedido aberto nao pode gerar novo pedido.</p>
            <p class="mono">Regra 3: latitude e longitude devem ser informadas juntas.</p>
          </div>
          <button class="btn" type="submit">Registrar pedido</button>
        </form>
        ${renderResultBox("Resposta atendimento", appState.apiResults.atendimento)}
        ${renderAtendimentoHandoffSection()}
      </section>
      <section class="panel">
        <div class="panel-header">
          <h3>Contexto e diagnostico</h3>
          <span class="pill info">apoio da sessao</span>
        </div>
        <div class="form-grid">
          ${blocoBusca}
          ${blocoAberto}
          ${blocoBloqueio}
          ${blocoUltimaAcao}
        </div>
        <details class="diag-details" open>
          <summary class="diag-summary">Diagnostico de timeline (on-demand)</summary>
          <div class="form-grid" style="margin-top: 0.75rem;">
            <form id="timeline-form" class="form-grid">
              <div class="form-row">
                <label for="timelinePedidoId">Consultar timeline (pedidoId)</label>
                <input
                  id="timelinePedidoId"
                  name="pedidoId"
                  type="number"
                  min="1"
                  value="${timelineExample.pedidoId}"
                  required
                />
              </div>
              <button class="btn" type="submit">Carregar timeline</button>
            </form>
            ${renderResultBox("Resposta timeline", appState.apiResults.timeline)}
          </div>
        </details>
      </section>
    </div>
    <details class="diag-details" style="margin-top: 1rem;">
      <summary class="diag-summary">Quadro operacional (pedidos + frotas)</summary>
      <div style="margin-top: 0.75rem;">
        ${renderAtendenteOrientacaoBoard(painel)}
      </div>
    </details>
  `);
}

function toneForOperationalEvent(eventType) {
  const normalized = String(eventType || "").toUpperCase();
  if (normalized.includes("CANCELADO") || normalized.includes("FALHOU")) {
    return "danger";
  }
  if (normalized.includes("ENTREGUE")) {
    return "ok";
  }
  if (normalized.includes("ROTA")) {
    return "info";
  }
  return "warn";
}

function classifyOperationalEvent(event) {
  const eventType = String(event?.eventType || "").toUpperCase();
  const aggregateType = String(event?.aggregateType || "").toUpperCase();
  if (eventType.startsWith("ROTA_") || aggregateType.includes("ROTA")) {
    return "rotas";
  }
  if (
    eventType.startsWith("PEDIDO_")
    || aggregateType.includes("PEDIDO")
    || aggregateType.includes("ENTREGA")
  ) {
    return "pedidos";
  }
  return "outros";
}

function splitOperationalEvents(eventosOperacionais) {
  const grouped = {
    rotas: [],
    pedidos: [],
    outros: []
  };

  (Array.isArray(eventosOperacionais) ? eventosOperacionais : []).forEach((event) => {
    grouped[classifyOperationalEvent(event)].push(event);
  });
  return grouped;
}

function renderOperationalEventRow(event) {
  const tone = toneForOperationalEvent(event?.eventType);
  const descricao = `${event.aggregateType || "-"} ${event.aggregateId ?? "-"} · ${event.status || "-"}`;
  const hora = String(event.createdEm || "").slice(11, 19) || "--:--:--";
  return `
    <div class="event-row">
      <p class="meta mono">${escapeHtml(hora)} · ${escapeHtml(String(event.eventType || "-"))}</p>
      <p class="title">${tonePill(String(event.eventType || "-"), tone)} ${escapeHtml(descricao)}</p>
    </div>
  `;
}

function renderOperationalEventFeed(events, emptyMessage) {
  const rows = Array.isArray(events) ? events : [];
  if (rows.length === 0) {
    return `<p class="ops-empty">${escapeHtml(emptyMessage)}</p>`;
  }
  return `<div class="event-feed">${rows.map((event) => renderOperationalEventRow(event)).join("")}</div>`;
}

function renderDespacho() {
  const eventoExample = appState.examples.eventoRequest;
  const painel = painelOrDefault();
  const layerSnapshot = buildFleetLayerSnapshot(painel);
  const rotaPrimaria = layerSnapshot.primaria;
  const rotaSecundaria = layerSnapshot.secundaria;
  const rotaSecundariaId = Number(rotaSecundaria?.rotaId || 0);
  const podeIniciarSecundaria = Boolean(rotaSecundaria && !layerSnapshot.hasAnomaly);
  const eventosAgrupados = splitOperationalEvents(appState.eventosOperacionais);

  return renderStateShell(`
    ${renderOperationalSplitBoard(painel)}
    ${renderDespachoHandoffSection()}
    <div class="panel-grid" style="margin-top: 1rem;">
      <section class="panel">
        <div class="panel-header">
          <h3>Eventos operacionais (DB real)</h3>
          <span class="pill info">/api/operacao/eventos</span>
        </div>
        <div class="event-split-grid">
          <section class="event-lane">
            <div class="event-lane-header">
              <p>Rotas</p>
              ${tonePill(String(eventosAgrupados.rotas.length), "info")}
            </div>
            ${renderOperationalEventFeed(eventosAgrupados.rotas, "Nenhum evento de rota encontrado.")}
          </section>
          <section class="event-lane">
            <div class="event-lane-header">
              <p>Pedidos</p>
              ${tonePill(String(eventosAgrupados.pedidos.length), "warn")}
            </div>
            ${renderOperationalEventFeed(eventosAgrupados.pedidos, "Nenhum evento de pedido encontrado.")}
          </section>
        </div>
        ${eventosAgrupados.outros.length > 0
          ? `
            <div class="result-box" style="margin-top: 0.75rem;">
              <p><strong>Eventos nao classificados</strong></p>
              ${renderOperationalEventFeed(eventosAgrupados.outros, "Nenhum evento nao classificado.")}
            </div>
          `
          : ""}
      </section>
      <section class="panel">
        <div class="panel-header">
          <h3>Mapa operacional (DB real)</h3>
          <span class="pill info">/api/operacao/mapa</span>
        </div>
        ${renderMapaOperacional()}
        <div class="result-box" style="margin-top: 0.75rem;">
          <p><strong>Resumo de camadas (painel)</strong></p>
          <p class="mono">
            Frota primaria: ${rotaPrimaria ? `R${escapeHtml(String(rotaPrimaria.rotaId))} · E${escapeHtml(String(rotaPrimaria.entregadorId))}` : "sem rota ativa"}
            · Frota secundaria: ${rotaSecundaria ? `R${escapeHtml(String(rotaSecundaria.rotaId))} · E${escapeHtml(String(rotaSecundaria.entregadorId))}` : "sem rota planejada"}
          </p>
          ${layerSnapshot.hasAnomaly
            ? `
              <ul class="layer-alert-list">
                ${layerSnapshot.anomalyMessages.map((message) => `<li class="mono">${escapeHtml(message)}</li>`).join("")}
              </ul>
            `
            : ""}
          <pre class="mono">${escapeHtml(JSON.stringify(painel.rotas || {}, null, 2))}</pre>
        </div>
      </section>
    </div>
    <section class="panel" style="margin-top: 1rem;">
      <div class="panel-header">
        <h3>Acoes operacionais (API real)</h3>
        <span class="pill info">/api/eventos + /api/operacao/rotas/prontas/iniciar</span>
      </div>
      <div class="panel-grid">
        <div>
          <form id="evento-form" class="form-grid">
            <div class="form-row">
              <label for="eventType">Tipo de evento</label>
              <select id="eventType" name="eventType">
                <option value="ROTA_INICIADA" ${eventoExample.eventType === "ROTA_INICIADA" ? "selected" : ""}>ROTA_INICIADA</option>
                <option value="PEDIDO_ENTREGUE" ${eventoExample.eventType === "PEDIDO_ENTREGUE" ? "selected" : ""}>PEDIDO_ENTREGUE</option>
                <option value="PEDIDO_FALHOU" ${eventoExample.eventType === "PEDIDO_FALHOU" ? "selected" : ""}>PEDIDO_FALHOU</option>
                <option value="PEDIDO_CANCELADO" ${eventoExample.eventType === "PEDIDO_CANCELADO" ? "selected" : ""}>PEDIDO_CANCELADO</option>
              </select>
            </div>
            <div class="form-row two">
              <div class="form-row">
                <label for="externalEventId">External event id (opcional)</label>
                <input
                  id="externalEventId"
                  name="externalEventId"
                  placeholder="evt-20260216-0001"
                  value="${escapeAttr(eventoExample.externalEventId || "")}"
                />
              </div>
              <div class="form-row">
                <label for="rotaId">Rota ID da frota secundaria (ROTA_INICIADA)</label>
                <input
                  id="rotaId"
                  name="rotaId"
                  type="number"
                  min="1"
                  value="${escapeAttr(eventoExample.rotaId || (rotaSecundariaId > 0 ? String(rotaSecundariaId) : ""))}"
                />
              </div>
              <div class="form-row">
                <label for="entregaId">Entrega ID (demais eventos)</label>
                <input id="entregaId" name="entregaId" type="number" min="1" value="${escapeAttr(eventoExample.entregaId || "")}" />
              </div>
            </div>
            <div class="form-row">
              <label for="actorEntregadorId">Actor entregador ID (opcional)</label>
              <input
                id="actorEntregadorId"
                name="actorEntregadorId"
                type="number"
                min="1"
                value="${escapeAttr(eventoExample.actorEntregadorId || "")}"
              />
            </div>
            <div class="form-row">
              <label for="motivo">Motivo (falha/cancelamento)</label>
              <input id="motivo" name="motivo" value="${escapeAttr(eventoExample.motivo || "")}" />
            </div>
            <div class="form-row">
              <label for="cobrancaCancelamentoCentavos">Cobranca cancelamento (centavos)</label>
              <input
                id="cobrancaCancelamentoCentavos"
                name="cobrancaCancelamentoCentavos"
                type="number"
                min="0"
                value="${escapeAttr(eventoExample.cobrancaCancelamentoCentavos || "")}"
              />
            </div>
            <button class="btn" type="submit">Enviar evento</button>
          </form>
          ${renderResultBox("Resposta evento", appState.apiResults.evento)}
        </div>
        <div>
          <p class="mono">
            Fluxo operacional fixo: existe uma frota PRIMARIA (execucao) e uma frota SECUNDARIA (planejamento).
            A iniciacao manual so atua na frota secundaria unica.
          </p>
          <div class="form-grid">
            ${!rotaSecundaria
              ? "<p>Nenhuma rota da frota SECUNDARIA disponivel para iniciar.</p>"
              : `
                <div class="form-row" style="display:flex; gap:0.5rem; flex-wrap:wrap;">
                  <button
                    class="btn"
                    type="button"
                    data-action="iniciar-rota-pronta"
                    data-entregador-id="${escapeAttr(String(rotaSecundaria.entregadorId || ""))}"
                    data-rota-id="${escapeAttr(String(rotaSecundaria.rotaId || ""))}"
                    ${podeIniciarSecundaria ? "" : "disabled"}
                  >
                    Iniciar frota secundaria · R${escapeHtml(String(rotaSecundaria.rotaId || "-"))} · Entregador ${escapeHtml(String(rotaSecundaria.entregadorId || "-"))}
                  </button>
                  <a
                    class="btn"
                    href="./entregador.html?entregadorId=${escapeAttr(String(rotaSecundaria.entregadorId || ""))}"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Abrir pagina entregador ${escapeHtml(String(rotaSecundaria.entregadorId || "-"))}
                  </a>
                </div>
              `}
            ${layerSnapshot.hasAnomaly
              ? `
                <div class="atendente-callout danger">
                  <p><strong>Iniciar frota secundaria bloqueado</strong></p>
                  <ul class="layer-alert-list">
                    ${layerSnapshot.anomalyMessages.map((message) => `<li class="mono">${escapeHtml(message)}</li>`).join("")}
                  </ul>
                </div>
              `
              : ""}
          </div>
          ${renderResultBox("Resposta iniciar rota pronta", appState.apiResults.iniciarRotaPronta)}
          ${ensureHandoffState().ultimoEntregadorId
            ? `
              <p class="mono" style="margin-top: 0.5rem;">
                Deep-link apos inicio:
                <a href="./entregador.html?entregadorId=${escapeAttr(String(ensureHandoffState().ultimoEntregadorId))}" target="_blank" rel="noopener noreferrer">
                  entregador ${escapeHtml(String(ensureHandoffState().ultimoEntregadorId))}
                </a>
              </p>
            `
            : ""}
        </div>
      </div>
    </section>
  `);
}

function buildFrotaRoteiros() {
  const painel = painelOrDefault();
  const roteiroMap = new Map();
  const rotasPainel = painel.rotas || {};

  const ensureFromPainel = (list) => {
    (Array.isArray(list) ? list : []).forEach((item) => {
      const entregadorId = Number(item?.entregadorId || 0);
      if (!Number.isInteger(entregadorId) || entregadorId <= 0 || roteiroMap.has(entregadorId)) {
        return;
      }
      roteiroMap.set(entregadorId, {
        entregadorId,
        rota: null,
        cargaRemanescente: 0,
        paradasPendentesExecucao: [],
        paradasConcluidas: []
      });
    });
  };

  ensureFromPainel(rotasPainel.emAndamento);
  ensureFromPainel(rotasPainel.planejadas);

  (Array.isArray(appState.frotaRoteiros) ? appState.frotaRoteiros : []).forEach((roteiro) => {
    const entregadorId = Number(roteiro?.entregadorId || 0);
    if (!Number.isInteger(entregadorId) || entregadorId <= 0) {
      return;
    }
    roteiroMap.set(entregadorId, roteiro);
  });

  return [...roteiroMap.values()].sort((a, b) => Number(a?.entregadorId || 0) - Number(b?.entregadorId || 0));
}

function renderFrota() {
  const painel = painelOrDefault();
  const layerSnapshot = buildFleetLayerSnapshot(painel);
  const roteiros = buildFrotaRoteiros();

  const resumo = {
    entregadores: roteiros.length,
    comRota: 0,
    emAndamento: 0,
    planejadas: 0,
    cargaRemanescente: 0,
    paradasPendentesExecucao: 0,
    paradasConcluidas: 0,
    hardPendentes: 0,
    asapPendentes: 0
  };

  (Array.isArray(painel.filas?.pendentesElegiveis) ? painel.filas.pendentesElegiveis : []).forEach((item) => {
    const janela = String(item?.janelaTipo || "").toUpperCase();
    if (janela === "HARD") {
      resumo.hardPendentes += 1;
    } else {
      resumo.asapPendentes += 1;
    }
  });

  const rows = roteiros
    .map((roteiro) => {
      const entregadorId = Number(roteiro?.entregadorId || 0);
      const rota = roteiro?.rota || null;
      const rotaStatus = String(rota?.status || "SEM_ROTA");
      const rotaTone = rotaStatus === "EM_ANDAMENTO" ? "info" : rotaStatus === "PLANEJADA" ? "warn" : "danger";
      const cargaRemanescente = Number(roteiro?.cargaRemanescente || 0);
      const pendentes = Array.isArray(roteiro?.paradasPendentesExecucao) ? roteiro.paradasPendentesExecucao : [];
      const concluidas = Array.isArray(roteiro?.paradasConcluidas) ? roteiro.paradasConcluidas : [];
      const totalParadas = pendentes.length + concluidas.length;
      const progresso = totalParadas > 0 ? Math.round((concluidas.length / totalParadas) * 100) : 0;
      const erroRoteiro = roteiro?.erro ? `<p class="mono">${escapeHtml(String(roteiro.erro))}</p>` : "";

      if (rota && Number(rota?.rotaId || 0) > 0) {
        resumo.comRota += 1;
        if (rotaStatus === "EM_ANDAMENTO") {
          resumo.emAndamento += 1;
        }
        if (rotaStatus === "PLANEJADA") {
          resumo.planejadas += 1;
        }
      }
      resumo.cargaRemanescente += cargaRemanescente;
      resumo.paradasPendentesExecucao += pendentes.length;
      resumo.paradasConcluidas += concluidas.length;

      const pendentesHtml = pendentes.slice(0, 4).map((parada) => {
        const nome = String(parada?.clienteNome || "-");
        const ordem = Number(parada?.ordemNaRota || 0);
        const quantidade = Number(parada?.quantidadeGaloes || 0);
        return `<li class="mono">P${escapeHtml(String(ordem))} · ${escapeHtml(nome)} · ${escapeHtml(String(quantidade))} galao(oes)</li>`;
      }).join("");

      return `
        <article class="fleet-row">
          <div class="fleet-row-header">
            <h4>Entregador ${escapeHtml(String(entregadorId))}</h4>
            <span class="pill ${rotaTone} mono">${escapeHtml(rotaStatus)}${rota ? ` · R${escapeHtml(String(rota.rotaId))}` : ""}</span>
          </div>
          <p class="fleet-meta">
            Carga remanescente: <strong>${escapeHtml(String(cargaRemanescente))}</strong> ·
            Pendentes/execucao: <strong>${escapeHtml(String(pendentes.length))}</strong> ·
            Concluidas: <strong>${escapeHtml(String(concluidas.length))}</strong>
          </p>
          <div class="fleet-progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${escapeAttr(String(progresso))}">
            <div class="fleet-progress-fill" style="width:${escapeAttr(String(progresso))}%"></div>
          </div>
          ${pendentesHtml ? `<ul class="fleet-stops">${pendentesHtml}</ul>` : '<p class="fleet-empty">Sem paradas pendentes no momento.</p>'}
          ${erroRoteiro}
        </article>
      `;
    })
    .join("");

  const kpis = [
    { label: "Entregadores", value: resumo.entregadores },
    { label: "Com rota", value: resumo.comRota },
    { label: "Frota primaria", value: layerSnapshot.primaria ? `R${layerSnapshot.primaria.rotaId}` : "SEM_ROTA" },
    { label: "Frota secundaria", value: layerSnapshot.secundaria ? `R${layerSnapshot.secundaria.rotaId}` : "SEM_ROTA" },
    { label: "Anomalias camada", value: layerSnapshot.anomalyMessages.length },
    { label: "Carga remanescente", value: resumo.cargaRemanescente },
    { label: "Paradas em aberto", value: resumo.paradasPendentesExecucao },
    { label: "Paradas concluidas", value: resumo.paradasConcluidas },
    { label: "Pendentes HARD", value: resumo.hardPendentes },
    { label: "Pendentes ASAP", value: resumo.asapPendentes }
  ]
    .map((item) => `
      <article class="frota-kpi">
        <p class="label">${escapeHtml(item.label)}</p>
        <p class="value">${escapeHtml(String(item.value))}</p>
      </article>
    `)
    .join("");

  const camadaStatus = layerSnapshot.hasAnomaly
    ? `
      <div class="atendente-callout danger" style="margin-top: 0.75rem;">
        <p><strong>Inconsistencia no modelo de frota (esperado 1 primaria + 1 secundaria)</strong></p>
        <ul class="layer-alert-list">
          ${layerSnapshot.anomalyMessages.map((message) => `<li class="mono">${escapeHtml(message)}</li>`).join("")}
        </ul>
      </div>
    `
    : `
      <div class="atendente-callout ok" style="margin-top: 0.75rem;">
        <p><strong>Modelo de frota consistente</strong></p>
        <p class="mono">
          Primaria ${layerSnapshot.primaria ? `R${escapeHtml(String(layerSnapshot.primaria.rotaId))} · E${escapeHtml(String(layerSnapshot.primaria.entregadorId))}` : "sem rota"}
          · Secundaria ${layerSnapshot.secundaria ? `R${escapeHtml(String(layerSnapshot.secundaria.rotaId))} · E${escapeHtml(String(layerSnapshot.secundaria.entregadorId))}` : "sem rota"}
        </p>
      </div>
    `;

  return renderStateShell(`
    <div class="panel-grid">
      <section class="panel">
        <div class="panel-header">
          <h3>Frota ativa (read model real)</h3>
          <span class="pill info">/api/entregadores/{id}/roteiro</span>
        </div>
        <div class="frota-kpis">${kpis}</div>
        ${camadaStatus}
        <div class="fleet-grid" style="margin-top: 1rem;">
          ${rows || "<p class=\"fleet-empty\">Sem entregadores com roteiro disponivel no momento.</p>"}
        </div>
      </section>
      <section class="panel">
        <div class="panel-header">
          <h3>Mapa e camadas operacionais</h3>
          <span class="pill info">/api/operacao/mapa</span>
        </div>
        ${renderMapaOperacional()}
        <div class="result-box" style="margin-top: 0.75rem;">
          <p><strong>Resumo de rotas (painel)</strong></p>
          <pre class="mono">${escapeHtml(JSON.stringify(painel.rotas || {}, null, 2))}</pre>
        </div>
      </section>
    </div>
  `);
}

function getViewContent() {
  if (appState.view === "frota") {
    viewTitle.textContent = "Frota";
    return renderFrota();
  }
  if (appState.view === "despacho") {
    viewTitle.textContent = "Despacho";
    return renderDespacho();
  }
  viewTitle.textContent = "Pedidos";
  return renderPedidos();
}

function setActiveNav(view) {
  document.querySelectorAll(".nav-item").forEach((el) => {
    el.classList.toggle("active", el.dataset.view === view);
  });
}

function setActiveMode(mode) {
  document.querySelectorAll(".mode").forEach((el) => {
    el.classList.toggle("active", el.dataset.mode === mode);
  });
}

async function syncTimelineForPedido(pedidoId) {
  const result = await requestApi(buildTimelinePath(pedidoId), { method: "GET" });
  const timeline = normalizeTimelinePayload(result.payload);
  appState.apiResults.timeline = {
    source: "api real",
    payload: timeline
  };
  appState.examples.timelineRequest = { pedidoId: timeline.pedidoId };
}

async function handleAtendimentoSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const formState = readAtendimentoFormState(formData);
  appState.examples.atendimentoRequest = {
    ...appState.examples.atendimentoRequest,
    ...formState,
    quantidadeGaloes: formState.quantidadeGaloes || appState.examples.atendimentoRequest.quantidadeGaloes,
    atendenteId: formState.atendenteId || appState.examples.atendimentoRequest.atendenteId
  };

  try {
    const payload = buildAtendimentoPayloadFromFormData(formData);
    const preflight = validateAtendimentoPreflight(payload);
    if (!preflight.ok) {
      appState.apiResults.atendimento = {
        source: "front-end guard",
        payload: {
          erro: preflight.motivo,
          bloqueadoNoFront: true,
          pedidoAbertoId: preflight.pedidoId || null
        }
      };
      pushTrilhaSessao("danger", "atendimento bloqueado", preflight.motivo, {
        telefone: formatPhoneForDisplay(payload.telefone)
      });
      render();
      return;
    }

    const result = await requestApi("/api/atendimento/pedidos", { method: "POST", body: payload });
    appState.apiResults.atendimento = {
      source: "api real",
      payload: result.payload
    };
    pushAtendimentoHandoff(result.payload, payload);
    const pedidoId = Number(result.payload?.pedidoId || 0);
    const statusRegistro = Boolean(result.payload?.idempotente) ? "idempotente" : "novo";
    pushTrilhaSessao(
      "ok",
      "pedido registrado",
      `Pedido #${pedidoId > 0 ? pedidoId : "?"} (${statusRegistro}) para ${formatPhoneForDisplay(payload.telefone)}.`
    );

    if (pedidoId > 0) {
      await syncTimelineForPedido(pedidoId);
      await refreshOperationalReadModels();
    }
  } catch (error) {
    appState.apiResults.atendimento = {
      source: "api real",
      payload: { erro: error?.message || "Falha ao registrar pedido" }
    };
    pushTrilhaSessao("danger", "falha atendimento", error?.message || "Falha ao registrar pedido.");
  } finally {
    render();
  }
}

async function handleTimelineSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const pedidoId = Number(formData.get("pedidoId"));

  try {
    await syncTimelineForPedido(pedidoId);
  } catch (error) {
    appState.apiResults.timeline = {
      source: "api real",
      payload: { erro: error?.message || "Falha ao carregar timeline" }
    };
  } finally {
    render();
  }
}

async function handleEventoSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const externalEventId = String(formData.get("externalEventId") || "").trim();
  const eventType = String(formData.get("eventType") || "");
  const rotaId = Number(formData.get("rotaId"));
  const entregaId = Number(formData.get("entregaId"));
  const actorEntregadorId = Number(formData.get("actorEntregadorId"));
  const motivo = String(formData.get("motivo") || "").trim();
  const cobrancaCancelamentoCentavos = Number(formData.get("cobrancaCancelamentoCentavos"));

  const payload = { eventType };
  if (externalEventId) {
    payload.externalEventId = externalEventId;
  }
  if (eventType === "ROTA_INICIADA" && rotaId > 0) {
    payload.rotaId = rotaId;
  }
  if (eventType !== "ROTA_INICIADA" && entregaId > 0) {
    payload.entregaId = entregaId;
  }
  if (!Number.isNaN(actorEntregadorId) && actorEntregadorId > 0) {
    payload.actorEntregadorId = actorEntregadorId;
  }
  if (motivo) {
    payload.motivo = motivo;
  }
  if (eventType === "PEDIDO_CANCELADO" && !Number.isNaN(cobrancaCancelamentoCentavos)) {
    payload.cobrancaCancelamentoCentavos = cobrancaCancelamentoCentavos;
  }

  const preflight = validateEventoManualPreflight(payload);
  if (!preflight.ok) {
    appState.apiResults.evento = {
      source: "front-end guard",
      payload: {
        erro: preflight.motivo,
        bloqueadoNoFront: true
      }
    };
    pushTrilhaSessao("danger", "evento bloqueado", preflight.motivo, payload);
    render();
    return;
  }

  try {
    const result = await requestApi("/api/eventos", { method: "POST", body: payload });
    appState.apiResults.evento = {
      source: "api real",
      payload: result.payload
    };
    pushTrilhaSessao(
      "ok",
      "evento manual",
      `${eventType} aplicado${payload.rotaId ? ` na rota ${payload.rotaId}` : ""}${payload.entregaId ? ` na entrega ${payload.entregaId}` : ""}.`
    );
    await refreshOperationalReadModels();
  } catch (error) {
    appState.apiResults.evento = {
      source: "api real",
      payload: { erro: error?.message || "Falha ao enviar evento operacional" }
    };
    pushTrilhaSessao("danger", "falha evento manual", error?.message || "Falha ao enviar evento operacional.", payload);
  } finally {
    render();
  }
}

async function handleIniciarRotaProntaClick(event) {
  const entregadorId = Number(event.currentTarget?.dataset?.entregadorId || 0);
  const rotaIdFromButton = Number(event.currentTarget?.dataset?.rotaId || 0);
  if (!Number.isInteger(entregadorId) || entregadorId <= 0) {
    appState.apiResults.iniciarRotaPronta = {
      source: "front-end guard",
      payload: { erro: "entregadorId invalido para iniciar rota pronta" }
    };
    pushTrilhaSessao("danger", "rota pronta bloqueada", "entregadorId invalido.");
    render();
    return;
  }

  const painel = painelOrDefault();
  const layerSnapshot = buildFleetLayerSnapshot(painel);
  if (layerSnapshot.hasAnomaly) {
    appState.apiResults.iniciarRotaPronta = {
      source: "front-end guard",
      payload: {
        erro: `Camadas inconsistentes: ${layerSnapshot.anomalyMessages.join(" ")}`,
        bloqueadoNoFront: true
      }
    };
    pushTrilhaSessao(
      "danger",
      "rota pronta bloqueada",
      "Camadas inconsistentes; inicio manual de rota bloqueado."
    );
    render();
    return;
  }

  if (!layerSnapshot.secundaria) {
    appState.apiResults.iniciarRotaPronta = {
      source: "front-end guard",
      payload: {
        erro: "Nao existe rota da frota SECUNDARIA para iniciar no painel atual.",
        bloqueadoNoFront: true
      }
    };
    pushTrilhaSessao("danger", "rota pronta bloqueada", "Frota secundaria sem rota planejada.");
    render();
    return;
  }

  const secondaryEntregadorId = Number(layerSnapshot.secundaria?.entregadorId || 0);
  const secondaryRotaId = Number(layerSnapshot.secundaria?.rotaId || 0);
  if (entregadorId !== secondaryEntregadorId) {
    appState.apiResults.iniciarRotaPronta = {
      source: "front-end guard",
      payload: {
        erro: `Somente a frota SECUNDARIA ativa pode iniciar (entregador ${secondaryEntregadorId}).`,
        bloqueadoNoFront: true
      }
    };
    pushTrilhaSessao(
      "danger",
      "rota pronta bloqueada",
      `Entregador ${entregadorId} diverge da frota secundaria ativa (${secondaryEntregadorId}).`
    );
    render();
    return;
  }

  if (rotaIdFromButton > 0 && secondaryRotaId > 0 && rotaIdFromButton !== secondaryRotaId) {
    appState.apiResults.iniciarRotaPronta = {
      source: "front-end guard",
      payload: {
        erro: `Botao aponta rota ${rotaIdFromButton}, mas frota SECUNDARIA ativa e rota ${secondaryRotaId}.`,
        bloqueadoNoFront: true
      }
    };
    pushTrilhaSessao(
      "danger",
      "rota pronta bloqueada",
      `Rota do botao (${rotaIdFromButton}) divergente da secundaria ativa (${secondaryRotaId}).`
    );
    render();
    return;
  }

  try {
    const result = await requestApi("/api/operacao/rotas/prontas/iniciar", {
      method: "POST",
      body: { entregadorId }
    });
    appState.apiResults.iniciarRotaPronta = {
      source: "api real",
      payload: result.payload
    };
    ensureHandoffState().ultimoEntregadorId = entregadorId;
    pushTrilhaSessao("ok", "rota pronta iniciada", `Entregador ${entregadorId} iniciou rota pronta.`);
    await refreshOperationalReadModels();
  } catch (error) {
    appState.apiResults.iniciarRotaPronta = {
      source: "api real",
      payload: { erro: error?.message || "Falha ao iniciar rota pronta" }
    };
    pushTrilhaSessao(
      "danger",
      "falha rota pronta",
      error?.message || `Falha ao iniciar rota pronta para entregador ${entregadorId}.`
    );
  } finally {
    render();
  }
}

function bindViewEvents() {
  const restore = viewRoot.querySelector("[data-mode-restore]");
  if (restore) {
    restore.addEventListener("click", async () => {
      appState.mode = "success";
      try {
        await refreshOperationalReadModels();
      } catch (_) {
        // mantém estado atual.
      }
      render();
    });
  }

  const atendimentoForm = viewRoot.querySelector("#atendimento-form");
  if (atendimentoForm) {
    atendimentoForm.addEventListener("submit", handleAtendimentoSubmit);
  }

  const timelineForm = viewRoot.querySelector("#timeline-form");
  if (timelineForm) {
    timelineForm.addEventListener("submit", handleTimelineSubmit);
  }

  const eventoForm = viewRoot.querySelector("#evento-form");
  if (eventoForm) {
    eventoForm.addEventListener("submit", handleEventoSubmit);
  }

  viewRoot.querySelectorAll('[data-action="iniciar-rota-pronta"]').forEach((button) => {
    button.addEventListener("click", handleIniciarRotaProntaClick);
  });

  viewRoot.querySelectorAll('[data-action="go-to-despacho"]').forEach((button) => {
    button.addEventListener("click", () => {
      const pedidoId = Number(button.dataset.pedidoId || 0);
      if (Number.isInteger(pedidoId) && pedidoId > 0) {
        ensureHandoffState().focoPedidoId = pedidoId;
      }
      setView("despacho");
    });
  });

  viewRoot.querySelectorAll('[data-action="handoff-open-timeline"]').forEach((button) => {
    button.addEventListener("click", () => {
      const pedidoId = Number(button.dataset.pedidoId || 0);
      if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
        return;
      }
      ensureHandoffState().focoPedidoId = pedidoId;
      appState.examples.timelineRequest = { pedidoId };
      setView("pedidos");
    });
  });

  viewRoot.querySelectorAll('[data-action="handoff-move"]').forEach((button) => {
    button.addEventListener("click", () => {
      const pedidoId = Number(button.dataset.pedidoId || 0);
      const direction = String(button.dataset.direction || "");
      if (moveHandoffOrder(pedidoId, direction)) {
        render();
      }
    });
  });

  viewRoot.querySelectorAll('[data-action="go-to-pedidos"]').forEach((button) => {
    button.addEventListener("click", () => {
      const pedidoId = Number(button.dataset.pedidoId || 0);
      if (Number.isInteger(pedidoId) && pedidoId > 0) {
        ensureHandoffState().focoPedidoId = pedidoId;
        appState.examples.timelineRequest = { pedidoId };
      }
      setView("pedidos");
    });
  });
}

function render() {
  destroyCityMaps();
  renderMetrics();
  renderSidebarTools();
  viewRoot.innerHTML = getViewContent();
  setActiveNav(appState.view);
  setActiveMode(appState.mode);
  bindViewEvents();
  bindSidebarEvents();
  initCityMaps();
}

function setView(view) {
  appState.view = view;
  window.location.hash = `#${view}`;
  render();
}

function setMode(mode) {
  appState.mode = mode;
  render();
}

function tickClock() {
  clock.textContent = new Date().toLocaleTimeString("pt-BR");
}

function bindStaticEvents() {
  document.querySelectorAll(".nav-item").forEach((button) => {
    if (button.dataset.view) {
      button.addEventListener("click", () => setView(button.dataset.view));
    }
  });

  document.querySelectorAll(".mode").forEach((button) => {
    button.addEventListener("click", () => setMode(button.dataset.mode));
  });

  window.addEventListener("hashchange", () => {
    const hashView = window.location.hash.replace("#", "");
    if (["pedidos", "despacho", "frota"].includes(hashView)) {
      appState.view = hashView;
      render();
    }
  });

  apiConnectButton.addEventListener("click", () => {
    checkHealth();
  });

  if (apiResetButton) {
    apiResetButton.addEventListener("click", () => {
      clearStoredApiBase();
      appState.api.baseUrl = DEFAULT_API_BASE;
      apiInput.value = DEFAULT_API_BASE;
      checkHealth();
    });
  }

  apiInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      checkHealth();
    }
  });

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      void refreshOperationalReadModelsSafe();
    }
  });

  document.addEventListener("focusout", () => {
    window.setTimeout(() => {
      flushDeferredViewRender();
    }, 0);
  });

  window.addEventListener("beforeunload", stopOperationalAutoRefresh);
}

function stopOperationalAutoRefresh() {
  if (operationalRefreshTimerId !== null) {
    clearInterval(operationalRefreshTimerId);
    operationalRefreshTimerId = null;
  }
}

function startOperationalAutoRefresh() {
  stopOperationalAutoRefresh();
  operationalRefreshTimerId = window.setInterval(() => {
    if (document.hidden) {
      return;
    }
    void refreshOperationalReadModelsSafe();
  }, OPERATIONAL_AUTO_REFRESH_MS);
}

async function init() {
  const hashView = window.location.hash.replace("#", "");
  if (["pedidos", "despacho", "frota"].includes(hashView)) {
    appState.view = hashView;
  }

  bindStaticEvents();
  tickClock();
  updateApiStatus();
  render();
  await checkHealth();
  setInterval(tickClock, 1000);
  startOperationalAutoRefresh();
}

init();
