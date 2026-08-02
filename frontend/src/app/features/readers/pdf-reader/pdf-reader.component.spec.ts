import {afterEach, beforeEach, describe, expect, it, vi, type Mock} from 'vitest';
import type {Navigation} from '@angular/router';

import {hasInAppReaderPredecessor, PdfReaderComponent} from './pdf-reader.component';

interface PdfReaderHarness {
  embedPdfIframe: {contentWindow: {postMessage: Mock<(message: unknown, targetOrigin: string) => void>}} | null;
  pdfEmbedBridge: {post: ReturnType<typeof vi.fn>};
  embedPdfSaveResolve?: (buffer: ArrayBuffer | null) => void;
  pdfBlobUrl: string | null;
  authService: {getInternalAccessToken: () => string | null};
  cacheStorageService: {delete: ReturnType<typeof vi.fn>};
  bookId: number;
  saveEmbedPdfDocument: () => Promise<boolean>;
}

interface PdfReaderCloseHarness {
  closeReaderPromise: Promise<void> | null;
  hasInAppPreviousNavigation: boolean;
  viewerMode: () => 'book' | 'document';
  embedPdfIframe: HTMLIFrameElement | null;
  persistAnnotations: () => Promise<void>;
  readingSessionService: {
    isSessionActive: () => boolean;
    endSession: ReturnType<typeof vi.fn>;
  };
  page: () => number;
  totalPages: () => number;
  location: {back: ReturnType<typeof vi.fn>};
  router: {navigate: ReturnType<typeof vi.fn>};
  isClosingReader: {set: ReturnType<typeof vi.fn>};
  closeReader: () => Promise<void>;
}

function makeComponent(savedBuffer: ArrayBuffer): PdfReaderHarness {
  const component = Object.create(PdfReaderComponent.prototype) as PdfReaderHarness;
  component.embedPdfIframe = {
    contentWindow: {
      postMessage: vi.fn(() => {
        setTimeout(() => component.embedPdfSaveResolve?.(savedBuffer.slice(0)));
      })
    }
  };
  component.pdfEmbedBridge = {
    post: vi.fn((iframe: PdfReaderHarness['embedPdfIframe'], message: unknown) => {
      iframe?.contentWindow.postMessage(message, location.origin);
      return true;
    })
  };
  component.pdfBlobUrl = 'blob:old-pdf';
  component.authService = {getInternalAccessToken: () => null};
  component.cacheStorageService = {delete: vi.fn(() => Promise.resolve(true))};
  component.bookId = 123;
  return component;
}

function makeCloseComponent(
  hasInAppPreviousNavigation: boolean,
  persistAnnotations: () => Promise<void> = () => Promise.resolve(),
): PdfReaderCloseHarness {
  const component = Object.create(PdfReaderComponent.prototype) as PdfReaderCloseHarness;
  component.closeReaderPromise = null;
  component.hasInAppPreviousNavigation = hasInAppPreviousNavigation;
  component.viewerMode = () => 'book';
  component.embedPdfIframe = null;
  component.persistAnnotations = persistAnnotations;
  component.readingSessionService = {
    isSessionActive: () => true,
    endSession: vi.fn(),
  };
  component.page = () => 3;
  component.totalPages = () => 10;
  component.location = {back: vi.fn()};
  component.router = {navigate: vi.fn(() => Promise.resolve(true))};
  component.isClosingReader = {set: vi.fn()};
  return component;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('PdfReaderComponent save handling', () => {
  it('shares one iframe export and upload across concurrent saves', async () => {
    const savedBuffer = new Uint8Array([1, 2, 3]).buffer;
    const component = makeComponent(savedBuffer);
    const fetchMock = vi.fn(() => Promise.resolve({ok: true} as Response));
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:saved-pdf');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    const firstSave = component.saveEmbedPdfDocument();
    const secondSave = component.saveEmbedPdfDocument();
    vi.advanceTimersByTime(0);

    await expect(Promise.all([firstSave, secondSave])).resolves.toEqual([true, true]);
    expect(component.embedPdfIframe?.contentWindow.postMessage).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(component.cacheStorageService.delete).toHaveBeenCalledTimes(1);
  });

  it('refreshes local PDF bytes and evicts the shared cache after upload succeeds', async () => {
    const savedBuffer = new Uint8Array([4, 5, 6]).buffer;
    const component = makeComponent(savedBuffer);
    const fetchMock = vi.fn(() => Promise.resolve({ok: true} as Response));
    const createObjectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:saved-pdf');
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.stubGlobal('fetch', fetchMock);

    const save = component.saveEmbedPdfDocument();
    vi.advanceTimersByTime(0);

    await expect(save).resolves.toBe(true);

    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:old-pdf');
    expect(createObjectUrl).toHaveBeenCalledTimes(1);
    expect(component.pdfBlobUrl).toBe('blob:saved-pdf');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/books/123/content'),
      expect.objectContaining({body: expect.any(ArrayBuffer)})
    );
    expect(component.cacheStorageService.delete).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/books/123/content')
    );
  });
});

describe('PdfReaderComponent source selection', () => {

  interface SourceHarness {
    localSettingsService: {get: () => {cacheStorageEnabled: boolean}};
    getBookData: (bookId: string, fileType: string | undefined, bookType?: string) => {subscribe: (fn: (uri: string) => void) => void};
  }

  function makeSourceComponent(): SourceHarness {
    const component = Object.create(PdfReaderComponent.prototype) as SourceHarness;
    component.localSettingsService = {get: () => ({cacheStorageEnabled: false})};
    return component;
  }

  function uriFor(bookType?: string): string {
    let uri = '';
    makeSourceComponent().getBookData('1494366', undefined, bookType).subscribe(value => (uri = value));
    return uri;
  }

  it('loads a DjVu book from its PDF rendition, not from the source file', () => {
    // /content serves the book's own bytes, and a .djvu is not a PDF: handing it to the viewer is
    // what leaves the reader spinning forever with no error to show for it.
    expect(uriFor('DJVU')).toContain('/api/v1/djvu/1494366/rendition');
  });

  it('still loads a real PDF from its own content', () => {
    expect(uriFor('PDF')).toContain('/api/v1/books/1494366/content');
  });
});

describe('PdfReaderComponent close navigation', () => {
  it('uses a normal Angular predecessor when no delegated provenance exists', () => {
    const navigation = {
      previousNavigation: {} as Navigation,
      extras: {},
    } as Pick<Navigation, 'extras' | 'previousNavigation'>;

    expect(hasInAppReaderPredecessor(navigation)).toBe(true);
  });

  it('rejects a transient CBX predecessor when CBX was opened directly', () => {
    const navigation = {
      previousNavigation: {} as Navigation,
      extras: {state: {readerHasInAppPreviousNavigation: false}},
    } as Pick<Navigation, 'extras' | 'previousNavigation'>;

    expect(hasInAppReaderPredecessor(navigation)).toBe(false);
  });

  it('rejects stale delegated provenance when a PDF reader navigation has no predecessor', () => {
    const navigation = {
      previousNavigation: null,
      extras: {state: {readerHasInAppPreviousNavigation: true}},
    } as Pick<Navigation, 'extras' | 'previousNavigation'>;

    expect(hasInAppReaderPredecessor(navigation)).toBe(false);
  });

  it('returns to the in-app page that opened the reader after persistence and session cleanup', async () => {
    const persistAnnotations = vi.fn(() => Promise.resolve());
    const component = makeCloseComponent(true, persistAnnotations);

    await component.closeReader();

    expect(persistAnnotations).toHaveBeenCalledOnce();
    expect(component.readingSessionService.endSession).toHaveBeenCalledWith('3', 30);
    expect(component.location.back).toHaveBeenCalledOnce();
    expect(component.router.navigate).not.toHaveBeenCalled();
  });

  it('replaces a directly loaded reader URL with the dashboard', async () => {
    const component = makeCloseComponent(false);

    await component.closeReader();

    expect(component.location.back).not.toHaveBeenCalled();
    expect(component.router.navigate).toHaveBeenCalledWith(['/dashboard'], {replaceUrl: true});
  });

  it('logs a persistence failure and still leaves the reader once', async () => {
    const error = new Error('save failed');
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const component = makeCloseComponent(true, () => Promise.reject(error));

    await component.closeReader();

    expect(consoleError).toHaveBeenCalledWith('[PDF Reader] Error saving on close:', error);
    expect(component.readingSessionService.endSession).toHaveBeenCalledOnce();
    expect(component.location.back).toHaveBeenCalledOnce();
  });

  it('shares persistence and navigation across duplicate close requests', async () => {
    let finishPersistence!: () => void;
    const persistAnnotations = vi.fn(() => new Promise<void>(resolve => {
      finishPersistence = resolve;
    }));
    const component = makeCloseComponent(true, persistAnnotations);

    const firstClose = component.closeReader();
    const secondClose = component.closeReader();

    expect(persistAnnotations).toHaveBeenCalledOnce();
    finishPersistence();
    await Promise.all([firstClose, secondClose]);

    expect(component.readingSessionService.endSession).toHaveBeenCalledOnce();
    expect(component.location.back).toHaveBeenCalledOnce();
  });
});
