import "./styles.css";
import { createAtendimentoModuleState } from "./atendimento/model";
import { createEntregadorState } from "./entregador/model";
import { createAppController } from "./app/controller";
import { resolveModuleId } from "./app/modules";
import { createPollingController } from "./app/polling";
import { createAppRouter } from "./app/router";
import { createAppStore } from "./app/store";
import { renderApp } from "./render";
import {
  readApiBase,
  readAutoRefresh,
  readAtendimentoState,
  readEntregadorId,
  writeApiBase,
  writeAtendimentoState,
  writeAutoRefresh,
  writeEntregadorId
} from "./storage";
import type { AppState } from "./types";

const AUTO_REFRESH_MS = 15000;
const DEFAULT_ENTREGADOR_ID = 1;

const root = document.querySelector<HTMLDivElement>("#app");

if (!root) {
  throw new Error("Elemento #app nao encontrado.");
}

const appRoot = root;
const initialApiBase = readApiBase();
const initialEntregadorId = readEntregadorIdFromUrl() ?? readEntregadorId() ?? DEFAULT_ENTREGADOR_ID;

const initialState: AppState = {
  activeModule: resolveModuleId(window.location.hash),
  connection: {
    apiBase: initialApiBase,
    apiBaseDraft: initialApiBase,
    autoRefresh: readAutoRefresh()
  },
  sync: {
    status: "idle",
    lastError: null
  },
  snapshot: null,
  atendimento: createAtendimentoModuleState(readAtendimentoState()),
  despacho: {
    routeStart: {
      status: "idle",
      lastError: null
    },
    lastRouteStart: null
  },
  entregador: createEntregadorState(initialEntregadorId)
};

const store = createAppStore(initialState);
const router = createAppRouter((moduleId) => {
  store.setActiveModule(moduleId);
});

let controller: ReturnType<typeof createAppController>;

const poller = createPollingController({
  intervalMs: AUTO_REFRESH_MS,
  onTick: () => {
    void controller.refreshSnapshot();
  }
});

controller = createAppController({
  root: appRoot,
  router,
  store,
  persistApiBase: writeApiBase,
  persistAutoRefresh: writeAutoRefresh,
  persistAtendimentoState: writeAtendimentoState,
  persistEntregadorId: writeEntregadorId
});

store.subscribe((state) => {
  renderApp(appRoot, state);
  poller.sync(state.connection.autoRefresh);
});

controller.bind();
renderApp(appRoot, store.getState());
router.start();
poller.sync(store.getState().connection.autoRefresh);
void controller.refreshSnapshot();

window.addEventListener("beforeunload", () => {
  controller.dispose();
  poller.stop();
});

function readEntregadorIdFromUrl(): number | null {
  try {
    const url = new URL(window.location.href);
    const entregadorId = Number(url.searchParams.get("entregadorId"));
    return Number.isInteger(entregadorId) && entregadorId > 0 ? entregadorId : null;
  } catch {
    return null;
  }
}
