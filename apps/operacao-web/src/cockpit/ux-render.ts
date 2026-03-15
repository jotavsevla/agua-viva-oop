import { escapeHtml } from "../shared/html";
import { renderPill } from "../shared/ui";
import type {
  CockpitAlertViewModel,
  CockpitEventViewModel,
  CockpitMapRouteViewModel,
  CockpitMapSummaryCardViewModel,
  CockpitMetricViewModel,
  CockpitQueueCardViewModel,
  CockpitQueueLaneViewModel,
  CockpitReadinessItemViewModel,
  CockpitRouteCardViewModel,
  CockpitSignalViewModel,
  CockpitViewModel
} from "./ux-view-model";

function renderSignal(signal: CockpitSignalViewModel): string {
  return `
    <article class="signal-card tone-${signal.tone}">
      <p class="panel-kicker">${escapeHtml(signal.label)}</p>
      <strong>${escapeHtml(signal.value)}</strong>
      <p>${escapeHtml(signal.detail)}</p>
    </article>
  `;
}

function renderMetric(metric: CockpitMetricViewModel): string {
  return `
    <article class="metric-card tone-${metric.tone}">
      <p class="metric-label">${escapeHtml(metric.label)}</p>
      <p class="metric-value">${escapeHtml(metric.value)}</p>
      <p class="metric-detail">${escapeHtml(metric.detail)}</p>
    </article>
  `;
}

function renderAlert(alert: CockpitAlertViewModel): string {
  return `
    <article class="priority-card tone-${alert.tone}">
      <div class="priority-topline">
        ${renderPill(alert.badge, alert.tone)}
        <strong>${escapeHtml(alert.title)}</strong>
      </div>
      <p>${escapeHtml(alert.detail)}</p>
      <p class="priority-action">${escapeHtml(alert.action)}</p>
    </article>
  `;
}

function renderRouteCard(card: CockpitRouteCardViewModel): string {
  return `
    <article class="route-card tone-${card.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${escapeHtml(card.title)}</p>
          <p class="card-copy">${escapeHtml(card.detail)}</p>
        </div>
        ${renderPill(card.badgeLabel, card.badgeTone)}
      </div>
      <div class="route-stat-row mono">
        ${card.meta.map((item) => `<span>${escapeHtml(item)}</span>`).join("")}
      </div>
    </article>
  `;
}

function renderQueueCard(card: CockpitQueueCardViewModel): string {
  return `
    <article class="queue-card tone-${card.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${escapeHtml(card.title)}</p>
          <p class="card-copy">${escapeHtml(card.summary)}</p>
        </div>
        ${renderPill(card.badgeLabel, card.badgeTone)}
      </div>
      <div class="queue-card-stats mono">
        ${card.lines.map((line) => `<span>${escapeHtml(line)}</span>`).join("")}
      </div>
    </article>
  `;
}

function renderQueueLane(lane: CockpitQueueLaneViewModel): string {
  return `
    <section class="queue-lane tone-${lane.tone}">
      <div class="queue-lane-header">
        <div>
          <p class="panel-kicker">Etapa ${escapeHtml(lane.step)}</p>
          <h3>${escapeHtml(lane.title)}</h3>
          <p class="queue-lane-copy">${escapeHtml(lane.summary)}</p>
        </div>
        ${renderPill(`${lane.count} item(ns)`, lane.tone)}
      </div>
      <div class="queue-list">
        ${lane.cards.length ? lane.cards.map(renderQueueCard).join("") : `<p class="empty-copy">${escapeHtml(lane.emptyMessage)}</p>`}
      </div>
    </section>
  `;
}

function renderReadinessItem(item: CockpitReadinessItemViewModel): string {
  return `
    <article class="readiness-item tone-${item.tone}">
      <p class="panel-kicker">${escapeHtml(item.label)}</p>
      <strong>${escapeHtml(item.title)}</strong>
      <p>${escapeHtml(item.detail)}</p>
    </article>
  `;
}

function renderEvent(event: CockpitEventViewModel): string {
  return `
    <article class="event-item tone-${event.tone}">
      <div class="event-topline">
        <div>
          <p class="card-title">${escapeHtml(event.title)}</p>
          <p class="card-copy">${escapeHtml(event.subject)}</p>
        </div>
        ${renderPill(event.badgeLabel, event.badgeTone)}
      </div>
      <p>${escapeHtml(event.detail)}</p>
      <p class="event-meta mono">${escapeHtml(event.meta)}</p>
    </article>
  `;
}

function renderMapSummaryCard(card: CockpitMapSummaryCardViewModel): string {
  return `
    <article class="micro-card tone-${card.tone}">
      <p class="panel-kicker">${escapeHtml(card.label)}</p>
      <strong>${escapeHtml(card.value)}</strong>
      <p>${escapeHtml(card.detail)}</p>
    </article>
  `;
}

function renderMapRoute(route: CockpitMapRouteViewModel): string {
  return `
    <article class="route-card large tone-${route.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${escapeHtml(route.title)}</p>
          <p class="card-copy">${escapeHtml(route.summary)}</p>
        </div>
        ${renderPill(route.badgeLabel, route.badgeTone)}
      </div>
      <p class="mono">${escapeHtml(route.detail)}</p>
      <div class="tag-row">
        ${route.tags.map((tag) => renderPill(tag, "muted")).join("")}
      </div>
    </article>
  `;
}

export function renderCockpit(viewModel: CockpitViewModel): string {
  return `
    <section class="panel cockpit-overview">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Cockpit operacional</p>
          <h2>${escapeHtml(viewModel.headline)}</h2>
          <p class="section-copy">${escapeHtml(viewModel.executiveSummary)}</p>
        </div>
        ${renderPill(viewModel.modeLabel, viewModel.modeTone)}
      </div>
      <div class="hero-signals">
        ${viewModel.signals.map(renderSignal).join("")}
      </div>
    </section>

    <section class="metrics-grid">
      ${viewModel.metrics.map(renderMetric).join("")}
    </section>

    <section class="panel priority-panel">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Direcionamento imediato</p>
          <h2>O que merece atencao primeiro</h2>
          <p class="section-copy">${escapeHtml(viewModel.nextActionDetail)}</p>
        </div>
        ${renderPill(viewModel.nextAction, viewModel.nextActionTone)}
      </div>
      <div class="priority-layout">
        <article class="priority-lead tone-${viewModel.leadAlert.tone}">
          <div class="priority-topline">
            ${renderPill("foco da rodada", viewModel.leadAlert.tone)}
            <strong>${escapeHtml(viewModel.leadAlert.title)}</strong>
          </div>
          <p>${escapeHtml(viewModel.leadAlert.detail)}</p>
          <p class="priority-action">${escapeHtml(viewModel.leadAlert.action)}</p>
        </article>
        <div class="priority-support">
          ${
            viewModel.supportingAlerts.length
              ? viewModel.supportingAlerts.map(renderAlert).join("")
              : `
                <article class="priority-card tone-ok">
                  <div class="priority-topline">
                    ${renderPill("estavel", "ok")}
                    <strong>Sem pressao adicional</strong>
                  </div>
                  <p>Os indicadores desta rodada nao mostraram um segundo foco concorrendo com a prioridade principal.</p>
                  <p class="priority-action">Mantenha o monitoramento normal enquanto a operacao seguir limpa.</p>
                </article>
              `
          }
        </div>
      </div>
      <div class="pulse-grid">
        ${viewModel.pulses
          .map(
            (pulse) => `
              <article class="pulse-card">
                <p class="panel-kicker">${escapeHtml(pulse.label)}</p>
                <strong>${escapeHtml(pulse.value)}</strong>
              </article>
            `
          )
          .join("")}
      </div>
    </section>

    <main class="content-grid">
      <div class="content-main">
        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Fluxo operacional</p>
              <h2>Triagem por etapa</h2>
              <p class="section-copy">${escapeHtml(viewModel.nextActionDetail)}</p>
            </div>
            ${renderPill(viewModel.panelUpdatedAt, "muted")}
          </div>
          <div class="routes-board">
            <article class="route-group tone-${viewModel.activeRoutes.length > 0 ? "info" : "ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas em campo</p>
                  <strong class="route-group-count">${escapeHtml(String(viewModel.activeRoutes.length))}</strong>
                </div>
                ${renderPill(viewModel.activeRoutes.length > 0 ? `${viewModel.activeRoutes.length} rota(s)` : "sem rota", viewModel.activeRoutes.length > 0 ? "info" : "ok")}
              </div>
              <p class="section-copy">${escapeHtml(viewModel.activeRoutes.length > 0 ? "Entregas rodando e pedindo acompanhamento de execucao." : "Nenhuma rota ativa na leitura atual.")}</p>
              <div class="route-list">
                ${viewModel.activeRoutes.length ? viewModel.activeRoutes.map(renderRouteCard).join("") : "<p class=\"empty-copy\">Sem rota ativa.</p>"}
              </div>
            </article>
            <article class="route-group tone-${viewModel.plannedRoutes.length > 0 ? "warn" : "ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas prontas</p>
                  <strong class="route-group-count">${escapeHtml(String(viewModel.plannedRoutes.length))}</strong>
                </div>
                ${renderPill(viewModel.plannedRoutes.length > 0 ? `${viewModel.plannedRoutes.length} rota(s)` : "sem rota", viewModel.plannedRoutes.length > 0 ? "warn" : "ok")}
              </div>
              <p class="section-copy">${escapeHtml(viewModel.plannedRoutes.length > 0 ? "Carga ja comprometida aguardando decisao para ganhar rua." : "Nenhuma rota pronta aguardando liberacao.")}</p>
              <div class="route-list">
                ${viewModel.plannedRoutes.length ? viewModel.plannedRoutes.map(renderRouteCard).join("") : "<p class=\"empty-copy\">Sem rota planejada.</p>"}
              </div>
            </article>
          </div>
          <div class="queue-grid">
            ${viewModel.queueLanes.map(renderQueueLane).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Mapa operacional</p>
              <h2>Camadas e rotas</h2>
              <p class="section-copy">${escapeHtml(viewModel.pulses.find((pulse) => pulse.label === "Rotas")?.value || "Sem resumo de rotas.")}</p>
            </div>
            ${viewModel.mapDeposit ? renderPill(viewModel.mapDeposit, "muted") : ""}
          </div>
          <div class="micro-grid">
            ${viewModel.mapSummaryCards.map(renderMapSummaryCard).join("")}
          </div>
          <div class="route-list">
            ${viewModel.mapRoutes.length ? viewModel.mapRoutes.map(renderMapRoute).join("") : "<p class=\"empty-copy\">Sem rotas mapeadas.</p>"}
          </div>
        </section>
      </div>

      <aside class="content-side">
        <section class="panel readiness-panel">
          <div class="readiness-summary">
            <div>
              <p class="panel-kicker">Confianca da leitura</p>
              <h2>${escapeHtml(viewModel.confidenceLabel)}</h2>
              <p class="section-copy">${escapeHtml(viewModel.confidenceDetail)}</p>
            </div>
            <div class="readiness-meta">
              ${renderPill(viewModel.readinessStatus.title, viewModel.readinessStatus.tone)}
            </div>
          </div>
          <div class="readiness-grid">
            ${viewModel.readinessItems.map(renderReadinessItem).join("")}
          </div>
          ${viewModel.notices
            .map(
              (notice) => `
                <div class="notice notice-${notice.tone}">
                  <strong>${escapeHtml(notice.label)}:</strong> ${escapeHtml(notice.body)}
                </div>
              `
            )
            .join("")}
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Ocorrencias recentes</p>
              <h2>Feed de excecoes e movimentos</h2>
              <p class="section-copy">Use este bloco para validar falhas, cancelamentos e eventos de rota antes da proxima decisao.</p>
            </div>
            ${renderPill(viewModel.eventBadgeLabel, viewModel.eventBadgeTone)}
          </div>
          <div class="event-list">
            ${viewModel.events.length ? viewModel.events.map(renderEvent).join("") : "<p class=\"empty-copy\">Nenhum evento retornado.</p>"}
          </div>
        </section>
      </aside>
    </main>
  `;
}
