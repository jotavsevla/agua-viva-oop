import type { AppState, AtendimentoCaseRecord, ExtratoItem } from "../types";
import { formatDateTime } from "../shared/formatters";
import { escapeHtml } from "../shared/html";
import { renderPill } from "../shared/ui";
import {
  buildDraftBlockers,
  buildDraftWarnings,
  buildHandoffNarrative,
  buildWindowLabel,
  findActiveCase,
  findCasesByPhone,
  formatPhoneDisplay,
  JANELAS,
  METODOS_PAGAMENTO,
  ORIGENS_CANAL
} from "./model";

function renderOption(value: string, label: string, selectedValue: string): string {
  return `<option value="${escapeHtml(value)}" ${value === selectedValue ? "selected" : ""}>${escapeHtml(label)}</option>`;
}

function resolveCaseTone(caseRecord: AtendimentoCaseRecord): "ok" | "warn" | "danger" | "info" | "muted" {
  const status = String(caseRecord.statusAtual || "").toUpperCase();
  if (status === "ENTREGUE") {
    return "ok";
  }
  if (status === "CANCELADO") {
    return "danger";
  }
  if (status === "CONFIRMADO") {
    return "warn";
  }
  if (status === "EM_ROTA") {
    return "info";
  }
  return "muted";
}

function renderOpsSnapshot(state: AppState): string {
  const painel = state.snapshot?.painel;
  const cards = [
    {
      label: "Fila nova",
      value: String(painel?.filas.pendentesElegiveis.length ?? "-"),
      copy: "Pedidos que ainda vao entrar em triagem."
    },
    {
      label: "Rotas prontas",
      value: String(painel?.rotas.planejadas.length ?? "-"),
      copy: "Demandas ja comprometidas aguardando acao do despacho."
    },
    {
      label: "Casos na sessao",
      value: String(state.atendimento.sessionCases.length),
      copy: "Atendimentos recentes preservados localmente para handoff."
    },
    {
      label: "Ultima leitura",
      value: state.snapshot?.fetchedAt ? formatDateTime(state.snapshot.fetchedAt) : "-",
      copy: "Base operacional usada como pano de fundo do atendimento."
    }
  ];

  return `
    <div class="atendimento-pulse-grid">
      ${cards
        .map(
          (card) => `
            <article class="pulse-card atendimento-pulse-card">
              <p class="label">${escapeHtml(card.label)}</p>
              <strong>${escapeHtml(card.value)}</strong>
              <p>${escapeHtml(card.copy)}</p>
            </article>
          `
        )
        .join("")}
    </div>
  `;
}

function renderDraftNotices(state: AppState): string {
  const blockers = buildDraftBlockers(state.atendimento.draft, state.atendimento.sessionCases);
  const warnings = buildDraftWarnings(state.atendimento.draft);
  const notices: string[] = [];

  if (blockers.length > 0) {
    notices.push(`
      <div class="notice notice-danger">
        <strong>Bloqueios antes de enviar:</strong> ${escapeHtml(blockers.join(" | "))}
      </div>
    `);
  }

  if (warnings.length > 0) {
    notices.push(`
      <div class="notice notice-warn">
        <strong>Pontos de atencao:</strong> ${escapeHtml(warnings.join(" | "))}
      </div>
    `);
  }

  if (state.atendimento.lastError) {
    notices.push(`
      <div class="notice notice-danger">
        <strong>Falha no atendimento:</strong> ${escapeHtml(state.atendimento.lastError)}
      </div>
    `);
  }

  if (state.atendimento.lastSuccess) {
    notices.push(`
      <div class="notice notice-ok">
        <strong>Atendimento registrado:</strong> ${escapeHtml(state.atendimento.lastSuccess)}
      </div>
    `);
  }

  if (state.snapshot?.partialErrors && state.snapshot.partialErrors.length > 0) {
    notices.push(`
      <div class="notice notice-warn">
        <strong>Leitura operacional parcial:</strong> ${escapeHtml(state.snapshot.partialErrors.join(" | "))}
      </div>
    `);
  }

  return notices.join("");
}

function renderAtendimentoForm(state: AppState): string {
  const draft = state.atendimento.draft;
  const blockers = buildDraftBlockers(draft, state.atendimento.sessionCases);
  const isHard = draft.janelaTipo === "HARD";
  const isManual = draft.origemCanal === "MANUAL";
  const isAuto = draft.origemCanal === "WHATSAPP" || draft.origemCanal === "BINA_FIXO" || draft.origemCanal === "TELEFONIA_FIXO";

  return `
    <section class="panel atendimento-form-panel">
      <div class="panel-header">
        <div>
          <h2>Novo atendimento</h2>
          <p class="section-copy">Entrada guiada para pedido telefonico com regras explicitas e rastro de idempotencia.</p>
        </div>
        ${renderPill(state.atendimento.submitting ? "enviando" : "pronto para envio", state.atendimento.submitting ? "warn" : "ok")}
      </div>
      <form id="atendimento-form" class="atendimento-form">
        <div class="form-grid form-grid-primary">
          <label class="field">
            <span>Telefone</span>
            <input name="telefone" type="tel" value="${escapeHtml(draft.telefone)}" placeholder="(38) 99876-1234" required />
          </label>
          <label class="field">
            <span>Quantidade de galoes</span>
            <input name="quantidadeGaloes" type="number" min="1" step="1" value="${escapeHtml(draft.quantidadeGaloes)}" required />
          </label>
          <label class="field">
            <span>Atendente ID</span>
            <input name="atendenteId" type="number" min="1" step="1" value="${escapeHtml(draft.atendenteId)}" required />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Metodo de pagamento</span>
            <select name="metodoPagamento">
              ${METODOS_PAGAMENTO.map((item) => renderOption(item, item.replace("_", " "), draft.metodoPagamento)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Janela</span>
            <select name="janelaTipo">
              ${JANELAS.map((item) => renderOption(item, item, draft.janelaTipo)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Origem do canal</span>
            <select name="origemCanal">
              ${ORIGENS_CANAL.map((item) => renderOption(item, item.replace("_", " "), draft.origemCanal)).join("")}
            </select>
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${isHard ? "" : "field-disabled"}">
            <span>Janela inicio</span>
            <input name="janelaInicio" type="time" value="${escapeHtml(draft.janelaInicio)}" ${isHard ? "" : "disabled"} />
          </label>
          <label class="field ${isHard ? "" : "field-disabled"}">
            <span>Janela fim</span>
            <input name="janelaFim" type="time" value="${escapeHtml(draft.janelaFim)}" ${isHard ? "" : "disabled"} />
          </label>
          <label class="field">
            <span>Nome do cliente</span>
            <input name="nomeCliente" type="text" value="${escapeHtml(draft.nomeCliente)}" placeholder="Condominio Horizonte" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Endereco</span>
            <input name="endereco" type="text" value="${escapeHtml(draft.endereco)}" placeholder="Rua da Operacao, 99" />
          </label>
          <label class="field">
            <span>Latitude</span>
            <input name="latitude" type="text" value="${escapeHtml(draft.latitude)}" placeholder="-16.7200" />
          </label>
          <label class="field">
            <span>Longitude</span>
            <input name="longitude" type="text" value="${escapeHtml(draft.longitude)}" placeholder="-43.8600" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${isAuto ? "" : "field-disabled"}">
            <span>sourceEventId</span>
            <input name="sourceEventId" type="text" value="${escapeHtml(draft.sourceEventId)}" placeholder="evt-whatsapp-001" ${isAuto ? "" : "disabled"} />
          </label>
          <label class="field ${isManual ? "" : "field-disabled"}">
            <span>manualRequestId</span>
            <input name="manualRequestId" type="text" value="${escapeHtml(draft.manualRequestId)}" placeholder="manual-ui-..." ${isManual ? "" : "disabled"} />
          </label>
          <label class="field">
            <span>externalCallId</span>
            <input name="externalCallId" type="text" value="${escapeHtml(draft.externalCallId)}" placeholder="call-center-001" />
          </label>
        </div>

        <div class="form-actions">
          <button class="button primary" type="submit" ${state.atendimento.submitting || blockers.length > 0 ? "disabled" : ""}>
            ${state.atendimento.submitting ? "Registrando..." : "Registrar pedido"}
          </button>
          <p class="form-footnote">O envio usa a API real e preserva uma chave de idempotencia para retries seguros.</p>
        </div>
      </form>
    </section>
  `;
}

function renderFinanceCard(caseRecord: AtendimentoCaseRecord): string {
  if (caseRecord.financeStatus === "unavailable") {
    return `<div class="notice notice-warn"><strong>Financeiro nao acoplado:</strong> a base atual nao expoe saldo/extrato do cliente nesta app.</div>`;
  }

  if (caseRecord.financeStatus === "error") {
    return `<div class="notice notice-danger"><strong>Financeiro com falha:</strong> nao foi possivel sincronizar saldo/extrato agora.</div>`;
  }

  if (!caseRecord.saldo && !caseRecord.extrato) {
    return `<p class="empty-copy">Saldo e extrato ainda nao sincronizados para este cliente.</p>`;
  }

  return `
    <div class="finance-grid">
      <article class="metric-card finance-card">
        <p class="metric-label">Saldo de vales</p>
        <p class="metric-value">${escapeHtml(caseRecord.saldo?.quantidade ?? "-")}</p>
      </article>
      <section class="timeline-shell">
        <div class="context-block-header">
          <h3>Extrato recente</h3>
          ${renderPill(caseRecord.extrato ? String(caseRecord.extrato.itens.length) : "0", "info")}
        </div>
        <div class="timeline-list">
          ${
            caseRecord.extrato && caseRecord.extrato.itens.length > 0
              ? caseRecord.extrato.itens.slice(0, 5).map(renderExtratoItem).join("")
              : "<p class=\"empty-copy\">Nenhum movimento recente retornado.</p>"
          }
        </div>
      </section>
    </div>
  `;
}

function renderExtratoItem(item: ExtratoItem): string {
  return `
    <article class="timeline-item compact">
      <div class="timeline-topline">
        <strong>${escapeHtml(item.tipo)}</strong>
        ${renderPill(`saldo ${item.saldoApos}`, item.tipo === "CREDITO" ? "ok" : "warn")}
      </div>
      <p class="mono">${escapeHtml(item.quantidade)} galao(oes) · ${escapeHtml(formatDateTime(item.data))}</p>
      <p class="empty-copy">${escapeHtml(item.observacao || item.registradoPor)}</p>
    </article>
  `;
}

function renderTimeline(caseRecord: AtendimentoCaseRecord): string {
  return `
    <section class="timeline-shell">
      <div class="context-block-header">
        <h3>Timeline do pedido</h3>
        ${renderPill(caseRecord.statusAtual || "sem status", resolveCaseTone(caseRecord))}
      </div>
      <div class="timeline-list">
        ${
          caseRecord.timeline && caseRecord.timeline.eventos.length > 0
            ? caseRecord.timeline.eventos
                .map(
                  (event) => `
                    <article class="timeline-item">
                      <div class="timeline-topline">
                        <strong>${escapeHtml(event.deStatus)} → ${escapeHtml(event.paraStatus)}</strong>
                        ${renderPill(event.origem, "muted")}
                      </div>
                      <p class="mono">${escapeHtml(formatDateTime(event.timestamp))}</p>
                      ${event.observacao ? `<p>${escapeHtml(event.observacao)}</p>` : ""}
                    </article>
                  `
                )
                .join("")
            : "<p class=\"empty-copy\">Timeline ainda nao carregada para este pedido.</p>"
        }
      </div>
    </section>
  `;
}

function renderContextPanel(state: AppState): string {
  const activeCase = findActiveCase(state.atendimento);
  const filteredCases = findCasesByPhone(state.atendimento, state.atendimento.lookupPhone);
  const handoff = buildHandoffNarrative(activeCase);
  const finance = activeCase ? renderFinanceCard(activeCase) : "<p class=\"empty-copy\">Selecione um atendimento para ver contexto financeiro e historico.</p>";
  const timeline = activeCase ? renderTimeline(activeCase) : "<p class=\"empty-copy\">Nenhum atendimento em foco.</p>";
  const isSyncingActive = activeCase && state.atendimento.syncingCaseId === activeCase.pedidoId;

  return `
    <section class="panel atendimento-context-panel">
      <div class="panel-header">
        <div>
          <h2>Contexto e handoff</h2>
          <p class="section-copy">Consulta rapida por pedido, ressincronizacao com a API e passagem clara para o despacho.</p>
        </div>
        ${renderPill(activeCase ? `pedido #${activeCase.pedidoId}` : "sem foco", activeCase ? resolveCaseTone(activeCase) : "muted")}
      </div>

      <form id="atendimento-lookup-form" class="lookup-form">
        <label class="field">
          <span>Buscar por telefone da sessao</span>
          <input name="lookupPhone" type="text" value="${escapeHtml(state.atendimento.lookupPhone)}" placeholder="38998761234" />
        </label>
        <label class="field">
          <span>Consultar pedidoId na API</span>
          <input name="lookupPedidoId" type="number" min="1" value="${escapeHtml(state.atendimento.lookupPedidoId)}" placeholder="8421" />
        </label>
        <div class="toolbar-actions lookup-actions">
          <button class="button secondary" type="submit">Buscar contexto</button>
          ${
            activeCase
              ? `<button class="button primary" data-action="refresh-atendimento-case" data-pedido-id="${activeCase.pedidoId}" type="button" ${isSyncingActive ? "disabled" : ""}>${
                  isSyncingActive ? "Atualizando..." : "Atualizar pedido"
                }</button>`
              : ""
          }
        </div>
      </form>

      ${
        activeCase
          ? `
            <article class="handoff-card tone-${handoff.tone}">
              <div class="priority-topline">
                ${renderPill(handoff.stage, handoff.tone)}
                <strong>${escapeHtml(activeCase.nomeCliente || formatPhoneDisplay(activeCase.telefone) || `Pedido #${activeCase.pedidoId}`)}</strong>
              </div>
              <p>${escapeHtml(handoff.detail)}</p>
              <p class="priority-action">${escapeHtml(handoff.action)}</p>
              <div class="context-metrics">
                <article class="metric-card compact">
                  <p class="metric-label">Pedido</p>
                  <p class="metric-value small">#${escapeHtml(activeCase.pedidoId)}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Cliente</p>
                  <p class="metric-value small">${escapeHtml(activeCase.clienteId ?? "-")}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Janela</p>
                  <p class="metric-value small">${escapeHtml(buildWindowLabel(activeCase))}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Execucao</p>
                  <p class="metric-value small">${escapeHtml(activeCase.execucao?.camada || activeCase.statusAtual || "-")}</p>
                </article>
              </div>
              <div class="tag-row">
                ${activeCase.requestKey ? renderPill(`key ${activeCase.requestKey}`, "muted") : ""}
                ${activeCase.execucao?.rotaId ? renderPill(`rota ${activeCase.execucao.rotaId}`, "info") : ""}
                ${activeCase.execucao?.entregaId ? renderPill(`entrega ${activeCase.execucao.entregaId}`, "info") : ""}
                ${renderPill(activeCase.metodoPagamento || "NAO_INFORMADO", activeCase.metodoPagamento === "VALE" ? "warn" : "muted")}
                ${activeCase.clienteCriado ? renderPill("cliente criado agora", "ok") : ""}
                ${activeCase.idempotente ? renderPill("retry idempotente", "info") : ""}
              </div>
              ${
                activeCase.error
                  ? `<div class="notice notice-danger"><strong>Ultimo erro:</strong> ${escapeHtml(activeCase.error)}</div>`
                  : ""
              }
              ${
                activeCase.notes.length > 0
                  ? `<div class="notice notice-warn"><strong>Notas de contexto:</strong> ${escapeHtml(activeCase.notes.join(" | "))}</div>`
                  : ""
              }
              <div class="toolbar-actions handoff-actions">
                <button class="button primary" type="button" data-action="navigate" data-module-id="cockpit">Abrir cockpit operacional</button>
              </div>
            </article>
          `
          : ""
      }

      <div class="context-stack">
        ${timeline}
        ${finance}
      </div>

      <section class="timeline-shell">
        <div class="context-block-header">
          <h3>Atendimentos encontrados</h3>
          ${renderPill(String(filteredCases.length), "muted")}
        </div>
        <div class="session-list">
          ${
            filteredCases.length > 0
              ? filteredCases.map((item) => renderSessionCase(item, state.atendimento.activeCaseId, state.atendimento.syncingCaseId)).join("")
              : "<p class=\"empty-copy\">Nenhum atendimento da sessao corresponde a essa busca.</p>"
          }
        </div>
      </section>
    </section>
  `;
}

function renderSessionCase(
  caseRecord: AtendimentoCaseRecord,
  activeCaseId: number | null,
  syncingCaseId: number | null
): string {
  const tone = resolveCaseTone(caseRecord);
  const isActive = activeCaseId === caseRecord.pedidoId;
  const isSyncing = syncingCaseId === caseRecord.pedidoId;

  return `
    <article class="session-card ${isActive ? "is-active" : ""}">
      <div class="queue-card-header">
        <strong>${escapeHtml(caseRecord.nomeCliente || formatPhoneDisplay(caseRecord.telefone) || `Pedido #${caseRecord.pedidoId}`)}</strong>
        ${renderPill(caseRecord.statusAtual || "sem status", tone)}
      </div>
      <p class="mono">Pedido #${escapeHtml(caseRecord.pedidoId)} · cliente ${escapeHtml(caseRecord.clienteId ?? "-")}</p>
      <p class="empty-copy">${escapeHtml(buildWindowLabel(caseRecord))} · ${escapeHtml(caseRecord.metodoPagamento || "NAO_INFORMADO")}</p>
      <p class="empty-copy">Atualizado ${escapeHtml(formatDateTime(caseRecord.lastSyncAt || caseRecord.updatedAt))}</p>
      <div class="toolbar-actions inline-actions">
        <button class="button secondary" data-action="focus-atendimento-case" data-pedido-id="${caseRecord.pedidoId}" type="button">
          ${isActive ? "Em foco" : "Trazer para foco"}
        </button>
        <button class="button primary" data-action="refresh-atendimento-case" data-pedido-id="${caseRecord.pedidoId}" type="button" ${isSyncing ? "disabled" : ""}>
          ${isSyncing ? "Atualizando..." : "Atualizar"}
        </button>
      </div>
    </article>
  `;
}

export function renderAtendimentoWorkspace(state: AppState): string {
  return `
    <section class="atendimento-workspace">
      ${renderOpsSnapshot(state)}
      ${renderDraftNotices(state)}
      <div class="atendimento-grid">
        ${renderAtendimentoForm(state)}
        ${renderContextPanel(state)}
      </div>
    </section>
  `;
}
