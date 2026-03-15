import type {
  AtendimentoRequestPayload,
  AtendimentoResponse,
  EventoOperacionalRequest,
  ExecucaoEntregaResultado,
  ExtratoResponse,
  HealthResponse,
  OperacaoEventosResponse,
  OperacaoMapaResponse,
  OperacaoPainelResponse,
  OperationalSnapshot,
  PedidoExecucaoResponse,
  ReadinessKey,
  ReadinessStatus,
  RoteiroEntregadorResponse,
  SaldoResponse,
  TimelineResponse
} from "./types";

interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
}

const OPERACAO_EVENTOS_LIMITE = 20;
const DEFAULT_EXTRATO_LIMIT = 8;

export class ApiError extends Error {
  readonly status: number;
  readonly detail: string | null;

  constructor(message: string, status: number, detail: string | null = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
  }
}

function withBaseUrl(baseUrl: string, path: string): string {
  return `${String(baseUrl).replace(/\/+$/, "")}${path}`;
}

export async function requestJson<T>(
  baseUrl: string,
  path: string,
  options: RequestOptions = {}
): Promise<T> {
  const response = await fetch(withBaseUrl(baseUrl, path), {
    method: options.method || "GET",
    headers: {
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.headers || {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  });

  const payload = (await response.json().catch(() => ({}))) as T & {
    erro?: string;
    detalhe?: string;
    message?: string;
  };

  if (!response.ok) {
    throw new ApiError(
      payload.erro || payload.message || `HTTP ${response.status}`,
      response.status,
      payload.detalhe || null
    );
  }

  return payload;
}

function createEmptyReadiness(): Record<ReadinessKey, ReadinessStatus> {
  return {
    health: "unknown",
    painel: "unknown",
    eventos: "unknown",
    mapa: "unknown"
  };
}

export async function fetchOperationalSnapshot(baseUrl: string): Promise<OperationalSnapshot> {
  const readiness = createEmptyReadiness();
  const partialErrors: string[] = [];

  const [healthResult, painelResult, eventosResult, mapaResult] = await Promise.allSettled([
    requestJson<HealthResponse>(baseUrl, "/health"),
    requestJson<OperacaoPainelResponse>(baseUrl, "/api/operacao/painel"),
    requestJson<OperacaoEventosResponse>(baseUrl, `/api/operacao/eventos?limite=${OPERACAO_EVENTOS_LIMITE}`),
    requestJson<OperacaoMapaResponse>(baseUrl, "/api/operacao/mapa")
  ]);

  const health = readSettledResult(healthResult, "health", readiness, partialErrors);
  const painel = readSettledResult(painelResult, "painel", readiness, partialErrors);
  const eventos = readSettledResult(eventosResult, "eventos", readiness, partialErrors);
  const mapa = readSettledResult(mapaResult, "mapa", readiness, partialErrors);

  if (!health && !painel && !eventos && !mapa) {
    throw new Error("Nao foi possivel carregar nenhum read model operacional.");
  }

  return {
    health,
    painel,
    eventos,
    mapa,
    readiness,
    partialErrors,
    fetchedAt: new Date().toISOString()
  };
}

function readSettledResult<T>(
  result: PromiseSettledResult<T>,
  key: ReadinessKey,
  readiness: Record<ReadinessKey, ReadinessStatus>,
  partialErrors: string[]
): T | null {
  if (result.status === "fulfilled") {
    readiness[key] = "ok";
    return result.value;
  }

  readiness[key] = "error";
  partialErrors.push(`${key}: ${result.reason instanceof Error ? result.reason.message : "falha"}`);
  return null;
}

export function isUnavailableOptionalEndpoint(error: unknown): boolean {
  return error instanceof ApiError && [400, 404, 405, 501].includes(error.status);
}

export async function createAtendimentoPedido(
  baseUrl: string,
  payload: AtendimentoRequestPayload,
  idempotencyKey: string | null
): Promise<AtendimentoResponse> {
  return requestJson<AtendimentoResponse>(baseUrl, "/api/atendimento/pedidos", {
    method: "POST",
    headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined,
    body: payload
  });
}

export async function fetchPedidoTimeline(baseUrl: string, pedidoId: number): Promise<TimelineResponse> {
  return requestJson<TimelineResponse>(baseUrl, `/api/pedidos/${pedidoId}/timeline`);
}

export async function fetchPedidoExecucao(baseUrl: string, pedidoId: number): Promise<PedidoExecucaoResponse> {
  return requestJson<PedidoExecucaoResponse>(baseUrl, `/api/pedidos/${pedidoId}/execucao`);
}

export async function fetchClienteSaldo(baseUrl: string, clienteId: number): Promise<SaldoResponse> {
  return requestJson<SaldoResponse>(baseUrl, `/api/financeiro/clientes/${clienteId}/saldo`);
}

export async function fetchClienteExtrato(
  baseUrl: string,
  clienteId: number,
  limit = DEFAULT_EXTRATO_LIMIT
): Promise<ExtratoResponse> {
  return requestJson<ExtratoResponse>(baseUrl, `/api/financeiro/clientes/${clienteId}/extrato?limit=${limit}`);
}

export async function fetchEntregadorRoteiro(
  baseUrl: string,
  entregadorId: number
): Promise<RoteiroEntregadorResponse> {
  return requestJson<RoteiroEntregadorResponse>(baseUrl, `/api/entregadores/${entregadorId}/roteiro`);
}

export async function iniciarRotaPronta(baseUrl: string, entregadorId: number): Promise<ExecucaoEntregaResultado> {
  return requestJson<ExecucaoEntregaResultado>(baseUrl, "/api/operacao/rotas/prontas/iniciar", {
    method: "POST",
    body: {
      entregadorId
    }
  });
}

export async function registrarEventoOperacional(
  baseUrl: string,
  payload: EventoOperacionalRequest
): Promise<ExecucaoEntregaResultado> {
  return requestJson<ExecucaoEntregaResultado>(baseUrl, "/api/eventos", {
    method: "POST",
    body: payload
  });
}
