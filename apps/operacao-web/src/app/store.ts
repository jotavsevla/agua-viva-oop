import type {
  AppModuleId,
  AtendimentoModuleState,
  AppState,
  EntregadorActionFeedback,
  EventoResponse,
  OperationalSnapshot,
  RoteiroEntregadorResponse
} from "../types";
import { createEntregadorState } from "../entregador/model";

type Listener = (state: AppState) => void;
type StateUpdater = (state: AppState) => AppState;
type AtendimentoUpdater = (state: AtendimentoModuleState) => AtendimentoModuleState;

export interface AppStore {
  getState(): AppState;
  subscribe(listener: Listener): () => void;
  setConnectionDraft(apiBaseDraft: string): void;
  commitConnection(): void;
  setAutoRefresh(autoRefresh: boolean): void;
  setActiveModule(moduleId: AppModuleId): void;
  updateAtendimento(updater: AtendimentoUpdater, options?: { notify?: boolean }): void;
  setEntregadorId(entregadorId: number): void;
  startSync(): void;
  finishSync(snapshot: OperationalSnapshot): void;
  failSync(message: string): void;
  startDespachoRouteStart(): void;
  finishDespachoRouteStart(payload: EventoResponse): void;
  failDespachoRouteStart(message: string): void;
  startEntregadorSync(): void;
  finishEntregadorSync(roteiro: RoteiroEntregadorResponse, fetchedAt: string): void;
  failEntregadorSync(message: string): void;
  startEntregadorAction(): void;
  finishEntregadorAction(feedback: EntregadorActionFeedback): void;
  failEntregadorAction(message: string): void;
}

interface UpdateOptions {
  notify?: boolean;
}

export function createAppStore(initialState: AppState): AppStore {
  let state = initialState;
  const listeners = new Set<Listener>();

  const notify = (): void => {
    listeners.forEach((listener) => listener(state));
  };

  const setState = (updater: StateUpdater, options: UpdateOptions = {}): void => {
    state = updater(state);

    if (options.notify !== false) {
      notify();
    }
  };

  return {
    getState(): AppState {
      return state;
    },
    subscribe(listener: Listener): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    setConnectionDraft(apiBaseDraft: string): void {
      setState(
        (currentState) => ({
          ...currentState,
          connection: {
            ...currentState.connection,
            apiBaseDraft
          }
        }),
        { notify: false }
      );
    },
    commitConnection(): void {
      setState((currentState) => {
        const nextApiBase = currentState.connection.apiBaseDraft.trim() || currentState.connection.apiBase;

        return {
          ...currentState,
          connection: {
            ...currentState.connection,
            apiBase: nextApiBase,
            apiBaseDraft: nextApiBase
          }
        };
      });
    },
    setAutoRefresh(autoRefresh: boolean): void {
      setState((currentState) => ({
        ...currentState,
        connection: {
          ...currentState.connection,
          autoRefresh
        }
      }));
    },
    setActiveModule(moduleId: AppModuleId): void {
      setState((currentState) => ({
        ...currentState,
        activeModule: moduleId
      }));
    },
    updateAtendimento(updater: AtendimentoUpdater, options?: { notify?: boolean }): void {
      setState(
        (currentState) => ({
          ...currentState,
          atendimento: updater(currentState.atendimento)
        }),
        options
      );
    },
    setEntregadorId(entregadorId: number): void {
      setState((currentState) => ({
        ...currentState,
        entregador: createEntregadorState(entregadorId)
      }));
    },
    startSync(): void {
      setState((currentState) => ({
        ...currentState,
        sync: {
          ...currentState.sync,
          status: "loading",
          lastError: null
        }
      }));
    },
    finishSync(snapshot: OperationalSnapshot): void {
      setState((currentState) => ({
        ...currentState,
        snapshot,
        sync: {
          status: "ready",
          lastError: null
        }
      }));
    },
    failSync(message: string): void {
      setState((currentState) => ({
        ...currentState,
        sync: {
          status: "error",
          lastError: message
        }
      }));
    },
    startDespachoRouteStart(): void {
      setState((currentState) => ({
        ...currentState,
        despacho: {
          ...currentState.despacho,
          routeStart: {
            status: "loading",
            lastError: null
          },
          lastRouteStart: null
        }
      }));
    },
    finishDespachoRouteStart(payload: EventoResponse): void {
      setState((currentState) => ({
        ...currentState,
        despacho: {
          routeStart: {
            status: "ready",
            lastError: null
          },
          lastRouteStart: {
            tone: payload.idempotente ? "warn" : "ok",
            title: payload.idempotente ? "Acao reconhecida como idempotente" : "Rota pronta iniciada",
            detail: `R${payload.rotaId} disparada para o pedido ${payload.pedidoId} e entrega ${payload.entregaId}.`,
            payload
          }
        }
      }));
    },
    failDespachoRouteStart(message: string): void {
      setState((currentState) => ({
        ...currentState,
        despacho: {
          routeStart: {
            status: "error",
            lastError: message
          },
          lastRouteStart: {
            tone: "danger",
            title: "Falha ao iniciar rota pronta",
            detail: message,
            payload: null
          }
        }
      }));
    },
    startEntregadorSync(): void {
      setState((currentState) => ({
        ...currentState,
        entregador: {
          ...currentState.entregador,
          sync: {
            status: "loading",
            lastError: null
          }
        }
      }));
    },
    finishEntregadorSync(roteiro: RoteiroEntregadorResponse, fetchedAt: string): void {
      setState((currentState) => ({
        ...currentState,
        entregador: {
          ...currentState.entregador,
          roteiro,
          fetchedAt,
          sync: {
            status: "ready",
            lastError: null
          }
        }
      }));
    },
    failEntregadorSync(message: string): void {
      setState((currentState) => ({
        ...currentState,
        entregador: {
          ...currentState.entregador,
          sync: {
            status: "error",
            lastError: message
          }
        }
      }));
    },
    startEntregadorAction(): void {
      setState((currentState) => ({
        ...currentState,
        entregador: {
          ...currentState.entregador,
          action: {
            status: "loading",
            lastError: null
          }
        }
      }));
    },
    finishEntregadorAction(feedback: EntregadorActionFeedback): void {
      setState((currentState) => ({
        ...currentState,
        entregador: {
          ...currentState.entregador,
          action: {
            status: "ready",
            lastError: null
          },
          lastAction: feedback
        }
      }));
    },
    failEntregadorAction(message: string): void {
      setState((currentState) => ({
        ...currentState,
        entregador: {
          ...currentState.entregador,
          action: {
            status: "error",
            lastError: message
          },
          lastAction: {
            tone: "danger",
            title: "Acao rejeitada",
            detail: message,
            payload: null
          }
        }
      }));
    }
  };
}
