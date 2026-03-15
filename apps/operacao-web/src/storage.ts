import type { AppView, AtendimentoPersistedState } from "./types";

const API_BASE_STORAGE_KEY = "agua-viva.operacao-web.api-base";
const AUTO_REFRESH_STORAGE_KEY = "agua-viva.operacao-web.auto-refresh";
const CURRENT_VIEW_STORAGE_KEY = "agua-viva.operacao-web.current-view";
const ATENDIMENTO_STATE_STORAGE_KEY = "agua-viva.operacao-web.atendimento-state";
const ENTREGADOR_ID_STORAGE_KEY = "agua-viva.operacao-web.entregador-id";

export const DEFAULT_API_BASE = "http://localhost:8082";

function readStorageValue(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorageValue(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Ignore storage failures in private mode or locked-down browsers.
  }
}

function readJsonValue<T>(key: string): T | null {
  const raw = readStorageValue(key);
  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

export function readApiBase(): string {
  return readStorageValue(API_BASE_STORAGE_KEY) || DEFAULT_API_BASE;
}

export function writeApiBase(value: string): void {
  writeStorageValue(API_BASE_STORAGE_KEY, value);
}

export function readAutoRefresh(): boolean {
  const stored = readStorageValue(AUTO_REFRESH_STORAGE_KEY);
  return stored !== "0";
}

export function writeAutoRefresh(value: boolean): void {
  writeStorageValue(AUTO_REFRESH_STORAGE_KEY, value ? "1" : "0");
}

export function readCurrentView(): AppView {
  const stored = readStorageValue(CURRENT_VIEW_STORAGE_KEY);
  return stored === "atendimento" ? "atendimento" : "operacao";
}

export function writeCurrentView(value: AppView): void {
  writeStorageValue(CURRENT_VIEW_STORAGE_KEY, value);
}

export function readAtendimentoState(): AtendimentoPersistedState | null {
  return readJsonValue<AtendimentoPersistedState>(ATENDIMENTO_STATE_STORAGE_KEY);
}

export function writeAtendimentoState(value: AtendimentoPersistedState): void {
  writeStorageValue(ATENDIMENTO_STATE_STORAGE_KEY, JSON.stringify(value));
}

export function readEntregadorId(): number | null {
  const stored = Number(readStorageValue(ENTREGADOR_ID_STORAGE_KEY));
  return Number.isInteger(stored) && stored > 0 ? stored : null;
}

export function writeEntregadorId(value: number): void {
  if (Number.isInteger(value) && value > 0) {
    writeStorageValue(ENTREGADOR_ID_STORAGE_KEY, String(value));
  }
}
