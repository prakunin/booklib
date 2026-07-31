import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, Router} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {MessageService} from 'primeng/api';
import {EMPTY, of} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {BookService} from '../../book/service/book.service';
import {CbxReaderService} from '../../book/service/cbx-reader.service';
import {ReaderPreferencesService} from '../../settings/reader-preferences/reader-preferences.service';
import {UserService} from '../../settings/user-management/user.service';
import {PageTitleService} from '../../../shared/service/page-title.service';
import {ReadingSessionService} from '../../../shared/service/reading-session.service';
import {WakeLockService} from '../../../shared/service/wake-lock.service';
import {DjvuRenditionService} from '../shared/djvu-rendition.service';
import {ReaderFullscreenService} from '../shared/reader-fullscreen.service';
import {CbxPageDimensionService} from './core/cbx-page-dimension.service';
import {CbxFooterService} from './layout/footer/cbx-footer.service';
import {CbxHeaderService} from './layout/header/cbx-header.service';
import {CbxQuickSettingsService} from './layout/quick-settings/cbx-quick-settings.service';
import {CbxSidebarService} from './layout/sidebar/cbx-sidebar.service';
import {CbxReaderComponent} from './cbx-reader.component';

// NOTE(frontend-seam): The reader's interactive surface — image preloading, fullscreen,
// touch/mouse/keyboard events, and the header/sidebar/footer service graph — still needs seams
// before pagination and control wiring can be covered. What is covered here is the part that needs
// none of it: the route-driven startup decision, reached with the template overridden away so the
// child components never render.
describe('CbxReaderComponent', () => {

  const BOOK_ID = 42;

  let router: {navigate: ReturnType<typeof vi.fn>};
  let cbxReaderService: {
    getAvailablePages: ReturnType<typeof vi.fn>;
    getPageInfo: ReturnType<typeof vi.fn>;
    getPageImageUrl: ReturnType<typeof vi.fn>;
  };
  let djvuRenditionService: {isRenditionReady: ReturnType<typeof vi.fn>};

  /**
   * A stand-in for one of the reader's control services. The component talks to these constantly —
   * dozens of setters and event streams — and none of that is what these tests are about, so
   * anything not named here answers as a no-op and anything ending in `$` as an empty stream.
   * Only the members whose *return value* the component actually uses are given real ones.
   */
  function controlServiceStub(values: Record<string, unknown> = {}): Record<string, unknown> {
    return new Proxy({...values}, {
      get(target: Record<string, unknown>, property: string) {
        if (property in target) {
          return target[property];
        }
        return property.endsWith('$') ? EMPTY : vi.fn();
      },
      has: () => true,
    });
  }

  function setup(bookType: string, renditionReady = false): void {
    router = {navigate: vi.fn()};
    cbxReaderService = {
      getAvailablePages: vi.fn(() => of([1, 2, 3])),
      getPageInfo: vi.fn(() => of([])),
      getPageImageUrl: vi.fn(() => 'blob:page'),
    };
    djvuRenditionService = {isRenditionReady: vi.fn(() => of(renditionReady))};

    TestBed.configureTestingModule({
      imports: [CbxReaderComponent],
      providers: [
        {provide: Router, useValue: router},
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of({get: () => String(BOOK_ID)}),
            snapshot: {queryParamMap: {get: () => null}},
          },
        },
        {provide: CbxReaderService, useValue: cbxReaderService},
        {provide: DjvuRenditionService, useValue: djvuRenditionService},
        {
          provide: BookService,
          useValue: {
            fetchFreshBookDetail: vi.fn(() => Promise.resolve({
              id: BOOK_ID,
              fileName: 'scan.djvu',
              metadata: {title: 'Radio Magazine'},
              primaryFile: {id: 7, bookId: BOOK_ID, bookType},
            })),
            getBookSetting: vi.fn(() => of({})),
            getBooksInSeries: vi.fn(() => of([])),
            saveCbxProgress: vi.fn(),
            updateViewerSetting: vi.fn(),
          },
        },
        {
          provide: UserService,
          useValue: {
            getMyself: vi.fn(() => of({
              id: 1,
              userSettings: {
                perBookSetting: {cbx: 'Global'},
                cbxReaderSetting: {},
              },
            })),
          },
        },
        {
          provide: CbxPageDimensionService,
          useValue: {
            getPageDimensions: vi.fn(() => of([])),
            detectWebtoon: vi.fn(() => ({isWebtoon: false})),
          },
        },
        {provide: PageTitleService, useValue: {setBookPageTitle: vi.fn()}},
        {
          provide: ReadingSessionService,
          useValue: {
            startSession: vi.fn(),
            endSession: vi.fn(),
            updateProgress: vi.fn(),
            isSessionActive: vi.fn(() => false),
          },
        },
        {provide: WakeLockService, useValue: {enable: vi.fn(), disable: vi.fn()}},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: TranslocoService, useValue: {translate: vi.fn((key: string) => key)}},
        {provide: ReaderFullscreenService, useValue: {isFullscreen: () => false, toggle: vi.fn(), enter: vi.fn(), exit: vi.fn()}},
        {provide: ReaderPreferencesService, useValue: {updatePreference: vi.fn()}},
      ],
    });

    // These four are provided by the component itself, so a module-level provider cannot reach
    // them; overriding the component is the only way to keep the real ones out of the test.
    TestBed.overrideComponent(CbxReaderComponent, {
      set: {
        template: '',
        providers: [
          {provide: CbxHeaderService, useValue: controlServiceStub()},
          {
            provide: CbxSidebarService,
            useValue: controlServiceStub({
              bookmarks: () => [],
              notes: () => [],
              isPageBookmarked: () => false,
              pageHasNotes: () => false,
            }),
          },
          {provide: CbxFooterService, useValue: controlServiceStub({forceVisible: () => false})},
          {provide: CbxQuickSettingsService, useValue: controlServiceStub({visible: () => false})},
        ],
      },
    });

    const fixture = TestBed.createComponent(CbxReaderComponent);
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    // fetchFreshBookDetail is a promise, so startup needs a microtask turn or two to reach the
    // decision under test.
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('opens a DjVu book in the PDF reader once its searchable rendition exists', async () => {
    setup('DJVU', true);
    await settle();

    expect(router.navigate).toHaveBeenCalledWith([`/pdf-reader/book/${BOOK_ID}`]);
    // Handing the book to the richer reader means this one must not go on loading pages it will
    // never show.
    expect(cbxReaderService.getAvailablePages).not.toHaveBeenCalled();
  });

  it('reads a DjVu book here while its rendition is still being built', async () => {
    setup('DJVU', false);
    await settle();

    expect(router.navigate).not.toHaveBeenCalled();
    expect(cbxReaderService.getAvailablePages).toHaveBeenCalled();
  });

  it('never asks about a rendition for a comic archive', async () => {
    setup('CBX');
    await settle();

    expect(djvuRenditionService.isRenditionReady).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
