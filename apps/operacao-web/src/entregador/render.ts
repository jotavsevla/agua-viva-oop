import { formatDateTime } from "../shared/formatters";
import { escapeHtml } from "../shared/html";
import { renderPill } from "../shared/ui";
import type { AppState, DisplayTone, RoteiroEntregadorParada } from "../types";
import { buildEntregadorDeepLink, deriveEntregadorProgress } from "./model";

function toneForRouteStatus(status: string | null | undefined): DisplayTone {
  const normalized = String(status || "").toUpperCase();

  if (normalized === "EM_ANDAMENTO") {
    return "ok";
  }
  if (normalized === "PLANEJADA") {
    return "warn";
  }
  return "muted";
}

function toneForStopStatus(status: string | null | undefined): DisplayTone {
  const normalized = String(status || "").toUpperCase();

  if (normalized === "EM_EXECUCAO") {
    return "info";
  }
  if (normalized === "ENTREGUE") {
    return "ok";
  }
  if (normalized === "FALHOU" || normalized === "CANCELADA") {
    return "danger";
  }
  return "warn";
}

function renderMetric(label: string, value: string): string {
  return `
    <article class="metric-card">
      <p class="metric-label">${escapeHtml(label)}</p>
      <p class="metric-value">${escapeHtml(value)}</p>
    </article>
  `;
}

function renderParadaCard(parada: RoteiroEntregadorParada, actionBusy: boolean): string {
  const podeFinalizar = String(parada.status).toUpperCase() === "EM_EXECUCAO" && !actionBusy;

  return `
    <article class="stop-card">
      <div class="stop-card-header">
        <div>
          <strong>Parada ${escapeHtml(parada.ordemNaRota)} · Pedido #${escapeHtml(parada.pedidoId)}</strong>
          <p class="mono">Entrega ${escapeHtml(parada.entregaId)} · ${escapeHtml(parada.quantidadeGaloes)} galao(oes)</p>
        </div>
        ${renderPill(parada.status, toneForStopStatus(parada.status))}
      </div>
      <p class="stop-client">${escapeHtml(parada.clienteNome || "Cliente sem nome cadastrado")}</p>
      <div class="stop-actions">
        <button
          class="button primary"
          type="button"
          data-action="run-entregador-event"
          data-event-type="PEDIDO_ENTREGUE"
          data-entrega-id="${escapeHtml(parada.entregaId)}"
          ${podeFinalizar ? "" : "disabled"}
        >
          Confirmar entrega
        </button>
        <button
          class="button secondary"
          type="button"
          data-action="run-entregador-event"
          data-event-type="PEDIDO_FALHOU"
          data-entrega-id="${escapeHtml(parada.entregaId)}"
          ${podeFinalizar ? "" : "disabled"}
        >
          Marcar falha
        </button>
        <button
          class="button ghost danger-text"
          type="button"
          data-action="run-entregador-event"
          data-event-type="PEDIDO_CANCELADO"
          data-entrega-id="${escapeHtml(parada.entregaId)}"
          ${podeFinalizar ? "" : "disabled"}
        >
          Cancelar pedido
        </button>
      </div>
    </article>
  `;
}

function renderConcluidaCard(parada: RoteiroEntregadorParada): string {
  return `
    <article class="history-card">
      <div class="stop-card-header">
        <strong>Pedido #${escapeHtml(parada.pedidoId)}</strong>
        ${renderPill(parada.status, toneForStopStatus(parada.status))}
      </div>
      <p>${escapeHtml(parada.clienteNome || "Cliente sem nome cadastrado")}</p>
      <p class="mono">Entrega ${escapeHtml(parada.entregaId)} · parada ${escapeHtml(parada.ordemNaRota)}</p>
    </article>
  `;
}

export function renderEntregadorModule(state: AppState, currentUrl: string): string {
  const entregadorState = state.entregador;
  const roteiro = entregadorState.roteiro;
  const route = roteiro?.rota ?? null;
  const progress = deriveEntregadorProgress(roteiro);
  const deepLink = buildEntregadorDeepLink(entregadorState.entregadorId, currentUrl);
  const routeStatus = route?.status ?? "SEM_ROTA";
  const routeTone = toneForRouteStatus(routeStatus);
  const actionBusy = entregadorState.action.status === "loading";

  return `
    <section class="entregador-stack">
      <section class="panel entregador-panel entregador-hero">
        <div class="panel-header">
          <div>
            <h2>Roteiro do entregador</h2>
            <p class="section-copy">Fluxo enxuto para rua: abrir rota, concluir parada e registrar excecao sem navegar por telas pesadas.</p>
          </div>
          ${renderPill(routeStatus, routeTone)}
        </div>

        <div class="entregador-control-grid">
          <label class="field">
            <span>Entregador ID</span>
            <input
              id="entregador-id-input"
              type="number"
              min="1"
              value="${escapeHtml(entregadorState.entregadorId)}"
              inputmode="numeric"
            />
          </label>
          <div class="link-card">
            <p class="label">Deep link</p>
            <a class="deep-link" href="${escapeHtml(deepLink)}">${escapeHtml(deepLink)}</a>
          </div>
        </div>

        <div class="toolbar-actions compact">
          <button class="button secondary" type="button" data-action="load-entregador">Carregar roteiro</button>
          <button class="button ghost" type="button" data-action="copy-entregador-link">Copiar link</button>
          <button
            class="button primary"
            type="button"
            data-action="start-entregador-route"
            ${route && route.status === "PLANEJADA" && !actionBusy ? "" : "disabled"}
          >
            Iniciar rota pronta
          </button>
        </div>

        <div class="metrics-grid compact">
          ${renderMetric("Rota", route ? `R${route.rotaId}` : "Sem rota")}
          ${renderMetric("Carga remanescente", `${roteiro?.cargaRemanescente ?? 0} galao(oes)`)}
          ${renderMetric("Concluidas", String(progress.concluidas))}
          ${renderMetric("Progresso", `${progress.percentualConcluido}%`)}
        </div>

        ${
          progress.proximaParada
            ? `
              <div class="notice notice-info">
                <strong>Proxima parada:</strong> pedido #${escapeHtml(progress.proximaParada.pedidoId)} ·
                ${escapeHtml(progress.proximaParada.clienteNome || "cliente sem nome")} ·
                ${escapeHtml(progress.proximaParada.quantidadeGaloes)} galao(oes)
              </div>
            `
            : ""
        }

        ${
          entregadorState.sync.lastError
            ? `<div class="notice notice-danger"><strong>Ultimo erro:</strong> ${escapeHtml(entregadorState.sync.lastError)}</div>`
            : ""
        }

        ${
          entregadorState.lastAction
            ? `
              <div class="notice notice-${entregadorState.lastAction.tone}">
                <strong>${escapeHtml(entregadorState.lastAction.title)}:</strong> ${escapeHtml(entregadorState.lastAction.detail)}
                ${
                  entregadorState.lastAction.payload
                    ? `<p class="mono action-payload">${escapeHtml(JSON.stringify(entregadorState.lastAction.payload))}</p>`
                    : ""
                }
              </div>
            `
            : ""
        }
      </section>

      <section class="entregador-grid">
        <section class="panel entregador-panel">
          <div class="panel-header">
            <div>
              <h3>Paradas ativas</h3>
              <p class="section-copy">Acoes terminais so ficam habilitadas quando a parada estiver em execucao no backend.</p>
            </div>
            ${renderPill(String(progress.pendentes), "info")}
          </div>
          <div class="stop-list">
            ${
              roteiro && roteiro.paradasPendentesExecucao.length > 0
                ? roteiro.paradasPendentesExecucao.map((parada) => renderParadaCard(parada, actionBusy)).join("")
                : `<p class="empty-copy">Nenhuma parada pendente/em execucao para este entregador.</p>`
            }
          </div>
        </section>

        <section class="panel entregador-panel">
          <div class="panel-header">
            <div>
              <h3>Fechadas nesta rota</h3>
              <p class="section-copy">Historico rapido para validar o que ja saiu da rua.</p>
            </div>
            ${renderPill(String(progress.concluidas), "ok")}
          </div>
          <div class="history-list">
            ${
              roteiro && roteiro.paradasConcluidas.length > 0
                ? roteiro.paradasConcluidas.map(renderConcluidaCard).join("")
                : `<p class="empty-copy">Nenhuma parada concluida ainda.</p>`
            }
          </div>
        </section>
      </section>

      <section class="panel entregador-panel">
        <div class="panel-header">
          <div>
            <h3>Leitura operacional</h3>
            <p class="section-copy">Use o endpoint dedicado de inicio de rota para abrir a proxima carga planejada do entregador. Depois disso, conclua ou registre excecao direto nas paradas em execucao.</p>
          </div>
          ${renderPill(
            entregadorState.fetchedAt ? formatDateTime(entregadorState.fetchedAt) : "sem sincronizacao",
            "muted"
          )}
        </div>
        <div class="entregador-readout">
          <article class="readout-card">
            <p class="label">Status da sincronizacao</p>
            <p>${escapeHtml(entregadorState.sync.status)}</p>
          </article>
          <article class="readout-card">
            <p class="label">Total de paradas</p>
            <p>${escapeHtml(progress.totalParadas)}</p>
          </article>
          <article class="readout-card">
            <p class="label">Rota ativa</p>
            <p>${route ? `R${escapeHtml(route.rotaId)} · ${escapeHtml(route.status)}` : "Sem rota hoje"}</p>
          </article>
        </div>
      </section>
    </section>
  `;
}
