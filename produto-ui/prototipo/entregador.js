const API_BASE_STORAGE_KEY = "aguaVivaApiBaseUrl";
const FALLBACK_API_BASE = "http://localhost:8082";
const ROTEIRO_AUTO_REFRESH_MS = 4000;
let roteiroRefreshTimerId = null;
let roteiroRefreshInFlight = false;

const state = {
  apiBase: readStoredApiBase(),
  connected: false,
  lastError: null,
  lastSyncAt: null,
  roteiro: null,
  entregadorId: 1,
  lastEvento: null
};

const apiBaseInput = document.getElementById("api-base");
const apiConnectButton = document.getElementById("api-connect");
const apiStatus = document.getElementById("api-status");
const roteiroForm = document.getElementById("roteiro-form");
const eventoForm = document.getElementById("evento-form");
const iniciarRotaButton = document.getElementById("iniciar-rota");
const roteiroBadge = document.getElementById("roteiro-badge");
const roteiroSummary = document.getElementById("roteiro-summary");
const roteiroPendentes = document.getElementById("roteiro-pendentes");
const roteiroConcluidas = document.getElementById("roteiro-concluidas");
const eventoResult = document.getElementById("evento-result");

apiBaseInput.value = state.apiBase;
updateApiStatus();
renderRoteiro();
renderEventoResult();
void carregarRoteiroComLock();
startRoteiroAutoRefresh();

apiConnectButton.addEventListener("click", async () => {
  applyApiBaseFromInput();
  try {
    await requestApi("/health");
  } catch (_) {
    // Status ja atualizado em requestApi.
  }
  await carregarRoteiroComLock();
});

roteiroForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const entregadorId = Number(formData.get("entregadorId"));
  if (!Number.isInteger(entregadorId) || entregadorId <= 0) {
    state.lastError = "entregadorId invalido";
    updateApiStatus();
    return;
  }

  state.entregadorId = entregadorId;
  await carregarRoteiroComLock();
});

eventoForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const formData = new FormData(event.currentTarget);
  const payload = {
    eventType: String(formData.get("eventType") || "").trim(),
    entregaId: Number(formData.get("entregaId")),
    actorEntregadorId: state.entregadorId
  };

  const motivo = String(formData.get("motivo") || "").trim();
  const externalEventId = String(formData.get("externalEventId") || "").trim();
  const cobranca = Number(formData.get("cobrancaCancelamentoCentavos"));

  if (motivo) {
    payload.motivo = motivo;
  }
  if (externalEventId) {
    payload.externalEventId = externalEventId;
  }
  if (payload.eventType === "PEDIDO_CANCELADO" && Number.isInteger(cobranca) && cobranca >= 0) {
    payload.cobrancaCancelamentoCentavos = cobranca;
  }

  try {
    const result = await requestApi("/api/eventos", {
      method: "POST",
      body: payload
    });
    state.lastEvento = {
      ok: true,
      payload: result.payload,
      message: result.payload.idempotente
        ? "Evento idempotente/ignorado (sem efeito duplicado)."
        : "Evento confirmado com sucesso."
    };
    await carregarRoteiroComLock();
  } catch (error) {
    state.lastEvento = {
      ok: false,
      payload: null,
      message: error?.message || "Falha ao enviar evento"
    };
    renderEventoResult();
  }
});

iniciarRotaButton.addEventListener("click", async () => {
  const rotaId = state.roteiro?.rota?.rotaId;
  if (!Number.isInteger(rotaId) || rotaId <= 0) {
    state.lastEvento = {
      ok: false,
      payload: null,
      message: "Rota ativa nao encontrada para iniciar"
    };
    renderEventoResult();
    return;
  }

  try {
    const result = await requestApi("/api/eventos", {
      method: "POST",
      body: {
        eventType: "ROTA_INICIADA",
        rotaId,
        actorEntregadorId: state.entregadorId
      }
    });
    state.lastEvento = {
      ok: true,
      payload: result.payload,
      message: result.payload.idempotente
        ? "ROTA_INICIADA idempotente/ignorado."
        : "ROTA_INICIADA confirmada."
    };
    await carregarRoteiroComLock();
  } catch (error) {
    state.lastEvento = {
      ok: false,
      payload: null,
      message: error?.message || "Falha ao iniciar rota"
    };
    renderEventoResult();
  }
});

async function carregarRoteiro() {
  try {
    const result = await requestApi(`/api/entregadores/${state.entregadorId}/roteiro`);
    state.roteiro = result.payload;
  } catch (_) {
    state.roteiro = null;
  }
  renderRoteiro();
  renderEventoResult();
}

async function carregarRoteiroComLock() {
  if (roteiroRefreshInFlight) {
    return;
  }

  roteiroRefreshInFlight = true;
  try {
    await carregarRoteiro();
  } finally {
    roteiroRefreshInFlight = false;
  }
}

function renderRoteiro() {
  const roteiro = state.roteiro;
  if (!roteiro) {
    roteiroBadge.className = "pill warn";
    roteiroBadge.textContent = "sem dados";
    roteiroSummary.innerHTML = `<p>Nenhum roteiro carregado.</p>`;
    roteiroPendentes.innerHTML = "";
    roteiroConcluidas.innerHTML = "";
    return;
  }

  const rota = roteiro.rota;
  if (!rota) {
    roteiroBadge.className = "pill warn";
    roteiroBadge.textContent = "sem rota";
    roteiroSummary.innerHTML = `
      <p><strong>Entregador:</strong> ${roteiro.entregadorId}</p>
      <p>Sem rota ativa/planejada para hoje.</p>
      <p><strong>Carga remanescente:</strong> ${roteiro.cargaRemanescente}</p>
    `;
    roteiroPendentes.innerHTML = "";
    roteiroConcluidas.innerHTML = "";
    return;
  }

  const statusRota = String(rota.status || "").toUpperCase();
  const badgeClass = statusRota === "EM_ANDAMENTO" ? "pill ok" : "pill info";
  roteiroBadge.className = badgeClass;
  roteiroBadge.textContent = `rota ${statusRota.toLowerCase()}`;

  roteiroSummary.innerHTML = `
    <p><strong>Entregador:</strong> ${roteiro.entregadorId}</p>
    <p><strong>Rota:</strong> #${rota.rotaId} (${statusRota})</p>
    <p><strong>Carga remanescente:</strong> ${roteiro.cargaRemanescente}</p>
  `;

  roteiroPendentes.innerHTML = renderParadas(
    "Paradas pendentes/em execucao",
    roteiro.paradasPendentesExecucao || []
  );
  roteiroConcluidas.innerHTML = renderParadas(
    "Paradas concluidas",
    roteiro.paradasConcluidas || []
  );
}

function renderParadas(title, paradas) {
  if (!Array.isArray(paradas) || paradas.length === 0) {
    return `
      <div class="result-box">
        <p><strong>${escapeHtml(title)}</strong></p>
        <p>Nenhuma parada.</p>
      </div>
    `;
  }

  const rows = paradas
    .map(
      (p) => `
        <tr>
          <td class="mono">${p.entregaId}</td>
          <td class="mono">${p.pedidoId}</td>
          <td>${escapeHtml(String(p.clienteNome || ""))}</td>
          <td>${escapeHtml(String(p.status || ""))}</td>
          <td>${p.quantidadeGaloes}</td>
        </tr>`
    )
    .join("");

  return `
    <div class="result-box">
      <p><strong>${escapeHtml(title)}</strong></p>
      <table>
        <thead>
          <tr>
            <th>Entrega</th>
            <th>Pedido</th>
            <th>Cliente</th>
            <th>Status</th>
            <th>Galoes</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
  `;
}

function renderEventoResult() {
  if (!state.lastEvento) {
    eventoResult.innerHTML = `<p>Aguardando acao operacional.</p>`;
    return;
  }

  const toneClass = state.lastEvento.ok ? "pill ok" : "pill danger";
  const toneLabel = state.lastEvento.ok ? "ok" : "erro";
  const payloadBlock = state.lastEvento.payload
    ? `<pre class="mono">${escapeHtml(JSON.stringify(state.lastEvento.payload, null, 2))}</pre>`
    : "";

  eventoResult.innerHTML = `
    <p>${pill(toneLabel, toneClass)}</p>
    <p>${escapeHtml(state.lastEvento.message || "")}</p>
    ${payloadBlock}
  `;
}

function pill(label, cssClass) {
  return `<span class="${cssClass}">${escapeHtml(label)}</span>`;
}

async function requestApi(path, options = {}) {
  const method = options.method || "GET";
  const url = `${state.apiBase}${path}`;

  const response = await fetch(url, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  });

  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    state.connected = false;
    state.lastError = payload?.erro || `HTTP ${response.status}`;
    updateApiStatus();
    throw new Error(state.lastError);
  }

  state.connected = true;
  state.lastError = null;
  state.lastSyncAt = new Date().toISOString();
  updateApiStatus();
  return { payload };
}

function updateApiStatus() {
  if (state.connected) {
    apiStatus.className = "pill ok";
    apiStatus.textContent = `API: conectada · auto ${Math.round(ROTEIRO_AUTO_REFRESH_MS / 1000)}s`;
    return;
  }

  if (state.lastError) {
    apiStatus.className = "pill danger";
    apiStatus.textContent = "API: offline";
    return;
  }

  apiStatus.className = "pill warn";
  apiStatus.textContent = "API: pendente";
}

function applyApiBaseFromInput() {
  const nextBase = apiBaseInput.value.trim().replace(/\/+$/, "");
  if (!nextBase) {
    return;
  }
  state.apiBase = nextBase;
  try {
    window.localStorage.setItem(API_BASE_STORAGE_KEY, nextBase);
  } catch (_) {
    // Sem persistencia local.
  }
}

function stopRoteiroAutoRefresh() {
  if (roteiroRefreshTimerId !== null) {
    clearInterval(roteiroRefreshTimerId);
    roteiroRefreshTimerId = null;
  }
}

function startRoteiroAutoRefresh() {
  stopRoteiroAutoRefresh();
  roteiroRefreshTimerId = window.setInterval(() => {
    if (document.hidden) {
      return;
    }
    void carregarRoteiroComLock();
  }, ROTEIRO_AUTO_REFRESH_MS);

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      void carregarRoteiroComLock();
    }
  });

  window.addEventListener("beforeunload", stopRoteiroAutoRefresh);
}

function readStoredApiBase() {
  try {
    const stored = window.localStorage.getItem(API_BASE_STORAGE_KEY);
    return stored || FALLBACK_API_BASE;
  } catch (_) {
    return FALLBACK_API_BASE;
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
