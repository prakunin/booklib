import {DOCUMENT} from '@angular/common';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {ReaderFullscreenService} from './reader-fullscreen.service';

describe('ReaderFullscreenService', () => {
  let documentElement: Element & { requestFullscreen: ReturnType<typeof vi.fn> };
  let fullscreenTarget: Element & { requestFullscreen: ReturnType<typeof vi.fn> };
  let documentMock: Document & { fullscreenElement: Element | null; exitFullscreen: ReturnType<typeof vi.fn> };
  let service: ReaderFullscreenService;

  beforeEach(() => {
    documentElement = {requestFullscreen: vi.fn(() => Promise.resolve())} as unknown as Element & { requestFullscreen: ReturnType<typeof vi.fn> };
    fullscreenTarget = {requestFullscreen: vi.fn(() => Promise.resolve())} as unknown as Element & { requestFullscreen: ReturnType<typeof vi.fn> };
    documentMock = {
      documentElement,
      fullscreenElement: null,
      exitFullscreen: vi.fn(() => Promise.resolve()),
    } as unknown as Document & { fullscreenElement: Element | null; exitFullscreen: ReturnType<typeof vi.fn> };

    TestBed.configureTestingModule({
      providers: [
        ReaderFullscreenService,
        {provide: DOCUMENT, useValue: documentMock},
      ]
    });
    service = TestBed.inject(ReaderFullscreenService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('tracks fullscreen globally or for a specific target', () => {
    documentMock.fullscreenElement = fullscreenTarget;

    expect(service.isFullscreen()).toBe(true);
    expect(service.isFullscreen(fullscreenTarget)).toBe(true);
    expect(service.isFullscreen(documentElement)).toBe(false);
  });

  it('enters fullscreen on the provided target', () => {
    service.enter(fullscreenTarget);

    expect(fullscreenTarget.requestFullscreen).toHaveBeenCalledOnce();
    expect(documentElement.requestFullscreen).not.toHaveBeenCalled();
  });

  it('uses the document element when no target is provided', () => {
    service.enter();

    expect(documentElement.requestFullscreen).toHaveBeenCalledOnce();
  });

  it('toggles between enter and exit based on document state', () => {
    service.toggle(fullscreenTarget);
    expect(fullscreenTarget.requestFullscreen).toHaveBeenCalledOnce();
    expect(documentMock.exitFullscreen).not.toHaveBeenCalled();

    documentMock.fullscreenElement = fullscreenTarget;

    service.toggle(fullscreenTarget);
    expect(documentMock.exitFullscreen).toHaveBeenCalledOnce();
  });

  it('swallows browser fullscreen promise rejections', async () => {
    const rejection = Promise.reject(new Error('denied'));
    fullscreenTarget.requestFullscreen.mockReturnValue(rejection);

    service.enter(fullscreenTarget);

    await expect(rejection.catch(() => undefined)).resolves.toBeUndefined();
  });
});
