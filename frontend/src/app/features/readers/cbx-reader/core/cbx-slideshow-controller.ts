export interface CbxSlideshowControllerHooks {
  canAdvance: () => boolean;
  advance: () => void;
  onActiveChange: (active: boolean) => void;
}

export class CbxSlideshowController {
  private timer: ReturnType<typeof setInterval> | null = null;
  private active = false;

  constructor(private readonly hooks: CbxSlideshowControllerHooks) {
  }

  isActive(): boolean {
    return this.active;
  }

  toggle(intervalMs: number): void {
    if (this.active) {
      this.stop();
      return;
    }

    this.start(intervalMs);
  }

  start(intervalMs: number): void {
    if (!this.hooks.canAdvance()) return;

    this.stopTimer();
    this.setActive(true);
    this.timer = setInterval(() => {
      if (this.hooks.canAdvance()) {
        this.hooks.advance();
      } else {
        this.stop();
      }
    }, intervalMs);
  }

  stop(): void {
    this.stopTimer();
    this.setActive(false);
  }

  pauseOnInteraction(): void {
    if (this.active) {
      this.stop();
    }
  }

  updateInterval(intervalMs: number): void {
    if (!this.active) return;

    this.stop();
    this.start(intervalMs);
  }

  destroy(): void {
    this.stop();
  }

  private setActive(active: boolean): void {
    if (this.active === active) return;

    this.active = active;
    this.hooks.onActiveChange(active);
  }

  private stopTimer(): void {
    if (!this.timer) return;

    clearInterval(this.timer);
    this.timer = null;
  }
}
