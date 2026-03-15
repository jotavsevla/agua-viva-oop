import type { AppModuleDefinition, AppModuleId } from "../types";

export const APP_MODULES: AppModuleDefinition[] = [
  {
    id: "cockpit",
    hash: "#/cockpit",
    label: "Cockpit",
    description: "Visao consolidada da operacao, dos alertas e dos read models.",
    status: "active"
  },
  {
    id: "atendimento",
    hash: "#/atendimento",
    label: "Atendimento",
    description: "Busca de cliente na sessao, criacao segura de pedido e handoff conectado ao despacho.",
    status: "active"
  },
  {
    id: "despacho",
    hash: "#/despacho",
    label: "Despacho",
    description: "Cockpit principal para fila operacional, camadas de frota, risco e acoes de saida.",
    status: "planned"
  },
  {
    id: "entregador",
    hash: "#/entregador",
    label: "Entregador",
    description: "Base reservada para roteiro, progresso de rota, deep link e eventos terminais.",
    status: "planned"
  }
];

export function getModuleById(moduleId: AppModuleId): AppModuleDefinition {
  const moduleDefinition = APP_MODULES.find((item) => item.id === moduleId);

  if (!moduleDefinition) {
    throw new Error(`Modulo desconhecido: ${moduleId}`);
  }

  return moduleDefinition;
}

export function getModuleHash(moduleId: AppModuleId): string {
  return getModuleById(moduleId).hash;
}

export function isAppModuleId(value: string): value is AppModuleId {
  return APP_MODULES.some((item) => item.id === value);
}

export function resolveModuleId(hash: string | null | undefined): AppModuleId {
  const normalized = String(hash || "")
    .replace(/^#\/?/, "")
    .trim()
    .toLowerCase();

  return isAppModuleId(normalized) ? normalized : "cockpit";
}
