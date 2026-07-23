import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {ReaderAnnotationService} from '../features/annotations/annotation-renderer.service';
import {ReaderEventService, ViewEvent} from './event.service';

interface TestView extends HTMLDivElement {
  addAnnotation: (annotation: {value: string}) => void;
  addAnnotationSpy: (annotation: {value: string}) => void;
  renderer?: {
    getAttribute: (name: string) => string | null;
    start: number;
    end: number;
    viewSize: number;
  };
}

interface RangeLike {
  toString: () => string;
  getBoundingClientRect: () => DOMRect;
  commonAncestorContainer: Node;
  startContainer: Node;
  endContainer: Node;
  intersectsNode: (node: Node) => boolean;
}

interface TestSelection {
  isCollapsed: boolean;
  rangeCount: number;
  getRangeAt: (index: number) => RangeLike;
  anchorNode: Node | null;
  focusNode: Node | null;
}

interface PrivateReaderEventService {
  longHoldTimeout: ReturnType<typeof setTimeout> | null;
  isNavigating: boolean;
  touchStartX: number;
  touchStartY: number;
  touchStartTime: number;
  selectionChangeTimeout: ReturnType<typeof setTimeout> | null;
  handleIframeClickMessage: (data: {
    type: 'iframe-click';
    clientX: number;
    clientY: number;
    iframeLeft: number;
    iframeWidth: number;
    eventClientX: number;
    target?: string;
  }) => void;
  processIframeClick: (data: {
    type: 'iframe-click';
    clientX: number;
    clientY: number;
    iframeLeft: number;
    iframeWidth: number;
    eventClientX: number;
    target?: string;
  }) => void;
  handleSelectionEnd: (doc: Document) => void;
  handleSelectionChange: (doc: Document) => void;
  handleTouchEnd: (event: TouchEvent, doc: Document) => void;
}

describe('ReaderEventService', () => {
  const getAllAnnotations = vi.fn(() => [] as {value: string}[]);
  const getAnnotationStyle = vi.fn();
  const getOverlayerDrawFunction = vi.fn(() => vi.fn(() => document.createElementNS('http://www.w3.org/2000/svg', 'path')));
  const prev = vi.fn();
  const next = vi.fn();
  const getCFI = vi.fn();
  const getContents = vi.fn();
  const postMessageSpy = vi.spyOn(window, 'postMessage').mockImplementation(() => undefined);

  let service: ReaderEventService;
  let privateService: PrivateReaderEventService;
  let view: TestView;
  let emittedEvents: ViewEvent[];
  let defaultSelection: TestSelection;
  let defaultView: {
    frameElement: HTMLIFrameElement;
    getSelection: () => TestSelection;
  };
  let doc: Document;
  let iframe: HTMLIFrameElement;

  function createView(width = 600): TestView {
    const element = document.createElement('div') as TestView;
    element.addAnnotationSpy = vi.fn();
    element.addAnnotation = annotation => {
      element.addAnnotationSpy(annotation);
    };
    Object.defineProperty(element, 'getBoundingClientRect', {
      value: () => new DOMRect(20, 10, width, 400),
    });
    return element;
  }

  function installSelection(targetDoc: Document, selection: TestSelection): void {
    const currentDefaultView = targetDoc.defaultView;
    if (!currentDefaultView) {
      throw new Error('expected defaultView');
    }
    Object.defineProperty(currentDefaultView, 'getSelection', {
      value: () => selection,
      configurable: true,
    });
  }

  function createTouchEvent(
    type: string,
    changed: {clientX: number; clientY: number}[],
    active: {clientX: number; clientY: number}[] = changed,
  ): TouchEvent {
    const event = new Event(type, {bubbles: true, cancelable: true}) as TouchEvent;
    Object.defineProperty(event, 'changedTouches', {value: changed, configurable: true});
    Object.defineProperty(event, 'touches', {value: active, configurable: true});
    return event;
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-03-27T03:30:00Z'));
    Reflect.deleteProperty(window, 'ontouchstart');
    Object.defineProperty(navigator, 'maxTouchPoints', {
      value: 0,
      configurable: true,
    });

    getAllAnnotations.mockReset();
    getAllAnnotations.mockReturnValue([]);
    getAnnotationStyle.mockReset();
    getOverlayerDrawFunction.mockReset();
    getOverlayerDrawFunction.mockReturnValue(vi.fn(() => document.createElementNS('http://www.w3.org/2000/svg', 'path')));
    prev.mockReset();
    next.mockReset();
    getCFI.mockReset();
    getContents.mockReset();
    postMessageSpy.mockClear();

    doc = document.implementation.createHTMLDocument('reader-doc');
    iframe = doc.createElement('iframe');
    Object.defineProperty(iframe, 'getBoundingClientRect', {
      value: () => new DOMRect(120, 80, 500, 400),
    });

    defaultSelection = {
      isCollapsed: true,
      rangeCount: 0,
      getRangeAt: () => ({
        toString: () => '',
        getBoundingClientRect: () => new DOMRect(0, 0, 0, 0),
        commonAncestorContainer: doc.body,
        startContainer: doc.body,
        endContainer: doc.body,
        intersectsNode: () => false,
      }),
      anchorNode: null,
      focusNode: null,
    };
    defaultView = {
      frameElement: iframe,
      getSelection: () => defaultSelection,
    };
    Object.defineProperty(doc, 'defaultView', {
      value: defaultView,
      configurable: true,
    });
    installSelection(doc, defaultSelection);

    TestBed.configureTestingModule({
      providers: [
        {
          provide: ReaderAnnotationService,
          useValue: {
            getAllAnnotations,
            getAnnotationStyle,
            getOverlayerDrawFunction,
          },
        },
      ],
    });

    service = TestBed.inject(ReaderEventService);
    privateService = service as unknown as PrivateReaderEventService;
    view = createView();
    emittedEvents = [];
    service.events$.subscribe(event => emittedEvents.push(event));
    service.initialize(view, {prev, next, getCFI, getContents});
  });

  afterEach(() => {
    vi.useRealTimers();
    service.destroy();
  });

  it('hydrates annotations on load and forwards load events', () => {
    getAllAnnotations.mockReturnValue([{value: 'cfi-1'}, {value: 'cfi-2'}]);

    view.dispatchEvent(new CustomEvent('load', {detail: {doc}}));
    vi.advanceTimersByTime(100);

    expect(view.addAnnotationSpy).toHaveBeenNthCalledWith(1, {value: 'cfi-1'});
    expect(view.addAnnotationSpy).toHaveBeenNthCalledWith(2, {value: 'cfi-2'});
    expect(emittedEvents).toContainEqual({type: 'load', detail: {doc}});
  });

  it('does not hijack wheel scrolling at section boundaries in scrolled mode', () => {
    view.renderer = {
      getAttribute: name => name === 'flow' ? 'scrolled' : null,
      start: 900,
      end: 1000,
      viewSize: 1000,
    };
    view.dispatchEvent(new CustomEvent('load', {detail: {doc}}));
    view.dispatchEvent(new CustomEvent('relocate', {detail: {section: {current: 1, total: 3}}}));

    const forward = new WheelEvent('wheel', {deltaY: 120, bubbles: true, cancelable: true});
    doc.dispatchEvent(forward);

    expect(next).not.toHaveBeenCalled();
    expect(forward.defaultPrevented).toBe(false);

    vi.advanceTimersByTime(300);
    view.renderer.start = 0;
    view.renderer.end = 100;
    const backward = new WheelEvent('wheel', {deltaY: -120, bubbles: true, cancelable: true});
    doc.dispatchEvent(backward);

    expect(prev).not.toHaveBeenCalled();
    expect(backward.defaultPrevented).toBe(false);
  });

  it('does not turn sections when wheel starts on the reader surface', () => {
    view.renderer = {
      getAttribute: name => name === 'flow' ? 'scrolled' : null,
      start: 0,
      end: 1100,
      viewSize: 220,
    };
    view.dispatchEvent(new CustomEvent('relocate', {detail: {section: {current: 0, total: 3}}}));

    const wheel = new WheelEvent('wheel', {deltaY: 180, bubbles: true, cancelable: true});
    view.dispatchEvent(wheel);

    expect(next).not.toHaveBeenCalled();
    expect(wheel.defaultPrevented).toBe(false);
  });

  it('does not change chapters while ordinary scrolling still has room', () => {
    view.renderer = {
      getAttribute: name => name === 'flow' ? 'scrolled' : null,
      start: 300,
      end: 700,
      viewSize: 1000,
    };
    view.dispatchEvent(new CustomEvent('load', {detail: {doc}}));

    const wheel = new WheelEvent('wheel', {deltaY: 120, bubbles: true, cancelable: true});
    doc.dispatchEvent(wheel);

    expect(next).not.toHaveBeenCalled();
    expect(wheel.defaultPrevented).toBe(false);
  });

  it('does not navigate beyond the first or last chapter', () => {
    view.renderer = {
      getAttribute: name => name === 'flow' ? 'scrolled' : null,
      start: 900,
      end: 1000,
      viewSize: 1000,
    };
    view.dispatchEvent(new CustomEvent('load', {detail: {doc}}));
    view.dispatchEvent(new CustomEvent('relocate', {detail: {section: {current: 2, total: 3}}}));

    doc.dispatchEvent(new WheelEvent('wheel', {deltaY: 120, bubbles: true, cancelable: true}));
    expect(next).not.toHaveBeenCalled();

    view.renderer.start = 0;
    view.renderer.end = 100;
    view.dispatchEvent(new CustomEvent('relocate', {detail: {section: {current: 0, total: 3}}}));
    doc.dispatchEvent(new WheelEvent('wheel', {deltaY: -120, bubbles: true, cancelable: true}));
    expect(prev).not.toHaveBeenCalled();
  });

  it('does not turn sections with an upward swipe at the scroll boundary', () => {
    view.renderer = {
      getAttribute: name => name === 'flow' ? 'scrolled' : null,
      start: 900,
      end: 1000,
      viewSize: 1000,
    };
    view.dispatchEvent(new CustomEvent('relocate', {detail: {section: {current: 1, total: 3}}}));
    privateService.touchStartX = 150;
    privateService.touchStartY = 200;
    privateService.touchStartTime = Date.now() - 100;

    const swipeUp = createTouchEvent('touchend', [{clientX: 150, clientY: 100}]);
    privateService.handleTouchEnd(swipeUp, doc);

    expect(next).not.toHaveBeenCalled();
    expect(swipeUp.defaultPrevented).toBe(false);
  });

  it('navigates and emits keyboard events for reader shortcuts while ignoring editable targets', () => {
    const input = document.createElement('input');
    input.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowLeft', bubbles: true}));
    expect(prev).not.toHaveBeenCalled();

    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowLeft', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: ' ', shiftKey: true, bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: ' ', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Home', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'End', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'f', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 't', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 's', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'n', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: '?', bubbles: true}));
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));

    expect(prev).toHaveBeenCalledTimes(2);
    expect(next).toHaveBeenCalledTimes(2);
    expect(emittedEvents.map(event => event.type)).toEqual(expect.arrayContaining([
      'go-first-section',
      'go-last-section',
      'toggle-fullscreen',
      'toggle-toc',
      'toggle-search',
      'toggle-notes',
      'toggle-shortcuts-help',
      'escape-pressed',
    ]));
  });

  it('turns browser zoom shortcuts and gestures into reader font-size changes', () => {
    const input = document.createElement('input');
    document.body.appendChild(input);
    const inputZoom = new KeyboardEvent('keydown', {key: '=', ctrlKey: true, bubbles: true, cancelable: true});
    input.dispatchEvent(inputZoom);

    view.appendChild(input);
    const inputWheel = new WheelEvent('wheel', {deltaY: -100, ctrlKey: true, bubbles: true, cancelable: true});
    input.dispatchEvent(inputWheel);

    const chromeWheel = new WheelEvent('wheel', {deltaY: -100, ctrlKey: true, bubbles: true, cancelable: true});
    document.dispatchEvent(chromeWheel);

    const zoomIn = new KeyboardEvent('keydown', {key: '=', ctrlKey: true, bubbles: true, cancelable: true});
    const zoomOut = new KeyboardEvent('keydown', {key: '-', metaKey: true, bubbles: true, cancelable: true});

    document.dispatchEvent(zoomIn);
    document.dispatchEvent(zoomOut);

    const readerWheel = new WheelEvent('wheel', {deltaY: -40, ctrlKey: true, bubbles: true, cancelable: true});
    const readerWheelContinuation = new WheelEvent('wheel', {deltaY: -60, ctrlKey: true, bubbles: true, cancelable: true});
    view.dispatchEvent(readerWheel);
    view.dispatchEvent(readerWheelContinuation);

    const firefoxWheel = new WheelEvent('wheel', {
      deltaY: 3,
      deltaMode: WheelEvent.DOM_DELTA_LINE,
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    });
    view.dispatchEvent(firefoxWheel);

    view.dispatchEvent(new CustomEvent('load', {detail: {doc}}));
    const iframeWheel = new WheelEvent('wheel', {deltaY: 100, ctrlKey: true, bubbles: true, cancelable: true});
    doc.dispatchEvent(iframeWheel);

    expect(emittedEvents.filter(event => event.type === 'change-font-size')).toEqual([
      {type: 'change-font-size', delta: 1},
      {type: 'change-font-size', delta: -1},
      {type: 'change-font-size', delta: 1},
      {type: 'change-font-size', delta: -1},
      {type: 'change-font-size', delta: -1},
    ]);
    expect(zoomIn.defaultPrevented).toBe(true);
    expect(zoomOut.defaultPrevented).toBe(true);
    expect(inputZoom.defaultPrevented).toBe(false);
    expect(inputWheel.defaultPrevented).toBe(false);
    expect(chromeWheel.defaultPrevented).toBe(false);
    expect(readerWheel.defaultPrevented).toBe(true);
    expect(readerWheelContinuation.defaultPrevented).toBe(true);
    expect(firefoxWheel.defaultPrevented).toBe(true);
    expect(iframeWheel.defaultPrevented).toBe(true);
    input.remove();
  });

  it('handles iframe clicks with double-click suppression and middle tap fallback', () => {
    privateService.longHoldTimeout = setTimeout(() => undefined, 1_000);

    privateService.handleIframeClickMessage({
      type: 'iframe-click',
      clientX: 40,
      clientY: 100,
      iframeLeft: 0,
      iframeWidth: 600,
      eventClientX: 20,
    });
    vi.advanceTimersByTime(300);
    expect(prev).toHaveBeenCalledTimes(1);

    vi.setSystemTime(new Date('2026-03-27T03:30:01Z'));
    privateService.handleIframeClickMessage({
      type: 'iframe-click',
      clientX: 280,
      clientY: 100,
      iframeLeft: 0,
      iframeWidth: 600,
      eventClientX: 260,
    });
    vi.setSystemTime(new Date('2026-03-27T03:30:01.100Z'));
    privateService.handleIframeClickMessage({
      type: 'iframe-click',
      clientX: 285,
      clientY: 100,
      iframeLeft: 0,
      iframeWidth: 600,
      eventClientX: 265,
    });
    vi.advanceTimersByTime(300);

    expect(emittedEvents.filter(event => event.type === 'middle-single-tap')).toHaveLength(1);

    vi.setSystemTime(new Date('2026-03-27T03:30:02Z'));
    privateService.handleIframeClickMessage({
      type: 'iframe-click',
      clientX: 300,
      clientY: 100,
      iframeLeft: 0,
      iframeWidth: 600,
      eventClientX: 280,
      target: 'SPAN',
    });
    vi.advanceTimersByTime(300);

    expect(emittedEvents.filter(event => event.type === 'middle-single-tap')).toHaveLength(2);
    expect(emittedEvents.at(-1)?.type).toBe('middle-single-tap');
  });

  it('does not treat footnote links or nested link content as page-zone clicks', () => {
    view.dispatchEvent(new CustomEvent('load', {detail: {doc}}));
    const link = doc.createElement('a');
    link.href = '#note-42';
    const marker = doc.createElement('sup');
    marker.textContent = '[42]';
    link.appendChild(marker);
    doc.body.appendChild(link);

    marker.dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    marker.dispatchEvent(new MouseEvent('click', {
      bubbles: true,
      clientX: 480,
      clientY: 100,
    }));
    vi.advanceTimersByTime(300);

    expect(postMessageSpy).not.toHaveBeenCalled();
    expect(prev).not.toHaveBeenCalled();
    expect(next).not.toHaveBeenCalled();
  });

  it('keeps the newest short-click timer active when clicks overlap', () => {
    view.dispatchEvent(new CustomEvent('load', {detail: {doc}}));

    doc.body.dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    vi.advanceTimersByTime(250);
    doc.body.dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    vi.advanceTimersByTime(250);

    expect(privateService.longHoldTimeout).not.toBeNull();

    vi.advanceTimersByTime(250);
    expect(privateService.longHoldTimeout).toBeNull();
  });

  it('processes touch gestures for swipe navigation, short taps, and selected text', () => {
    privateService.longHoldTimeout = setTimeout(() => undefined, 1_000);
    privateService.touchStartX = 150;
    privateService.touchStartY = 120;
    privateService.touchStartTime = Date.now() - 100;

    const swipeLeft = createTouchEvent('touchend', [{clientX: 60, clientY: 120}]);
    privateService.handleTouchEnd(swipeLeft, doc);
    expect(next).toHaveBeenCalledTimes(1);

    privateService.isNavigating = false;
    privateService.touchStartX = 120;
    privateService.touchStartY = 90;
    privateService.touchStartTime = Date.now() - 100;

    const tap = createTouchEvent('touchend', [{clientX: 122, clientY: 92}]);
    privateService.handleTouchEnd(tap, doc);
    expect(postMessageSpy).toHaveBeenCalledWith(expect.objectContaining({
      type: 'iframe-click',
      target: undefined,
    }), window.location.origin);

    const selectedRange: RangeLike = {
      toString: () => 'Highlighted text',
      getBoundingClientRect: () => new DOMRect(20, 15, 80, 20),
      commonAncestorContainer: doc.body,
      startContainer: doc.body,
      endContainer: doc.body,
      intersectsNode: () => false,
    };
    installSelection(doc, {
      isCollapsed: false,
      rangeCount: 1,
      getRangeAt: () => selectedRange,
      anchorNode: doc.body,
      focusNode: doc.body,
    });
    getContents.mockReturnValue([{index: 7, doc}]);
    getCFI.mockReturnValue('epubcfi(/6/2!/4/2:0)');

    const selectedTouch = createTouchEvent('touchend', [{clientX: 140, clientY: 120}]);
    privateService.handleTouchEnd(selectedTouch, doc);
    vi.advanceTimersByTime(50);
    vi.advanceTimersByTime(10);

    expect(emittedEvents.at(-1)).toMatchObject({
      type: 'text-selected',
      detail: {
        text: 'Highlighted text',
        cfi: 'epubcfi(/6/2!/4/2:0)',
        index: 7,
      },
    });
  });

  it('debounces selection changes and only emits when selection text and CFI are available', () => {
    const emptyRange: RangeLike = {
      toString: () => '   ',
      getBoundingClientRect: () => new DOMRect(0, 0, 0, 0),
      commonAncestorContainer: doc.body,
      startContainer: doc.body,
      endContainer: doc.body,
      intersectsNode: () => false,
    };
    installSelection(doc, {
      isCollapsed: false,
      rangeCount: 1,
      getRangeAt: () => emptyRange,
      anchorNode: doc.body,
      focusNode: doc.body,
    });

    privateService.handleSelectionChange(doc);
    vi.advanceTimersByTime(300);
    expect(emittedEvents).toHaveLength(0);

    const visibleRange: RangeLike = {
      toString: () => 'Visible note',
      getBoundingClientRect: () => new DOMRect(10, 10, 60, 25),
      commonAncestorContainer: doc.body,
      startContainer: doc.body,
      endContainer: doc.body,
      intersectsNode: () => false,
    };
    installSelection(doc, {
      isCollapsed: false,
      rangeCount: 1,
      getRangeAt: () => visibleRange,
      anchorNode: doc.body,
      focusNode: doc.body,
    });
    getContents.mockReturnValue([{index: 3, doc}]);
    getCFI.mockReturnValueOnce(null).mockReturnValueOnce('epubcfi(/6/8!/4/2:0)');

    privateService.handleSelectionEnd(doc);
    vi.advanceTimersByTime(10);
    expect(emittedEvents).toHaveLength(0);

    privateService.handleSelectionEnd(doc);
    vi.advanceTimersByTime(10);
    expect(emittedEvents.at(-1)).toMatchObject({
      type: 'text-selected',
      detail: {
        text: 'Visible note',
        cfi: 'epubcfi(/6/8!/4/2:0)',
        index: 3,
      },
      popupPosition: {
        showBelow: true,
      },
    });
  });

  it('uses the selected document index when multiple scrolled sections are loaded', () => {
    const secondDoc = document.implementation.createHTMLDocument('second-reader-doc');
    const secondIframe = secondDoc.createElement('iframe');
    Object.defineProperty(secondIframe, 'getBoundingClientRect', {
      value: () => new DOMRect(220, 120, 500, 400),
    });
    Object.defineProperty(secondDoc, 'defaultView', {
      value: {
        frameElement: secondIframe,
        getSelection: () => selectedSelection,
      },
      configurable: true,
    });

    const selectedRange: RangeLike = {
      toString: () => 'Second chapter note',
      getBoundingClientRect: () => new DOMRect(30, 25, 90, 22),
      commonAncestorContainer: secondDoc.body,
      startContainer: secondDoc.body,
      endContainer: secondDoc.body,
      intersectsNode: () => false,
    };
    const selectedSelection: TestSelection = {
      isCollapsed: false,
      rangeCount: 1,
      getRangeAt: () => selectedRange,
      anchorNode: secondDoc.body,
      focusNode: secondDoc.body,
    };

    getContents.mockReturnValue([{index: 7, doc}, {index: 8, doc: secondDoc}]);
    getCFI.mockReturnValue('epubcfi(/6/8!/4/2:0)');

    privateService.handleSelectionEnd(secondDoc);
    vi.advanceTimersByTime(10);

    expect(getCFI).toHaveBeenLastCalledWith(8, selectedRange);
    expect(emittedEvents.at(-1)).toMatchObject({
      type: 'text-selected',
      detail: {
        text: 'Second chapter note',
        cfi: 'epubcfi(/6/8!/4/2:0)',
        index: 8,
      },
    });
  });

  it('styles draw-annotation events when a stored annotation style exists', () => {
    const draw = vi.fn();
    const overlayer = vi.fn(() => document.createElementNS('http://www.w3.org/2000/svg', 'rect'));

    getAnnotationStyle.mockReturnValue({color: '#0f0', style: 'highlight'});
    getOverlayerDrawFunction.mockReturnValue(overlayer);

    view.dispatchEvent(new CustomEvent('draw-annotation', {
      detail: {
        draw,
        annotation: {value: 'epubcfi(/6/2!/4/2:0)'},
        doc,
        range: document.createRange(),
      },
    }));

    expect(getAnnotationStyle).toHaveBeenCalledWith('epubcfi(/6/2!/4/2:0)');
    expect(draw).toHaveBeenCalledWith(overlayer, {color: '#0f0'});
    expect(emittedEvents.at(-1)?.type).toBe('draw-annotation');
  });
});
