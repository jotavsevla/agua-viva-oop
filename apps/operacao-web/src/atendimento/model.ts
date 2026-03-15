import type {
  AtendimentoCaseRecord,
  AtendimentoFinanceStatus,
  AtendimentoJanelaTipo,
  AtendimentoMetodoPagamento,
  AtendimentoModuleState,
  AtendimentoOrigemCanal,
  AtendimentoPersistedState,
  AtendimentoRequestDraft,
  AtendimentoRequestPayload,
  AtendimentoResponse,
  ExtratoResponse,
  PedidoExecucaoResponse,
  SaldoResponse,
  TimelineResponse
} from "../types";

export const ORIGENS_CANAL: AtendimentoOrigemCanal[] = ["MANUAL", "WHATSAPP", "BINA_FIXO", "TELEFONIA_FIXO"];
export const METODOS_PAGAMENTO: AtendimentoMetodoPagamento[] = [
  "NAO_INFORMADO",
  "DINHEIRO",
  "PIX",
  "CARTAO",
  "VALE"
];
export const JANELAS: AtendimentoJanelaTipo[] = ["ASAP", "HARD", "FLEXIVEL"];

const TERMINAL_STATUSES = new Set(["ENTREGUE", "CANCELADO"]);
const AUTO_CHANNELS = new Set<AtendimentoOrigemCanal>(["WHATSAPP", "BINA_FIXO", "TELEFONIA_FIXO"]);

function normalizeOptionalText(value: FormDataEntryValue | string | null | undefined): string {
  return String(value ?? "").trim();
}

function normalizeUpperOptionalText(value: FormDataEntryValue | string | null | undefined): string {
  return normalizeOptionalText(value).toUpperCase();
}

function parsePositiveInteger(value: string, fieldName: string): number {
  const numeric = Number(value);
  if (!Number.isInteger(numeric) || numeric <= 0) {
    throw new Error(`${fieldName} invalido`);
  }
  return numeric;
}

function parseCoordinate(value: string, fieldName: string): number {
  const normalized = normalizeOptionalText(value).replace(",", ".");
  if (!normalized) {
    throw new Error(`${fieldName} invalida`);
  }

  const numeric = Number(normalized);
  if (!Number.isFinite(numeric)) {
    throw new Error(`${fieldName} invalida`);
  }

  return numeric;
}

function normalizeOrigemCanal(value: string): AtendimentoOrigemCanal | "" {
  const normalized = normalizeUpperOptionalText(value);
  if (!normalized) {
    return "";
  }
  if (!ORIGENS_CANAL.includes(normalized as AtendimentoOrigemCanal)) {
    throw new Error("origemCanal invalido");
  }
  return normalized as AtendimentoOrigemCanal;
}

function normalizeMetodoPagamento(value: string): AtendimentoMetodoPagamento | "" {
  const normalized = normalizeUpperOptionalText(value);
  if (!normalized) {
    return "";
  }
  if (!METODOS_PAGAMENTO.includes(normalized as AtendimentoMetodoPagamento)) {
    throw new Error("metodoPagamento invalido");
  }
  return normalized as AtendimentoMetodoPagamento;
}

function normalizeJanelaTipo(value: string): AtendimentoJanelaTipo | "" {
  const normalized = normalizeUpperOptionalText(value);
  if (!normalized) {
    return "";
  }
  if (normalized === "FLEX") {
    return "FLEXIVEL";
  }
  if (!JANELAS.includes(normalized as AtendimentoJanelaTipo)) {
    throw new Error("janelaTipo invalido");
  }
  return normalized as AtendimentoJanelaTipo;
}

function buildDefaultDraft(): AtendimentoRequestDraft {
  return {
    telefone: "",
    quantidadeGaloes: "1",
    atendenteId: "1",
    origemCanal: "MANUAL",
    sourceEventId: "",
    manualRequestId: buildManualRequestId(),
    externalCallId: "",
    metodoPagamento: "NAO_INFORMADO",
    janelaTipo: "ASAP",
    janelaInicio: "",
    janelaFim: "",
    nomeCliente: "",
    endereco: "",
    latitude: "",
    longitude: ""
  };
}

export function createAtendimentoPersistedState(
  persisted?: Partial<AtendimentoPersistedState> | null
): AtendimentoPersistedState {
  const draft = sanitizeDraft(persisted?.draft);
  const sessionCases = sanitizeSessionCases(persisted?.sessionCases);
  const activeCaseId = persisted?.activeCaseId ?? null;

  return {
    draft,
    lookupPhone: normalizeOptionalText(persisted?.lookupPhone),
    lookupPedidoId: normalizeOptionalText(persisted?.lookupPedidoId),
    sessionCases,
    activeCaseId: sessionCases.some((item) => item.pedidoId === activeCaseId) ? activeCaseId : null
  };
}

export function createAtendimentoModuleState(
  persisted?: Partial<AtendimentoPersistedState> | null
): AtendimentoModuleState {
  const base = createAtendimentoPersistedState(persisted);
  return {
    ...base,
    submitting: false,
    syncingCaseId: null,
    lastError: null,
    lastSuccess: null
  };
}

function sanitizeDraft(draft?: Partial<AtendimentoRequestDraft> | null): AtendimentoRequestDraft {
  const base = buildDefaultDraft();
  const nextDraft: AtendimentoRequestDraft = {
    ...base,
    ...draft,
    telefone: normalizeOptionalText(draft?.telefone),
    quantidadeGaloes: normalizeOptionalText(draft?.quantidadeGaloes) || base.quantidadeGaloes,
    atendenteId: normalizeOptionalText(draft?.atendenteId) || base.atendenteId,
    origemCanal: normalizeOrigemCanal(normalizeOptionalText(draft?.origemCanal)) || base.origemCanal,
    sourceEventId: normalizeOptionalText(draft?.sourceEventId),
    manualRequestId: normalizeOptionalText(draft?.manualRequestId),
    externalCallId: normalizeOptionalText(draft?.externalCallId),
    metodoPagamento: normalizeMetodoPagamento(normalizeOptionalText(draft?.metodoPagamento)) || base.metodoPagamento,
    janelaTipo: normalizeJanelaTipo(normalizeOptionalText(draft?.janelaTipo)) || base.janelaTipo,
    janelaInicio: normalizeOptionalText(draft?.janelaInicio),
    janelaFim: normalizeOptionalText(draft?.janelaFim),
    nomeCliente: normalizeOptionalText(draft?.nomeCliente),
    endereco: normalizeOptionalText(draft?.endereco),
    latitude: normalizeOptionalText(draft?.latitude),
    longitude: normalizeOptionalText(draft?.longitude)
  };

  if (nextDraft.origemCanal === "MANUAL" && !nextDraft.manualRequestId) {
    nextDraft.manualRequestId = buildManualRequestId();
  }

  return nextDraft;
}

function sanitizeSessionCases(sessionCases?: AtendimentoCaseRecord[] | null): AtendimentoCaseRecord[] {
  if (!Array.isArray(sessionCases)) {
    return [];
  }

  return sessionCases
    .filter((item): item is AtendimentoCaseRecord => Boolean(item && Number.isInteger(item.pedidoId)))
    .map((item) => ({
      pedidoId: item.pedidoId,
      clienteId: Number.isInteger(item.clienteId) ? item.clienteId : null,
      telefone: normalizeOptionalText(item.telefone),
      telefoneNormalizado: normalizePhoneDigits(item.telefoneNormalizado || item.telefone),
      quantidadeGaloes: Number.isInteger(item.quantidadeGaloes) ? item.quantidadeGaloes : 0,
      atendenteId: Number.isInteger(item.atendenteId) ? item.atendenteId : 0,
      origemCanal: normalizeOrigemCanal(item.origemCanal) || "MANUAL",
      metodoPagamento: normalizeMetodoPagamento(item.metodoPagamento) || "NAO_INFORMADO",
      janelaTipo: normalizeJanelaTipo(item.janelaTipo) || "ASAP",
      janelaInicio: normalizeOptionalText(item.janelaInicio),
      janelaFim: normalizeOptionalText(item.janelaFim),
      nomeCliente: normalizeOptionalText(item.nomeCliente),
      endereco: normalizeOptionalText(item.endereco),
      requestKey: normalizeOptionalText(item.requestKey) || null,
      clienteCriado: Boolean(item.clienteCriado),
      idempotente: Boolean(item.idempotente),
      statusAtual: normalizeOptionalText(item.statusAtual) || null,
      timeline: item.timeline || null,
      execucao: item.execucao || null,
      saldo: item.saldo || null,
      extrato: item.extrato || null,
      financeStatus: sanitizeFinanceStatus(item.financeStatus),
      notes: Array.isArray(item.notes) ? item.notes.map((note) => normalizeOptionalText(note)).filter(Boolean) : [],
      error: normalizeOptionalText(item.error) || null,
      createdAt: normalizeOptionalText(item.createdAt) || new Date().toISOString(),
      updatedAt: normalizeOptionalText(item.updatedAt) || normalizeOptionalText(item.createdAt) || new Date().toISOString(),
      lastSyncAt: normalizeOptionalText(item.lastSyncAt) || null
    }))
    .sort(compareCasesByRecency);
}

function sanitizeFinanceStatus(value: AtendimentoFinanceStatus | string | null | undefined): AtendimentoFinanceStatus {
  return value === "ok" || value === "unavailable" || value === "error" ? value : "idle";
}

export function buildManualRequestId(): string {
  const datePart = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
  const randomPart = Math.random().toString(36).slice(2, 8);
  return `manual-ui-${datePart}-${randomPart}`;
}

export function readDraftFromFormData(formData: FormData): AtendimentoRequestDraft {
  const draft = sanitizeDraft({
    telefone: normalizeOptionalText(formData.get("telefone")),
    quantidadeGaloes: normalizeOptionalText(formData.get("quantidadeGaloes")),
    atendenteId: normalizeOptionalText(formData.get("atendenteId")),
    origemCanal: normalizeOrigemCanal(normalizeOptionalText(formData.get("origemCanal"))),
    sourceEventId: normalizeOptionalText(formData.get("sourceEventId")),
    manualRequestId: normalizeOptionalText(formData.get("manualRequestId")),
    externalCallId: normalizeOptionalText(formData.get("externalCallId")),
    metodoPagamento: normalizeMetodoPagamento(normalizeOptionalText(formData.get("metodoPagamento"))),
    janelaTipo: normalizeJanelaTipo(normalizeOptionalText(formData.get("janelaTipo"))),
    janelaInicio: normalizeOptionalText(formData.get("janelaInicio")),
    janelaFim: normalizeOptionalText(formData.get("janelaFim")),
    nomeCliente: normalizeOptionalText(formData.get("nomeCliente")),
    endereco: normalizeOptionalText(formData.get("endereco")),
    latitude: normalizeOptionalText(formData.get("latitude")),
    longitude: normalizeOptionalText(formData.get("longitude"))
  });

  if (draft.origemCanal === "MANUAL" && !draft.manualRequestId) {
    draft.manualRequestId = buildManualRequestId();
  }

  return draft;
}

export function buildAtendimentoPayload(draft: AtendimentoRequestDraft): {
  payload: AtendimentoRequestPayload;
  idempotencyKey: string | null;
} {
  const nextDraft = sanitizeDraft(draft);
  const payload: AtendimentoRequestPayload = {
    telefone: normalizeOptionalText(nextDraft.telefone),
    quantidadeGaloes: parsePositiveInteger(nextDraft.quantidadeGaloes, "quantidadeGaloes"),
    atendenteId: parsePositiveInteger(nextDraft.atendenteId, "atendenteId")
  };

  if (!payload.telefone) {
    throw new Error("telefone obrigatorio");
  }

  if (nextDraft.origemCanal) {
    payload.origemCanal = nextDraft.origemCanal;
  }
  if (nextDraft.metodoPagamento) {
    payload.metodoPagamento = nextDraft.metodoPagamento;
  }
  if (nextDraft.janelaTipo) {
    payload.janelaTipo = nextDraft.janelaTipo;
  }

  if (nextDraft.sourceEventId) {
    payload.sourceEventId = nextDraft.sourceEventId;
  }
  if (nextDraft.manualRequestId) {
    payload.manualRequestId = nextDraft.manualRequestId;
  }
  if (nextDraft.externalCallId) {
    payload.externalCallId = nextDraft.externalCallId;
  }
  if (nextDraft.janelaInicio) {
    payload.janelaInicio = nextDraft.janelaInicio;
  }
  if (nextDraft.janelaFim) {
    payload.janelaFim = nextDraft.janelaFim;
  }
  if (nextDraft.nomeCliente) {
    payload.nomeCliente = nextDraft.nomeCliente;
  }
  if (nextDraft.endereco) {
    payload.endereco = nextDraft.endereco;
  }

  const hasLatitude = Boolean(nextDraft.latitude);
  const hasLongitude = Boolean(nextDraft.longitude);
  if (hasLatitude !== hasLongitude) {
    throw new Error("latitude e longitude devem ser informadas juntas");
  }
  if (hasLatitude && hasLongitude) {
    payload.latitude = parseCoordinate(nextDraft.latitude, "latitude");
    payload.longitude = parseCoordinate(nextDraft.longitude, "longitude");
  }

  validateChannelConsistency(payload);
  validateJanela(payload);

  return {
    payload,
    idempotencyKey: resolveIdempotencyKey(payload)
  };
}

function validateChannelConsistency(payload: AtendimentoRequestPayload): void {
  const origemCanal = payload.origemCanal || "";

  if (origemCanal === "MANUAL" && payload.sourceEventId) {
    throw new Error("sourceEventId nao pode ser usado com origemCanal=MANUAL");
  }

  if (AUTO_CHANNELS.has(origemCanal as AtendimentoOrigemCanal) && !payload.sourceEventId) {
    throw new Error(`sourceEventId obrigatorio para origemCanal=${origemCanal}`);
  }

  if (AUTO_CHANNELS.has(origemCanal as AtendimentoOrigemCanal) && payload.manualRequestId) {
    throw new Error(`manualRequestId so pode ser usado com origemCanal=MANUAL (recebido: ${origemCanal})`);
  }

  if (!origemCanal && payload.sourceEventId && payload.manualRequestId) {
    throw new Error("manualRequestId nao pode ser combinado com sourceEventId quando origemCanal estiver vazio");
  }

  if (payload.sourceEventId && payload.externalCallId && payload.sourceEventId !== payload.externalCallId) {
    throw new Error("sourceEventId diverge de externalCallId");
  }

  if (payload.manualRequestId && payload.externalCallId && payload.manualRequestId !== payload.externalCallId) {
    throw new Error("manualRequestId diverge de externalCallId");
  }
}

function validateJanela(payload: AtendimentoRequestPayload): void {
  if (payload.janelaTipo === "HARD") {
    if (!payload.janelaInicio || !payload.janelaFim) {
      throw new Error("janelaTipo=HARD exige janelaInicio e janelaFim");
    }
    return;
  }

  if (payload.janelaInicio || payload.janelaFim) {
    throw new Error("janelaInicio/janelaFim so podem ser enviados com janelaTipo=HARD");
  }
}

function resolveIdempotencyKey(payload: AtendimentoRequestPayload): string | null {
  if (payload.origemCanal === "MANUAL") {
    return payload.manualRequestId || payload.externalCallId || null;
  }

  if (AUTO_CHANNELS.has((payload.origemCanal || "") as AtendimentoOrigemCanal)) {
    return payload.sourceEventId || payload.externalCallId || null;
  }

  return payload.sourceEventId || payload.manualRequestId || payload.externalCallId || null;
}

export function buildDraftBlockers(
  draft: AtendimentoRequestDraft,
  sessionCases: AtendimentoCaseRecord[]
): string[] {
  const blockers: string[] = [];

  try {
    buildAtendimentoPayload(draft);
  } catch (error) {
    blockers.push(error instanceof Error ? error.message : "payload invalido");
  }

  const normalizedPhone = normalizePhoneDigits(draft.telefone);
  if (normalizedPhone) {
    const conflictingCase = sessionCases.find(
      (item) => item.telefoneNormalizado === normalizedPhone && !isCaseTerminal(item)
    );
    if (conflictingCase) {
      blockers.push(
        `Ja existe pedido em acompanhamento na sessao para este telefone (#${conflictingCase.pedidoId}, ${conflictingCase.statusAtual || "sem status"}).`
      );
    }
  }

  return dedupeMessages(blockers);
}

export function buildDraftWarnings(draft: AtendimentoRequestDraft): string[] {
  const warnings: string[] = [];

  if (draft.metodoPagamento === "VALE") {
    warnings.push("Checkout em vale depende de saldo suficiente no cliente e pode ser bloqueado pelo backend.");
  }
  if (draft.origemCanal === "MANUAL" && !draft.nomeCliente) {
    warnings.push("Sem nome do cliente, o pedido entra, mas o atendimento perde contexto para o despacho.");
  }
  if (draft.janelaTipo === "HARD") {
    warnings.push("Pedidos HARD devem entrar com horario fechado para evitar retrabalho no despacho.");
  }

  return dedupeMessages(warnings);
}

export function normalizePhoneDigits(value: string | null | undefined): string {
  return String(value ?? "").replace(/\D/g, "");
}

export function formatPhoneDisplay(value: string | null | undefined): string {
  const digits = normalizePhoneDigits(value);
  if (digits.length === 11) {
    return `(${digits.slice(0, 2)}) ${digits.slice(2, 7)}-${digits.slice(7)}`;
  }
  if (digits.length === 10) {
    return `(${digits.slice(0, 2)}) ${digits.slice(2, 6)}-${digits.slice(6)}`;
  }
  return normalizeOptionalText(value);
}

export function buildAtendimentoCaseRecord(
  draft: AtendimentoRequestDraft,
  response: AtendimentoResponse
): AtendimentoCaseRecord {
  const normalizedDraft = sanitizeDraft(draft);
  const now = new Date().toISOString();
  const { payload, idempotencyKey } = buildAtendimentoPayload(normalizedDraft);

  return {
    pedidoId: response.pedidoId,
    clienteId: response.clienteId,
    telefone: payload.telefone,
    telefoneNormalizado: response.telefoneNormalizado,
    quantidadeGaloes: payload.quantidadeGaloes,
    atendenteId: payload.atendenteId,
    origemCanal: payload.origemCanal || "MANUAL",
    metodoPagamento: payload.metodoPagamento || "NAO_INFORMADO",
    janelaTipo: payload.janelaTipo || "ASAP",
    janelaInicio: payload.janelaInicio || "",
    janelaFim: payload.janelaFim || "",
    nomeCliente: payload.nomeCliente || "",
    endereco: payload.endereco || "",
    requestKey: idempotencyKey,
    clienteCriado: response.clienteCriado,
    idempotente: response.idempotente,
    statusAtual: "PENDENTE",
    timeline: null,
    execucao: null,
    saldo: null,
    extrato: null,
    financeStatus: "idle",
    notes: [],
    error: null,
    createdAt: now,
    updatedAt: now,
    lastSyncAt: null
  };
}

export function updateCaseFromContext(
  currentCase: AtendimentoCaseRecord,
  context: {
    timeline?: TimelineResponse | null;
    execucao?: PedidoExecucaoResponse | null;
    saldo?: SaldoResponse | null;
    extrato?: ExtratoResponse | null;
    financeStatus?: AtendimentoFinanceStatus;
    error?: string | null;
    notes?: string[];
  }
): AtendimentoCaseRecord {
  const nextTimeline = context.timeline === undefined ? currentCase.timeline : context.timeline;
  const nextExecucao = context.execucao === undefined ? currentCase.execucao : context.execucao;

  return {
    ...currentCase,
    timeline: nextTimeline,
    execucao: nextExecucao,
    saldo: context.saldo === undefined ? currentCase.saldo : context.saldo,
    extrato: context.extrato === undefined ? currentCase.extrato : context.extrato,
    financeStatus: context.financeStatus || currentCase.financeStatus,
    statusAtual: nextTimeline?.statusAtual || nextExecucao?.statusPedido || currentCase.statusAtual,
    error: context.error === undefined ? currentCase.error : context.error,
    notes: context.notes ? dedupeMessages([...currentCase.notes, ...context.notes]) : currentCase.notes,
    updatedAt: new Date().toISOString(),
    lastSyncAt: new Date().toISOString()
  };
}

export function buildNextDraft(previousDraft: AtendimentoRequestDraft): AtendimentoRequestDraft {
  const previous = sanitizeDraft(previousDraft);
  return {
    ...buildDefaultDraft(),
    quantidadeGaloes: previous.quantidadeGaloes,
    atendenteId: previous.atendenteId,
    origemCanal: "MANUAL",
    metodoPagamento: previous.metodoPagamento,
    janelaTipo: previous.janelaTipo,
    janelaInicio: previous.janelaTipo === "HARD" ? previous.janelaInicio : "",
    janelaFim: previous.janelaTipo === "HARD" ? previous.janelaFim : "",
    manualRequestId: buildManualRequestId()
  };
}

export function upsertSessionCase(
  sessionCases: AtendimentoCaseRecord[],
  nextCase: AtendimentoCaseRecord
): AtendimentoCaseRecord[] {
  const existingIndex = sessionCases.findIndex((item) => item.pedidoId === nextCase.pedidoId);
  if (existingIndex === -1) {
    return [...sessionCases, nextCase].sort(compareCasesByRecency);
  }

  const merged = [...sessionCases];
  merged[existingIndex] = {
    ...merged[existingIndex],
    ...nextCase,
    notes: dedupeMessages([...(merged[existingIndex].notes || []), ...(nextCase.notes || [])])
  };
  return merged.sort(compareCasesByRecency);
}

export function findActiveCase(atendimento: AtendimentoModuleState): AtendimentoCaseRecord | null {
  return atendimento.sessionCases.find((item) => item.pedidoId === atendimento.activeCaseId) || null;
}

export function findCasesByPhone(
  atendimento: AtendimentoModuleState,
  phoneValue: string
): AtendimentoCaseRecord[] {
  const digits = normalizePhoneDigits(phoneValue);
  if (!digits) {
    return atendimento.sessionCases;
  }

  return atendimento.sessionCases.filter((item) => item.telefoneNormalizado.includes(digits));
}

export function isCaseTerminal(item: AtendimentoCaseRecord): boolean {
  return TERMINAL_STATUSES.has(String(item.statusAtual || "").toUpperCase());
}

export function buildWindowLabel(caseRecord: Pick<AtendimentoCaseRecord, "janelaTipo" | "janelaInicio" | "janelaFim">): string {
  if (caseRecord.janelaTipo === "HARD") {
    if (caseRecord.janelaInicio && caseRecord.janelaFim) {
      return `HARD ${caseRecord.janelaInicio}-${caseRecord.janelaFim}`;
    }
    return "HARD incompleta";
  }

  if (caseRecord.janelaTipo === "FLEXIVEL") {
    return "Flexivel";
  }

  return "ASAP";
}

export function buildHandoffNarrative(caseRecord: AtendimentoCaseRecord | null): {
  tone: "ok" | "warn" | "danger" | "info" | "muted";
  stage: string;
  detail: string;
  action: string;
} {
  if (!caseRecord) {
    return {
      tone: "muted",
      stage: "Sem pedido em foco",
      detail: "Crie um novo pedido ou selecione um atendimento da sessao para montar o handoff.",
      action: "Nada para repassar ao despacho por enquanto."
    };
  }

  const status = String(caseRecord.statusAtual || "").toUpperCase();
  const camada = String(caseRecord.execucao?.camada || "").toUpperCase();

  if (status === "CANCELADO") {
    return {
      tone: "danger",
      stage: "Atendimento interrompido",
      detail: "O pedido foi cancelado e nao deve seguir para a operacao sem nova triagem.",
      action: "Registrar motivo com clareza e orientar novo contato se necessario."
    };
  }

  if (status === "ENTREGUE") {
    return {
      tone: "ok",
      stage: "Ciclo encerrado",
      detail: "O pedido ja foi concluido em campo.",
      action: "Encerrar o caso no atendimento e manter apenas rastreabilidade."
    };
  }

  if (camada === "PRIMARIA_EM_EXECUCAO") {
    return {
      tone: "info",
      stage: "Em execucao",
      detail: "O pedido ja esta com a operacao de entrega, com rota primaria ativa.",
      action: "Atendimento acompanha apenas excecoes; despacho ja recebeu o handoff."
    };
  }

  if (camada === "SECUNDARIA_CONFIRMADA") {
    return {
      tone: "warn",
      stage: "Aguardando inicio de rota",
      detail: "O pedido foi encaixado na secundaria e depende de acao do despacho para avancar.",
      action: "Avisar o despacho sobre rota pronta e janela do cliente."
    };
  }

  if (status === "CONFIRMADO") {
    return {
      tone: "warn",
      stage: "Confirmado para despacho",
      detail: "O pedido ja saiu do atendimento, mas ainda precisa ser roteirizado.",
      action: "Repassar prioridade, forma de pagamento e observacoes do cadastro."
    };
  }

  return {
    tone: "info",
    stage: "Entrou na fila",
    detail: "Pedido registrado e aguardando a cadencia normal do despacho.",
    action: "Checar o cockpit operacional para acompanhar a entrada nas filas."
  };
}

export function createLookupPlaceholderCase(pedidoId: number): AtendimentoCaseRecord {
  const now = new Date().toISOString();
  return {
    pedidoId,
    clienteId: null,
    telefone: "",
    telefoneNormalizado: "",
    quantidadeGaloes: 0,
    atendenteId: 0,
    origemCanal: "MANUAL",
    metodoPagamento: "NAO_INFORMADO",
    janelaTipo: "ASAP",
    janelaInicio: "",
    janelaFim: "",
    nomeCliente: "",
    endereco: "",
    requestKey: null,
    clienteCriado: false,
    idempotente: false,
    statusAtual: null,
    timeline: null,
    execucao: null,
    saldo: null,
    extrato: null,
    financeStatus: "idle",
    notes: ["Pedido importado por consulta manual."],
    error: null,
    createdAt: now,
    updatedAt: now,
    lastSyncAt: null
  };
}

function compareCasesByRecency(left: AtendimentoCaseRecord, right: AtendimentoCaseRecord): number {
  return right.updatedAt.localeCompare(left.updatedAt);
}

function dedupeMessages(values: string[]): string[] {
  return [...new Set(values.map((value) => normalizeOptionalText(value)).filter(Boolean))];
}
