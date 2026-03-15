import {
  createAtendimentoPedido,
  fetchClienteExtrato,
  fetchClienteSaldo,
  fetchEntregadorRoteiro,
  fetchOperationalSnapshot,
  fetchPedidoExecucao,
  fetchPedidoTimeline,
  iniciarRotaPronta,
  isUnavailableOptionalEndpoint,
  registrarEventoOperacional
} from "../api";
import {
  buildAtendimentoCaseRecord,
  buildAtendimentoPayload,
  buildNextDraft,
  createLookupPlaceholderCase,
  findCasesByPhone,
  readDraftFromFormData,
  updateCaseFromContext,
  upsertSessionCase
} from "../atendimento/model";
import { buildActionFeedback, buildEntregadorDeepLink, buildExternalEventId } from "../entregador/model";
import type {
  AppModuleId,
  AtendimentoCaseRecord,
  AtendimentoPersistedState,
  AtendimentoRequestDraft,
  EntregadorEventType,
  EventoOperacionalRequest
} from "../types";
import type { AppRouter } from "./router";
import type { AppStore } from "./store";

interface AppControllerOptions {
  root: HTMLElement;
  router: AppRouter;
  store: AppStore;
  persistApiBase: (value: string) => void;
  persistAutoRefresh: (value: boolean) => void;
  persistAtendimentoState: (value: AtendimentoPersistedState) => void;
  persistEntregadorId: (value: number) => void;
  targetWindow?: Window;
}

export interface AppController {
  bind(): void;
  refreshSnapshot(): Promise<void>;
  dispose(): void;
}

function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Falha ao sincronizar a operacao.";
}

function parseEntregadorEventType(value: string | undefined): EntregadorEventType | null {
  if (value === "PEDIDO_ENTREGUE" || value === "PEDIDO_FALHOU" || value === "PEDIDO_CANCELADO") {
    return value;
  }

  return null;
}

export function createAppController(options: AppControllerOptions): AppController {
  const { root, router, store, persistApiBase, persistAutoRefresh, persistAtendimentoState, persistEntregadorId } =
    options;
  const targetWindow = options.targetWindow ?? window;

  const persistAtendimento = (): void => {
    const atendimento = store.getState().atendimento;
    persistAtendimentoState({
      draft: atendimento.draft,
      lookupPhone: atendimento.lookupPhone,
      lookupPedidoId: atendimento.lookupPedidoId,
      sessionCases: atendimento.sessionCases,
      activeCaseId: atendimento.activeCaseId
    });
  };

  const updateAtendimento = (
    updater: (state: ReturnType<AppStore["getState"]>["atendimento"]) => ReturnType<AppStore["getState"]>["atendimento"],
    notify = true
  ): void => {
    store.updateAtendimento(updater, { notify });
    persistAtendimento();
  };

  const syncEntregadorQueryParam = (entregadorId: number): void => {
    const url = new URL(targetWindow.location.href);
    url.searchParams.set("entregadorId", String(entregadorId));
    targetWindow.history.replaceState(null, "", url.toString());
  };

  const readEntregadorIdInput = (): number | null => {
    const input = root.querySelector<HTMLInputElement>("#entregador-id-input");
    const value = Number(input?.value);
    return Number.isInteger(value) && value > 0 ? value : null;
  };

  const refreshOperationalSnapshot = async (): Promise<void> => {
    if (store.getState().sync.status === "loading") {
      return;
    }

    store.startSync();

    try {
      const snapshot = await fetchOperationalSnapshot(store.getState().connection.apiBase);
      store.finishSync(snapshot);
    } catch (error) {
      store.failSync(toErrorMessage(error));
    }
  };

  const refreshEntregador = async (): Promise<void> => {
    if (store.getState().entregador.sync.status === "loading") {
      return;
    }

    store.startEntregadorSync();

    try {
      const { apiBase } = store.getState().connection;
      const { entregadorId } = store.getState().entregador;
      const roteiro = await fetchEntregadorRoteiro(apiBase, entregadorId);
      store.finishEntregadorSync(roteiro, new Date().toISOString());
    } catch (error) {
      store.failEntregadorSync(toErrorMessage(error));
    }
  };

  const syncAtendimentoCase = async (pedidoId: number): Promise<void> => {
    if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
      return;
    }

    let currentCase = store.getState().atendimento.sessionCases.find((item) => item.pedidoId === pedidoId);

    if (!currentCase) {
      const placeholderCase = createLookupPlaceholderCase(pedidoId);
      currentCase = placeholderCase;
      updateAtendimento((currentState) => ({
        ...currentState,
        sessionCases: upsertSessionCase(currentState.sessionCases, placeholderCase),
        activeCaseId: pedidoId,
        lastError: null
      }));
    } else {
      updateAtendimento((currentState) => ({
        ...currentState,
        activeCaseId: pedidoId,
        lastError: null
      }));
    }

    updateAtendimento((currentState) => ({
      ...currentState,
      syncingCaseId: pedidoId
    }));

    try {
      const latestCase = store.getState().atendimento.sessionCases.find((item) => item.pedidoId === pedidoId);

      if (!latestCase) {
        throw new Error(`Pedido #${pedidoId} nao encontrado na sessao de atendimento.`);
      }
      const [timelineResult, execucaoResult, saldoResult, extratoResult] = await Promise.allSettled([
        fetchPedidoTimeline(store.getState().connection.apiBase, pedidoId),
        fetchPedidoExecucao(store.getState().connection.apiBase, pedidoId),
        latestCase.clienteId
          ? fetchClienteSaldo(store.getState().connection.apiBase, latestCase.clienteId)
          : Promise.resolve(null),
        latestCase.clienteId
          ? fetchClienteExtrato(store.getState().connection.apiBase, latestCase.clienteId)
          : Promise.resolve(null)
      ]);

      const notes: string[] = [];
      let financeStatus: AtendimentoCaseRecord["financeStatus"] = latestCase.financeStatus;
      let financeError: string | null = null;

      const nextCase = updateCaseFromContext(latestCase, {
        timeline: timelineResult.status === "fulfilled" ? timelineResult.value : latestCase.timeline,
        execucao: execucaoResult.status === "fulfilled" ? execucaoResult.value : latestCase.execucao
      });

      if (timelineResult.status === "rejected") {
        notes.push(toErrorMessage(timelineResult.reason));
      }

      if (execucaoResult.status === "rejected") {
        notes.push(toErrorMessage(execucaoResult.reason));
      }

      if (saldoResult.status === "fulfilled" && saldoResult.value) {
        financeStatus = "ok";
      } else if (saldoResult.status === "rejected") {
        if (isUnavailableOptionalEndpoint(saldoResult.reason)) {
          financeStatus = "unavailable";
          notes.push("Saldo/extrato nao estao disponiveis nesta base da app.");
        } else {
          financeStatus = "error";
          financeError = toErrorMessage(saldoResult.reason);
        }
      }

      if (extratoResult.status === "fulfilled" && extratoResult.value) {
        financeStatus = "ok";
      } else if (extratoResult.status === "rejected") {
        if (isUnavailableOptionalEndpoint(extratoResult.reason)) {
          financeStatus = "unavailable";
        } else if (financeStatus !== "unavailable") {
          financeStatus = "error";
          financeError = toErrorMessage(extratoResult.reason);
        }
      }

      const mergedCase = updateCaseFromContext(nextCase, {
        saldo: saldoResult.status === "fulfilled" ? saldoResult.value : nextCase.saldo,
        extrato: extratoResult.status === "fulfilled" ? extratoResult.value : nextCase.extrato,
        financeStatus,
        error: financeError,
        notes
      });

      updateAtendimento((currentState) => ({
        ...currentState,
        syncingCaseId: null,
        lastSuccess: `Pedido #${pedidoId} sincronizado com timeline e execucao.`,
        sessionCases: upsertSessionCase(currentState.sessionCases, mergedCase)
      }));
    } catch (error) {
      updateAtendimento((currentState) => ({
        ...currentState,
        syncingCaseId: null,
        lastError: toErrorMessage(error)
      }));
    }
  };

  const submitAtendimento = async (form: HTMLFormElement): Promise<void> => {
    const draft: AtendimentoRequestDraft = readDraftFromFormData(new FormData(form));

    updateAtendimento((currentState) => ({
      ...currentState,
      draft,
      lastError: null,
      lastSuccess: null
    }));

    let requestPayload: ReturnType<typeof buildAtendimentoPayload>;
    try {
      requestPayload = buildAtendimentoPayload(draft);
    } catch (error) {
      updateAtendimento((currentState) => ({
        ...currentState,
        lastError: toErrorMessage(error)
      }));
      return;
    }

    updateAtendimento((currentState) => ({
      ...currentState,
      submitting: true
    }));

    try {
      const response = await createAtendimentoPedido(
        store.getState().connection.apiBase,
        requestPayload.payload,
        requestPayload.idempotencyKey
      );
      const caseRecord = buildAtendimentoCaseRecord(draft, response);

      updateAtendimento((currentState) => ({
        ...currentState,
        submitting: false,
        draft: buildNextDraft(currentState.draft),
        activeCaseId: response.pedidoId,
        lastSuccess: `Pedido #${response.pedidoId} criado para cliente ${response.clienteId}.`,
        sessionCases: upsertSessionCase(currentState.sessionCases, caseRecord)
      }));

      await syncAtendimentoCase(response.pedidoId);
      await refreshOperationalSnapshot();
    } catch (error) {
      updateAtendimento((currentState) => ({
        ...currentState,
        submitting: false,
        lastError: toErrorMessage(error)
      }));
    }
  };

  const lookupAtendimento = async (form: HTMLFormElement): Promise<void> => {
    const formData = new FormData(form);
    const lookupPhone = String(formData.get("lookupPhone") || "").trim();
    const lookupPedidoId = Number(String(formData.get("lookupPedidoId") || "").trim());

    updateAtendimento((currentState) => ({
      ...currentState,
      lookupPhone,
      lookupPedidoId: Number.isInteger(lookupPedidoId) && lookupPedidoId > 0 ? String(lookupPedidoId) : "",
      lastError: null
    }));

    if (lookupPedidoId > 0) {
      await syncAtendimentoCase(lookupPedidoId);
      return;
    }

    const matches = findCasesByPhone(store.getState().atendimento, lookupPhone);
    if (matches.length === 0) {
      updateAtendimento((currentState) => ({
        ...currentState,
        lastError: "Nenhum atendimento da sessao corresponde a esse telefone."
      }));
      return;
    }

    updateAtendimento((currentState) => ({
      ...currentState,
      activeCaseId: matches[0].pedidoId
    }));
  };

  const refreshCurrentModule = async (): Promise<void> => {
    if (store.getState().activeModule === "entregador") {
      await refreshEntregador();
      return;
    }

    await refreshOperationalSnapshot();

    if (store.getState().activeModule === "atendimento" && store.getState().atendimento.activeCaseId) {
      await syncAtendimentoCase(store.getState().atendimento.activeCaseId as number);
    }
  };

  const runDespachoRouteStart = async (entregadorId: number): Promise<void> => {
    const confirmed = targetWindow.confirm(`Iniciar a proxima rota pronta do entregador ${entregadorId}?`);

    if (!confirmed) {
      return;
    }

    store.startDespachoRouteStart();

    try {
      const result = await iniciarRotaPronta(store.getState().connection.apiBase, entregadorId);
      store.finishDespachoRouteStart(result);
      await refreshOperationalSnapshot();
    } catch (error) {
      store.failDespachoRouteStart(toErrorMessage(error));
    }
  };

  const requestEntregadorEventFields = (
    eventType: EntregadorEventType,
    entregaId: number
  ): Pick<EventoOperacionalRequest, "motivo" | "cobrancaCancelamentoCentavos"> | null => {
    if (eventType === "PEDIDO_ENTREGUE") {
      const confirmed = targetWindow.confirm(`Confirmar entrega da entrega ${entregaId}?`);
      return confirmed ? {} : null;
    }

    const promptLabel =
      eventType === "PEDIDO_FALHOU"
        ? "Motivo da falha"
        : "Motivo do cancelamento (obrigatorio para registrar na operacao)";
    const promptValue = targetWindow.prompt(promptLabel, "");

    if (promptValue === null) {
      return null;
    }

    const motivo = promptValue.trim();
    if (!motivo) {
      store.failEntregadorAction("Informe um motivo para registrar a excecao.");
      return null;
    }

    if (eventType !== "PEDIDO_CANCELADO") {
      return { motivo };
    }

    const chargePrompt = targetWindow.prompt("Cobranca de cancelamento em centavos (opcional)", "");

    if (!chargePrompt || !chargePrompt.trim()) {
      return { motivo };
    }

    const cobrancaCancelamentoCentavos = Number(chargePrompt);
    if (!Number.isInteger(cobrancaCancelamentoCentavos) || cobrancaCancelamentoCentavos < 0) {
      store.failEntregadorAction("Cobranca de cancelamento invalida.");
      return null;
    }

    return { motivo, cobrancaCancelamentoCentavos };
  };

  const runEntregadorEvent = async (eventType: EntregadorEventType, entregaId: number): Promise<void> => {
    const { entregadorId } = store.getState().entregador;
    const requestedFields = requestEntregadorEventFields(eventType, entregaId);

    if (requestedFields === null) {
      return;
    }

    const payload: EventoOperacionalRequest = {
      externalEventId: buildExternalEventId(eventType, entregadorId, entregaId),
      eventType,
      entregaId,
      actorEntregadorId: entregadorId,
      ...requestedFields
    };

    store.startEntregadorAction();

    try {
      const result = await registrarEventoOperacional(store.getState().connection.apiBase, payload);
      store.finishEntregadorAction(
        buildActionFeedback(
          eventType,
          result,
          result.idempotente
            ? `A API reconheceu que a entrega ${entregaId} ja estava nesse estado final.`
            : `Entrega ${entregaId} atualizada com sucesso para o evento ${eventType}.`
        )
      );
      await refreshEntregador();
    } catch (error) {
      store.failEntregadorAction(toErrorMessage(error));
    }
  };

  const copyEntregadorLink = async (): Promise<void> => {
    const deepLink = buildEntregadorDeepLink(store.getState().entregador.entregadorId, targetWindow.location.href);

    try {
      if (!targetWindow.navigator.clipboard) {
        throw new Error("Clipboard indisponivel neste navegador.");
      }

      await targetWindow.navigator.clipboard.writeText(deepLink);
      store.finishEntregadorAction({
        tone: "ok",
        title: "Link copiado",
        detail: "Deep link do entregador copiado para compartilhamento rapido.",
        payload: null
      });
    } catch (error) {
      store.failEntregadorAction(toErrorMessage(error));
    }
  };

  const startEntregadorRoute = async (): Promise<void> => {
    const { entregadorId } = store.getState().entregador;
    const confirmed = targetWindow.confirm(`Iniciar a proxima rota pronta do entregador ${entregadorId}?`);

    if (!confirmed) {
      return;
    }

    store.startEntregadorAction();

    try {
      const result = await iniciarRotaPronta(store.getState().connection.apiBase, entregadorId);
      store.finishEntregadorAction(
        buildActionFeedback(
          "ROTA_INICIADA",
          result,
          result.idempotente
            ? `A rota ${result.rotaId} ja estava em andamento para o entregador ${entregadorId}.`
            : `Rota ${result.rotaId} iniciada com sucesso para o entregador ${entregadorId}.`
        )
      );
      await refreshEntregador();
    } catch (error) {
      store.failEntregadorAction(toErrorMessage(error));
    }
  };

  const handleClick = (event: MouseEvent): void => {
    const target = event.target instanceof HTMLElement ? event.target : null;
    const actionElement = target?.closest<HTMLElement>("[data-action]");

    if (!actionElement || !root.contains(actionElement)) {
      return;
    }

    const action = actionElement.dataset.action;
    switch (action) {
      case "save-api-base":
        store.commitConnection();
        persistApiBase(store.getState().connection.apiBase);
        void refreshCurrentModule();
        return;
      case "refresh-snapshot":
        void refreshCurrentModule();
        return;
      case "navigate": {
        const moduleId = actionElement.dataset.moduleId as AppModuleId | undefined;

        if (moduleId) {
          router.navigate(moduleId);
          store.setActiveModule(moduleId);
          void refreshCurrentModule();
        }
        return;
      }
      case "focus-atendimento-case": {
        const pedidoId = Number(actionElement.dataset.pedidoId);

        if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
          return;
        }

        updateAtendimento((currentState) => ({
          ...currentState,
          activeCaseId: pedidoId,
          lastError: null
        }));
        return;
      }
      case "refresh-atendimento-case": {
        const pedidoId = Number(actionElement.dataset.pedidoId);

        if (!Number.isInteger(pedidoId) || pedidoId <= 0) {
          return;
        }

        void syncAtendimentoCase(pedidoId);
        return;
      }
      case "start-prepared-route": {
        const entregadorId = Number(actionElement.dataset.entregadorId);

        if (!Number.isInteger(entregadorId) || entregadorId <= 0) {
          store.failDespachoRouteStart("Entregador invalido para iniciar a rota.");
          return;
        }

        void runDespachoRouteStart(entregadorId);
        return;
      }
      case "load-entregador": {
        const entregadorId = readEntregadorIdInput();

        if (!entregadorId) {
          store.failEntregadorSync("Informe um entregadorId valido para carregar o roteiro.");
          return;
        }

        store.setEntregadorId(entregadorId);
        persistEntregadorId(entregadorId);
        syncEntregadorQueryParam(entregadorId);
        router.navigate("entregador");
        store.setActiveModule("entregador");
        void refreshEntregador();
        return;
      }
      case "copy-entregador-link":
        void copyEntregadorLink();
        return;
      case "start-entregador-route":
        void startEntregadorRoute();
        return;
      case "run-entregador-event": {
        const eventType = parseEntregadorEventType(actionElement.dataset.eventType);
        const entregaId = Number(actionElement.dataset.entregaId);

        if (!eventType || !Number.isInteger(entregaId) || entregaId <= 0) {
          store.failEntregadorAction("Acao de parada invalida.");
          return;
        }

        void runEntregadorEvent(eventType, entregaId);
        return;
      }
      default:
        return;
    }
  };

  const handleInput = (event: Event): void => {
    const target = event.target;

    if (
      !(target instanceof HTMLInputElement || target instanceof HTMLSelectElement || target instanceof HTMLTextAreaElement) ||
      !root.contains(target)
    ) {
      return;
    }

    if (target instanceof HTMLInputElement && target.id === "api-base-input") {
      store.setConnectionDraft(target.value);
      return;
    }

    if (target instanceof HTMLInputElement && target.id === "auto-refresh-input") {
      const nextAutoRefresh = Boolean(target.checked);
      store.setAutoRefresh(nextAutoRefresh);
      persistAutoRefresh(nextAutoRefresh);
      return;
    }

    const form = target.closest("form");
    const shouldNotify = event.type === "change";

    if (form?.id === "atendimento-form") {
      const draft: AtendimentoRequestDraft = readDraftFromFormData(new FormData(form));
      updateAtendimento(
        (currentState) => ({
          ...currentState,
          draft
        }),
        shouldNotify
      );
      return;
    }

    if (form?.id === "atendimento-lookup-form") {
      const formData = new FormData(form);
      updateAtendimento(
        (currentState) => ({
          ...currentState,
          lookupPhone: String(formData.get("lookupPhone") || "").trim(),
          lookupPedidoId: String(formData.get("lookupPedidoId") || "").trim()
        }),
        shouldNotify
      );
    }
  };

  const handleSubmit = (event: SubmitEvent): void => {
    const form = event.target;

    if (!(form instanceof HTMLFormElement) || !root.contains(form)) {
      return;
    }

    if (form.id === "atendimento-form") {
      event.preventDefault();
      void submitAtendimento(form);
      return;
    }

    if (form.id === "atendimento-lookup-form") {
      event.preventDefault();
      void lookupAtendimento(form);
    }
  };

  return {
    bind(): void {
      root.addEventListener("click", handleClick);
      root.addEventListener("input", handleInput);
      root.addEventListener("change", handleInput);
      root.addEventListener("submit", handleSubmit);
    },
    refreshSnapshot: refreshCurrentModule,
    dispose(): void {
      root.removeEventListener("click", handleClick);
      root.removeEventListener("input", handleInput);
      root.removeEventListener("change", handleInput);
      root.removeEventListener("submit", handleSubmit);
      router.dispose();
    }
  };
}
