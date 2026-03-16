export interface PollingController {
  sync(enabled: boolean): void;
  stop(): void;
}

interface PollingOptions {
  intervalMs: number;
  onTick: () => void;
  targetWindow?: Window;
}

export function createPollingController(options: PollingOptions): PollingController {
  const targetWindow = options.targetWindow ?? window;
  let timerId: number | null = null;

  const clearTimer = (): void => {
    if (timerId !== null) {
      targetWindow.clearInterval(timerId);
      timerId = null;
    }
  };

  const startTimer = (): void => {
    clearTimer();
    timerId = targetWindow.setInterval(options.onTick, options.intervalMs);
  };

  return {
    sync(enabled: boolean): void {
      if (!enabled) {
        clearTimer();
        return;
      }

      if (timerId === null) {
        startTimer();
      }
    },
    stop(): void {
      clearTimer();
    }
  };
}
