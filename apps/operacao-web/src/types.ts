export interface HealthResponse {
  status: string;
  database: string;
}

export interface OperacaoPainelPedidosPorStatus {
  pendente: number;
  confirmado: number;
  emRota: number;
  entregue: number;
  cancelado: number;
}

export interface OperacaoPainelIndicadoresEntrega {
  totalFinalizadas: number;
  entregasConcluidas: number;
  entregasCanceladas: number;
  taxaSucessoPercentual: number;
}

export interface OperacaoPainelRotaEmAndamento {
  rotaId: number;
  entregadorId: number;
  pendentes: number;
  emExecucao: number;
}

export interface OperacaoPainelRotaPlanejada {
  rotaId: number;
  entregadorId: number;
  pendentes: number;
}

export interface OperacaoPainelPendenteElegivel {
  pedidoId: number;
  criadoEm: string;
  quantidadeGaloes: number;
  janelaTipo: string;
}

export interface OperacaoPainelConfirmadoSecundaria {
  pedidoId: number;
  rotaId: number;
  ordemNaRota: number;
  entregadorId: number;
  quantidadeGaloes: number;
}

export interface OperacaoPainelEmRotaPrimaria {
  pedidoId: number;
  rotaId: number;
  entregaId: number;
  entregadorId: number;
  quantidadeGaloes: number;
  statusEntrega: string;
}

export interface OperacaoPainelResponse {
  atualizadoEm: string;
  ambiente: string;
  pedidosPorStatus: OperacaoPainelPedidosPorStatus;
  indicadoresEntrega: OperacaoPainelIndicadoresEntrega;
  rotas: {
    emAndamento: OperacaoPainelRotaEmAndamento[];
    planejadas: OperacaoPainelRotaPlanejada[];
  };
  filas: {
    pendentesElegiveis: OperacaoPainelPendenteElegivel[];
    confirmadosSecundaria: OperacaoPainelConfirmadoSecundaria[];
    emRotaPrimaria: OperacaoPainelEmRotaPrimaria[];
  };
}

export interface OperacaoEventoItem {
  id: number;
  eventType: string;
  status: string;
  aggregateType: string;
  aggregateId: number | null;
  payload: Record<string, unknown>;
  createdEm: string;
  processedEm: string | null;
}

export interface OperacaoEventosResponse {
  eventos: OperacaoEventoItem[];
}

export interface OperacaoMapaDeposito {
  lat: number;
  lon: number;
}

export interface OperacaoMapaParada {
  pedidoId: number;
  entregaId: number;
  ordemNaRota: number;
  statusEntrega: string;
  quantidadeGaloes: number;
  lat: number;
  lon: number;
}

export interface OperacaoMapaPonto {
  tipo: "DEPOSITO" | "PARADA";
  pedidoId: number | null;
  entregaId: number | null;
  ordemNaRota: number | null;
  lat: number;
  lon: number;
}

export interface OperacaoMapaRota {
  rotaId: number;
  entregadorId: number;
  statusRota: string;
  camada: string;
  paradas: OperacaoMapaParada[];
  trajeto: OperacaoMapaPonto[];
}

export interface OperacaoMapaResponse {
  atualizadoEm: string;
  ambiente: string;
  deposito: OperacaoMapaDeposito;
  rotas: OperacaoMapaRota[];
}

export type ReadinessKey = "health" | "painel" | "eventos" | "mapa";
export type ReadinessStatus = "unknown" | "ok" | "error";

export interface OperationalSnapshot {
  health: HealthResponse | null;
  painel: OperacaoPainelResponse | null;
  eventos: OperacaoEventosResponse | null;
  mapa: OperacaoMapaResponse | null;
  readiness: Record<ReadinessKey, ReadinessStatus>;
  partialErrors: string[];
  fetchedAt: string | null;
}

export type AtendimentoOrigemCanal = "MANUAL" | "WHATSAPP" | "BINA_FIXO" | "TELEFONIA_FIXO";
export type AtendimentoMetodoPagamento = "NAO_INFORMADO" | "DINHEIRO" | "PIX" | "CARTAO" | "VALE";
export type AtendimentoJanelaTipo = "ASAP" | "HARD" | "FLEXIVEL";
export type AtendimentoFinanceStatus = "idle" | "ok" | "unavailable" | "error";

export interface AtendimentoRequestDraft {
  telefone: string;
  quantidadeGaloes: string;
  atendenteId: string;
  origemCanal: AtendimentoOrigemCanal | "";
  sourceEventId: string;
  manualRequestId: string;
  externalCallId: string;
  metodoPagamento: AtendimentoMetodoPagamento | "";
  janelaTipo: AtendimentoJanelaTipo | "";
  janelaInicio: string;
  janelaFim: string;
  nomeCliente: string;
  endereco: string;
  latitude: string;
  longitude: string;
}

export interface AtendimentoRequestPayload {
  telefone: string;
  quantidadeGaloes: number;
  atendenteId: number;
  origemCanal?: AtendimentoOrigemCanal;
  sourceEventId?: string;
  manualRequestId?: string;
  externalCallId?: string;
  metodoPagamento?: AtendimentoMetodoPagamento;
  janelaTipo?: AtendimentoJanelaTipo;
  janelaInicio?: string;
  janelaFim?: string;
  nomeCliente?: string;
  endereco?: string;
  latitude?: number;
  longitude?: number;
}

export interface AtendimentoResponse {
  pedidoId: number;
  clienteId: number;
  telefoneNormalizado: string;
  clienteCriado: boolean;
  idempotente: boolean;
}

export interface TimelineEvent {
  timestamp: string;
  deStatus: string;
  paraStatus: string;
  origem: string;
  observacao?: string;
}

export interface TimelineResponse {
  pedidoId: number;
  statusAtual: string;
  eventos: TimelineEvent[];
}

export interface PedidoExecucaoResponse {
  pedidoId: number;
  rotaPrimariaId: number | null;
  entregaAtivaId: number | null;
  statusPedido: string;
  camada: string;
  rotaId: number | null;
  entregaId: number | null;
}

export interface SaldoResponse {
  clienteId: number;
  quantidade: number;
}

export interface ExtratoItem {
  data: string;
  tipo: "CREDITO" | "DEBITO";
  quantidade: number;
  saldoApos: number;
  registradoPor: string;
  pedidoId?: number;
  observacao?: string;
}

export interface ExtratoResponse {
  clienteId: number;
  itens: ExtratoItem[];
}

export interface AtendimentoCaseRecord {
  pedidoId: number;
  clienteId: number | null;
  telefone: string;
  telefoneNormalizado: string;
  quantidadeGaloes: number;
  atendenteId: number;
  origemCanal: AtendimentoOrigemCanal | "";
  metodoPagamento: AtendimentoMetodoPagamento | "";
  janelaTipo: AtendimentoJanelaTipo | "";
  janelaInicio: string;
  janelaFim: string;
  nomeCliente: string;
  endereco: string;
  requestKey: string | null;
  clienteCriado: boolean;
  idempotente: boolean;
  statusAtual: string | null;
  timeline: TimelineResponse | null;
  execucao: PedidoExecucaoResponse | null;
  saldo: SaldoResponse | null;
  extrato: ExtratoResponse | null;
  financeStatus: AtendimentoFinanceStatus;
  notes: string[];
  error: string | null;
  createdAt: string;
  updatedAt: string;
  lastSyncAt: string | null;
}

export interface AtendimentoModuleState {
  draft: AtendimentoRequestDraft;
  lookupPhone: string;
  lookupPedidoId: string;
  sessionCases: AtendimentoCaseRecord[];
  activeCaseId: number | null;
  submitting: boolean;
  syncingCaseId: number | null;
  lastError: string | null;
  lastSuccess: string | null;
}

export interface AtendimentoPersistedState {
  draft: AtendimentoRequestDraft;
  lookupPhone: string;
  lookupPedidoId: string;
  sessionCases: AtendimentoCaseRecord[];
  activeCaseId: number | null;
}

export type AppModuleId = "cockpit" | "atendimento" | "despacho" | "entregador";
export type AppView = "operacao" | "atendimento";
export type SyncStatus = "idle" | "loading" | "ready" | "error";
export type DisplayTone = "ok" | "warn" | "danger" | "info" | "muted";

export interface AppModuleDefinition {
  id: AppModuleId;
  hash: string;
  label: string;
  description: string;
  status: "active" | "planned";
}

export interface ConnectionState {
  apiBase: string;
  apiBaseDraft: string;
  autoRefresh: boolean;
}

export interface SyncState {
  status: SyncStatus;
  lastError: string | null;
}

export interface RoteiroEntregadorRota {
  rotaId: number;
  status: string;
}

export interface RoteiroEntregadorParada {
  entregaId: number;
  pedidoId: number;
  ordemNaRota: number;
  status: string;
  quantidadeGaloes: number;
  clienteNome: string;
}

export interface RoteiroEntregadorResponse {
  entregadorId: number;
  rota: RoteiroEntregadorRota | null;
  cargaRemanescente: number;
  paradasPendentesExecucao: RoteiroEntregadorParada[];
  paradasConcluidas: RoteiroEntregadorParada[];
}

export type EntregadorEventType = "PEDIDO_ENTREGUE" | "PEDIDO_FALHOU" | "PEDIDO_CANCELADO";

export interface EventoOperacionalRequest {
  externalEventId?: string;
  eventType: EntregadorEventType;
  entregaId: number;
  actorEntregadorId: number;
  motivo?: string;
  cobrancaCancelamentoCentavos?: number;
}

export interface ExecucaoEntregaResultado {
  evento: string;
  rotaId: number;
  entregaId: number;
  pedidoId: number;
  idempotente: boolean;
}

export type EventoResponse = ExecucaoEntregaResultado;

export interface DespachoRouteStartFeedback {
  tone: Exclude<DisplayTone, "muted">;
  title: string;
  detail: string;
  payload: EventoResponse | null;
}

export interface DespachoState {
  routeStart: SyncState;
  lastRouteStart: DespachoRouteStartFeedback | null;
}

export interface EntregadorActionFeedback {
  tone: Exclude<DisplayTone, "muted">;
  title: string;
  detail: string;
  payload: ExecucaoEntregaResultado | null;
}

export interface EntregadorState {
  entregadorId: number;
  roteiro: RoteiroEntregadorResponse | null;
  fetchedAt: string | null;
  sync: SyncState;
  action: SyncState;
  lastAction: EntregadorActionFeedback | null;
}

export interface AppState {
  activeModule: AppModuleId;
  connection: ConnectionState;
  sync: SyncState;
  snapshot: OperationalSnapshot | null;
  atendimento: AtendimentoModuleState;
  despacho: DespachoState;
  entregador: EntregadorState;
}
