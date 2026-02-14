const API_BASE_STORAGE_KEY = "aguaVivaApiBaseUrl";
const INITIAL_API_BASE = localStorage.getItem(API_BASE_STORAGE_KEY) || "http://localhost:8081";
const CANONICAL_EXAMPLES_BASE = "../../contracts/v1/examples";
const timelineUtils = window.TimelineUtils;
const timelineFlow = window.TimelineFlow;

if (!timelineUtils) {
  throw new Error("TimelineUtils nao carregado. Verifique a ordem dos scripts.");
}
if (!timelineFlow) {
  throw new Error("TimelineFlow nao carregado. Verifique a ordem dos scripts.");
}

const {
  buildTimelinePath,
  normalizeTimelinePayload,
  mergeTimelineIntoPedido
} = timelineUtils;
const { fetchTimelineForPedido } = timelineFlow;

const appState = {
  view: "pedidos",
  mode: "success",
  api: {
    baseUrl: INITIAL_API_BASE,
    connected: false,
    lastError: null,
    lastSyncAt: null
  },
  examples: {
    loadedFromCanonical: false,
    atendimentoRequest: {
      externalCallId: "call-20260213-0001",
      telefone: "(38) 99876-1234",
      quantidadeGaloes: 2,
      atendenteId: 11
    },
    eventoRequest: {
      eventType: "PEDIDO_ENTREGUE",
      rotaId: "",
      entregaId: 55671,
      motivo: "",
      cobrancaCancelamentoCentavos: ""
    },
    replanejamentoRequest: {
      debounceSegundos: 20,
      limiteEventos: 100
    },
    timelineRequest: {
      pedidoId: 8421
    }
  },
  metrics: {
    pedidosHoje: 128,
    entregasConcluidas: 94,
    falhas: 7,
    replanejamentos: 3
  },
  pedidos: [
    {
      pedidoId: 8421,
      clienteId: 392,
      cliente: "Condominio Horizonte",
      status: "EM_ROTA",
      idempotente: false,
      eventos: [
        { hora: "07:58", de: "PENDENTE", para: "CONFIRMADO", origem: "Atendimento" },
        { hora: "09:02", de: "CONFIRMADO", para: "EM_ROTA", origem: "Despacho" }
      ]
    },
    {
      pedidoId: 8422,
      clienteId: 393,
      cliente: "Padaria Nova Vila",
      status: "ENTREGUE",
      idempotente: false,
      eventos: [
        { hora: "08:10", de: "PENDENTE", para: "CONFIRMADO", origem: "Atendimento" },
        { hora: "10:14", de: "EM_ROTA", para: "ENTREGUE", origem: "Evento operacional" }
      ]
    },
    {
      pedidoId: 8423,
      clienteId: 394,
      cliente: "Clinica Santa Livia",
      status: "CANCELADO",
      idempotente: true,
      eventos: [
        { hora: "08:22", de: "PENDENTE", para: "CONFIRMADO", origem: "Atendimento" },
        { hora: "11:05", de: "EM_ROTA", para: "CANCELADO", origem: "Evento operacional" }
      ]
    }
  ],
  extrato: {
    cliente: "Condominio Horizonte",
    saldo: 6,
    cobrancasPendentes: [{ pedidoId: 8423, valor: 2500, status: "PENDENTE" }],
    itens: [
      { data: "2026-02-13 11:05", tipo: "DEBITO", qtd: 2, saldoApos: 6, por: "entregador-44" },
      { data: "2026-02-12 16:41", tipo: "CREDITO", qtd: 10, saldoApos: 8, por: "financeiro-3" },
      { data: "2026-02-10 09:12", tipo: "DEBITO", qtd: 2, saldoApos: 0, por: "entregador-41" }
    ]
  },
  eventosDispatch: [
    {
      hora: "11:08",
      tipo: "PEDIDO_CANCELADO",
      descricao: "Pedido 8423 cancelado em rota; cobranca pendente"
    },
    {
      hora: "11:03",
      tipo: "PEDIDO_ENTREGUE",
      descricao: "Pedido 8422 entregue com sucesso"
    },
    {
      hora: "10:58",
      tipo: "ROTA_INICIADA",
      descricao: "Rota 903 iniciada para entregador 44"
    }
  ],
  stops: [
    { label: "DEP", x: 15, y: 78, level: "info" },
    { label: "P1", x: 30, y: 64, level: "warn" },
    { label: "P2", x: 52, y: 45, level: "ok" },
    { label: "P3", x: 70, y: 34, level: "danger" },
    { label: "P4", x: 84, y: 22, level: "ok" }
  ],
  apiResults: {
    atendimento: null,
    timeline: null,
    evento: null,
    replanejamento: null
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

function formatMoney(centavos) {
  return `R$ ${(centavos / 100).toFixed(2).replace(".", ",")}`;
}

function formatNow() {
  return new Date().toLocaleTimeString("pt-BR");
}

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function escapeAttr(text) {
  return escapeHtml(text).replace(/`/g, "&#96;");
}

function statusPill(status) {
  const normalized = String(status).toUpperCase();
  const tone = normalized === "ENTREGUE"
    ? "ok"
    : normalized === "CANCELADO" || normalized === "FALHOU"
      ? "danger"
      : normalized === "EM_ROTA" || normalized === "PENDENTE"
        ? "warn"
        : "info";

  return `<span class="pill ${tone}">${normalized}</span>`;
}

function tonePill(label, tone) {
  return `<span class="pill ${tone}">${label}</span>`;
}

function renderResultBox(title, result) {
  if (!result) {
    return "";
  }

  const source = result.fallback ? "mock fallback" : "api real";
  return `
    <div class="result-box">
      <p><strong>${title}</strong> · ${tonePill(source, result.fallback ? "warn" : "ok")}</p>
      <pre class="mono">${escapeHtml(JSON.stringify(result.payload, null, 2))}</pre>
    </div>
  `;
}

function getMaxPedidoId() {
  return appState.pedidos.reduce((max, p) => Math.max(max, p.pedidoId), 0);
}

function buildFallbackTimelinePayload(pedidoId) {
  const pedido = appState.pedidos.find((item) => item.pedidoId === pedidoId);
  if (!pedido) {
    return {
      pedidoId,
      statusAtual: "PENDENTE",
      eventos: []
    };
  }

  const hoje = new Date().toISOString().slice(0, 10);
  const eventos = pedido.eventos.map((evento) => {
    const hora = /^\d{2}:\d{2}$/.test(evento.hora) ? evento.hora : "00:00";
    return {
      timestamp: `${hoje}T${hora}:00`,
      deStatus: evento.de,
      paraStatus: evento.para,
      origem: evento.origem,
      observacao: evento.observacao || ""
    };
  });

  return {
    pedidoId,
    statusAtual: pedido.status,
    eventos
  };
}

function updateApiStatus() {
  if (appState.api.connected) {
    apiStatus.className = "pill ok";
    apiStatus.textContent = "API: conectada";
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
  localStorage.setItem(API_BASE_STORAGE_KEY, nextBase);
}

async function requestApi(path, options = {}, fallbackFactory) {
  const url = `${appState.api.baseUrl}${path}`;
  try {
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

    appState.api.connected = true;
    appState.api.lastError = null;
    appState.api.lastSyncAt = new Date().toISOString();
    updateApiStatus();
    return { payload, fallback: false };
  } catch (error) {
    appState.api.connected = false;
    appState.api.lastError = error?.message || String(error);
    updateApiStatus();
    if (fallbackFactory) {
      return { payload: fallbackFactory(), fallback: true };
    }
    throw error;
  }
}

async function checkHealth() {
  applyApiBaseFromInput();
  try {
    await requestApi("/health");
  } catch (_) {
    // Mantem status offline.
  } finally {
    render();
  }
}

function renderMetrics() {
  const connectionValue = appState.api.connected ? "online" : "offline";
  const examplesValue = appState.examples.loadedFromCanonical ? "canonico" : "local";
  const cards = [
    { label: "Pedidos hoje", value: appState.metrics.pedidosHoje },
    { label: "Entregas concluidas", value: appState.metrics.entregasConcluidas },
    { label: "Falhas/cancelamentos", value: appState.metrics.falhas },
    { label: "API", value: connectionValue },
    { label: "Examples", value: examplesValue }
  ];

  metricsRoot.innerHTML = cards
    .map((item, idx) => {
      return `
        <article class="metric-card" style="animation-delay:${idx * 70}ms">
          <p class="metric-label">${item.label}</p>
          <p class="metric-value">${item.value}</p>
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
    return `
      <section class="notice error">
        <h3>Falha ao carregar dados</h3>
        <p>API indisponivel. A interface esta em fallback para mock local.</p>
        <button class="btn" data-mode-restore>Tentar novamente</button>
      </section>
    `;
  }

  return content;
}

function renderPedidos() {
  const atendimentoExample = appState.examples.atendimentoRequest;
  const timelineExample = appState.examples.timelineRequest;
  const linhas = appState.pedidos
    .map((pedido) => {
      const eventos = pedido.eventos
        .map((ev) => `<li class="mono">${ev.hora} · ${ev.de} -> ${ev.para} (${ev.origem})</li>`)
        .join("");

      return `
        <tr>
          <td class="mono">#${pedido.pedidoId}</td>
          <td>${pedido.cliente}</td>
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
          <h3>Timeline de pedidos</h3>
          <span class="pill info">B2 target</span>
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
          <tbody>${linhas}</tbody>
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
      </section>
    </div>
  `);
}

function renderFinanceiro() {
  const cobrancas = appState.extrato.cobrancasPendentes
    .map((c) => {
      return `
        <div class="event-row">
          <p class="meta mono">Pedido #${c.pedidoId}</p>
          <p class="title">Cobranca ${formatMoney(c.valor)} · ${statusPill(c.status)}</p>
        </div>
      `;
    })
    .join("");

  const itens = appState.extrato.itens
    .map((i) => {
      const tone = i.tipo === "CREDITO" ? "ok" : "warn";
      return `
        <tr>
          <td class="mono">${i.data}</td>
          <td>${tonePill(i.tipo, tone)}</td>
          <td><span class="pill ${tone}">${i.qtd}</span></td>
          <td class="mono">${i.saldoApos}</td>
          <td class="mono">${i.por}</td>
        </tr>
      `;
    })
    .join("");

  return renderStateShell(`
    <div class="panel-grid">
      <section class="panel">
        <div class="panel-header">
          <h3>Saldo e cobrancas</h3>
          <span class="pill warn">A3 handoff</span>
        </div>
        <p>Cliente: <strong>${appState.extrato.cliente}</strong></p>
        <p>Saldo atual: <strong class="mono">${appState.extrato.saldo} vales</strong></p>
        <div class="event-feed">${cobrancas || "<p>Nenhuma cobranca pendente.</p>"}</div>
      </section>
      <section class="panel">
        <h3>Extrato</h3>
        <table>
          <thead>
            <tr>
              <th>Data</th>
              <th>Tipo</th>
              <th>QTD</th>
              <th>Saldo apos</th>
              <th>Registrado por</th>
            </tr>
          </thead>
          <tbody>${itens}</tbody>
        </table>
      </section>
    </div>
  `);
}

function renderMap() {
  const stops = appState.stops
    .map((stop) => {
      return `<div class="stop ${stop.level}" style="left:${stop.x}%;top:${stop.y}%">${stop.label}</div>`;
    })
    .join("");

  return `
    <div class="map-box">
      <div class="map-grid"></div>
      <svg class="map-route" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <polyline
          points="15,78 30,64 52,45 70,34 84,22"
          fill="none"
          stroke="rgba(79, 201, 221, 0.9)"
          stroke-width="1.2"
          stroke-dasharray="1.5 1"
        ></polyline>
      </svg>
      ${stops}
    </div>
  `;
}

function renderDespacho() {
  const eventoExample = appState.examples.eventoRequest;
  const replanejamentoExample = appState.examples.replanejamentoRequest;
  const eventos = appState.eventosDispatch
    .map((event) => {
      const tone = event.tipo.includes("CANCELADO")
        ? "danger"
        : event.tipo.includes("ENTREGUE")
          ? "ok"
          : "info";
      return `
        <div class="event-row">
          <p class="meta mono">${event.hora} · ${event.tipo}</p>
          <p class="title">${tonePill(event.tipo, tone)} ${event.descricao}</p>
        </div>
      `;
    })
    .join("");

  return renderStateShell(`
    <div class="panel-grid">
      <section class="panel">
        <div class="panel-header">
          <h3>Eventos operacionais</h3>
          <span class="pill info">A4 stream</span>
        </div>
        <div class="event-feed">${eventos}</div>
      </section>
      <section class="panel">
        <div class="panel-header">
          <h3>Mapa (placeholder B4)</h3>
          <span class="pill warn">Leaflet depois</span>
        </div>
        ${renderMap()}
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
                <label for="rotaId">Rota ID (para ROTA_INICIADA)</label>
                <input id="rotaId" name="rotaId" type="number" min="1" value="${escapeAttr(eventoExample.rotaId || "")}" />
              </div>
              <div class="form-row">
                <label for="entregaId">Entrega ID (demais eventos)</label>
                <input id="entregaId" name="entregaId" type="number" min="1" value="${escapeAttr(eventoExample.entregaId || "")}" />
              </div>
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
                  min="1"
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
  switch (appState.view) {
    case "financeiro":
      viewTitle.textContent = "Financeiro";
      return renderFinanceiro();
    case "despacho":
      viewTitle.textContent = "Despacho";
      return renderDespacho();
    case "pedidos":
    default:
      viewTitle.textContent = "Pedidos";
      return renderPedidos();
  }
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

async function handleAtendimentoSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const externalCallId = String(formData.get("externalCallId") || "").trim();
  const payload = {
    telefone: String(formData.get("telefone") || "").trim(),
    quantidadeGaloes: Number(formData.get("quantidadeGaloes")),
    atendenteId: Number(formData.get("atendenteId"))
  };
  if (externalCallId) {
    payload.externalCallId = externalCallId;
  }

  const result = await requestApi(
    "/api/atendimento/pedidos",
    { method: "POST", body: payload },
    () => {
      const existing = appState.pedidos.find(
        (pedido) =>
          pedido.status === "PENDENTE" ||
          pedido.status === "CONFIRMADO" ||
          pedido.status === "EM_ROTA"
      );
      if (existing) {
        return {
          pedidoId: existing.pedidoId,
          clienteId: existing.clienteId,
          telefoneNormalizado: payload.telefone.replace(/\D/g, ""),
          clienteCriado: false,
          idempotente: true
        };
      }
      const pedidoId = getMaxPedidoId() + 1;
      return {
        pedidoId,
        clienteId: 500 + pedidoId,
        telefoneNormalizado: payload.telefone.replace(/\D/g, ""),
        clienteCriado: true,
        idempotente: false
      };
    }
  );

  appState.apiResults.atendimento = result;

  const exists = appState.pedidos.find((pedido) => pedido.pedidoId === result.payload.pedidoId);
  if (!exists) {
    appState.pedidos.unshift({
      pedidoId: result.payload.pedidoId,
      clienteId: result.payload.clienteId,
      cliente: `Cliente ${result.payload.clienteId}`,
      status: "PENDENTE",
      idempotente: Boolean(result.payload.idempotente),
      eventos: [
        {
          hora: formatNow(),
          de: "PENDENTE",
          para: "PENDENTE",
          origem: result.fallback ? "Mock fallback" : "API atendimento"
        }
      ]
    });
    appState.metrics.pedidosHoje += 1;
  }

  await syncTimelineForPedido(result.payload.pedidoId);
  render();
}

async function handleTimelineSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const pedidoId = Number(formData.get("pedidoId"));

  await syncTimelineForPedido(pedidoId);
  render();
}

async function syncTimelineForPedido(pedidoId) {
  try {
    const result = await fetchTimelineForPedido({
      pedidoId,
      requestApi,
      buildTimelinePath,
      normalizeTimelinePayload,
      buildFallbackTimelinePayload
    });
    const timeline = result.payload;
    appState.apiResults.timeline = {
      ...result,
      payload: timeline
    };
    appState.examples.timelineRequest = { pedidoId: timeline.pedidoId };

    const index = appState.pedidos.findIndex((pedido) => pedido.pedidoId === timeline.pedidoId);
    if (index >= 0) {
      appState.pedidos[index] = mergeTimelineIntoPedido(appState.pedidos[index], timeline);
    } else {
      appState.pedidos.unshift({
        pedidoId: timeline.pedidoId,
        clienteId: 0,
        cliente: "Cliente nao identificado",
        status: timeline.status || "PENDENTE",
        idempotente: false,
        eventos: timeline.eventos
      });
    }
  } catch (error) {
    appState.apiResults.timeline = {
      fallback: true,
      payload: { erro: error?.message || "Falha ao carregar timeline" }
    };
  }
}

async function handleEventoSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const eventType = String(formData.get("eventType") || "");
  const rotaId = Number(formData.get("rotaId"));
  const entregaId = Number(formData.get("entregaId"));
  const motivo = String(formData.get("motivo") || "").trim();
  const cobrancaCancelamentoCentavos = Number(formData.get("cobrancaCancelamentoCentavos"));

  const payload = { eventType };
  if (eventType === "ROTA_INICIADA" && rotaId > 0) {
    payload.rotaId = rotaId;
  }
  if (eventType !== "ROTA_INICIADA" && entregaId > 0) {
    payload.entregaId = entregaId;
  }
  if (motivo) {
    payload.motivo = motivo;
  }
  if (eventType === "PEDIDO_CANCELADO" && !Number.isNaN(cobrancaCancelamentoCentavos)) {
    payload.cobrancaCancelamentoCentavos = cobrancaCancelamentoCentavos;
  }

  const result = await requestApi(
    "/api/eventos",
    { method: "POST", body: payload },
    () => {
      return {
        evento: eventType,
        rotaId: payload.rotaId || 0,
        entregaId: payload.entregaId || 0,
        pedidoId: payload.entregaId ? payload.entregaId + 3000 : 0,
        idempotente: false
      };
    }
  );

  appState.apiResults.evento = result;
  appState.eventosDispatch.unshift({
    hora: formatNow(),
    tipo: result.payload.evento,
    descricao: `Evento recebido para pedido ${result.payload.pedidoId || "-"}.`
  });
  appState.eventosDispatch = appState.eventosDispatch.slice(0, 8);

  render();
}

async function handleReplanejamentoSubmit(event) {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const payload = {
    debounceSegundos: Number(formData.get("debounceSegundos")),
    limiteEventos: Number(formData.get("limiteEventos"))
  };

  const result = await requestApi(
    "/api/replanejamento/run",
    { method: "POST", body: payload },
    () => {
      return {
        eventosProcessados: 2,
        replanejou: true,
        rotasCriadas: 1,
        entregasCriadas: 3,
        pedidosNaoAtendidos: 0
      };
    }
  );

  appState.apiResults.replanejamento = result;
  appState.metrics.replanejamentos += 1;
  render();
}

async function loadCanonicalExamples() {
  const files = {
    atendimentoRequest: "atendimento-pedido.request.json",
    atendimentoResponse: "atendimento-pedido.response.json",
    eventoRequest: "evento-operacional.request.json",
    replanejamentoRequest: "replanejamento-run.request.json"
  };

  try {
    const [atendimento, atendimentoResponse, evento, replanejamento] = await Promise.all([
      fetch(`${CANONICAL_EXAMPLES_BASE}/${files.atendimentoRequest}`),
      fetch(`${CANONICAL_EXAMPLES_BASE}/${files.atendimentoResponse}`),
      fetch(`${CANONICAL_EXAMPLES_BASE}/${files.eventoRequest}`),
      fetch(`${CANONICAL_EXAMPLES_BASE}/${files.replanejamentoRequest}`)
    ]);

    if (!atendimento.ok || !atendimentoResponse.ok || !evento.ok || !replanejamento.ok) {
      throw new Error("Falha ao carregar exemplos canonicos");
    }

    appState.examples.atendimentoRequest = await atendimento.json();
    const atendimentoPayload = await atendimentoResponse.json();
    appState.examples.eventoRequest = await evento.json();
    appState.examples.replanejamentoRequest = await replanejamento.json();
    appState.examples.timelineRequest = {
      pedidoId: Number(atendimentoPayload.pedidoId) || appState.examples.timelineRequest.pedidoId
    };
    appState.examples.loadedFromCanonical = true;
  } catch (_) {
    appState.examples.loadedFromCanonical = false;
  }
}

function bindViewEvents() {
  const restore = viewRoot.querySelector("[data-mode-restore]");
  if (restore) {
    restore.addEventListener("click", () => {
      appState.mode = "success";
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
  const now = new Date();
  clock.textContent = now.toLocaleTimeString("pt-BR");
}

function bindStaticEvents() {
  document.querySelectorAll(".nav-item").forEach((button) => {
    button.addEventListener("click", () => setView(button.dataset.view));
  });

  document.querySelectorAll(".mode").forEach((button) => {
    button.addEventListener("click", () => setMode(button.dataset.mode));
  });

  window.addEventListener("hashchange", () => {
    const hashView = window.location.hash.replace("#", "");
    if (["pedidos", "financeiro", "despacho"].includes(hashView)) {
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
}

function init() {
  const hashView = window.location.hash.replace("#", "");
  if (["pedidos", "financeiro", "despacho"].includes(hashView)) {
    appState.view = hashView;
  }

  bindStaticEvents();
  tickClock();
  render();
  loadCanonicalExamples().finally(() => render());
  checkHealth();
  setInterval(tickClock, 1000);
}

updateApiStatus();
init();
