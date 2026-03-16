import { APP_MODULES, getModuleById } from "./app/modules";
import { renderCockpit } from "./cockpit/ux-render";
import { buildCockpitViewModel } from "./cockpit/ux-view-model";
import { formatDateTime } from "./shared/formatters";
import { escapeHtml } from "./shared/html";
import { renderPill } from "./shared/ui";
import type { AppState } from "./types";

function renderModuleNavigation(state: AppState): string {
  return `
    <section class="panel module-nav">
      <div class="panel-header">
        <div>
          <h2>Fluxos da operacao</h2>
          <p class="section-copy">Cada modulo organiza a mesma operacao sob a otica do papel que esta trabalhando agora.</p>
        </div>
        ${renderPill(getModuleById(state.activeModule).label, "info")}
      </div>
      <div class="module-tab-list">
        ${APP_MODULES
          .map(
            (module) => `
              <button
                class="module-tab ${module.id === state.activeModule ? "is-active" : ""}"
                type="button"
                data-action="navigate"
                data-module-id="${escapeHtml(module.id)}"
              >
                <div class="module-tab-topline">
                  <strong>${escapeHtml(module.label)}</strong>
                  ${renderPill(module.status === "active" ? "ativo" : "planejado", module.status === "active" ? "ok" : "muted")}
                </div>
                <p>${escapeHtml(module.description)}</p>
              </button>
            `
          )
          .join("")}
      </div>
    </section>
  `;
}

function renderPlaceholderModule(state: AppState): string {
  const moduleDefinition = getModuleById(state.activeModule);
  return `
    <section class="panel">
      <div class="panel-header">
        <div>
          <h2>${escapeHtml(moduleDefinition.label)}</h2>
          <p class="section-copy">${escapeHtml(moduleDefinition.description)}</p>
        </div>
        ${renderPill("em preparacao", "warn")}
      </div>
      <div class="notice notice-warn">
        <strong>Proxima etapa:</strong> este fluxo ja esta previsto na shell modular e entra nos PRs seguintes.
      </div>
    </section>
  `;
}

function renderActiveModule(state: AppState): string {
  if (state.activeModule !== "cockpit") {
    return renderPlaceholderModule(state);
  }

  return renderCockpit(buildCockpitViewModel(state));
}

interface FocusSnapshot {
  elementTag: "input" | "select" | "textarea";
  id: string | null;
  name: string | null;
  selectionStart: number | null;
  selectionEnd: number | null;
}

function captureFocusSnapshot(root: HTMLElement): FocusSnapshot | null {
  const activeElement = document.activeElement;

  if (
    !(activeElement instanceof HTMLInputElement || activeElement instanceof HTMLSelectElement || activeElement instanceof HTMLTextAreaElement) ||
    !root.contains(activeElement)
  ) {
    return null;
  }

  return {
    elementTag: activeElement.tagName.toLowerCase() as FocusSnapshot["elementTag"],
    id: activeElement.id || null,
    name: activeElement.getAttribute("name"),
    selectionStart: "selectionStart" in activeElement ? activeElement.selectionStart : null,
    selectionEnd: "selectionEnd" in activeElement ? activeElement.selectionEnd : null
  };
}

function restoreFocusSnapshot(root: HTMLElement, snapshot: FocusSnapshot | null): void {
  if (!snapshot) {
    return;
  }

  const selector =
    snapshot.id !== null
      ? `#${CSS.escape(snapshot.id)}`
      : snapshot.name !== null
        ? `${snapshot.elementTag}[name="${CSS.escape(snapshot.name)}"]`
        : null;

  if (!selector) {
    return;
  }

  const nextElement = root.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(selector);

  if (!nextElement) {
    return;
  }

  nextElement.focus({ preventScroll: true });

  if (
    nextElement instanceof HTMLInputElement ||
    nextElement instanceof HTMLTextAreaElement
  ) {
    if (snapshot.selectionStart !== null && snapshot.selectionEnd !== null) {
      nextElement.setSelectionRange(snapshot.selectionStart, snapshot.selectionEnd);
    }
  }
}

export function renderApp(root: HTMLElement, state: AppState): void {
  const activeModule = getModuleById(state.activeModule);
  const partialErrors = state.snapshot?.partialErrors.length ?? 0;
  const focusSnapshot = captureFocusSnapshot(root);

  root.innerHTML = `
    <div class="app-shell">
      <header class="hero">
        <div class="hero-copy-block">
          <p class="eyebrow">Agua Viva</p>
          <h1>Operacao web por papel</h1>
          <p class="hero-copy">${escapeHtml(activeModule.description)}</p>
          <div class="hero-meta">
            ${renderPill("dados reais", "info")}
            ${renderPill(state.connection.autoRefresh ? "auto-refresh ligado" : "auto-refresh desligado", state.connection.autoRefresh ? "ok" : "muted")}
            ${renderPill(state.snapshot?.fetchedAt ? formatDateTime(state.snapshot.fetchedAt) : "sem sincronizacao", "muted")}
          </div>
        </div>
        <div class="hero-signals">
          <article class="signal-card">
            <p class="label">Modulo ativo</p>
            <strong>${escapeHtml(activeModule.label)}</strong>
            <p>${escapeHtml(activeModule.description)}</p>
          </article>
          <article class="signal-card">
            <p class="label">Sincronizacao</p>
            <strong>${escapeHtml(state.sync.status)}</strong>
            <p>${escapeHtml(state.sync.lastError || "Leitura operacional sem erro nesta rodada.")}</p>
          </article>
          <article class="signal-card">
            <p class="label">Leitura parcial</p>
            <strong>${escapeHtml(String(partialErrors))}</strong>
            <p>${partialErrors > 0 ? "Existe degradacao em pelo menos um read model." : "Todos os read models responderam nesta rodada."}</p>
          </article>
        </div>
      </header>

      <section class="panel toolbar">
        <div class="toolbar-intro">
          <p class="panel-kicker">Conexao</p>
          <h2>API operacional</h2>
          <p class="section-copy">A base vale para todos os modulos e pode ser trocada sem sair da app.</p>
        </div>
        <div class="toolbar-body">
          <div class="toolbar-fields">
            <label class="field">
              <span>API base</span>
              <input id="api-base-input" type="text" value="${escapeHtml(state.connection.apiBaseDraft)}" spellcheck="false" />
            </label>
            <label class="toggle">
              <input id="auto-refresh-input" type="checkbox" ${state.connection.autoRefresh ? "checked" : ""} />
              <span>Auto-refresh</span>
            </label>
          </div>
          <div class="toolbar-actions">
            <button class="button secondary" type="button" data-action="save-api-base">Salvar base</button>
            <button class="button primary" type="button" data-action="refresh-snapshot" ${state.sync.status === "loading" ? "disabled" : ""}>
              ${state.sync.status === "loading" ? "Atualizando..." : "Atualizar agora"}
            </button>
          </div>
        </div>
      </section>

      ${renderModuleNavigation(state)}
      ${renderActiveModule(state)}
    </div>
  `;

  restoreFocusSnapshot(root, focusSnapshot);
}
