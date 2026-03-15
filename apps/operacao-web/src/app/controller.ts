import { fetchOperationalSnapshot } from "../api";
import type { AppModuleId } from "../types";
import type { AppRouter } from "./router";
import type { AppStore } from "./store";

interface AppControllerOptions {
  root: HTMLElement;
  router: AppRouter;
  store: AppStore;
  persistApiBase: (value: string) => void;
  persistAutoRefresh: (value: boolean) => void;
}

export interface AppController {
  bind(): void;
  refreshSnapshot(): Promise<void>;
  dispose(): void;
}

function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Falha ao sincronizar a operacao.";
}

export function createAppController(options: AppControllerOptions): AppController {
  const { root, router, store, persistApiBase, persistAutoRefresh } = options;

  const refreshSnapshot = async (): Promise<void> => {
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

  const handleClick = (event: MouseEvent): void => {
    const target = event.target instanceof HTMLElement ? event.target : null;
    const actionElement = target?.closest<HTMLElement>("[data-action]");

    if (!actionElement || !root.contains(actionElement)) {
      return;
    }

    const action = actionElement.dataset.action;

    if (action === "save-api-base") {
      store.commitConnection();
      persistApiBase(store.getState().connection.apiBase);
      void refreshSnapshot();
      return;
    }

    if (action === "refresh-snapshot") {
      void refreshSnapshot();
      return;
    }

    if (action === "navigate") {
      const moduleId = actionElement.dataset.moduleId as AppModuleId | undefined;

      if (!moduleId) {
        return;
      }

      router.navigate(moduleId);
      store.setActiveModule(moduleId);
    }
  };

  const handleInput = (event: Event): void => {
    const target = event.target;

    if (!(target instanceof HTMLInputElement) || !root.contains(target)) {
      return;
    }

    if (target.id === "api-base-input") {
      store.setConnectionDraft(target.value);
      return;
    }

    if (target.id === "auto-refresh-input") {
      const nextAutoRefresh = Boolean(target.checked);
      store.setAutoRefresh(nextAutoRefresh);
      persistAutoRefresh(nextAutoRefresh);
    }
  };

  return {
    bind(): void {
      root.addEventListener("click", handleClick);
      root.addEventListener("input", handleInput);
    },
    refreshSnapshot,
    dispose(): void {
      root.removeEventListener("click", handleClick);
      root.removeEventListener("input", handleInput);
      router.dispose();
    }
  };
}
