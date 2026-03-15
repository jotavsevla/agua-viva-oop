import type {
  EntregadorActionFeedback,
  EntregadorEventType,
  EntregadorState,
  ExecucaoEntregaResultado,
  RoteiroEntregadorParada,
  RoteiroEntregadorResponse
} from "../types";

export interface EntregadorProgress {
  totalParadas: number;
  pendentes: number;
  concluidas: number;
  percentualConcluido: number;
  proximaParada: RoteiroEntregadorParada | null;
}

export function createEntregadorState(entregadorId = 1): EntregadorState {
  return {
    entregadorId,
    roteiro: null,
    fetchedAt: null,
    sync: {
      status: "idle",
      lastError: null
    },
    action: {
      status: "idle",
      lastError: null
    },
    lastAction: null
  };
}

export function buildEntregadorDeepLink(entregadorId: number, currentUrl: string): string {
  const url = new URL(currentUrl);
  url.searchParams.set("entregadorId", String(entregadorId));
  url.hash = "#/entregador";
  return url.toString();
}

export function deriveEntregadorProgress(roteiro: RoteiroEntregadorResponse | null): EntregadorProgress {
  const pendentes = roteiro?.paradasPendentesExecucao ?? [];
  const concluidas = roteiro?.paradasConcluidas ?? [];
  const totalParadas = pendentes.length + concluidas.length;

  return {
    totalParadas,
    pendentes: pendentes.length,
    concluidas: concluidas.length,
    percentualConcluido: totalParadas === 0 ? 0 : Math.round((concluidas.length / totalParadas) * 100),
    proximaParada: pendentes[0] ?? null
  };
}

export function buildExternalEventId(
  eventType: EntregadorEventType,
  entregadorId: number,
  entregaId: number
): string {
  return `operacao-web-${entregadorId}-${eventType.toLowerCase()}-${entregaId}-${Date.now()}`;
}

export function buildActionFeedback(
  eventType: string,
  payload: ExecucaoEntregaResultado,
  detalhe: string
): EntregadorActionFeedback {
  if (payload.idempotente) {
    return {
      tone: "warn",
      title: `${eventType} ja reconhecido`,
      detail: detalhe,
      payload
    };
  }

  return {
    tone: "ok",
    title: `${eventType} confirmado`,
    detail: detalhe,
    payload
  };
}
