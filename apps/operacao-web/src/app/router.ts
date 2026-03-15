import type { AppModuleId } from "../types";
import { getModuleHash, resolveModuleId } from "./modules";

export interface AppRouter {
  getCurrentModule(): AppModuleId;
  navigate(moduleId: AppModuleId): void;
  start(): void;
  dispose(): void;
}

export function createAppRouter(
  onModuleChange: (moduleId: AppModuleId) => void,
  targetWindow: Window = window
): AppRouter {
  let currentModule = resolveModuleId(targetWindow.location.hash);

  const handleHashChange = (): void => {
    const nextModule = resolveModuleId(targetWindow.location.hash);

    if (nextModule === currentModule) {
      return;
    }

    currentModule = nextModule;
    onModuleChange(nextModule);
  };

  return {
    getCurrentModule(): AppModuleId {
      return currentModule;
    },
    navigate(moduleId: AppModuleId): void {
      currentModule = moduleId;
      const nextHash = getModuleHash(moduleId);

      if (targetWindow.location.hash !== nextHash) {
        targetWindow.location.hash = nextHash;
      }
    },
    start(): void {
      targetWindow.addEventListener("hashchange", handleHashChange);

      const expectedHash = getModuleHash(currentModule);
      if (targetWindow.location.hash !== expectedHash) {
        targetWindow.location.hash = expectedHash;
      }
    },
    dispose(): void {
      targetWindow.removeEventListener("hashchange", handleHashChange);
    }
  };
}
