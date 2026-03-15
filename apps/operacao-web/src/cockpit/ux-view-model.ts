import { buildOperationalInsights, type InsightTone } from "../insights";
import { formatDateTime, formatPercent } from "../shared/formatters";
import type {
  AppState,
  DisplayTone,
  OperacaoEventoItem,
  OperacaoMapaRota,
  OperacaoPainelConfirmadoSecundaria,
  OperacaoPainelEmRotaPrimaria,
  OperacaoPainelPendenteElegivel,
  ReadinessKey,
  ReadinessStatus
} from "../types";

export interface CockpitMetricViewModel {
  label: string;
  value: string;
  detail: string;
  tone: DisplayTone;
}

export interface CockpitSignalViewModel {
  label: string;
  value: string;
  detail: string;
  tone: DisplayTone;
}

export interface CockpitAlertViewModel {
  badge: string;
  tone: Exclude<DisplayTone, "muted">;
  title: string;
  detail: string;
  action: string;
}

export interface CockpitPulseViewModel {
  label: string;
  value: string;
}

export interface CockpitReadinessItemViewModel {
  label: string;
  title: string;
  detail: string;
  tone: DisplayTone;
}

export interface CockpitNoticeViewModel {
  label: string;
  body: string;
  tone: Exclude<DisplayTone, "muted">;
}

export interface CockpitRouteCardViewModel {
  title: string;
  detail: string;
  meta: string[];
  badgeLabel: string;
  badgeTone: DisplayTone;
  tone: DisplayTone;
}

export interface CockpitQueueCardViewModel {
  title: string;
  summary: string;
  badgeLabel: string;
  badgeTone: DisplayTone;
  tone: DisplayTone;
  lines: string[];
}

export interface CockpitQueueLaneViewModel {
  step: string;
  title: string;
  summary: string;
  tone: DisplayTone;
  count: number;
  cards: CockpitQueueCardViewModel[];
  emptyMessage: string;
}

export interface CockpitEventViewModel {
  title: string;
  badgeLabel: string;
  badgeTone: DisplayTone;
  subject: string;
  detail: string;
  meta: string;
  tone: DisplayTone;
}

export interface CockpitMapRouteViewModel {
  title: string;
  badgeLabel: string;
  badgeTone: DisplayTone;
  summary: string;
  detail: string;
  tags: string[];
  tone: DisplayTone;
}

export interface CockpitMapSummaryCardViewModel {
  label: string;
  value: string;
  detail: string;
  tone: DisplayTone;
}

export interface CockpitViewModel {
  headline: string;
  executiveSummary: string;
  modeLabel: string;
  modeTone: DisplayTone;
  nextAction: string;
  nextActionDetail: string;
  nextActionTone: DisplayTone;
  confidenceLabel: string;
  confidenceDetail: string;
  confidenceTone: DisplayTone;
  signals: CockpitSignalViewModel[];
  metrics: CockpitMetricViewModel[];
  leadAlert: CockpitAlertViewModel;
  supportingAlerts: CockpitAlertViewModel[];
  pulses: CockpitPulseViewModel[];
  readinessStatus: CockpitReadinessItemViewModel;
  readinessItems: CockpitReadinessItemViewModel[];
  notices: CockpitNoticeViewModel[];
  panelUpdatedAt: string;
  activeRoutes: CockpitRouteCardViewModel[];
  plannedRoutes: CockpitRouteCardViewModel[];
  queueLanes: CockpitQueueLaneViewModel[];
  eventBadgeLabel: string;
  eventBadgeTone: DisplayTone;
  events: CockpitEventViewModel[];
  mapDeposit: string | null;
  mapSummaryCards: CockpitMapSummaryCardViewModel[];
  mapRoutes: CockpitMapRouteViewModel[];
}

function formatRelativeTime(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  const diffMs = Date.now() - parsed.getTime();
  const absMinutes = Math.round(Math.abs(diffMs) / 60000);

  if (absMinutes < 1) {
    return "agora";
  }

  if (absMinutes < 60) {
    return diffMs >= 0 ? `${absMinutes} min atras` : `em ${absMinutes} min`;
  }

  const absHours = Math.round(absMinutes / 60);
  if (absHours < 24) {
    return diffMs >= 0 ? `${absHours} h atras` : `em ${absHours} h`;
  }

  const absDays = Math.round(absHours / 24);
  return diffMs >= 0 ? `${absDays} dia(s) atras` : `em ${absDays} dia(s)`;
}

function humanizeToken(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }

  return value
    .toLowerCase()
    .split(/[_-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function readinessLabel(key: ReadinessKey): string {
  if (key === "health") {
    return "Health";
  }
  if (key === "painel") {
    return "Painel";
  }
  if (key === "eventos") {
    return "Eventos";
  }
  return "Mapa";
}

function toneForReadiness(status: ReadinessStatus): DisplayTone {
  if (status === "ok") {
    return "ok";
  }

  if (status === "error") {
    return "danger";
  }

  return "muted";
}

function toneForAlert(tone: InsightTone): Exclude<DisplayTone, "muted"> {
  return tone;
}

function badgeForAlert(tone: InsightTone): string {
  if (tone === "danger") {
    return "agir agora";
  }

  if (tone === "warn") {
    return "atencao";
  }

  if (tone === "info") {
    return "acompanhar";
  }

  return "estavel";
}

function toneForEvent(event: OperacaoEventoItem): DisplayTone {
  const type = String(event.eventType || "").toUpperCase();
  const status = String(event.status || "").toUpperCase();

  if (type.includes("FALHOU") || type.includes("CANCELADO") || status.includes("ERRO") || status.includes("FALHA")) {
    return "danger";
  }

  if (type.includes("ENTREGUE")) {
    return "ok";
  }

  if (type.includes("ROTA")) {
    return "info";
  }

  return "warn";
}

function toneForRouteLayer(layer: string): DisplayTone {
  return layer === "PRIMARIA" ? "info" : "warn";
}

function exceptionalEventCount(state: AppState): number {
  const events = state.snapshot?.eventos?.eventos || [];
  return events.filter((event) => toneForEvent(event) === "danger").length;
}

function buildMetrics(state: AppState): CockpitMetricViewModel[] {
  const painel = state.snapshot?.painel;
  const hardPendings = painel?.filas.pendentesElegiveis.filter((item) => item.janelaTipo === "HARD").length ?? 0;
  const activeRoutes = painel?.rotas.emAndamento.length ?? 0;
  const plannedRoutes = painel?.rotas.planejadas.length ?? 0;
  const issueEvents = exceptionalEventCount(state);
  const successRate = painel?.indicadoresEntrega.taxaSucessoPercentual;
  const totalFinalizadas = painel?.indicadoresEntrega.totalFinalizadas;
  const entregasConcluidas = painel?.indicadoresEntrega.entregasConcluidas;

  return [
    {
      label: "Fila para triar",
      value: painel ? String(painel.filas.pendentesElegiveis.length) : "-",
      detail: painel ? `${painel.pedidosPorStatus.pendente} pedido(s) pendente(s) na visao geral` : "aguardando leitura do painel",
      tone: !painel ? "muted" : hardPendings > 0 ? "danger" : painel.filas.pendentesElegiveis.length > 0 ? "warn" : "ok"
    },
    {
      label: "Pedidos HARD",
      value: painel ? String(hardPendings) : "-",
      detail: hardPendings > 0 ? "janela critica exigindo prioridade maxima" : "sem pedido critico escondido na fila",
      tone: !painel ? "muted" : hardPendings > 0 ? "danger" : "ok"
    },
    {
      label: "Rotas prontas",
      value: painel ? String(plannedRoutes) : "-",
      detail: plannedRoutes > 0 ? "aguardando decisao de liberar saida" : "sem rota pronta esperando liberacao",
      tone: !painel ? "muted" : plannedRoutes > 0 ? "warn" : "ok"
    },
    {
      label: "Rotas em campo",
      value: painel ? String(activeRoutes) : "-",
      detail: activeRoutes > 0 ? "execucao ativa pedindo acompanhamento" : "sem entrega em curso nesta rodada",
      tone: !painel ? "muted" : activeRoutes > 0 ? "info" : "ok"
    },
    {
      label: "Excecoes recentes",
      value: state.snapshot ? String(issueEvents) : "-",
      detail: issueEvents > 0 ? "falhas ou cancelamentos visiveis no feed" : "feed recente sem falha aparente",
      tone: !state.snapshot ? "muted" : issueEvents >= 3 ? "danger" : issueEvents > 0 ? "warn" : "ok"
    },
    {
      label: "Taxa de sucesso",
      value: painel ? formatPercent(successRate) : "-",
      detail:
        painel && typeof totalFinalizadas === "number" && typeof entregasConcluidas === "number"
          ? `${entregasConcluidas} concluida(s) em ${totalFinalizadas} finalizada(s)`
          : "indicador ainda indisponivel",
      tone:
        !painel || typeof successRate !== "number"
          ? "muted"
          : successRate >= 95
            ? "ok"
            : successRate >= 85
              ? "warn"
              : "danger"
    }
  ];
}

function buildReadinessItems(state: AppState): CockpitReadinessItemViewModel[] {
  const readiness = state.snapshot?.readiness;
  const keys: ReadinessKey[] = ["health", "painel", "eventos", "mapa"];

  return keys.map((key) => {
    const status = readiness?.[key] || "unknown";

    return {
      label: readinessLabel(key),
      title: status === "ok" ? "Leitura pronta" : status === "error" ? "Falhou nesta rodada" : "Aguardando resposta",
      detail:
        status === "ok"
          ? "Fonte pronta para sustentar o cockpit."
          : status === "error"
            ? "Precisa de nova tentativa ou validacao manual."
            : "Ainda sem retorno desta fonte.",
      tone: toneForReadiness(status)
    };
  });
}

function buildNotices(state: AppState): CockpitNoticeViewModel[] {
  const notices: CockpitNoticeViewModel[] = [];

  if (state.snapshot?.partialErrors.length) {
    notices.push({
      label: "Leitura parcial",
      body: state.snapshot.partialErrors.join(" | "),
      tone: "warn"
    });
  }

  if (state.sync.lastError) {
    notices.push({
      label: "Ultimo erro",
      body: state.sync.lastError,
      tone: "danger"
    });
  }

  return notices;
}

function buildActiveRoutes(state: AppState): CockpitRouteCardViewModel[] {
  const activeRoutes = state.snapshot?.painel?.rotas.emAndamento || [];

  return activeRoutes.map((route) => ({
    title: `R${route.rotaId}`,
    detail: `Entregador ${route.entregadorId}`,
    meta: [`${route.pendentes} pendente(s)`, `${route.emExecucao} em execucao`],
    badgeLabel: "em campo",
    badgeTone: "info",
    tone: "info"
  }));
}

function buildPlannedRoutes(state: AppState): CockpitRouteCardViewModel[] {
  const plannedRoutes = state.snapshot?.painel?.rotas.planejadas || [];

  return plannedRoutes.map((route) => ({
    title: `R${route.rotaId}`,
    detail: `Entregador ${route.entregadorId}`,
    meta: [`${route.pendentes} parada(s) pronta(s)`],
    badgeLabel: "aguardando saida",
    badgeTone: "warn",
    tone: "warn"
  }));
}

function buildPendenteCard(item: OperacaoPainelPendenteElegivel): CockpitQueueCardViewModel {
  const critical = item.janelaTipo === "HARD";

  return {
    title: `Pedido #${item.pedidoId}`,
    summary: critical ? "Janela critica para despacho." : "Janela flexivel em triagem.",
    badgeLabel: critical ? "prioridade maxima" : "fila ativa",
    badgeTone: critical ? "danger" : "warn",
    tone: critical ? "danger" : "warn",
    lines: [`${item.quantidadeGaloes} galao(oes)`, `Criado ${formatRelativeTime(item.criadoEm)}`, formatDateTime(item.criadoEm)]
  };
}

function buildConfirmadoCard(item: OperacaoPainelConfirmadoSecundaria): CockpitQueueCardViewModel {
  return {
    title: `Pedido #${item.pedidoId}`,
    summary: "Carga confirmada na secundaria aguardando liberacao de rota.",
    badgeLabel: `R${item.rotaId}`,
    badgeTone: "info",
    tone: "info",
    lines: [
      `Entregador ${item.entregadorId}`,
      `Ordem ${item.ordemNaRota}`,
      `${item.quantidadeGaloes} galao(oes)`
    ]
  };
}

function buildEmRotaCard(item: OperacaoPainelEmRotaPrimaria): CockpitQueueCardViewModel {
  return {
    title: `Pedido #${item.pedidoId}`,
    summary: `Entrega em andamento na rota ${item.rotaId}.`,
    badgeLabel: humanizeToken(item.statusEntrega),
    badgeTone: "ok",
    tone: "ok",
    lines: [`Entrega ${item.entregaId}`, `Entregador ${item.entregadorId}`, `${item.quantidadeGaloes} galao(oes)`]
  };
}

function buildQueueLanes(state: AppState): CockpitQueueLaneViewModel[] {
  const filas = state.snapshot?.painel?.filas;
  const hardPendings = filas?.pendentesElegiveis.filter((item) => item.janelaTipo === "HARD").length ?? 0;

  return [
    {
      step: "1",
      title: "Triar pedidos novos",
      summary:
        hardPendings > 0
          ? `${hardPendings} pedido(s) HARD precisam abrir a decisao desta fila.`
          : (filas?.pendentesElegiveis.length ?? 0) > 0
            ? "Fila ativa aguardando alocacao e priorizacao."
            : "Entrada limpa nesta rodada.",
      tone: hardPendings > 0 ? "danger" : (filas?.pendentesElegiveis.length ?? 0) > 0 ? "warn" : "ok",
      count: filas?.pendentesElegiveis.length ?? 0,
      cards: (filas?.pendentesElegiveis || []).map(buildPendenteCard),
      emptyMessage: "Nenhum pedido pendente elegivel."
    },
    {
      step: "2",
      title: "Liberar carga preparada",
      summary:
        (filas?.confirmadosSecundaria.length ?? 0) > 0
          ? "Pedidos ja encaixados em rota secundaria aguardando o aval operacional."
          : "Sem carga pronta aguardando liberacao.",
      tone: (filas?.confirmadosSecundaria.length ?? 0) > 0 ? "warn" : "ok",
      count: filas?.confirmadosSecundaria.length ?? 0,
      cards: (filas?.confirmadosSecundaria || []).map(buildConfirmadoCard),
      emptyMessage: "Nenhum pedido aguardando rota secundaria."
    },
    {
      step: "3",
      title: "Acompanhar entrega em curso",
      summary:
        (filas?.emRotaPrimaria.length ?? 0) > 0
          ? "Entregas em rua que pedem monitoramento continuo."
          : "Sem pedido em execucao agora.",
      tone: (filas?.emRotaPrimaria.length ?? 0) > 0 ? "info" : "ok",
      count: filas?.emRotaPrimaria.length ?? 0,
      cards: (filas?.emRotaPrimaria || []).map(buildEmRotaCard),
      emptyMessage: "Nenhum pedido em execucao agora."
    }
  ];
}

function buildEvents(state: AppState): CockpitEventViewModel[] {
  const events = state.snapshot?.eventos?.eventos || [];

  return events.slice(0, 8).map((event) => {
    const tone = toneForEvent(event);

    return {
      title: humanizeToken(event.eventType),
      badgeLabel: humanizeToken(event.status),
      badgeTone: tone,
      subject: `${humanizeToken(event.aggregateType)} ${event.aggregateId ?? "-"}`,
      detail: tone === "danger" ? "Excecao recente com potencial de alterar a prioridade operacional." : "Movimento recente da operacao registrado no feed.",
      meta: event.processedEm
        ? `${formatRelativeTime(event.createdEm)} · criado ${formatDateTime(event.createdEm)} · processado ${formatDateTime(event.processedEm)}`
        : `${formatRelativeTime(event.createdEm)} · criado ${formatDateTime(event.createdEm)}`,
      tone
    };
  });
}

function buildMapSummaryCards(state: AppState): CockpitMapSummaryCardViewModel[] {
  const routes = state.snapshot?.mapa?.rotas || [];
  const primarias = routes.filter((route) => route.camada === "PRIMARIA").length;
  const secundarias = routes.filter((route) => route.camada === "SECUNDARIA").length;
  const totalStops = routes.reduce((total, route) => total + route.paradas.length, 0);

  return [
    {
      label: "Primarias",
      value: String(primarias),
      detail: "Rotas que sustentam a execucao principal.",
      tone: "info"
    },
    {
      label: "Secundarias",
      value: String(secundarias),
      detail: "Rotas de apoio, preparacao ou contingencia.",
      tone: "warn"
    },
    {
      label: "Paradas mapeadas",
      value: String(totalStops),
      detail: "Total de entregas representadas nesta leitura.",
      tone: "ok"
    }
  ];
}

function buildMapRoutes(state: AppState): CockpitMapRouteViewModel[] {
  const routes = state.snapshot?.mapa?.rotas || [];

  return routes.map((route: OperacaoMapaRota) => {
    const completedStops = route.paradas.filter((stop) => String(stop.statusEntrega).toUpperCase().includes("ENTREGUE")).length;
    const pendingStops = Math.max(route.paradas.length - completedStops, 0);
    const tone = toneForRouteLayer(route.camada);

    return {
      title: `R${route.rotaId} · Entregador ${route.entregadorId}`,
      badgeLabel: route.camada === "PRIMARIA" ? "camada primaria" : "camada secundaria",
      badgeTone: tone,
      summary: humanizeToken(route.statusRota),
      detail: `${route.paradas.length} parada(s) · ${route.trajeto.length} ponto(s) no trajeto · ${completedStops} concluida(s) · ${pendingStops} aberta(s)`,
      tags: route.paradas.slice(0, 4).map((stop) => `P${stop.ordemNaRota} pedido ${stop.pedidoId}`),
      tone
    };
  });
}

export function buildCockpitViewModel(state: AppState): CockpitViewModel {
  const insights = buildOperationalInsights(state.snapshot);
  const alerts = insights.alerts.map((alert) => ({
    badge: badgeForAlert(alert.tone),
    tone: toneForAlert(alert.tone),
    title: alert.title,
    detail: alert.detail,
    action: alert.action
  }));
  const exceptionalEvents = exceptionalEventCount(state);

  return {
    headline: insights.headline,
    executiveSummary: insights.executiveSummary,
    modeLabel: insights.modeLabel,
    modeTone: insights.modeTone,
    nextAction: insights.nextAction,
    nextActionDetail: insights.nextActionDetail,
    nextActionTone: insights.nextActionTone,
    confidenceLabel: insights.confidenceLabel,
    confidenceDetail: insights.confidenceDetail,
    confidenceTone: insights.confidenceTone,
    signals: [
      {
        label: "Modo atual",
        value: insights.modeLabel,
        detail: insights.executiveSummary,
        tone: insights.modeTone
      },
      {
        label: "Proxima decisao",
        value: insights.nextAction,
        detail: insights.nextActionDetail,
        tone: insights.nextActionTone
      },
      {
        label: "Confianca",
        value: insights.confidenceLabel,
        detail: insights.confidenceDetail,
        tone: insights.confidenceTone
      }
    ],
    metrics: buildMetrics(state),
    leadAlert: alerts[0],
    supportingAlerts: alerts.slice(1),
    pulses: [
      { label: "Fila", value: insights.queueSummary },
      { label: "Rotas", value: insights.routeSummary },
      { label: "Ocorrencias", value: insights.eventSummary }
    ],
    readinessStatus: {
      label: insights.confidenceLabel,
      title: insights.confidenceLabel,
      detail: insights.confidenceDetail,
      tone: insights.confidenceTone
    },
    readinessItems: buildReadinessItems(state),
    notices: buildNotices(state),
    panelUpdatedAt: formatDateTime(state.snapshot?.painel?.atualizadoEm),
    activeRoutes: buildActiveRoutes(state),
    plannedRoutes: buildPlannedRoutes(state),
    queueLanes: buildQueueLanes(state),
    eventBadgeLabel: exceptionalEvents > 0 ? `${exceptionalEvents} excecao(oes)` : "sem excecao",
    eventBadgeTone: exceptionalEvents >= 3 ? "danger" : exceptionalEvents > 0 ? "warn" : "ok",
    events: buildEvents(state),
    mapDeposit: state.snapshot?.mapa
      ? `Deposito ${state.snapshot.mapa.deposito.lat.toFixed(4)}, ${state.snapshot.mapa.deposito.lon.toFixed(4)}`
      : null,
    mapSummaryCards: buildMapSummaryCards(state),
    mapRoutes: buildMapRoutes(state)
  };
}
