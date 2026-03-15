import { escapeHtml } from "../shared/html";
import { renderPill } from "../shared/ui";
import type {
  DespachoActionViewModel,
  DespachoEventBucketViewModel,
  DespachoLayerCardViewModel,
  DespachoPriorityCardViewModel,
  DespachoQueueLaneViewModel,
  DespachoRouteCardViewModel,
  DespachoViewModel
} from "./view-model";

function renderMetric(metric: DespachoViewModel["metrics"][number]): string {
  return `
    <article class="metric-card">
      <p class="metric-label">${escapeHtml(metric.label)}</p>
      <p class="metric-value">${escapeHtml(metric.value)}</p>
      <p class="metric-detail">${escapeHtml(metric.detail)}</p>
    </article>
  `;
}

function renderPriority(card: DespachoPriorityCardViewModel): string {
  return `
    <article class="priority-card tone-${card.tone}">
      <div class="priority-topline">
        ${renderPill(card.badge, card.tone)}
        <strong>${escapeHtml(card.title)}</strong>
      </div>
      <p>${escapeHtml(card.detail)}</p>
      <p class="priority-action">${escapeHtml(card.action)}</p>
    </article>
  `;
}

function renderQueueLane(lane: DespachoQueueLaneViewModel): string {
  return `
    <section class="queue-lane">
      <div class="queue-lane-header">
        <div>
          <h3>${escapeHtml(lane.title)}</h3>
          <p class="queue-lane-copy">${escapeHtml(lane.summary)}</p>
        </div>
        ${renderPill(lane.tone, lane.tone)}
      </div>
      <div class="queue-list">
        ${
          lane.cards.length > 0
            ? lane.cards
                .map(
                  (card) => `
                    <article class="queue-card">
                      <div class="queue-card-header">
                        <strong>${escapeHtml(card.title)}</strong>
                        ${renderPill(card.badgeLabel, card.badgeTone)}
                      </div>
                      ${card.lines.map((line) => `<p class="mono">${escapeHtml(line)}</p>`).join("")}
                      <p class="card-copy">${escapeHtml(card.action)}</p>
                    </article>
                  `
                )
                .join("")
            : `<p class="empty-copy">${escapeHtml(lane.emptyMessage)}</p>`
        }
      </div>
    </section>
  `;
}

function renderLayerCard(card: DespachoLayerCardViewModel): string {
  return `
    <article class="route-group">
      <div class="route-group-header">
        <div>
          <h3>${escapeHtml(card.title)}</h3>
          <p class="section-copy">${escapeHtml(card.summary)}</p>
        </div>
        ${renderPill(String(card.routes.length), card.tone)}
      </div>
      <div class="route-list">
        ${
          card.routes.length > 0
            ? card.routes
                .map(
                  (route) => `
                    <article class="route-card">
                      <strong>${escapeHtml(route.title)}</strong>
                      <p class="mono">${escapeHtml(route.meta)}</p>
                    </article>
                  `
                )
                .join("")
            : `<p class="empty-copy">${escapeHtml(card.emptyMessage)}</p>`
        }
      </div>
    </article>
  `;
}

function renderEventBucket(bucket: DespachoEventBucketViewModel): string {
  return `
    <section class="event-bucket">
      <div class="panel-header">
        <div>
          <h3>${escapeHtml(bucket.title)}</h3>
          <p class="section-copy">${escapeHtml(bucket.summary)}</p>
        </div>
        ${renderPill(String(bucket.cards.length), bucket.tone)}
      </div>
      <div class="event-list">
        ${
          bucket.cards.length > 0
            ? bucket.cards
                .map(
                  (card) => `
                    <article class="event-item">
                      <div class="event-topline">
                        <strong>${escapeHtml(card.title)}</strong>
                        ${renderPill(card.badgeLabel, card.badgeTone)}
                      </div>
                      <p class="mono">${escapeHtml(card.subject)}</p>
                      <p class="event-meta">${escapeHtml(card.meta)}</p>
                      <p class="card-copy">${escapeHtml(card.detail)}</p>
                    </article>
                  `
                )
                .join("")
            : `<p class="empty-copy">${escapeHtml(bucket.emptyMessage)}</p>`
        }
      </div>
    </section>
  `;
}

function renderRouteCard(card: DespachoRouteCardViewModel): string {
  return `
    <article class="route-card large">
      <div class="queue-card-header">
        <strong>${escapeHtml(card.title)}</strong>
        ${renderPill(card.badgeLabel, card.badgeTone)}
      </div>
      <p class="mono">${escapeHtml(card.summary)}</p>
      <div class="tag-row">
        ${card.tags.map((tag) => renderPill(tag, "muted")).join("")}
      </div>
    </article>
  `;
}

function renderActionCard(action: DespachoActionViewModel): string {
  return `
    <aside class="dispatch-action-card tone-${action.tone}">
      <div class="panel-header">
        <div>
          <h2>${escapeHtml(action.title)}</h2>
          <p class="section-copy">${escapeHtml(action.detail)}</p>
        </div>
        ${renderPill(action.badgeLabel, action.tone)}
      </div>
      <p class="card-copy">${escapeHtml(action.supportingText)}</p>
      ${
        action.blocker
          ? `<div class="notice notice-warn"><strong>Bloqueio:</strong> ${escapeHtml(action.blocker)}</div>`
          : ""
      }
      <button
        class="button primary"
        type="button"
        data-action="start-prepared-route"
        data-entregador-id="${escapeHtml(action.entregadorId ?? "")}"
        ${action.enabled ? "" : "disabled"}
      >
        ${escapeHtml(action.buttonLabel)}
      </button>
    </aside>
  `;
}

export function renderDespachoModule(viewModel: DespachoViewModel): string {
  return `
    <section class="dispatch-stack">
      <section class="panel dispatch-hero-panel">
        <div class="dispatch-hero-grid">
          <div>
            <p class="panel-kicker">Despacho</p>
            <h2>${escapeHtml(viewModel.headline)}</h2>
            <p class="section-copy">${escapeHtml(viewModel.summary)}</p>
            <div class="metrics-grid">
              ${viewModel.metrics.map(renderMetric).join("")}
            </div>
          </div>
          ${renderActionCard(viewModel.action)}
        </div>
      </section>

      <section class="panel priority-panel">
        <div class="panel-header">
          <div>
            <h2>Prioridades de despacho</h2>
            <p class="section-copy">Leitura guiada para decisao em fila, risco, frota e ocorrencias.</p>
          </div>
          ${renderPill("modulo ativo", "info")}
        </div>
        <div class="priority-grid">
          ${viewModel.priorities.map(renderPriority).join("")}
        </div>
        <div class="pulse-grid">
          ${viewModel.pulses
            .map(
              (pulse) => `
                <article class="pulse-card">
                  <p class="label">${escapeHtml(pulse.label)}</p>
                  <p>${escapeHtml(pulse.value)}</p>
                </article>
              `
            )
            .join("")}
        </div>
      </section>

      ${
        viewModel.notices.length > 0
          ? `
            <section class="dispatch-notice-stack">
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
          `
          : ""
      }

      <section class="panel">
        <div class="panel-header">
          <div>
            <h2>Fila operacional</h2>
            <p class="section-copy">A fila e lida pelo estagio de decisao, nao pela resposta crua do backend.</p>
          </div>
          ${renderPill(String(viewModel.queueLanes.reduce((sum, lane) => sum + lane.cards.length, 0)), "info")}
        </div>
        <div class="queue-grid">
          ${viewModel.queueLanes.map(renderQueueLane).join("")}
        </div>
      </section>

      <main class="dispatch-grid">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>Camadas de frota</h2>
              <p class="section-copy">Separacao clara entre primaria, secundaria e qualquer incoerencia do read model.</p>
            </div>
            ${renderPill(String(viewModel.layerWarnings.length), viewModel.layerWarnings.length > 0 ? "warn" : "ok")}
          </div>
          ${
            viewModel.layerWarnings.length > 0
              ? `<div class="notice notice-warn"><strong>Anomalias de camada:</strong> ${escapeHtml(viewModel.layerWarnings.join(" | "))}</div>`
              : `<div class="notice notice-ok"><strong>Modelo consistente:</strong> primaria e secundaria legiveis nesta leitura.</div>`
          }
          <div class="dispatch-layer-grid">
            ${viewModel.layerCards.map(renderLayerCard).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>Feed de ocorrencias</h2>
              <p class="section-copy">O feed separa o que pede acao imediata do que e apenas movimento normal da operacao.</p>
            </div>
            ${renderPill(String(viewModel.eventBuckets.reduce((sum, bucket) => sum + bucket.cards.length, 0)), "info")}
          </div>
          <div class="dispatch-event-grid">
            ${viewModel.eventBuckets.map(renderEventBucket).join("")}
          </div>
        </section>

        <section class="panel dispatch-routes-panel">
          <div class="panel-header">
            <div>
              <h2>Rotas e mapa operacional</h2>
              <p class="section-copy">${escapeHtml(viewModel.routeSummary)}</p>
            </div>
            ${viewModel.mapDeposit ? renderPill(viewModel.mapDeposit, "muted") : renderPill("sem mapa", "warn")}
          </div>
          <div class="route-list">
            ${viewModel.routeCards.length > 0 ? viewModel.routeCards.map(renderRouteCard).join("") : "<p class=\"empty-copy\">Nenhuma rota retornada pelo mapa operacional.</p>"}
          </div>
        </section>
      </main>
    </section>
  `;
}
