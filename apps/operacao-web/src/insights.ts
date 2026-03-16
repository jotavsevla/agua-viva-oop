import type { OperacaoEventoItem, OperacaoMapaResponse, OperacaoPainelResponse, OperationalSnapshot } from "./types";

export type InsightTone = "ok" | "warn" | "danger" | "info";

export interface OperationalAlert {
  tone: InsightTone;
  title: string;
  detail: string;
  action: string;
}

export interface OperationalInsights {
  alerts: OperationalAlert[];
  headline: string;
  queueSummary: string;
  routeSummary: string;
  eventSummary: string;
  executiveSummary: string;
  modeLabel: string;
  modeTone: InsightTone;
  nextAction: string;
  nextActionDetail: string;
  nextActionTone: InsightTone;
  confidenceLabel: string;
  confidenceDetail: string;
  confidenceTone: InsightTone;
}

export function buildOperationalInsights(snapshot: OperationalSnapshot | null): OperationalInsights {
  if (!snapshot || !snapshot.painel) {
    return {
      alerts: [
        {
          tone: "warn",
          title: "Sem leitura operacional suficiente",
          detail: "A interface ainda nao conseguiu carregar o painel principal.",
          action: "Validar API base e atualizar a sincronizacao."
        }
      ],
      headline: "Aguardando dados operacionais",
      queueSummary: "Fila indisponivel",
      routeSummary: "Rotas indisponiveis",
      eventSummary: "Eventos indisponiveis",
      executiveSummary: "A operacao ainda nao entregou leitura suficiente para orientar uma decisao com seguranca.",
      modeLabel: "Aguardando leitura",
      modeTone: "warn",
      nextAction: "Validar conexao",
      nextActionDetail: "Conferir API base e executar nova sincronizacao antes de operar.",
      nextActionTone: "warn",
      confidenceLabel: "Baixa confianca",
      confidenceDetail: "Sem painel principal, qualquer decisao pode estar desatualizada.",
      confidenceTone: "danger"
    };
  }

  const painel = snapshot.painel;
  const mapa = snapshot.mapa;
  const eventos = snapshot.eventos?.eventos || [];
  const alerts: OperationalAlert[] = [];

  if (snapshot.health?.status !== "ok" || snapshot.health?.database !== "ok") {
    alerts.push({
      tone: "danger",
      title: "Infra com degradacao",
      detail: "API ou banco nao responderam como esperado nesta leitura.",
      action: "Conferir health e estabilizar a base antes de operar manualmente."
    });
  }

  if (snapshot.partialErrors.length > 0) {
    alerts.push({
      tone: "warn",
      title: "Visao parcial da operacao",
      detail: snapshot.partialErrors.join(" | "),
      action: "Atualizar novamente antes de tomar decisao de despacho."
    });
  }

  const hardPendings = painel.filas.pendentesElegiveis.filter((item) => item.janelaTipo === "HARD").length;
  if (hardPendings > 0) {
    alerts.push({
      tone: "danger",
      title: `${hardPendings} pedido(s) HARD aguardando despacho`,
      detail: "Esses pedidos merecem prioridade maxima porque a janela e mais restrita.",
      action: "Separar esses pedidos primeiro na avaliacao de fila."
    });
  }

  const plannedRoutes = painel.rotas.planejadas.length;
  if (plannedRoutes > 0) {
    alerts.push({
      tone: "warn",
      title: `${plannedRoutes} rota(s) pronta(s) para iniciar`,
      detail: "Existe carga ja comprometida aguardando acao operacional.",
      action: "Confirmar se a capacidade da frota permite iniciar a proxima rota."
    });
  }

  const failureEvents = countOperationalIssues(eventos);
  if (failureEvents > 0) {
    alerts.push({
      tone: "info",
      title: `${failureEvents} ocorrencia(s) recente(s) de falha/cancelamento`,
      detail: "O feed operacional registrou eventos de excecao nas ultimas leituras.",
      action: "Olhar o bloco de ocorrencias para entender o contexto antes da proxima acao."
    });
  }

  const layerConsistency = describeLayerConsistency(mapa);
  if (!layerConsistency.consistent) {
    alerts.push({
      tone: "warn",
      title: "Camadas da frota fora do esperado",
      detail: layerConsistency.message,
      action: "Revisar leitura de rotas antes de assumir que existe uma unica primaria e uma unica secundaria."
    });
  }

  if (alerts.length === 0) {
    alerts.push({
      tone: "ok",
      title: "Operacao em regime estavel",
      detail: "Nao surgiram sinais imediatos de excecao na leitura atual.",
      action: "Seguir monitorando fila, eventos e rotas por cadencia normal."
    });
  }

  const headline = buildHeadline(painel);
  const queueSummary = buildQueueSummary(painel);
  const routeSummary = buildRouteSummary(painel, mapa, layerConsistency.message);
  const eventSummary = buildEventSummary(eventos);
  const leadingAlert = alerts[0];

  return {
    alerts: alerts.slice(0, 4),
    headline,
    queueSummary,
    routeSummary,
    eventSummary,
    executiveSummary: `${headline}. ${queueSummary} ${routeSummary}`,
    modeLabel: leadingAlert?.title || "Operacao estavel",
    modeTone: leadingAlert?.tone || "ok",
    nextAction: leadingAlert?.action || "Seguir monitorando",
    nextActionDetail: leadingAlert?.detail || "Sem excecoes imediatas na leitura atual.",
    nextActionTone: leadingAlert?.tone || "ok",
    confidenceLabel:
      snapshot.partialErrors.length > 0 ? "Confianca moderada" : snapshot.health?.status === "ok" ? "Confianca alta" : "Confianca reduzida",
    confidenceDetail:
      snapshot.partialErrors.length > 0
        ? "Ha leituras parciais nesta sincronizacao; valide antes de decidir."
        : snapshot.health?.status === "ok"
          ? "Painel, eventos e mapa responderam como esperado."
          : "Infra ou banco sinalizaram degradacao.",
    confidenceTone:
      snapshot.partialErrors.length > 0 ? "warn" : snapshot.health?.status === "ok" ? "ok" : "danger"
  };
}

function countOperationalIssues(events: OperacaoEventoItem[]): number {
  return events.filter((event) => {
    const type = String(event.eventType || "").toUpperCase();
    return type.includes("FALHOU") || type.includes("CANCELADO");
  }).length;
}

function buildHeadline(painel: OperacaoPainelResponse): string {
  if (painel.filas.pendentesElegiveis.length > 0) {
    return "Fila exigindo triagem ativa";
  }
  if (painel.rotas.emAndamento.length > 0) {
    return "Entrega em curso com operacao ja movimentada";
  }
  if (painel.rotas.planejadas.length > 0) {
    return "Operacao pronta para iniciar nova rota";
  }
  return "Base limpa para acompanhamento";
}

function buildQueueSummary(painel: OperacaoPainelResponse): string {
  return `${painel.pedidosPorStatus.pendente} pendente(s), ${painel.pedidosPorStatus.confirmado} confirmado(s), ${painel.pedidosPorStatus.emRota} em rota.`;
}

function buildRouteSummary(
  painel: OperacaoPainelResponse,
  mapa: OperacaoMapaResponse | null,
  layerMessage: string
): string {
  const primarias = mapa?.rotas.filter((route) => route.camada === "PRIMARIA").length ?? 0;
  const secundarias = mapa?.rotas.filter((route) => route.camada === "SECUNDARIA").length ?? 0;
  return `${painel.rotas.emAndamento.length} em andamento, ${painel.rotas.planejadas.length} planejada(s), ${primarias} primaria(s), ${secundarias} secundaria(s). ${layerMessage}`;
}

function buildEventSummary(events: OperacaoEventoItem[]): string {
  if (events.length === 0) {
    return "Sem ocorrencias recentes.";
  }

  const issueCount = countOperationalIssues(events);
  if (issueCount > 0) {
    return `${issueCount} excecao(oes) recente(s) em ${events.length} evento(s) lido(s).`;
  }

  return `${events.length} evento(s) recente(s) sem excecao imediata.`;
}

function describeLayerConsistency(mapa: OperacaoMapaResponse | null): { consistent: boolean; message: string } {
  if (!mapa || mapa.rotas.length === 0) {
    return {
      consistent: true,
      message: "Sem rotas mapeadas."
    };
  }

  const primarias = mapa.rotas.filter((route) => route.camada === "PRIMARIA").length;
  const secundarias = mapa.rotas.filter((route) => route.camada === "SECUNDARIA").length;
  const consistent = primarias <= 1 && secundarias <= 1;

  if (consistent) {
    return {
      consistent: true,
      message: "Camadas dentro da expectativa atual."
    };
  }

  return {
    consistent: false,
    message: `Leitura retornou ${primarias} primaria(s) e ${secundarias} secundaria(s).`
  };
}
