import { buildOperationalInsights, type OperationalAlert } from "../insights";
import { formatDateTime, formatPercent } from "../shared/formatters";
import type {
  AppState,
  DisplayTone,
  OperacaoEventoItem,
  OperacaoMapaRota,
  OperacaoPainelConfirmadoSecundaria,
  OperacaoPainelEmRotaPrimaria,
  OperacaoPainelPendenteElegivel,
  OperationalSnapshot
} from "../types";

interface RouteContext {
  routeId: number;
  entregadorId: number;
  camada: "PRIMARIA" | "SECUNDARIA" | "DESCONHECIDA";
  statusRota: string;
  paradas: number;
  pontosTrajeto: number;
  pendentes: number;
  emExecucao: number;
  concluidas: number;
  quantidadeGaloes: number;
  tags: string[];
}

export interface DespachoMetricViewModel {
  label: string;
  value: string;
  detail: string;
  tone: DisplayTone;
}

export interface DespachoPriorityCardViewModel {
  badge: string;
  tone: Exclude<DisplayTone, "muted">;
  title: string;
  detail: string;
  action: string;
}

export interface DespachoPulseViewModel {
  label: string;
  value: string;
}

export interface DespachoQueueCardViewModel {
  title: string;
  badgeLabel: string;
  badgeTone: DisplayTone;
  lines: string[];
  action: string;
}

export interface DespachoQueueLaneViewModel {
  title: string;
  tone: DisplayTone;
  summary: string;
  cards: DespachoQueueCardViewModel[];
  emptyMessage: string;
}

export interface DespachoLayerCardViewModel {
  title: string;
  tone: DisplayTone;
  summary: string;
  routes: {
    title: string;
    meta: string;
  }[];
  emptyMessage: string;
}

export interface DespachoEventCardViewModel {
  title: string;
  badgeLabel: string;
  badgeTone: DisplayTone;
  subject: string;
  meta: string;
  detail: string;
}

export interface DespachoEventBucketViewModel {
  title: string;
  tone: DisplayTone;
  summary: string;
  cards: DespachoEventCardViewModel[];
  emptyMessage: string;
}

export interface DespachoRouteCardViewModel {
  title: string;
  badgeLabel: string;
  badgeTone: DisplayTone;
  summary: string;
  tags: string[];
}

export interface DespachoActionViewModel {
  title: string;
  tone: DisplayTone;
  badgeLabel: string;
  detail: string;
  supportingText: string;
  buttonLabel: string;
  enabled: boolean;
  entregadorId: number | null;
  blocker: string | null;
}

export interface DespachoViewModel {
  headline: string;
  summary: string;
  metrics: DespachoMetricViewModel[];
  priorities: DespachoPriorityCardViewModel[];
  pulses: DespachoPulseViewModel[];
  queueLanes: DespachoQueueLaneViewModel[];
  layerCards: DespachoLayerCardViewModel[];
  layerWarnings: string[];
  eventBuckets: DespachoEventBucketViewModel[];
  routeCards: DespachoRouteCardViewModel[];
  routeSummary: string;
  mapDeposit: string | null;
  action: DespachoActionViewModel;
  notices: { label: string; body: string; tone: DisplayTone }[];
}

function toneForAlert(alert: OperationalAlert): Exclude<DisplayTone, "muted"> {
  return alert.tone;
}

function badgeForAlert(tone: Exclude<DisplayTone, "muted">): string {
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

  if (type.includes("FALHOU") || type.includes("CANCELADO")) {
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

function normalizeLayer(camada: string | null | undefined, statusRota: string | null | undefined): RouteContext["camada"] {
  const normalizedLayer = String(camada || "").toUpperCase();
  if (normalizedLayer.includes("PRIMARIA")) {
    return "PRIMARIA";
  }
  if (normalizedLayer.includes("SECUNDARIA")) {
    return "SECUNDARIA";
  }

  const normalizedStatus = String(statusRota || "").toUpperCase();
  if (normalizedStatus === "EM_ANDAMENTO") {
    return "PRIMARIA";
  }
  if (normalizedStatus === "PLANEJADA") {
    return "SECUNDARIA";
  }

  return "DESCONHECIDA";
}

function stopBucket(status: string): "pendentes" | "emExecucao" | "concluidas" {
  const normalized = String(status || "").toUpperCase();

  if (normalized.includes("EXECUCAO")) {
    return "emExecucao";
  }
  if (normalized.includes("ENTREGUE") || normalized.includes("CONCLUIDA") || normalized.includes("CANCELADA")) {
    return "concluidas";
  }
  return "pendentes";
}

function buildRouteContexts(snapshot: OperationalSnapshot | null): RouteContext[] {
  const contexts = new Map<number, RouteContext>();

  for (const route of snapshot?.mapa?.rotas ?? []) {
    const summary = summarizeMapRoute(route);
    contexts.set(route.rotaId, summary);
  }

  for (const route of snapshot?.painel?.rotas.emAndamento ?? []) {
    const current = contexts.get(route.rotaId);
    contexts.set(route.rotaId, {
      routeId: route.rotaId,
      entregadorId: route.entregadorId,
      camada: "PRIMARIA",
      statusRota: current?.statusRota ?? "EM_ANDAMENTO",
      paradas: current?.paradas ?? route.pendentes + route.emExecucao,
      pontosTrajeto: current?.pontosTrajeto ?? 0,
      pendentes: route.pendentes,
      emExecucao: route.emExecucao,
      concluidas: current?.concluidas ?? 0,
      quantidadeGaloes: current?.quantidadeGaloes ?? 0,
      tags: current?.tags ?? []
    });
  }

  for (const route of snapshot?.painel?.rotas.planejadas ?? []) {
    const current = contexts.get(route.rotaId);
    contexts.set(route.rotaId, {
      routeId: route.rotaId,
      entregadorId: route.entregadorId,
      camada: "SECUNDARIA",
      statusRota: current?.statusRota ?? "PLANEJADA",
      paradas: current?.paradas ?? route.pendentes,
      pontosTrajeto: current?.pontosTrajeto ?? 0,
      pendentes: route.pendentes,
      emExecucao: current?.emExecucao ?? 0,
      concluidas: current?.concluidas ?? 0,
      quantidadeGaloes: current?.quantidadeGaloes ?? 0,
      tags: current?.tags ?? []
    });
  }

  return [...contexts.values()].sort((left, right) => layerOrder(left.camada) - layerOrder(right.camada) || left.routeId - right.routeId);
}

function summarizeMapRoute(route: OperacaoMapaRota): RouteContext {
  const counters = route.paradas.reduce(
    (accumulator, parada) => {
      accumulator[stopBucket(parada.statusEntrega)] += 1;
      accumulator.quantidadeGaloes += parada.quantidadeGaloes;
      return accumulator;
    },
    { pendentes: 0, emExecucao: 0, concluidas: 0, quantidadeGaloes: 0 }
  );

  return {
    routeId: route.rotaId,
    entregadorId: route.entregadorId,
    camada: normalizeLayer(route.camada, route.statusRota),
    statusRota: route.statusRota,
    paradas: route.paradas.length,
    pontosTrajeto: route.trajeto.length,
    pendentes: counters.pendentes,
    emExecucao: counters.emExecucao,
    concluidas: counters.concluidas,
    quantidadeGaloes: counters.quantidadeGaloes,
    tags: route.paradas
      .slice()
      .sort((left, right) => left.ordemNaRota - right.ordemNaRota)
      .slice(0, 5)
      .map((parada) => `P${parada.ordemNaRota} · pedido ${parada.pedidoId}`)
  };
}

function layerOrder(layer: RouteContext["camada"]): number {
  if (layer === "PRIMARIA") {
    return 0;
  }
  if (layer === "SECUNDARIA") {
    return 1;
  }
  return 2;
}

function buildLayerWarnings(routes: RouteContext[]): string[] {
  const primarias = routes.filter((route) => route.camada === "PRIMARIA").length;
  const secundarias = routes.filter((route) => route.camada === "SECUNDARIA").length;
  const desconhecidas = routes.filter((route) => route.camada === "DESCONHECIDA").length;
  const warnings: string[] = [];

  if (primarias > 1) {
    warnings.push(`Foram detectadas ${primarias} rotas na camada PRIMARIA.`);
  }
  if (secundarias > 1) {
    warnings.push(`Foram detectadas ${secundarias} rotas na camada SECUNDARIA.`);
  }
  if (desconhecidas > 0) {
    warnings.push(`${desconhecidas} rota(s) vieram sem camada reconhecivel.`);
  }

  return warnings;
}

function buildPendingCard(item: OperacaoPainelPendenteElegivel): DespachoQueueCardViewModel {
  const windowType = String(item.janelaTipo || "ASAP").toUpperCase();
  return {
    title: `Pedido #${item.pedidoId}`,
    badgeLabel: windowType,
    badgeTone: windowType === "HARD" ? "danger" : windowType === "ASAP" ? "warn" : "info",
    lines: [
      `${item.quantidadeGaloes} galao(oes)`,
      `Criado em ${formatDateTime(item.criadoEm)}`
    ],
    action: windowType === "HARD" ? "Priorizar este encaixe antes da proxima rodada." : "Avaliar com a proxima disponibilidade da frota."
  };
}

function buildConfirmedCard(item: OperacaoPainelConfirmadoSecundaria): DespachoQueueCardViewModel {
  return {
    title: `Pedido #${item.pedidoId}`,
    badgeLabel: `R${item.rotaId}`,
    badgeTone: "info",
    lines: [
      `Entregador ${item.entregadorId} · ordem ${item.ordemNaRota}`,
      `${item.quantidadeGaloes} galao(oes) ja comprometidos`
    ],
    action: "Checar contexto da secundaria antes de girar a rota."
  };
}

function buildActiveCard(item: OperacaoPainelEmRotaPrimaria): DespachoQueueCardViewModel {
  return {
    title: `Pedido #${item.pedidoId}`,
    badgeLabel: item.statusEntrega,
    badgeTone: item.statusEntrega === "EM_EXECUCAO" ? "ok" : "info",
    lines: [
      `Entrega ${item.entregaId} · rota ${item.rotaId}`,
      `Entregador ${item.entregadorId} · ${item.quantidadeGaloes} galao(oes)`
    ],
    action: "Monitorar excecoes e liberar espaco para a proxima onda."
  };
}

function summarizePayload(event: OperacaoEventoItem): string {
  const parts = Object.entries(event.payload ?? {})
    .filter(([, value]) => {
      const valueType = typeof value;
      return valueType === "string" || valueType === "number" || valueType === "boolean";
    })
    .slice(0, 2)
    .map(([key, value]) => `${key}: ${String(value)}`);

  return parts.length > 0 ? parts.join(" · ") : "Sem detalhe resumivel.";
}

function buildEventCard(event: OperacaoEventoItem): DespachoEventCardViewModel {
  const tone = toneForEvent(event);
  return {
    title: event.eventType,
    badgeLabel: tone === "danger" ? "agir" : event.status,
    badgeTone: tone,
    subject: `${event.aggregateType} ${event.aggregateId ?? "-"}`,
    meta: event.processedEm
      ? `criado ${formatDateTime(event.createdEm)} · processado ${formatDateTime(event.processedEm)}`
      : `criado ${formatDateTime(event.createdEm)}`,
    detail: summarizePayload(event)
  };
}

export function buildDespachoViewModel(state: AppState): DespachoViewModel {
  const snapshot = state.snapshot;
  const painel = snapshot?.painel;
  const insights = buildOperationalInsights(snapshot);
  const routes = buildRouteContexts(snapshot);
  const layerWarnings = buildLayerWarnings(routes);
  const issueEvents = (snapshot?.eventos?.eventos ?? []).filter((event) => toneForEvent(event) === "danger").length;
  const hardPendings = painel?.filas.pendentesElegiveis.filter((item) => String(item.janelaTipo).toUpperCase() === "HARD").length ?? 0;
  const secondaryRoutes = routes.filter((route) => route.camada === "SECUNDARIA");
  const candidateRoute = secondaryRoutes[0] ?? null;
  const actionBlockers: string[] = [];

  if (!painel) {
    actionBlockers.push("Painel operacional indisponivel.");
  }
  if (!snapshot?.mapa) {
    actionBlockers.push("Mapa operacional indisponivel.");
  }
  if (layerWarnings.length > 0) {
    actionBlockers.push(layerWarnings[0]);
  }
  if (secondaryRoutes.length === 0) {
    actionBlockers.push("Nenhuma rota secundaria pronta para iniciar.");
  }
  if (secondaryRoutes.length > 1) {
    actionBlockers.push("Mais de uma secundaria apareceu na mesma leitura.");
  }

  const actionEnabled = actionBlockers.length === 0 && candidateRoute !== null;

  return {
    headline: !painel
      ? "Aguardando contexto seguro de despacho"
      : hardPendings > 0
        ? "Fila pressionando a triagem"
        : secondaryRoutes.length > 0
          ? "Saida pronta para decisao"
          : issueEvents > 0
            ? "Excecoes pedindo leitura cuidadosa"
            : "Despacho sob controle no recorte atual",
    summary: !painel
      ? "Sem painel principal nao ha contexto suficiente para girar a frota com seguranca."
      : `${painel.filas.pendentesElegiveis.length} entrada(s) na triagem · ${painel.rotas.planejadas.length} rota(s) planejada(s) · ${issueEvents} excecao(oes) recente(s).`,
    metrics: [
      {
        label: "Pendentes criticos",
        value: String(hardPendings),
        detail: "Pedidos HARD exigindo resposta curta.",
        tone: hardPendings > 0 ? "danger" : "ok"
      },
      {
        label: "Fila de triagem",
        value: String(painel?.filas.pendentesElegiveis.length ?? 0),
        detail: "Entradas novas aguardando encaixe.",
        tone: (painel?.filas.pendentesElegiveis.length ?? 0) > 0 ? "warn" : "ok"
      },
      {
        label: "Secundaria pronta",
        value: String(painel?.rotas.planejadas.length ?? 0),
        detail: "Carga preparada para virar saida.",
        tone: (painel?.rotas.planejadas.length ?? 0) > 0 ? "info" : "muted"
      },
      {
        label: "Primaria em curso",
        value: String(painel?.rotas.emAndamento.length ?? 0),
        detail: "Rotas que ja sustentam a operacao.",
        tone: (painel?.rotas.emAndamento.length ?? 0) > 0 ? "ok" : "muted"
      },
      {
        label: "Excecoes recentes",
        value: String(issueEvents),
        detail: "Falhas e cancelamentos no feed.",
        tone: issueEvents > 0 ? "danger" : "ok"
      },
      {
        label: "Taxa de sucesso",
        value: formatPercent(painel?.indicadoresEntrega.taxaSucessoPercentual),
        detail: "Finalizacao acumulada do read model.",
        tone: (painel?.indicadoresEntrega.taxaSucessoPercentual ?? 0) >= 90 ? "ok" : "info"
      }
    ],
    priorities: insights.alerts.map((alert) => ({
      badge: badgeForAlert(toneForAlert(alert)),
      tone: toneForAlert(alert),
      title: alert.title,
      detail: alert.detail,
      action: alert.action
    })),
    pulses: [
      { label: "Leitura da fila", value: insights.queueSummary },
      { label: "Leitura das rotas", value: insights.routeSummary },
      { label: "Leitura das ocorrencias", value: insights.eventSummary }
    ],
    queueLanes: [
      {
        title: "Triagem imediata",
        tone: hardPendings > 0 ? "danger" : (painel?.filas.pendentesElegiveis.length ?? 0) > 0 ? "warn" : "ok",
        summary: (painel?.filas.pendentesElegiveis.length ?? 0) > 0
          ? `${painel?.filas.pendentesElegiveis.length ?? 0} pedido(s) aguardando avaliacao.`
          : "Sem nova entrada pressionando a fila.",
        cards: (painel?.filas.pendentesElegiveis ?? []).slice().sort((left, right) => left.criadoEm.localeCompare(right.criadoEm)).map(buildPendingCard),
        emptyMessage: "Nenhum pedido pendente elegivel neste momento."
      },
      {
        title: "Preparar saida",
        tone: (painel?.filas.confirmadosSecundaria.length ?? 0) > 0 ? "info" : (painel?.rotas.planejadas.length ?? 0) > 0 ? "warn" : "muted",
        summary: (painel?.filas.confirmadosSecundaria.length ?? 0) > 0
          ? `${painel?.filas.confirmadosSecundaria.length ?? 0} pedido(s) comprometidos na secundaria.`
          : (painel?.rotas.planejadas.length ?? 0) > 0
            ? "Existe rota pronta, mas sem detalhamento dos pedidos nesta leitura."
            : "Sem carga montada para uma nova saida.",
        cards: (painel?.filas.confirmadosSecundaria ?? []).map(buildConfirmedCard),
        emptyMessage: (painel?.rotas.planejadas.length ?? 0) > 0
          ? "A secundaria existe, mas o read model nao detalhou os pedidos desta rota."
          : "Nenhum pedido confirmado aguardando saida."
      },
      {
        title: "Monitorar execucao",
        tone: (painel?.filas.emRotaPrimaria.length ?? 0) > 0 ? "ok" : "muted",
        summary: (painel?.filas.emRotaPrimaria.length ?? 0) > 0
          ? `${painel?.filas.emRotaPrimaria.length ?? 0} entrega(s) em circulacao na primaria.`
          : "Sem entrega ativa na primaria agora.",
        cards: (painel?.filas.emRotaPrimaria ?? []).map(buildActiveCard),
        emptyMessage: "Nenhum pedido em execucao agora."
      }
    ],
    layerCards: [
      {
        title: "Camada primaria",
        tone: routes.some((route) => route.camada === "PRIMARIA") ? "ok" : "muted",
        summary: "Sustenta a execucao que ja saiu para a rua.",
        routes: routes
          .filter((route) => route.camada === "PRIMARIA")
          .map((route) => ({
            title: `R${route.routeId} · Entregador ${route.entregadorId}`,
            meta: `${route.pendentes} pendente(s) · ${route.emExecucao} em execucao · ${route.concluidas} concluida(s)`
          })),
        emptyMessage: "Nenhuma rota primaria encontrada."
      },
      {
        title: "Camada secundaria",
        tone: routes.some((route) => route.camada === "SECUNDARIA") ? "info" : "muted",
        summary: "Reserva operacional pronta para a proxima decisao.",
        routes: routes
          .filter((route) => route.camada === "SECUNDARIA")
          .map((route) => ({
            title: `R${route.routeId} · Entregador ${route.entregadorId}`,
            meta: `${route.pendentes} pendente(s) · ${route.paradas} parada(s) previstas`
          })),
        emptyMessage: "Nenhuma rota secundaria pronta neste recorte."
      },
      {
        title: "Leitura inconsistente",
        tone: routes.some((route) => route.camada === "DESCONHECIDA") ? "warn" : "muted",
        summary: "Qualquer rota sem camada clara pede dupla checagem antes de agir.",
        routes: routes
          .filter((route) => route.camada === "DESCONHECIDA")
          .map((route) => ({
            title: `R${route.routeId} · Entregador ${route.entregadorId}`,
            meta: `${route.statusRota} · ${route.paradas} parada(s) lida(s)`
          })),
        emptyMessage: "Todas as rotas vieram com camada reconhecivel."
      }
    ],
    layerWarnings,
    eventBuckets: [
      {
        title: "Excecoes para agir",
        tone: issueEvents > 0 ? "danger" : "ok",
        summary: issueEvents > 0
          ? `${issueEvents} evento(s) merecem triagem imediata.`
          : "Sem falha ou cancelamento na janela recente.",
        cards: (snapshot?.eventos?.eventos ?? [])
          .filter((event) => toneForEvent(event) === "danger")
          .slice(0, 5)
          .map(buildEventCard),
        emptyMessage: "Nenhuma excecao recente."
      },
      {
        title: "Fluxo operacional",
        tone: "info",
        summary: "Rotas iniciadas, entregas concluidas e movimentos normais da operacao.",
        cards: (snapshot?.eventos?.eventos ?? [])
          .filter((event) => {
            const tone = toneForEvent(event);
            return tone === "info" || tone === "ok";
          })
          .slice(0, 5)
          .map(buildEventCard),
        emptyMessage: "Nenhum movimento recente de rota ou entrega."
      }
    ],
    routeCards: routes.map((route) => ({
      title: `R${route.routeId} · Entregador ${route.entregadorId}`,
      badgeLabel: route.camada,
      badgeTone: route.camada === "PRIMARIA" ? "ok" : route.camada === "SECUNDARIA" ? "info" : "warn",
      summary: `${route.statusRota} · ${route.paradas} parada(s) · ${route.pontosTrajeto} ponto(s) no trajeto · ${route.quantidadeGaloes} galao(oes)`,
      tags: route.tags.length > 0 ? route.tags : ["sem paradas detalhadas"]
    })),
    routeSummary: routes.length > 0
      ? `${routes.filter((route) => route.camada === "PRIMARIA").length} primaria(s), ${routes.filter((route) => route.camada === "SECUNDARIA").length} secundaria(s) e ${routes.reduce((sum, route) => sum + route.paradas, 0)} parada(s) mapeada(s).`
      : "Sem rotas materializadas no mapa operacional.",
    mapDeposit: snapshot?.mapa
      ? `Deposito ${snapshot.mapa.deposito.lat.toFixed(4)}, ${snapshot.mapa.deposito.lon.toFixed(4)}`
      : null,
    action: {
      title: actionEnabled ? "Acao recomendada agora" : "Acao operacional protegida",
      tone: state.despacho.routeStart.status === "loading" ? "warn" : actionEnabled ? "info" : "warn",
      badgeLabel: state.despacho.routeStart.status === "loading" ? "executando" : actionEnabled ? "liberado" : "bloqueado",
      detail: actionEnabled && candidateRoute
        ? `Iniciar R${candidateRoute.routeId} do entregador ${candidateRoute.entregadorId} para transformar a secundaria em frota ativa.`
        : candidateRoute
          ? `Existe candidata R${candidateRoute.routeId}, mas o contexto ainda nao esta seguro para girar.`
          : "Sem candidata confiavel para o proximo giro de frota.",
      supportingText: actionEnabled
        ? "O gatilho usa o endpoint operacional real de inicio de rota pronta."
        : "A saida manual fica bloqueada quando a leitura esta parcial ou quando as camadas nao estao consistentes.",
      buttonLabel: state.despacho.routeStart.status === "loading"
        ? "Iniciando rota..."
        : actionEnabled && candidateRoute
          ? `Iniciar R${candidateRoute.routeId}`
          : "Aguardando contexto seguro",
      enabled: actionEnabled,
      entregadorId: candidateRoute?.entregadorId ?? null,
      blocker: actionEnabled ? null : actionBlockers[0] ?? null
    },
    notices: [
      ...(snapshot?.partialErrors.length
        ? [
            {
              label: "Leitura parcial",
              body: snapshot.partialErrors.join(" | "),
              tone: "warn" as DisplayTone
            }
          ]
        : []),
      ...(state.sync.lastError
        ? [
            {
              label: "Ultimo erro de sincronizacao",
              body: state.sync.lastError,
              tone: "danger" as DisplayTone
            }
          ]
        : []),
      ...(state.despacho.lastRouteStart
        ? [
            {
              label: state.despacho.lastRouteStart.title,
              body: state.despacho.lastRouteStart.detail,
              tone: state.despacho.lastRouteStart.tone
            }
          ]
        : [])
    ]
  };
}
