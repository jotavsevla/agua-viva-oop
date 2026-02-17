const API_BASE_STORAGE_KEY = "aguaVivaApiBaseUrl";
const DEFAULT_API_BASE = "http://localhost:8082";
const OPERATIONAL_AUTO_REFRESH_MS = 5000;
let operationalRefreshTimerId = null;
let operationalRefreshInFlight = false;
let deferredViewRender = false;

function readStoredApiBase() {
  try {
    const stored = window.localStorage.getItem(API_BASE_STORAGE_KEY);
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

function toFiniteNumberOr(value, fallback) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : fallback;
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

function expectedStatusForScenario(value) {
  const scenario = String(value || "").trim().toLowerCase();
  if (scenario === "feliz") {
    return "ENTREGUE";
  }
  if (scenario === "falha" || scenario === "cancelamento") {
    return "CANCELADO";
  }
  throw new Error("cenario invalido");
}

function buildTerminalEventPayload(params) {
  const scenario = String(params?.scenario || "").trim().toLowerCase();
  const entregaId = Number(params?.entregaId);
  if (!Number.isInteger(entregaId) || entregaId <= 0) {
    throw new Error("entregaId invalido");
  }
  if (scenario === "feliz") {
    return { eventType: "PEDIDO_ENTREGUE", entregaId };
  }
  if (scenario === "falha") {
    return {
      eventType: "PEDIDO_FALHOU",
      entregaId,
      motivo: String(params?.motivoFalha || "cliente ausente")
    };
  }
  if (scenario === "cancelamento") {
    const payload = {
      eventType: "PEDIDO_CANCELADO",
      entregaId,
      motivo: String(params?.motivoCancelamento || "cliente cancelou")
    };
    const cobranca = Number(params?.cobrancaCancelamentoCentavos);
    if (Number.isInteger(cobranca) && cobranca >= 0) {
      payload.cobrancaCancelamentoCentavos = cobranca;
    }
    return payload;
  }
  throw new Error("cenario invalido");
}

const appState = {
  view: "pedidos",
  mode: "success",
  api: {
    baseUrl: readStoredApiBase(),
    connected: false,
    lastError: null,
    lastSyncAt: null
  },
  painel: null,
  eventosOperacionais: [],
  mapaOperacional: null,
  apiResults: {
    atendimento: null,
    timeline: null,
    evento: null,
    replanejamento: null
  },
  examples: {
    atendimentoRequest: {
      externalCallId: "call-20260213-0001",
      telefone: "(38) 99876-1234",
      quantidadeGaloes: 2,
      atendenteId: 1,
      metodoPagamento: "PIX"
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
    replanejamentoRequest: {
      debounceSegundos: 0,
      limiteEventos: 100
    },
    timelineRequest: {
      pedidoId: 1
    }
  },
  e2e: {
    running: false,
    form: {
      scenario: "feliz",
      telefone: "(38) 99876-9901",
      quantidadeGaloes: 1,
      atendenteId: 1,
      metodoPagamento: "PIX",
      debounceSegundos: 0,
      limiteEventos: 100,
      motivoFalha: "cliente ausente",
      motivoCancelamento: "cliente cancelou",
      cobrancaCancelamentoCentavos: 2500
    },
    lastRun: null
  }
};

const viewTitle = document.getElementById("view-title");
const viewRoot = document.getElementById("view-root");
const metricsRoot = document.getElementById("metrics");
const clock = document.getElementById("clock");
const apiInput = document.getElementById("api-base");
const apiConnectButton = document.getElementById("api-connect");
const apiStatus = document.getElementById("api-status");

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

function renderResultBox(title, result) {
  if (!result) {
    return "";
  }

  const source = result.source || "api real";
  return `
    <div class="result-box">
      <p><strong>${escapeHtml(title)}</strong> · ${tonePill(source, "ok")}</p>
      <pre class="mono">${escapeHtml(JSON.stringify(result.payload, null, 2))}</pre>
    </div>
  `;
}

function statusForRun(state) {
  if (state === "sucesso") {
    return "ok";
  }
  if (state === "executando") {
    return "info";
  }
  return "danger";
}

function renderGuidedRunBox() {
  const run = appState.e2e.lastRun;
  if (!run) {
    return "";
  }

  const steps = (run.steps || [])
    .map((step) => {
      const tone = step.ok ? "ok" : "danger";
      const details = step.detail ? `<p class="mono e2e-detail">${escapeHtml(step.detail)}</p>` : "";
      return `
        <li class="e2e-step">
          ${tonePill(step.ok ? "OK" : "FALHA", tone)} <strong>${escapeHtml(step.title)}</strong>
          ${details}
        </li>
      `;
    })
    .join("");

  const resumo = [];
  if (run.pedidoId) {
    resumo.push(`pedidoId=${run.pedidoId}`);
  }
  if (run.rotaId) {
    resumo.push(`rotaId=${run.rotaId}`);
  }
  if (run.entregaId) {
    resumo.push(`entregaId=${run.entregaId}`);
  }
  if (run.timelineStatus) {
    resumo.push(`timeline=${run.timelineStatus}`);
  }
  if (run.expectedStatus) {
    resumo.push(`esperado=${run.expectedStatus}`);
  }

  const runState = run.state || "erro";
  const runStateTone = statusForRun(runState);
  const stateLabel = runState === "sucesso" ? "sucesso" : runState === "executando" ? "executando" : "erro";

  return `
    <div class="result-box">
      <p>
        <strong>Fluxo guiado E2E</strong>
        · ${tonePill(stateLabel, runStateTone)}
      </p>
      ${resumo.length > 0 ? `<p class="mono">${escapeHtml(resumo.join(" · "))}</p>` : ""}
      ${run.error ? `<p class="mono">${escapeHtml(run.error)}</p>` : ""}
      <ul class="e2e-steps">${steps}</ul>
    </div>
  `;
}

function updateApiStatus() {
  if (appState.api.connected) {
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
  const nextBase = apiInput.value.trim().replace(/\/+$/, "");
  if (!nextBase) {
    return;
  }
  appState.api.baseUrl = nextBase;
  try {
    window.localStorage.setItem(API_BASE_STORAGE_KEY, nextBase);
  } catch (_) {
    // Sem persistencia local.
  }
}

async function requestApi(path, options = {}) {
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
  try {
    await requestApi("/health");
    appState.api.connected = true;
    appState.api.lastError = null;
    await refreshOperationalReadModels();
  } catch (error) {
    appState.api.connected = false;
    appState.api.lastError = error?.message || "Falha ao conectar API";
  } finally {
    appState.api.lastSyncAt = new Date().toISOString();
    updateApiStatus();
    requestViewRender();
  }
}

async function refreshOperationalReadModels() {
  const [painelResponse, eventosResponse, mapaResponse] = await Promise.all([
    requestApi("/api/operacao/painel"),
    requestApi("/api/operacao/eventos?limite=50"),
    requestApi("/api/operacao/mapa")
  ]);
  appState.painel = painelResponse.payload;
  appState.eventosOperacionais = Array.isArray(eventosResponse.payload?.eventos)
    ? eventosResponse.payload.eventos
    : [];
  appState.mapaOperacional = mapaResponse.payload || null;
  appState.api.connected = true;
  appState.api.lastError = null;
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

function renderMapaOperacional() {
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
      for (const parada of paradas) {
        points.push({ lat: parada.lat, lon: parada.lon });
      }
      return {
        rotaId: Number(rota?.rotaId || 0),
        entregadorId: Number(rota?.entregadorId || 0),
        statusRota: String(rota?.statusRota || ""),
        camada: String(rota?.camada || ""),
        paradas
      };
    })
    .filter((rota) => rota.paradas.length > 0);

  if (rotas.length === 0) {
    return `
      <section class="notice">
        <p>Nenhuma rota com coordenadas disponiveis para hoje.</p>
      </section>
    `;
  }

  const bounds = buildMapBounds(points);
  const depositoPoint = projectMapPoint(bounds, depositoLat, depositoLon);

  const renderedRotas = rotas.map((rota, index) => {
    const color = colorForRota(index);
    const polylinePoints = [
      `${depositoPoint.x.toFixed(2)},${depositoPoint.y.toFixed(2)}`,
      ...rota.paradas.map((parada) => {
        const point = projectMapPoint(bounds, parada.lat, parada.lon);
        return `${point.x.toFixed(2)},${point.y.toFixed(2)}`;
      })
    ].join(" ");

    const markers = rota.paradas
      .map((parada) => {
        const point = projectMapPoint(bounds, parada.lat, parada.lon);
        const tone = toneForEntregaStatus(parada.statusEntrega);
        const title = `Pedido ${parada.pedidoId} · Entrega ${parada.entregaId} · ${parada.statusEntrega}`;
        return `
          <div
            class="stop ${tone}"
            style="left:${point.x.toFixed(2)}%;top:${point.y.toFixed(2)}%;"
            title="${escapeAttr(title)}"
          >
            P${escapeHtml(String(parada.ordemNaRota))}
          </div>
        `;
      })
      .join("");

    const legendTone = rota.statusRota === "EM_ANDAMENTO" ? "info" : "warn";
    const legendText = `R${rota.rotaId} · E${rota.entregadorId} · ${rota.camada}`;

    return {
      path: `<polyline points="${polylinePoints}" fill="none" stroke="${color}" stroke-width="2.8" opacity="0.92" stroke-linecap="round" stroke-linejoin="round"></polyline>`,
      markers,
      legend: `<span class="pill ${legendTone} mono">${escapeHtml(legendText)}</span>`
    };
  });

  return `
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
    </div>
    <div class="map-legend">${renderedRotas.map((rota) => rota.legend).join("")}</div>
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

function renderMetrics() {
  const painel = painelOrDefault();
  const connectionValue = appState.api.connected ? "online" : "offline";
  const cards = [
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
  const pedidos = buildPedidosRowsFromPainel(painel);

  const linhas = pedidos
    .map((pedido) => {
      const eventos = (pedido.eventos || [])
        .map((ev) => `<li class="mono">${escapeHtml(ev.hora)} · ${escapeHtml(ev.de)} -> ${escapeHtml(ev.para)} (${escapeHtml(ev.origem)})</li>`)
        .join("");

      return `
        <tr>
          <td class="mono">#${escapeHtml(String(pedido.pedidoId))}</td>
          <td>${escapeHtml(pedido.cliente || "-")}</td>
          <td>${statusPill(pedido.status)}</td>
          <td>${pedido.idempotente ? statusPill("IDEMPOTENTE") : "-"}</td>
          <td><ul>${eventos}</ul></td>
        </tr>
      `;
    })
    .join("");

  return renderStateShell(`
    <div class="panel-grid">
      <section class="panel">
        <div class="panel-header">
          <h3>Timeline de pedidos (read model real)</h3>
          <span class="pill info">/api/operacao/painel</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>Pedido</th>
              <th>Cliente</th>
              <th>Status</th>
              <th>Idempotencia</th>
              <th>Eventos</th>
            </tr>
          </thead>
          <tbody>${linhas || "<tr><td colspan=\"5\">Sem pedidos operacionais visiveis.</td></tr>"}</tbody>
        </table>
      </section>
      <section class="panel">
        <div class="panel-header">
          <h3>Novo pedido (API real)</h3>
          <span class="pill info">/api/atendimento/pedidos</span>
        </div>
        <form id="atendimento-form" class="form-grid">
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
          <div class="form-row">
            <label for="externalCallId">External call id (opcional, manual se vazio)</label>
            <input
              id="externalCallId"
              name="externalCallId"
              placeholder="call-20260213-0001"
              value="${escapeAttr(atendimentoExample.externalCallId || "")}" 
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
          <button class="btn" type="submit">Registrar pedido</button>
        </form>
        ${renderResultBox("Resposta atendimento", appState.apiResults.atendimento)}
        <hr style="border-color: rgba(73, 104, 121, 0.4); margin: 1rem 0;" />
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
        <hr style="border-color: rgba(73, 104, 121, 0.4); margin: 1rem 0;" />
        <div class="panel-header">
          <h3>Fluxo guiado E2E (usuario)</h3>
          <span class="pill info">PoC M1</span>
        </div>
        <p>
          Roda o ciclo operacional no backend real:
          atendimento -> replanejamento -> rota iniciada -> evento terminal -> replanejamento -> timeline.
        </p>
        <form id="e2e-form" class="form-grid">
          <div class="form-row two">
            <div class="form-row">
              <label for="e2eScenario">Cenario</label>
              <select id="e2eScenario" name="scenario">
                <option value="feliz" ${appState.e2e.form.scenario === "feliz" ? "selected" : ""}>feliz</option>
                <option value="falha" ${appState.e2e.form.scenario === "falha" ? "selected" : ""}>falha</option>
                <option value="cancelamento" ${appState.e2e.form.scenario === "cancelamento" ? "selected" : ""}>cancelamento</option>
              </select>
            </div>
            <div class="form-row">
              <label for="e2eMetodoPagamento">Metodo de pagamento</label>
              <select id="e2eMetodoPagamento" name="metodoPagamento">
                <option value="NAO_INFORMADO" ${appState.e2e.form.metodoPagamento === "NAO_INFORMADO" ? "selected" : ""}>NAO_INFORMADO</option>
                <option value="DINHEIRO" ${appState.e2e.form.metodoPagamento === "DINHEIRO" ? "selected" : ""}>DINHEIRO</option>
                <option value="PIX" ${appState.e2e.form.metodoPagamento === "PIX" ? "selected" : ""}>PIX</option>
                <option value="CARTAO" ${appState.e2e.form.metodoPagamento === "CARTAO" ? "selected" : ""}>CARTAO</option>
                <option value="VALE" ${appState.e2e.form.metodoPagamento === "VALE" ? "selected" : ""}>VALE</option>
              </select>
            </div>
          </div>
          <div class="form-row two">
            <div class="form-row">
              <label for="e2eTelefone">Telefone</label>
              <input id="e2eTelefone" name="telefone" value="${escapeAttr(appState.e2e.form.telefone)}" required />
            </div>
            <div class="form-row">
              <label for="e2eQuantidadeGaloes">Quantidade de galoes</label>
              <input
                id="e2eQuantidadeGaloes"
                name="quantidadeGaloes"
                type="number"
                min="1"
                value="${escapeAttr(appState.e2e.form.quantidadeGaloes)}"
                required
              />
            </div>
          </div>
          <div class="form-row">
            <label for="e2eAtendenteId">Atendente ID</label>
            <input
              id="e2eAtendenteId"
              name="atendenteId"
              type="number"
              min="1"
              value="${escapeAttr(appState.e2e.form.atendenteId)}"
              required
            />
          </div>
          <div class="form-row two">
            <div class="form-row">
              <label for="e2eDebounceSegundos">Debounce (s)</label>
              <input
                id="e2eDebounceSegundos"
                name="debounceSegundos"
                type="number"
                min="0"
                value="${escapeAttr(appState.e2e.form.debounceSegundos)}"
                required
              />
            </div>
            <div class="form-row">
              <label for="e2eLimiteEventos">Limite de eventos</label>
              <input
                id="e2eLimiteEventos"
                name="limiteEventos"
                type="number"
                min="1"
                value="${escapeAttr(appState.e2e.form.limiteEventos)}"
                required
              />
            </div>
          </div>
          <div class="form-row">
            <label for="e2eMotivoFalha">Motivo para falha (cenario falha)</label>
            <input id="e2eMotivoFalha" name="motivoFalha" value="${escapeAttr(appState.e2e.form.motivoFalha)}" />
          </div>
          <div class="form-row two">
            <div class="form-row">
              <label for="e2eMotivoCancelamento">Motivo para cancelamento</label>
              <input
                id="e2eMotivoCancelamento"
                name="motivoCancelamento"
                value="${escapeAttr(appState.e2e.form.motivoCancelamento)}"
              />
            </div>
            <div class="form-row">
              <label for="e2eCobrancaCancelamento">Cobranca cancelamento (centavos)</label>
              <input
                id="e2eCobrancaCancelamento"
                name="cobrancaCancelamentoCentavos"
                type="number"
                min="0"
                value="${escapeAttr(appState.e2e.form.cobrancaCancelamentoCentavos)}"
              />
            </div>
          </div>
          <button class="btn" type="submit" ${appState.e2e.running ? "disabled" : ""}>
            ${appState.e2e.running ? "Executando..." : "Executar fluxo guiado"}
          </button>
        </form>
        ${renderGuidedRunBox()}
      </section>
    </div>
  `);
}

function renderDespacho() {
  const eventoExample = appState.examples.eventoRequest;
  const replanejamentoExample = appState.examples.replanejamentoRequest;

  const eventos = appState.eventosOperacionais
    .map((event) => {
      const tone = String(event.eventType || "").includes("CANCELADO")
        ? "danger"
        : String(event.eventType || "").includes("ENTREGUE")
          ? "ok"
          : "info";
      const descricao = `${event.aggregateType || "-"} ${event.aggregateId ?? "-"} · ${event.status || "-"}`;
      const hora = String(event.createdEm || "").slice(11, 19) || "--:--:--";
      return `
        <div class="event-row">
          <p class="meta mono">${escapeHtml(hora)} · ${escapeHtml(String(event.eventType || "-"))}</p>
          <p class="title">${tonePill(String(event.eventType || "-"), tone)} ${escapeHtml(descricao)}</p>
        </div>
      `;
    })
    .join("");

  return renderStateShell(`
    <div class="panel-grid">
      <section class="panel">
        <div class="panel-header">
          <h3>Eventos operacionais (DB real)</h3>
          <span class="pill info">/api/operacao/eventos</span>
        </div>
        <div class="event-feed">${eventos || "<p>Nenhum evento operacional encontrado.</p>"}</div>
      </section>
      <section class="panel">
        <div class="panel-header">
          <h3>Mapa operacional (DB real)</h3>
          <span class="pill info">/api/operacao/mapa</span>
        </div>
        ${renderMapaOperacional()}
        <div class="result-box" style="margin-top: 0.75rem;">
          <p><strong>Resumo de camadas (painel)</strong></p>
          <pre class="mono">${escapeHtml(JSON.stringify(painelOrDefault().rotas || {}, null, 2))}</pre>
        </div>
      </section>
    </div>
    <section class="panel" style="margin-top: 1rem;">
      <div class="panel-header">
        <h3>Acoes operacionais (API real)</h3>
        <span class="pill info">/api/eventos + /api/replanejamento/run</span>
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
                <label for="rotaId">Rota ID (para ROTA_INICIADA)</label>
                <input id="rotaId" name="rotaId" type="number" min="1" value="${escapeAttr(eventoExample.rotaId || "")}" />
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
          <form id="replanejamento-form" class="form-grid">
            <div class="form-row two">
              <div class="form-row">
                <label for="debounceSegundos">Debounce (s)</label>
                <input
                  id="debounceSegundos"
                  name="debounceSegundos"
                  type="number"
                  min="0"
                  value="${replanejamentoExample.debounceSegundos}"
                  required
                />
              </div>
              <div class="form-row">
                <label for="limiteEventos">Limite eventos</label>
                <input
                  id="limiteEventos"
                  name="limiteEventos"
                  type="number"
                  min="1"
                  value="${replanejamentoExample.limiteEventos}"
                  required
                />
              </div>
            </div>
            <button class="btn" type="submit">Executar replanejamento</button>
          </form>
          ${renderResultBox("Resposta replanejamento", appState.apiResults.replanejamento)}
        </div>
      </div>
    </section>
  `);
}

function getViewContent() {
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
  const externalCallId = String(formData.get("externalCallId") || "").trim();
  const payload = {
    telefone: String(formData.get("telefone") || "").trim(),
    quantidadeGaloes: Number(formData.get("quantidadeGaloes")),
    atendenteId: Number(formData.get("atendenteId"))
  };
  const metodoPagamento = String(formData.get("metodoPagamento") || "").trim();
  if (metodoPagamento) {
    payload.metodoPagamento = metodoPagamento;
  }
  if (externalCallId) {
    payload.externalCallId = externalCallId;
  }

  try {
    const result = await requestApi("/api/atendimento/pedidos", { method: "POST", body: payload });
    appState.apiResults.atendimento = {
      source: "api real",
      payload: result.payload
    };

    const pedidoId = Number(result.payload?.pedidoId || 0);
    if (pedidoId > 0) {
      await syncTimelineForPedido(pedidoId);
      await refreshOperationalReadModels();
    }
  } catch (error) {
    appState.apiResults.atendimento = {
      source: "api real",
      payload: { erro: error?.message || "Falha ao registrar pedido" }
    };
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

function pushRunStep(steps, title, ok, detail, payload) {
  steps.push({
    title,
    ok,
    detail: detail || "",
    payload: payload || null
  });
}

function readE2EFormFromFormData(formData) {
  const current = appState.e2e.form;
  return {
    scenario: String(formData.get("scenario") || current.scenario || "feliz").trim().toLowerCase(),
    telefone: String(formData.get("telefone") || current.telefone || "").trim(),
    quantidadeGaloes: toFiniteNumberOr(formData.get("quantidadeGaloes"), current.quantidadeGaloes),
    atendenteId: toFiniteNumberOr(formData.get("atendenteId"), current.atendenteId),
    metodoPagamento: String(formData.get("metodoPagamento") || current.metodoPagamento || "PIX").trim() || "PIX",
    debounceSegundos: toFiniteNumberOr(formData.get("debounceSegundos"), current.debounceSegundos),
    limiteEventos: toFiniteNumberOr(formData.get("limiteEventos"), current.limiteEventos),
    motivoFalha: String(formData.get("motivoFalha") || current.motivoFalha || "").trim(),
    motivoCancelamento: String(formData.get("motivoCancelamento") || current.motivoCancelamento || "").trim(),
    cobrancaCancelamentoCentavos: toFiniteNumberOr(
      formData.get("cobrancaCancelamentoCentavos"),
      current.cobrancaCancelamentoCentavos
    )
  };
}

async function handleE2EFlowSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  appState.e2e.form = readE2EFormFromFormData(formData);
  const scenario = appState.e2e.form.scenario;

  const steps = [];
  appState.e2e.running = true;
  appState.e2e.lastRun = {
    state: "executando",
    scenario,
    steps
  };
  render();

  try {
    const atendimentoPayload = {
      externalCallId: `ui-e2e-${scenario}-${Date.now()}`,
      telefone: appState.e2e.form.telefone,
      quantidadeGaloes: appState.e2e.form.quantidadeGaloes,
      atendenteId: appState.e2e.form.atendenteId,
      metodoPagamento: appState.e2e.form.metodoPagamento
    };
    const atendimento = await requestApi("/api/atendimento/pedidos", {
      method: "POST",
      body: atendimentoPayload
    });
    appState.apiResults.atendimento = { source: "api real", payload: atendimento.payload };

    const pedidoId = Number(atendimento.payload?.pedidoId || 0);
    pushRunStep(
      steps,
      "Atendimento",
      pedidoId > 0,
      pedidoId > 0 ? `pedidoId=${pedidoId}` : "API nao retornou pedidoId",
      atendimento.payload
    );
    if (pedidoId <= 0) {
      throw new Error("API nao retornou pedidoId para o atendimento");
    }

    const replanejamentoPayload = {
      debounceSegundos: appState.e2e.form.debounceSegundos,
      limiteEventos: appState.e2e.form.limiteEventos
    };
    const replanejamentoInicial = await requestApi("/api/replanejamento/run", {
      method: "POST",
      body: replanejamentoPayload
    });
    appState.apiResults.replanejamento = { source: "api real", payload: replanejamentoInicial.payload };
    pushRunStep(
      steps,
      "Replanejamento inicial",
      true,
      `eventos=${replanejamentoInicial.payload?.eventosProcessados ?? "-"} · replanejou=${Boolean(
        replanejamentoInicial.payload?.replanejou
      )}`,
      replanejamentoInicial.payload
    );

    const execucaoAntesRota = await requestApi(buildExecucaoPath(pedidoId), { method: "GET" });
    const rotaId = Number(execucaoAntesRota.payload?.rotaId || execucaoAntesRota.payload?.rotaPrimariaId || 0);
    pushRunStep(
      steps,
      "Resolver execucao do pedido",
      rotaId > 0,
      `camada=${execucaoAntesRota.payload?.camada || "-"} · rotaId=${rotaId || "-"}`,
      execucaoAntesRota.payload
    );
    if (!Number.isInteger(rotaId) || rotaId <= 0) {
      throw new Error("Execucao do pedido nao retornou rotaId valido");
    }

    let entregaId = 0;
    let execucaoAposRota = null;
    const maxTentativasRota = 12;
    for (let tentativa = 1; tentativa <= maxTentativasRota; tentativa += 1) {
      const rotaIniciada = await requestApi("/api/eventos", {
        method: "POST",
        body: { eventType: "ROTA_INICIADA", rotaId }
      });
      appState.apiResults.evento = { source: "api real", payload: rotaIniciada.payload };
      const pedidoEvento = Number(rotaIniciada.payload?.pedidoId || 0);
      const entregaEvento = Number(rotaIniciada.payload?.entregaId || 0);
      pushRunStep(
        steps,
        "Rota iniciada",
        true,
        `tentativa=${tentativa} · rotaId=${rotaId} · pedidoId=${pedidoEvento || "-"}`,
        rotaIniciada.payload
      );

      if (pedidoEvento > 0 && pedidoEvento !== pedidoId && entregaEvento > 0) {
        const liberarFila = await requestApi("/api/eventos", {
          method: "POST",
          body: { eventType: "PEDIDO_ENTREGUE", entregaId: entregaEvento }
        });
        appState.apiResults.evento = { source: "api real", payload: liberarFila.payload };
        pushRunStep(
          steps,
          "Liberar fila da rota",
          true,
          `pedidoId=${pedidoEvento} concluido para liberar pedido alvo ${pedidoId}`,
          liberarFila.payload
        );
      }

      execucaoAposRota = await requestApi(buildExecucaoPath(pedidoId), { method: "GET" });
      entregaId = Number(execucaoAposRota.payload?.entregaAtivaId || execucaoAposRota.payload?.entregaId || 0);
      const camadaExecucao = String(execucaoAposRota.payload?.camada || "");
      const alvoEmExecucao = camadaExecucao === "PRIMARIA_EM_EXECUCAO" && Number.isInteger(entregaId) && entregaId > 0;
      pushRunStep(
        steps,
        "Resolver entrega ativa",
        true,
        `tentativa=${tentativa} · camada=${camadaExecucao || "-"} · entregaId=${entregaId || "-"}${
          alvoEmExecucao ? "" : " · aguardando vez na rota"
        }`,
        execucaoAposRota.payload
      );
      if (alvoEmExecucao) {
        break;
      }
    }

    const entregaAtivaValida = Number.isInteger(entregaId) && entregaId > 0;
    const camadaFinal = String(execucaoAposRota?.payload?.camada || "");
    if (!entregaAtivaValida || camadaFinal !== "PRIMARIA_EM_EXECUCAO") {
      throw new Error("Pedido alvo nao entrou em execucao na rota dentro do limite de tentativas.");
    }

    const eventoTerminalPayload = buildTerminalEventPayload({
      scenario,
      entregaId,
      motivoFalha: appState.e2e.form.motivoFalha,
      motivoCancelamento: appState.e2e.form.motivoCancelamento,
      cobrancaCancelamentoCentavos: appState.e2e.form.cobrancaCancelamentoCentavos
    });
    const eventoTerminal = await requestApi("/api/eventos", {
      method: "POST",
      body: eventoTerminalPayload
    });
    appState.apiResults.evento = { source: "api real", payload: eventoTerminal.payload };
    pushRunStep(
      steps,
      "Evento terminal",
      true,
      `${eventoTerminalPayload.eventType} aplicado`,
      eventoTerminal.payload
    );

    const replanejamentoFinal = await requestApi("/api/replanejamento/run", {
      method: "POST",
      body: replanejamentoPayload
    });
    appState.apiResults.replanejamento = { source: "api real", payload: replanejamentoFinal.payload };
    pushRunStep(
      steps,
      "Replanejamento final",
      true,
      `eventos=${replanejamentoFinal.payload?.eventosProcessados ?? "-"} · replanejou=${Boolean(
        replanejamentoFinal.payload?.replanejou
      )}`,
      replanejamentoFinal.payload
    );

    await syncTimelineForPedido(pedidoId);
    const timeline = appState.apiResults.timeline?.payload;
    const timelineStatus = String(timeline?.status || "").toUpperCase() || "INDEFINIDO";
    const expectedStatus = expectedStatusForScenario(scenario);
    const statusOk = timelineStatus === expectedStatus;
    pushRunStep(
      steps,
      "Timeline final",
      statusOk,
      `status=${timelineStatus} · esperado=${expectedStatus}`,
      timeline
    );

    appState.e2e.lastRun = {
      state: statusOk ? "sucesso" : "erro",
      scenario,
      pedidoId,
      rotaId,
      entregaId,
      expectedStatus,
      timelineStatus,
      steps,
      error: statusOk ? "" : "Timeline final diferente do esperado para o cenario."
    };
  } catch (error) {
    pushRunStep(
      steps,
      "Falha de execucao",
      false,
      error?.message || "Falha inesperada ao executar fluxo guiado",
      null
    );
    appState.e2e.lastRun = {
      state: "erro",
      scenario,
      steps,
      error: error?.message || "Falha inesperada ao executar fluxo guiado"
    };
  } finally {
    try {
      await refreshOperationalReadModels();
    } catch (_) {
      // Estado offline ja indicado no status.
    }
    appState.e2e.running = false;
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

  try {
    const result = await requestApi("/api/eventos", { method: "POST", body: payload });
    appState.apiResults.evento = {
      source: "api real",
      payload: result.payload
    };
    await refreshOperationalReadModels();
  } catch (error) {
    appState.apiResults.evento = {
      source: "api real",
      payload: { erro: error?.message || "Falha ao enviar evento operacional" }
    };
  } finally {
    render();
  }
}

async function handleReplanejamentoSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const payload = {
    debounceSegundos: Number(formData.get("debounceSegundos")),
    limiteEventos: Number(formData.get("limiteEventos"))
  };

  try {
    const result = await requestApi("/api/replanejamento/run", { method: "POST", body: payload });
    appState.apiResults.replanejamento = {
      source: "api real",
      payload: result.payload
    };
    await refreshOperationalReadModels();
  } catch (error) {
    appState.apiResults.replanejamento = {
      source: "api real",
      payload: { erro: error?.message || "Falha ao executar replanejamento" }
    };
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

  const e2eForm = viewRoot.querySelector("#e2e-form");
  if (e2eForm) {
    const syncE2EFormState = () => {
      appState.e2e.form = readE2EFormFromFormData(new FormData(e2eForm));
    };
    syncE2EFormState();
    e2eForm.addEventListener("input", syncE2EFormState);
    e2eForm.addEventListener("change", syncE2EFormState);
    e2eForm.addEventListener("submit", handleE2EFlowSubmit);
  }

  const eventoForm = viewRoot.querySelector("#evento-form");
  if (eventoForm) {
    eventoForm.addEventListener("submit", handleEventoSubmit);
  }

  const replanejamentoForm = viewRoot.querySelector("#replanejamento-form");
  if (replanejamentoForm) {
    replanejamentoForm.addEventListener("submit", handleReplanejamentoSubmit);
  }
}

function render() {
  renderMetrics();
  viewRoot.innerHTML = getViewContent();
  setActiveNav(appState.view);
  setActiveMode(appState.mode);
  bindViewEvents();
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
    if (["pedidos", "despacho"].includes(hashView)) {
      appState.view = hashView;
      render();
    }
  });

  apiConnectButton.addEventListener("click", () => {
    checkHealth();
  });

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
  if (["pedidos", "despacho"].includes(hashView)) {
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
