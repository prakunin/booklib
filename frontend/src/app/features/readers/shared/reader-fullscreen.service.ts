import {DOCUMENT} from '@angular/common';
import {inject, Injectable} from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class ReaderFullscreenService {
  private readonly document = inject(DOCUMENT);

  isFullscreen(target?: Element | null): boolean {
    const fullscreenElement = this.document.fullscreenElement;
    return target ? fullscreenElement === target : !!fullscreenElement;
  }

  toggle(target: Element = this.document.documentElement): void {
    if (this.document.fullscreenElement) {
      this.exit();
      return;
    }

    this.enter(target);
  }

  enter(target: Element = this.document.documentElement): void {
    this.ignoreRejection(target.requestFullscreen?.());
  }

  exit(): void {
    this.ignoreRejection(this.document.exitFullscreen?.());
  }

  private ignoreRejection(result: Promise<void> | void): void {
    if (result && typeof result.catch === 'function') {
      void result.catch(() => undefined);
    }
  }
}
