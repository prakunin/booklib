import {AfterViewInit, ChangeDetectionStrategy, Component, computed, CUSTOM_ELEMENTS_SCHEMA, DestroyRef, effect, ElementRef, HostListener, inject, OnInit, signal, ViewChild} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {forkJoin, from, Observable, of, throwError} from 'rxjs';
import {catchError, map, switchMap, tap} from 'rxjs/operators';
import {MessageService} from 'primeng/api';
import {ReaderLoaderService} from './core/loader.service';
import {ReaderViewManagerService} from './core/view-manager.service';
import {ReaderStateService} from './state/reader-state.service';
import {ReaderStyleService} from './core/style.service';
import {ReaderBookmarkService} from './features/bookmarks/bookmark.service';
import {ReaderAnnotationHttpService} from './features/annotations/annotation.service';
import {ReaderProgressService} from './state/progress.service';
import {ReaderSelectionService} from './features/selection/selection.service';
import {ReaderSidebarService} from './layout/sidebar/sidebar.service';
import {ReaderLeftSidebarService} from './layout/panel/panel.service';
import {ReaderHeaderService} from './layout/header/header.service';
import {ReaderNoteService} from './features/notes/note.service';
import {BookService} from '../../book/service/book.service';
import {BookFileService} from '../../book/service/book-file.service';
import {ActivatedRoute} from '@angular/router';
import {AdditionalFile, Book, BookType} from '../../book/model/book.model';
import {ReaderHeaderComponent} from './layout/header/header.component';
import {ReaderSidebarComponent} from './layout/sidebar/sidebar.component';
import {ReaderLeftSidebarComponent} from './layout/panel/panel.component';
import {ReaderNavbarComponent} from './layout/footer/footer.component';
import {ReaderSettingsDialogComponent} from './dialogs/settings-dialog.component';
import {ReaderQuickSettingsComponent} from './layout/header/quick-settings.component';
import {ReaderBookMetadataDialogComponent} from './dialogs/metadata-dialog.component';
import {ReaderHeaderFooterVisibilityManager} from './shared/visibility.util';
import {EpubCustomFontService} from './features/fonts/custom-font.service';
import {TextSelectionAction, TextSelectionPopupComponent} from './shared/selection-popup.component';
import {NoteDialogResult, ReaderNoteDialogComponent} from './dialogs/note-dialog.component';
import {ReaderFootnoteService} from './features/footnotes/footnote.service';
import {FootnotePopupComponent} from './shared/footnote-popup.component';
import {EbookShortcutsHelpComponent} from './dialogs/shortcuts-help.component';
import {TranslocoPipe} from '@jsverse/transloco';
import {RelocateProgressData} from './state/progress.service';
import {WakeLockService} from '../../../shared/service/wake-lock.service';
import {ViewEvent} from './core/view-manager.service';
import {ReaderFullscreenService} from '../shared/reader-fullscreen.service';

interface PendingInitialChapterRestore {
  href: string;
  contentSourceProgressPercent: number;
  attempts: number;
}

@Component({
  selector: 'app-ebook-reader',
  standalone: true,
  imports: [
    ReaderHeaderComponent,
    ReaderSettingsDialogComponent,
    ReaderQuickSettingsComponent,
    ReaderBookMetadataDialogComponent,
    ReaderSidebarComponent,
    ReaderLeftSidebarComponent,
    ReaderNavbarComponent,
    TextSelectionPopupComponent,
    FootnotePopupComponent,
    ReaderNoteDialogComponent,
    EbookShortcutsHelpComponent,
    TranslocoPipe
  ],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  providers: [
    MessageService,
    ReaderLoaderService,
    ReaderViewManagerService,
    ReaderStateService,
    ReaderStyleService,
    ReaderBookmarkService,
    ReaderAnnotationHttpService,
    ReaderProgressService,
    ReaderSelectionService,
    ReaderSidebarService,
    ReaderLeftSidebarService,
    ReaderHeaderService,
    ReaderNoteService,
    ReaderFootnoteService
  ],
  templateUrl: './ebook-reader.component.html',
  styleUrls: ['./ebook-reader.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EbookReaderComponent implements AfterViewInit, OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private static readonly MAX_CHAPTER_PROGRESS_PERCENT = 99.9;
  private static readonly INITIAL_CHAPTER_RESTORE_RETRY_MS = 100;
  private static readonly INITIAL_CHAPTER_RESTORE_MAX_ATTEMPTS = 15;
  private static readonly PINCH_PERCENT_HYSTERESIS = 0.65;
  private readonly loaderService = inject(ReaderLoaderService);
  private readonly styleService = inject(ReaderStyleService);
  private readonly bookService = inject(BookService);
  private readonly bookFileService = inject(BookFileService);
  private readonly route = inject(ActivatedRoute);
  private readonly epubCustomFontService = inject(EpubCustomFontService);
  private readonly annotationService = inject(ReaderAnnotationHttpService);
  private readonly progressService = inject(ReaderProgressService);
  private readonly selectionService = inject(ReaderSelectionService);
  private readonly headerService = inject(ReaderHeaderService);
  private readonly noteService = inject(ReaderNoteService);
  private readonly footnoteService = inject(ReaderFootnoteService);
  private readonly wakeLockService = inject(WakeLockService);
  private readonly messageService = inject(MessageService);
  private readonly fullscreenService = inject(ReaderFullscreenService);

  public sidebarService = inject(ReaderSidebarService);
  public leftSidebarService = inject(ReaderLeftSidebarService);
  public viewManager = inject(ReaderViewManagerService);
  public stateService = inject(ReaderStateService);

  @ViewChild('readerRoot', {static: true}) private readonly readerRoot?: ElementRef<HTMLElement>;
  @ViewChild(ReaderSettingsDialogComponent) private settingsDialog?: ReaderSettingsDialogComponent;

  protected bookId!: number;
  protected altBookType?: string;

  private hasLoadedOnce = false;
  private _fileUrl: string | null = null;
  private visibilityManager!: ReaderHeaderFooterVisibilityManager;
  private relocateTimeout?: ReturnType<typeof setTimeout>;
  private sectionFractionsTimeout?: ReturnType<typeof setTimeout>;
  private pendingInitialChapterRestore: PendingInitialChapterRestore | null = null;
  private pendingInitialChapterRestoreTimeout?: ReturnType<typeof setTimeout>;
  private fontSizePersistTimeout?: ReturnType<typeof setTimeout>;
  private sectionBoundaryRevealTimeout?: ReturnType<typeof setTimeout>;
  private pinchStartDistance: number | null = null;
  private pinchStartFontSize: number | null = null;
  private pinchPreviewScale = 1;
  private pinchStableZoomPercent: number | null = null;
  private pinchOverlayTimeout?: ReturnType<typeof setTimeout>;
  private touchListenerCleanups: (() => void)[] = [];
  private readonly pinchListenerTargets = new WeakSet<EventTarget>();
  private readonly readerTouchStartListener: EventListener = event => this.onReaderTouchStart(event as TouchEvent);
  private readonly readerTouchMoveListener: EventListener = event => this.onReaderTouchMove(event as TouchEvent);
  private readonly readerTouchEndListener: EventListener = event => this.onReaderTouchEnd(event as TouchEvent);
  private readonly readerTouchCancelListener: EventListener = event => this.onReaderTouchCancel(event as TouchEvent);
  private readonly readerGesturePreventListener = (event: Event) => this.preventReaderGesture(event);

  isLoading = signal(true);
  showQuickSettings = signal(false);
  showControls = signal(false);
  showMetadata = signal(false);
  forceNavbarVisible = signal(false);
  private readonly sectionBoundaryControlsVisible = signal(false);
  book = signal<Book | null>(null);
  sectionFractions = signal<number[]>([]);
  isFullscreen = signal(false);
  showShortcutsHelp = signal(false);
  immersiveMode = signal(false);
  private immersiveAutoHideTimer?: ReturnType<typeof setTimeout>;
  protected progressData = signal<RelocateProgressData | null>(null);
  protected pinchZoomPercent = signal<number | null>(null);
  protected pinchTransform = signal('none');
  protected pinchTransformOrigin = signal('50% 50%');

  readonly readerState = this.stateService.state;
  readonly readerBackground = computed(() => this.styleService.getAdjustedBackgroundColor(this.readerState()));
  readonly footnoteBackground = computed(() => this.styleService.getBaseBackgroundColor(this.readerState()));
  readonly footnoteForeground = computed(() => this.styleService.getForegroundColor(this.readerState()));
  readonly footnoteIsDark = computed(() => this.readerState().isDark);
  readonly selectionState = this.selectionService.state;
  readonly footnoteState = this.footnoteService.state;
  readonly noteDialogState = this.noteService.dialogState;
  readonly isCurrentCfiBookmarked = this.headerService.isCurrentCfiBookmarked;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.persistPendingFontSize();
      this.wakeLockService.disable();
      this.viewManager.destroy();
      this.annotationService.reset();
      this.progressService.endSession();
      this.progressService.reset();
      this.selectionService.reset();
      this.sidebarService.reset();
      this.leftSidebarService.reset();
      this.headerService.reset();
      this.noteService.reset();
      this.epubCustomFontService.cleanup();

      if (this.immersiveAutoHideTimer) clearTimeout(this.immersiveAutoHideTimer);
      if (this.relocateTimeout) clearTimeout(this.relocateTimeout);
      if (this.sectionFractionsTimeout) clearTimeout(this.sectionFractionsTimeout);
      if (this.pendingInitialChapterRestoreTimeout) clearTimeout(this.pendingInitialChapterRestoreTimeout);
      if (this.pinchOverlayTimeout) clearTimeout(this.pinchOverlayTimeout);
      if (this.sectionBoundaryRevealTimeout) clearTimeout(this.sectionBoundaryRevealTimeout);
      this.touchListenerCleanups.forEach(cleanup => cleanup());
      this.touchListenerCleanups = [];

      if (this._fileUrl) {
        URL.revokeObjectURL(this._fileUrl);
        this._fileUrl = null;
      }
    });

    effect(() => {
      this.stateService.state();
      this.applyStyles();
    });

    effect(
      () => {
        this.sidebarService.bookmarks();
        this.updateBookmarkIndicator();
      },
      {allowSignalWrites: true}
    );
  }

  ngAfterViewInit(): void {
    this.bindReaderTouchListeners();
  }

  ngOnInit() {
    this.visibilityManager = new ReaderHeaderFooterVisibilityManager(window.innerHeight);
    this.visibilityManager.onStateChange((state) => {
      this.applyChromeVisibility(state.footerVisible);
    });
    this.refreshChromeVisibility();

    this.sidebarService.showMetadata$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.showMetadata.set(true));

    this.headerService.showControls$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.showQuickSettings.set(true));

    this.headerService.showMetadata$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.showMetadata.set(true));

    this.headerService.toggleFullscreen$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.toggleFullscreen());

    this.headerService.showShortcutsHelp$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.showShortcutsHelp.set(true));

    // Enable wake lock after a short delay
    setTimeout(() => this.wakeLockService.enable(), 1000);

    this.isLoading.set(true);

    this.bookId = +this.route.snapshot.paramMap.get('bookId')!;
    this.altBookType = this.route.snapshot.queryParamMap.get('bookType') ?? undefined;

    // Parallelize Foliate script loading and initial book detail fetch
    forkJoin([
      this.initializeFoliate(),
      from(this.bookService.fetchFreshBookDetail(this.bookId, false))
    ]).pipe(
      switchMap(([, book]) => {
        this.book.set(book);
        const bookType = (this.altBookType as BookType | undefined) ?? book.primaryFile?.bookType;
        if (!bookType) {
          return throwError(() => new Error('Book type not found'));
        }

        let bookFileId: number | undefined;
        if (this.altBookType) {
          const altFile = book.alternativeFormats?.find((f: AdditionalFile) => f.bookType === this.altBookType);
          bookFileId = altFile?.id;
        } else {
          bookFileId = book.primaryFile?.id;
        }

        // Parallelize font loading/view setup and state initialization
        return forkJoin([
          this.epubCustomFontService.loadAndCacheFonts().pipe(
            tap(() => this.stateService.refreshCustomFonts())
          ),
          this.setupView().pipe(
            tap(() => this.subscribeToViewEvents())
          ),
          this.stateService.initializeState(this.bookId, bookFileId!).pipe(
            map(() => ({book, bookType, bookFileId}))
          )
        ]);
      }),
      switchMap(([, , {book, bookType, bookFileId}]) => {
        this.progressService.initialize(this.bookId, bookType, bookFileId);
        this.selectionService.initialize(this.bookId);
        this.headerService.initialize(this.bookId, book.metadata?.title || '');

        const useStreaming = this.route.snapshot.queryParamMap.get('streaming') !== 'false';
        const loadBook$ = bookType === 'EPUB' && useStreaming
          ? this.viewManager.loadEpubStreaming(this.bookId, this.altBookType)
          : this.loadBookBlob(bookType);

        return loadBook$.pipe(
          tap(() => {
            this.applyStyles();
            this.sidebarService.initialize(this.bookId, book);
            this.leftSidebarService.initialize(this.bookId);
            this.noteService.initialize(this.bookId);
          }),
          switchMap(() => this.viewManager.getMetadata()),
          switchMap(() => {
            if (!this.hasLoadedOnce) {
              this.hasLoadedOnce = true;
              return this.restoreSavedPosition(book);
            }
            return of(undefined);
          })
        );
      }),
      tap(() => this.isLoading.set(false)),
      catchError((err) => {
        console.error('Failed to load ebook', err);
        this.isLoading.set(false);
        this.messageService.add({severity: 'error', summary: 'Error', detail: 'Failed to load book'});
        return of(null);
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe();
  }

  private initializeFoliate(): Observable<void> {
    return this.loaderService.loadFoliateScript().pipe(
      switchMap(() => this.loaderService.waitForCustomElement())
    );
  }

  private setupView(): Observable<void> {
    const container = document.getElementById('foliate-container');
    if (!container) {
      return throwError(() => new Error('Container not found'));
    }
    container.setAttribute('tabindex', '0');
    this.viewManager.createView(container);
    this.footnoteService.register();
    return of(undefined);
  }
  private loadBookBlob(bookType: BookType): Observable<void> {
    return this.bookFileService.getFileContent(this.bookId, this.altBookType).pipe(
      switchMap(fileBlob => {
        const fileUrl = URL.createObjectURL(fileBlob);
        this._fileUrl = fileUrl;
        return this.viewManager.loadEpub(fileUrl, bookType);
      })
    );
  }

  private subscribeToViewEvents(): void {
    this.viewManager.events$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event: ViewEvent) => {
        switch (event.type) {
          case 'load':
            this.applyStyles();
            if (event.detail?.doc) {
              this.bindReaderDocumentTouchListeners(event.detail.doc);
            }
            this.sidebarService.updateChapters();
            this.updateSectionFractions();
            break;
          case 'relocate':
            this.updateSectionBoundaryChrome(event.detail);
            if (this.handlePendingInitialChapterRestore(event.detail)) {
              break;
            }

            if (this.relocateTimeout) clearTimeout(this.relocateTimeout);
            this.relocateTimeout = setTimeout(() => {
              this.handleRelocateProgress(event.detail);
            }, 100);

            if (this.sectionFractionsTimeout) clearTimeout(this.sectionFractionsTimeout);
            this.sectionFractionsTimeout = setTimeout(() => {
              this.updateSectionFractions();
            }, 500);
            break;
          case 'middle-single-tap':
            if (this.immersiveMode()) {
              this.immersiveTemporaryShow();
            }
            break;
          case 'text-selected':
            this.selectionService.handleTextSelected(event.detail, event.popupPosition);
            break;
          case 'toggle-fullscreen':
            this.toggleFullscreen();
            break;
          case 'toggle-shortcuts-help':
            this.showShortcutsHelp.update(v => !v);
            break;
          case 'toggle-immersive':
            this.toggleImmersiveMode();
            break;
          case 'change-font-size':
            this.changeFontSize(event.delta);
            break;
          case 'go-first-section':
            this.viewManager.goToSection(0).pipe(catchError(() => of(undefined))).subscribe();
            break;
          case 'go-last-section': {
            const s = this.progressService.currentProgressData?.section;
            if (s && s.total > 0) {
              this.viewManager.goToSection(s.total - 1).pipe(catchError(() => of(undefined))).subscribe();
            }
            break;
          }
          case 'toggle-toc':
            this.sidebarService.toggle('chapters');
            break;
          case 'toggle-search':
            this.leftSidebarService.toggle('search');
            break;
          case 'toggle-notes':
            this.leftSidebarService.toggle('notes');
            break;
          case 'escape-pressed':
            if (this.footnoteState().visible) {
              this.footnoteService.close();
            } else if (this.showShortcutsHelp()) {
              this.showShortcutsHelp.set(false);
            } else if (this.noteDialogState().visible) {
              this.noteService.closeDialog();
            } else if (this.showControls()) {
              if (this.settingsDialog) {
                this.settingsDialog.cancel();
              } else {
                this.showControls.set(false);
              }
            } else if (this.showQuickSettings()) {
              this.showQuickSettings.set(false);
            } else if (this.showMetadata()) {
              this.showMetadata.set(false);
            } else if (this.isFullscreen()) {
              this.exitFullscreen();
            }
            break;
        }
      });
  }

  private updateSectionFractions(): void {
    this.sectionFractions.set(this.viewManager.getSectionFractions());
  }

  private restoreSavedPosition(book: Book): Observable<void> {
    this.pendingInitialChapterRestore = null;

    const requestedCfi = this.route.snapshot.queryParamMap.get('cfi');
    if (requestedCfi) {
      return this.viewManager.goToAnnotation(requestedCfi);
    }

    const progress = book.epubProgress;

    if (progress?.cfi) {
      return this.viewManager.goTo(progress.cfi);
    }

    if (progress?.href) {
      const chapterProgress = progress.contentSourceProgressPercent;
      if (typeof chapterProgress === 'number' && Number.isFinite(chapterProgress) && chapterProgress > 0) {
        this.pendingInitialChapterRestore = {
          href: this.normalizeHref(progress.href),
          contentSourceProgressPercent: chapterProgress,
          attempts: 0
        };
      }
      return this.viewManager.goTo(progress.href);
    }

    if (progress?.percentage && progress.percentage > 0) {
      return this.viewManager.goToFraction(progress.percentage / 100);
    }

    return this.viewManager.goTo(0);
  }

  private handlePendingInitialChapterRestore(detail: RelocateProgressData): boolean {
    const pendingRestore = this.pendingInitialChapterRestore;
    if (!pendingRestore) {
      return false;
    }

    const currentHref = this.normalizeHref(detail.pageItem?.href ?? detail.tocItem?.href ?? null);
    if (!currentHref) {
      return this.retryInitialRestore(detail);
    }
    if (!this.hrefsMatch(currentHref, pendingRestore.href)) {
      return this.retryInitialRestore(detail);
    }

    const targetFraction = this.resolveChapterFraction(
      detail.section?.current,
      pendingRestore.contentSourceProgressPercent
    );

    if (targetFraction === null) {
      return this.retryInitialRestore(detail);
    }

    if (typeof detail.fraction === 'number' && Math.abs(detail.fraction - targetFraction) < 0.0001) {
      this.pendingInitialChapterRestore = null;
      return false;
    }

    this.pendingInitialChapterRestore = null;
    if (this.pendingInitialChapterRestoreTimeout) {
      clearTimeout(this.pendingInitialChapterRestoreTimeout);
      this.pendingInitialChapterRestoreTimeout = undefined;
    }
    this.viewManager.goToFraction(targetFraction)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe();
    return true;
  }

  private retryInitialRestore(detail: RelocateProgressData): boolean {
    const pendingRestore = this.pendingInitialChapterRestore;
    if (!pendingRestore) {
      return false;
    }

    if (pendingRestore.attempts >= EbookReaderComponent.INITIAL_CHAPTER_RESTORE_MAX_ATTEMPTS) {
      this.pendingInitialChapterRestore = null;
      this.handleRelocateProgress(detail);
      return true;
    }

    pendingRestore.attempts += 1;
    if (this.pendingInitialChapterRestoreTimeout) clearTimeout(this.pendingInitialChapterRestoreTimeout);
    this.pendingInitialChapterRestoreTimeout = setTimeout(() => {
      this.updateSectionFractions();
      this.handlePendingInitialChapterRestore(detail);
    }, EbookReaderComponent.INITIAL_CHAPTER_RESTORE_RETRY_MS);
    return true;
  }

  private handleRelocateProgress(detail: RelocateProgressData): void {
    this.progressService.handleRelocateEvent(detail);
    this.progressData.set(this.progressService.currentProgressData);
    this.updateBookmarkIndicator();
  }

  private updateSectionBoundaryChrome(detail: RelocateProgressData): void {
    if (this.readerState().flow === 'paginated') {
      this.hideSectionBoundaryChrome();
      return;
    }

    const sectionEndFraction = detail.sectionEndFraction ?? detail.sectionFraction;
    if (typeof sectionEndFraction !== 'number') {
      if (detail.reason !== 'resize') {
        this.hideSectionBoundaryChrome();
      }
      return;
    }

    const nearSectionEnd = sectionEndFraction >= 0.96;
    if (!nearSectionEnd) {
      if (detail.reason !== 'resize') {
        this.hideSectionBoundaryChrome();
      }
      return;
    }

    this.sectionBoundaryControlsVisible.set(true);
    this.refreshChromeVisibility();
    if (this.sectionBoundaryRevealTimeout) clearTimeout(this.sectionBoundaryRevealTimeout);
    this.sectionBoundaryRevealTimeout = setTimeout(() => {
      this.sectionBoundaryRevealTimeout = undefined;
      this.hideSectionBoundaryChrome();
    }, 4000);
  }

  private hideSectionBoundaryChrome(): void {
    if (this.sectionBoundaryRevealTimeout) {
      clearTimeout(this.sectionBoundaryRevealTimeout);
      this.sectionBoundaryRevealTimeout = undefined;
    }
    if (!this.sectionBoundaryControlsVisible()) return;
    this.sectionBoundaryControlsVisible.set(false);
    this.refreshChromeVisibility();
  }

  private refreshChromeVisibility(): void {
    const state = this.visibilityManager.getVisibilityState();
    this.applyChromeVisibility(state.footerVisible);
  }

  private applyChromeVisibility(footerVisible: boolean): void {
    const visible = this.sectionBoundaryControlsVisible();
    this.forceNavbarVisible.set(footerVisible || visible);
  }

  private resolveChapterFraction(sectionIndex: number | undefined, chapterProgressPercent: number): number | null {
    if (sectionIndex === undefined) {
      return null;
    }

    const sectionFractions = this.viewManager.getSectionFractions();
    const start = sectionFractions[sectionIndex];
    const end = sectionFractions[sectionIndex + 1];

    if (start === undefined || end === undefined || end <= start) {
      return null;
    }

    const normalizedProgress = Math.min(
      Math.max(chapterProgressPercent, 0),
      EbookReaderComponent.MAX_CHAPTER_PROGRESS_PERCENT
    ) / 100;

    return start + ((end - start) * normalizedProgress);
  }

  private normalizeHref(href: string | null | undefined): string {
    return (href ?? '').split('#')[0].replace(/^(\.\/|\/)+/, '');
  }

  private hrefsMatch(leftHref: string, rightHref: string): boolean {
    return leftHref === rightHref
      || leftHref.endsWith(`/${rightHref}`)
      || rightHref.endsWith(`/${leftHref}`);
  }

  private updateBookmarkIndicator(): void {
    const currentCfi = this.progressService.currentCfi;
    const isBookmarked = currentCfi
      ? this.sidebarService.bookmarks().some(bookmark => bookmark.cfi === currentCfi)
      : false;
    this.headerService.setCurrentCfiBookmarked(isBookmarked);
  }

  private applyStyles(): void {
    const state = this.stateService.state();
    this.viewManager.setFlow(state.flow);
    const renderer = this.viewManager.getRenderer();
    if (renderer) {
      this.styleService.applyStylesToRenderer(renderer, state);
      const rendererElement = renderer as unknown as HTMLElement;
      const isSwitchingToContinuous = state.flow === 'scrolled'
        && rendererElement.localName !== 'foliate-continuous-scroller';
      if (state.flow && !isSwitchingToContinuous) {
        renderer.setAttribute?.('flow', state.flow);
      }
    }
  }

  @HostListener('document:fullscreenchange')
  onFullscreenChange(): void {
    this.isFullscreen.set(this.fullscreenService.isFullscreen());
    this.headerService.setFullscreen(this.isFullscreen());
  }

  toggleFullscreen(): void {
    if (this.fullscreenService.isFullscreen()) {
      this.exitFullscreen();
    } else {
      this.enterFullscreen();
    }
  }

  private enterFullscreen(): void {
    this.fullscreenService.enter();
  }

  private exitFullscreen(): void {
    this.fullscreenService.exit();
  }

  onProgressChange(fraction: number): void {
    this.viewManager.goToFraction(fraction)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => of(undefined))
      )
      .subscribe();
  }

  private changeFontSize(delta: number): void {
    this.stateService.updateFontSize(delta);
    this.scheduleFontSizePersist();
  }

  private bindReaderTouchListeners(): void {
    const reader = this.readerRoot?.nativeElement;
    if (!reader) {
      return;
    }

    this.bindPinchListeners(reader);
  }

  private bindReaderDocumentTouchListeners(doc: Document): void {
    this.bindPinchListeners(doc);
    doc.documentElement?.style.setProperty('touch-action', 'none', 'important');
    doc.body?.style.setProperty('touch-action', 'none', 'important');
  }

  private bindPinchListeners(target: EventTarget): void {
    if (this.pinchListenerTargets.has(target)) {
      return;
    }
    this.pinchListenerTargets.add(target);

    const options: AddEventListenerOptions = {passive: false};
    target.addEventListener('touchstart', this.readerTouchStartListener, options);
    target.addEventListener('touchmove', this.readerTouchMoveListener, options);
    target.addEventListener('touchend', this.readerTouchEndListener, options);
    target.addEventListener('touchcancel', this.readerTouchCancelListener, options);
    target.addEventListener('gesturestart', this.readerGesturePreventListener, options);
    target.addEventListener('gesturechange', this.readerGesturePreventListener, options);
    target.addEventListener('gestureend', this.readerGesturePreventListener, options);
    this.touchListenerCleanups.push(
      () => target.removeEventListener('touchstart', this.readerTouchStartListener, options),
      () => target.removeEventListener('touchmove', this.readerTouchMoveListener, options),
      () => target.removeEventListener('touchend', this.readerTouchEndListener, options),
      () => target.removeEventListener('touchcancel', this.readerTouchCancelListener, options),
      () => target.removeEventListener('gesturestart', this.readerGesturePreventListener, options),
      () => target.removeEventListener('gesturechange', this.readerGesturePreventListener, options),
      () => target.removeEventListener('gestureend', this.readerGesturePreventListener, options),
    );
  }

  onReaderTouchStart(event: TouchEvent): void {
    if (event.touches.length !== 2 || !this.canHandleReaderPinch(event)) {
      this.resetPinchState();
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    this.pinchStartDistance = this.getTouchDistance(event.touches);
    this.pinchStartFontSize = this.readerState().fontSize;
    this.pinchPreviewScale = 1;
    this.pinchStableZoomPercent = 100;
    this.updatePinchTransform(event, 1);
    this.pinchZoomPercent.set(100);
    if (this.pinchOverlayTimeout) clearTimeout(this.pinchOverlayTimeout);
  }

  onReaderTouchMove(event: TouchEvent): void {
    if (event.touches.length !== 2 || this.pinchStartDistance === null || this.pinchStartFontSize === null) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    const distance = this.getTouchDistance(event.touches);
    if (this.pinchStartDistance <= 0 || distance <= 0) {
      return;
    }

    const scale = this.stabilizePinchScale(
      this.clampPinchScale(distance / this.pinchStartDistance, this.pinchStartFontSize)
    );
    this.pinchPreviewScale = scale;
    this.updatePinchTransform(event, scale);
    this.pinchZoomPercent.set(Math.round(scale * 100));
    if (this.pinchOverlayTimeout) clearTimeout(this.pinchOverlayTimeout);
  }

  onReaderTouchEnd(event: TouchEvent): void {
    if (this.pinchStartDistance !== null) {
      event.preventDefault();
      event.stopPropagation();
      this.commitPinchFontSize();
      this.schedulePinchOverlayDismiss();
    }
    this.resetPinchState();
  }

  onReaderTouchCancel(event: TouchEvent): void {
    if (this.pinchStartDistance !== null) {
      event.preventDefault();
      event.stopPropagation();
    }
    this.pinchZoomPercent.set(null);
    this.resetPinchState();
  }

  private preventReaderGesture(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
  }

  private scheduleFontSizePersist(): void {
    if (this.fontSizePersistTimeout) clearTimeout(this.fontSizePersistTimeout);
    this.fontSizePersistTimeout = setTimeout(() => {
      this.stateService.persistSettings(this.bookId);
      this.fontSizePersistTimeout = undefined;
    }, 250);
  }

  private canHandleReaderPinch(event: TouchEvent): boolean {
    if (this.showControls() || this.showQuickSettings() || this.showMetadata() || this.showShortcutsHelp()) {
      return false;
    }

    if (this.noteDialogState().visible || this.selectionState().visible) {
      return false;
    }

    const target = event.target;
    if (!(target instanceof Element)) {
      return true;
    }

    return !target.closest('button, input, select, textarea, a, [role="button"]');
  }

  private getTouchDistance(touches: TouchList): number {
    const first = touches.item(0);
    const second = touches.item(1);
    if (!first || !second) {
      return 0;
    }

    return Math.hypot(first.clientX - second.clientX, first.clientY - second.clientY);
  }

  private schedulePinchOverlayDismiss(): void {
    if (this.pinchOverlayTimeout) clearTimeout(this.pinchOverlayTimeout);
    this.pinchOverlayTimeout = setTimeout(() => {
      this.pinchZoomPercent.set(null);
      this.pinchOverlayTimeout = undefined;
    }, 700);
  }

  private resetPinchState(): void {
    this.pinchStartDistance = null;
    this.pinchStartFontSize = null;
    this.pinchPreviewScale = 1;
    this.pinchStableZoomPercent = null;
    this.pinchTransform.set('none');
    this.pinchTransformOrigin.set('50% 50%');
  }

  private commitPinchFontSize(): void {
    if (this.pinchStartFontSize === null) return;

    const nextFontSize = Math.round(this.pinchStartFontSize * this.pinchPreviewScale);
    if (nextFontSize === this.readerState().fontSize) return;

    this.stateService.setFontSize(nextFontSize);
    this.scheduleFontSizePersist();
  }

  private clampPinchScale(scale: number, startFontSize: number): number {
    if (!Number.isFinite(scale) || scale <= 0) return 1;

    const minScale = 10 / startFontSize;
    const maxScale = 40 / startFontSize;
    return Math.max(minScale, Math.min(maxScale, scale));
  }

  private stabilizePinchScale(scale: number): number {
    const percent = scale * 100;
    const current = this.pinchStableZoomPercent ?? Math.round(percent);
    const rounded = Math.round(percent);

    let stablePercent = current;
    if (rounded > current && percent >= current + EbookReaderComponent.PINCH_PERCENT_HYSTERESIS) {
      stablePercent = rounded;
    } else if (rounded < current && percent <= current - EbookReaderComponent.PINCH_PERCENT_HYSTERESIS) {
      stablePercent = rounded;
    }

    this.pinchStableZoomPercent = stablePercent;
    return stablePercent / 100;
  }

  private updatePinchTransform(event: TouchEvent, scale: number): void {
    const center = this.getTouchCenterInReader(event);
    if (center) {
      this.pinchTransformOrigin.set(`${Math.round(center.x)}px ${Math.round(center.y)}px`);
    }
    this.pinchTransform.set(`scale(${scale.toFixed(3)})`);
  }

  private getTouchCenterInReader(event: TouchEvent): { x: number; y: number } | null {
    const first = event.touches.item(0);
    const second = event.touches.item(1);
    const container = document.getElementById('foliate-container');
    if (!first || !second || !container) return null;

    let x = (first.clientX + second.clientX) / 2;
    let y = (first.clientY + second.clientY) / 2;
    const eventDoc = this.getTouchEventDocument(event);
    const iframe = eventDoc && eventDoc !== document
      ? eventDoc.defaultView?.frameElement
      : null;
    if (iframe instanceof HTMLIFrameElement) {
      const iframeRect = iframe.getBoundingClientRect();
      x += iframeRect.left;
      y += iframeRect.top;
    }

    const readerRect = container.getBoundingClientRect();
    return {
      x: Math.max(0, Math.min(readerRect.width, x - readerRect.left)),
      y: Math.max(0, Math.min(readerRect.height, y - readerRect.top)),
    };
  }

  private getTouchEventDocument(event: TouchEvent): Document | null {
    if (event.target instanceof Node) {
      return event.target.ownerDocument;
    }
    if (event.currentTarget instanceof Document) {
      return event.currentTarget;
    }
    return null;
  }

  private persistPendingFontSize(): void {
    if (!this.fontSizePersistTimeout) return;

    clearTimeout(this.fontSizePersistTimeout);
    this.fontSizePersistTimeout = undefined;
    if (Number.isFinite(this.bookId)) {
      this.stateService.persistSettings(this.bookId);
    }
  }

  goToPreviousChapter(): void {
    const section = this.progressData()?.section;
    if (section && section.current > 0) {
      this.viewManager.goToSection(section.current - 1)
        .pipe(takeUntilDestroyed(this.destroyRef), catchError(() => of(undefined)))
        .subscribe();
    }
  }

  goToNextChapter(): void {
    const section = this.progressData()?.section;
    if (section && section.current < section.total - 1) {
      this.viewManager.goToSection(section.current + 1)
        .pipe(takeUntilDestroyed(this.destroyRef), catchError(() => of(undefined)))
        .subscribe();
    }
  }

  canGoToPreviousChapter(): boolean {
    const section = this.progressData()?.section;
    return !!section && section.current > 0;
  }

  canGoToNextChapter(): boolean {
    const section = this.progressData()?.section;
    return !!section && section.current < section.total - 1;
  }

  @HostListener('document:mousemove', ['$event'])
  onMouseMove(event: MouseEvent): void {
    this.visibilityManager.handleMouseMove(event.clientY);
  }

  @HostListener('document:mouseleave')
  onMouseLeave(): void {
    this.visibilityManager.handleMouseLeave();
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    this.visibilityManager.updateWindowHeight(window.innerHeight);
    this.applyStyles();
  }

  onFooterTriggerZoneEnter(): void {
    this.visibilityManager.handleFooterZoneEnter();
  }

  onFooterHovered(hovered: boolean): void {
    this.visibilityManager.setFooterHovered(hovered);
  }

  toggleImmersiveMode(): void {
    const newValue = !this.immersiveMode();
    this.immersiveMode.set(newValue);
    this.visibilityManager.setImmersive(newValue);
  }

  private immersiveTemporaryShow(): void {
    if (!this.immersiveMode()) return;
    this.visibilityManager.temporaryShow();
    if (this.immersiveAutoHideTimer) clearTimeout(this.immersiveAutoHideTimer);
    this.immersiveAutoHideTimer = setTimeout(() => {
      this.visibilityManager.hideTemporary();
    }, 3000);
  }

  onFootnoteDismissed(): void {
    this.footnoteService.close();
  }

  onFootnoteOpenFull(): void {
    this.footnoteService.openFull();
  }

  handleSelectionAction(action: TextSelectionAction): void {
    if (action.type === 'note') {
      this.noteService.openNewNoteDialog();
    } else if (action.type === 'go-to-link') {
      const linkUrl = this.selectionState().linkUrl;
      if (linkUrl) {
        this.viewManager.goTo(linkUrl)
          .pipe(
            takeUntilDestroyed(this.destroyRef),
            catchError(() => of(undefined))
          )
          .subscribe();
      }
      this.selectionService.handleAction(action);
    } else {
      this.selectionService.handleAction(action);
    }
  }

  onNoteSave(result: NoteDialogResult): void {
    this.noteService.saveNote(result);
  }

  onNoteCancel(): void {
    this.noteService.closeDialog();
  }
}
