import {inject, Injectable} from '@angular/core';
import {defer, from, Observable, of, throwError, timer} from 'rxjs';
import {catchError, map, switchMap} from 'rxjs/operators';
import {ReaderAnnotationService, Annotation} from '../features/annotations/annotation-renderer.service';
import {ReaderEventService, ViewEvent, TextSelection} from './event.service';
import {PageInfo, ThemeInfo, PageDecorator} from '../shared/header-footer.util';
import {EpubStreamingService, EpubBookInfo} from './epub-streaming.service';
import {ReaderFlow} from '../state/reader-state.service';

export type {ViewEvent, TextSelection} from './event.service';
export type {PageInfo, ThemeInfo} from '../shared/header-footer.util';

interface FoliateTocItem {
  label: string;
  href: string;
  subitems?: FoliateTocItem[];
}

interface TocItem {
  label: string;
  href: string;
  subitems?: TocItem[];
}

export interface BookMetadata {
  title?: string;
  authors?: string[];
  language?: string;
  publisher?: string;
  description?: string;
  identifier?: string;
  coverUrl?: string | null;

  [key: string]: unknown;
}

interface RendererContent {
  index: number;
  doc: Document;
}

export interface FoliateRenderer {
  heads?: HTMLElement[];
  feet?: HTMLElement[];
  getContents(): RendererContent[];
  setAttribute(name: string, value: string | number): void;
  removeAttribute(name: string): void;
  setStyles?(css: string): void;
}

export interface FoliateResolvedTarget {
  index: number;
  anchor?: (doc: Document) => Element | null;
}

export interface FoliateSection {
  linear?: string;

  createDocument?(): Document | Promise<Document>;
}

export type ReaderLinkHandler = (anchor: HTMLAnchorElement, href: string) => boolean;

interface FoliateBook {
  toc?: FoliateTocItem[];
  metadata?: BookMetadata;
  sections?: FoliateSection[];

  getCover?(): Promise<Blob | null> | null;

  resolveHref?(href: string): FoliateResolvedTarget | null;
}

interface FoliateSearchSubitem {
  cfi: string;
  excerpt: {
    pre: string;
    match: string;
    post: string;
  };
}

interface FoliateSearchProgress {
  progress: number;
}

interface FoliateSearchSectionResult {
  label?: string;
  subitems?: FoliateSearchSubitem[];
}

type FoliateSearchResult = FoliateSearchProgress | FoliateSearchSectionResult | 'done';

interface FoliateViewElement extends HTMLElement {
  renderer?: FoliateRenderer | null;
  book?: FoliateBook;
  open(target: File | object): Promise<void>;
  goTo(target: string | number): Promise<void>;
  goToFraction(fraction: number): Promise<void>;
  prev(distance?: number): void;
  next(distance?: number): void;
  getCFI(index: number, range: Range): string | null;
  deselect(): void;
  addAnnotation(annotation: { value: string }): void;
  deleteAnnotation(annotation: { value: string }): Promise<void>;
  showAnnotation(annotation: { value: string }): Promise<void>;
  getSectionFractions?(): number[];
  search?(opts: { query: string; matchCase?: boolean; matchWholeWords?: boolean }): AsyncGenerator<FoliateSearchResult>;
  clearSearch?(): void;
}

interface StreamingBookFactoryWindow extends Window {
  makeStreamingBook?: (
    bookId: number,
    baseUrl: string,
    bookInfo: EpubBookInfo,
    authToken: string | null,
    bookType?: string
  ) => Promise<object>;
}

@Injectable({
  providedIn: 'root'
})
export class ReaderViewManagerService {
  private readonly annotationService = inject(ReaderAnnotationService);
  private readonly eventService = inject(ReaderEventService);
  private readonly epubStreamingService = inject(EpubStreamingService);
  private view: FoliateViewElement | null = null;
  private linkHandler: ReaderLinkHandler | null = null;

  public get events$(): Observable<ViewEvent> {
    return this.eventService.events$;
  }

  createView(container: HTMLElement): void {
    this.view = document.createElement('foliate-view') as FoliateViewElement;
    this.view.style.width = '100%';
    this.view.style.height = '100%';
    this.view.style.display = 'block';
    container.appendChild(this.view);

    this.eventService.initialize(this.view, {
      prev: () => this.prev(),
      next: () => this.next(),
      getCFI: (index: number, range: Range) => this.view?.getCFI(index, range) ?? null,
      getContents: () => this.view?.renderer?.getContents() ?? null,
      onLink: (anchor: HTMLAnchorElement, href: string) => this.linkHandler?.(anchor, href) ?? false
    });
  }

  setLinkHandler(handler: ReaderLinkHandler | null): void {
    this.linkHandler = handler;
  }

  resolveHref(href: string): FoliateResolvedTarget | null {
    const book = this.view?.book;
    if (!book?.resolveHref) return null;
    try {
      return book.resolveHref(href) ?? null;
    } catch {
      return null;
    }
  }

  getSection(index: number): FoliateSection | null {
    return this.view?.book?.sections?.[index] ?? null;
  }

  loadEpub(epubPath: string, bookType: string = 'EPUB'): Observable<void> {
    if (!this.view) {
      return throwError(() => new Error('View not created'));
    }

    const view = this.view;
    return timer(100).pipe(
      switchMap(() => from(fetch(epubPath))),
      switchMap(response => {
        if (!response.ok) {
          throw new Error(`EPUB not found: ${response.status}`);
        }
        return from(response.blob());
      }),
      switchMap(blob => {
        const isFb2 = bookType.toUpperCase() === 'FB2';
        const file = new File([blob], isFb2 ? 'book.fb2' : (epubPath.split('/').pop() || 'book.epub'), {
          type: isFb2 ? 'application/x-fictionbook+xml' : 'application/epub+zip'
        });
        return from(view.open(file));
      }),
      map(() => undefined),
      catchError(err => throwError(() => err))
    );
  }

  loadEpubStreaming(bookId: number, bookType?: string): Observable<void> {
    if (!this.view) {
      return throwError(() => new Error('View not created'));
    }

    return this.epubStreamingService.getBookInfo(bookId, bookType).pipe(
      switchMap(bookInfo => from(this.openStreamingBook(bookId, bookInfo, bookType))),
      map(() => undefined),
      catchError(err => throwError(() => err))
    );
  }

  private async openStreamingBook(bookId: number, bookInfo: EpubBookInfo, bookType?: string): Promise<void> {
    const makeStreamingBook = (globalThis.window as StreamingBookFactoryWindow).makeStreamingBook;
    if (!makeStreamingBook) {
      throw new Error('makeStreamingBook not available - Foliate script may not be loaded');
    }
    const baseUrl = this.epubStreamingService.getBaseUrl();
    const authToken = this.epubStreamingService.getAuthToken();
    const book = await makeStreamingBook(bookId, baseUrl, bookInfo, authToken, bookType);
    const view = this.view;
    if (!view) {
      throw new Error('View not created');
    }
    await view.open(book);
  }

  destroy(): void {
    this.linkHandler = null;
    this.eventService.destroy();
    this.view?.remove();
    this.view = null;
  }

  goTo(target?: string | number | null): Observable<void> {
    const resolvedTarget = target ?? 0;
    if (!this.view) {
      return of(undefined);
    }
    const view = this.view;
    return defer(() =>
      from(view.goTo(resolvedTarget))
    ).pipe(
      map(() => undefined)
    );
  }

  goToAnnotation(cfi: string): Observable<void> {
    if (!this.view) {
      return of(undefined);
    }

    return this.annotationService.showAnnotation(this.view, cfi).pipe(
      catchError(() => this.goTo(cfi))
    );
  }

  goToSection(index: number): Observable<void> {
    return this.goTo(index);
  }

  goToFraction(fraction: number): Observable<void> {
    if (!this.view) {
      return of(undefined);
    }
    const view = this.view;
    return defer(() => from(view.goToFraction(fraction))).pipe(
      map(() => undefined)
    );
  }

  prev(distance?: number): void {
    this.view?.prev(distance);
  }

  next(distance?: number): void {
    this.view?.next(distance);
  }

  getRenderer(): FoliateRenderer | null {
    return this.view?.renderer ?? null;
  }

  setFlow(flow: ReaderFlow): void {
    this.view?.setAttribute('flow', flow);
  }

  getSelection(): TextSelection | null {
    const renderer = this.getRenderer();
    if (!renderer) return null;

    const contents = renderer.getContents();
    if (!contents || contents.length === 0) return null;

    for (const {index, doc} of contents) {
      if (!doc) continue;

      const selection = doc.defaultView?.getSelection();
      if (!selection || selection.rangeCount === 0 || selection.isCollapsed) continue;

      const range = selection.getRangeAt(0);
      const text = range.toString().trim();
      if (!text) continue;

      const cfi = this.view?.getCFI(index, range);
      if (!cfi) continue;

      return {text, cfi, range, index};
    }

    return null;
  }

  clearSelection(): void {
    this.view?.deselect();
  }

  addAnnotation(annotation: Annotation): Observable<{index: number; label: string} | undefined> {
    return this.annotationService.addAnnotation(this.view, annotation);
  }

  deleteAnnotation(cfi: string): Observable<void> {
    return this.annotationService.deleteAnnotation(this.view, cfi);
  }

  showAnnotation(cfi: string): Observable<void> {
    return this.annotationService.showAnnotation(this.view, cfi);
  }

  addAnnotations(annotations: Annotation[]): void {
    this.annotationService.addAnnotations(this.view, annotations);
  }

  updateHeadersAndFooters(chapterName: string, pageInfo?: PageInfo, theme?: ThemeInfo, timeRemainingLabel?: string): void {
    const renderer = this.getRenderer();
    PageDecorator.updateHeadersAndFooters(renderer, chapterName, pageInfo, theme, timeRemainingLabel);
  }

  getChapters(): TocItem[] {
    if (!this.view?.book?.toc) return [];

    const mapToc = (items: FoliateTocItem[]): TocItem[] =>
      items.map(item => ({
        label: item.label,
        href: item.href,
        subitems: item.subitems?.length ? mapToc(item.subitems) : undefined
      }));

    return mapToc(this.view.book.toc);
  }

  getSectionFractions(): number[] {
    if (!this.view?.getSectionFractions) return [];
    return this.view.getSectionFractions();
  }

  getMetadata(): Observable<BookMetadata> {
    if (!this.view?.book?.metadata) {
      return of({});
    }

    const {metadata} = this.view.book;

    return this.getCoverUrl().pipe(
      map(coverUrl => ({
        title: metadata.title,
        authors: metadata.authors,
        language: metadata.language,
        publisher: metadata.publisher,
        description: metadata.description,
        identifier: metadata.identifier,
        coverUrl,
        ...metadata
      }))
    );
  }

  getCover(): Observable<Blob | null> {
    if (!this.view?.book?.getCover) {
      return of(null);
    }
    const book = this.view.book;
    return defer(() => {
      const coverPromise = book.getCover?.();
      return coverPromise ? from(coverPromise) : of(null);
    });
  }

  getCoverUrl(): Observable<string | null> {
    return this.getCover().pipe(
      map(blob => blob ? URL.createObjectURL(blob) : null)
    );
  }

  async* search(opts: { query: string; matchCase?: boolean; matchWholeWords?: boolean }): AsyncGenerator<FoliateSearchResult> {
    const search = this.view?.search;
    if (!search) return;
    yield* search.call(this.view, opts);
  }

  clearSearch(): void {
    this.view?.clearSearch?.();
  }
}
