import {afterEach, beforeEach, describe, expect, it, vi, type Mock} from 'vitest';

import {CbxSlideshowController, CbxSlideshowControllerHooks} from './cbx-slideshow-controller';

describe('CbxSlideshowController', () => {
  let canAdvance: Mock<() => boolean>;
  let advance: Mock<() => void>;
  let activeChanges: boolean[];
  let controller: CbxSlideshowController;

  beforeEach(() => {
    vi.useFakeTimers();
    activeChanges = [];
    canAdvance = vi.fn(() => true);
    advance = vi.fn();

    const hooks: CbxSlideshowControllerHooks = {
      canAdvance,
      advance,
      onActiveChange: active => activeChanges.push(active),
    };
    controller = new CbxSlideshowController(hooks);
  });

  afterEach(() => {
    controller.destroy();
    vi.useRealTimers();
  });

  it('does not start when there is no next page', () => {
    canAdvance.mockReturnValue(false);

    controller.start(5000);
    vi.advanceTimersByTime(5000);

    expect(controller.isActive()).toBe(false);
    expect(activeChanges).toEqual([]);
    expect(advance).not.toHaveBeenCalled();
  });

  it('advances on interval until it reaches the end', () => {
    canAdvance
      .mockReturnValueOnce(true)
      .mockReturnValueOnce(true)
      .mockReturnValueOnce(false);

    controller.start(3000);
    vi.advanceTimersByTime(3000);
    vi.advanceTimersByTime(3000);

    expect(advance).toHaveBeenCalledOnce();
    expect(controller.isActive()).toBe(false);
    expect(activeChanges).toEqual([true, false]);
  });

  it('toggles an active slideshow off and clears its timer', () => {
    controller.toggle(3000);
    controller.toggle(3000);
    vi.advanceTimersByTime(3000);

    expect(controller.isActive()).toBe(false);
    expect(activeChanges).toEqual([true, false]);
    expect(advance).not.toHaveBeenCalled();
  });

  it('restarts an active slideshow with a new interval', () => {
    controller.start(3000);
    controller.updateInterval(1000);

    vi.advanceTimersByTime(999);
    expect(advance).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(advance).toHaveBeenCalledOnce();
    expect(activeChanges).toEqual([true, false, true]);
  });

  it('pauses only while active', () => {
    controller.pauseOnInteraction();
    expect(activeChanges).toEqual([]);

    controller.start(3000);
    controller.pauseOnInteraction();

    expect(controller.isActive()).toBe(false);
    expect(activeChanges).toEqual([true, false]);
  });
});
