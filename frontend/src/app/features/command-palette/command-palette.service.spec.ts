import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MessageService } from 'primeng/api';
import { getTranslocoModule } from '../../core/testing/transloco-testing';
import { BookDialogHelperService } from '../book/components/book-browser/book-dialog-helper.service';
import { Book } from '../book/model/book.model';
import type { AppBookSummary } from '../book/model/app-book.model';
import { AppBooksApiService } from '../book/service/app-books-api.service';
import { LibraryService } from '../book/service/library.service';
import { ShelfService } from '../book/service/shelf.service';
import { MagicShelfService } from '../magic-shelf/service/magic-shelf.service';
import { UrlHelperService } from '../../shared/service/url-helper.service';
import { UserService } from '../settings/user-management/user.service';
import { CustomSvgService } from '../../shared/services/custom-svg.service';
import { DialogLauncherService } from '../../shared/services/dialog-launcher.service';

import { CommandPaletteService } from './command-palette.service';

function makeBook(id: number, title: string, authors: string[] = [], overrides: Partial<Book> = {}): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Library',
    ...overrides,
    metadata: {
      bookId: id,
      title,
      authors,
      ...overrides.metadata,
    },
  } as Book;
}

describe('CommandPaletteService', () => {
  let service: CommandPaletteService;
  let books = signal<Book[]>([]);
  let urlHelper: {
    getThumbnailUrl: ReturnType<typeof vi.fn>;
    getAudiobookThumbnailUrl: ReturnType<typeof vi.fn>;
  };
  let searchBooks: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
  });

  beforeEach(() => {
    books = signal([
      makeBook(1, 'The Hobbit', ['J.R.R. Tolkien']),
      makeBook(2, 'The Fellowship of the Ring', ['J.R.R. Tolkien']),
      makeBook(3, 'Dune', ['Frank Herbert']),
    ]);
    urlHelper = {
      getThumbnailUrl: vi.fn(() => null),
      getAudiobookThumbnailUrl: vi.fn(() => null),
    };
    searchBooks = vi.fn((query: string) => {
      const normalized = query.toLowerCase();
      const content = books()
        .filter(book => `${book.metadata?.title} ${book.metadata?.authors?.join(' ')}`.toLowerCase().includes(normalized))
        .map(book => ({
          id: book.id,
          title: book.metadata?.title ?? '',
          authors: book.metadata?.authors ?? [],
          libraryId: book.libraryId,
          primaryFileId: book.primaryFile?.id ?? null,
          primaryFileType: book.primaryFile?.bookType ?? null,
          primaryFileName: book.primaryFile?.fileName ?? null,
          audiobookCoverUpdatedOn: book.metadata?.audiobookCoverUpdatedOn ?? null,
        } as AppBookSummary));
      return of({content, page: 0, size: 50, totalElements: content.length, totalPages: 1, hasNext: false, hasPrevious: false});
    });

    TestBed.configureTestingModule({
      imports: [getTranslocoModule()],
      providers: [
        { provide: Router, useValue: { navigate: vi.fn(() => Promise.resolve(true)) } },
        { provide: AppBooksApiService, useValue: { searchBooks } },
        { provide: ShelfService, useValue: { shelves: signal([]) } },
        { provide: MagicShelfService, useValue: { shelves: signal([]) } },
        { provide: LibraryService, useValue: { libraries: signal([]) } },
        { provide: UserService, useValue: { currentUser: signal({ permissions: {} }) } },
        { provide: MessageService, useValue: { add: vi.fn() } },
        { provide: UrlHelperService, useValue: urlHelper },
        { provide: CustomSvgService, useValue: { getSvgIconContent: vi.fn(() => of('')) } },
        {
          provide: DialogLauncherService,
          useValue: {
            openLibraryCreateDialog: vi.fn(() => Promise.resolve(null)),
            openMagicShelfCreateDialog: vi.fn(() => Promise.resolve(null)),
            openFileUploadDialog: vi.fn(() => Promise.resolve(null)),
          },
        },
        {
          provide: BookDialogHelperService,
          useValue: {
            openShelfCreatorDialog: vi.fn(() => Promise.resolve(null)),
          },
        },
      ],
    });

    service = TestBed.inject(CommandPaletteService);
    TestBed.flushEffects();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('queries matching book groups through the specialized search API after the debounce window', async () => {
    service.query.set('tolkien');
    TestBed.flushEffects();
    await vi.advanceTimersByTimeAsync(200);
    TestBed.flushEffects();

    const bookGroup = service.groups().find((group) => group.kind === 'book');

    expect(bookGroup).toBeDefined();
    expect(bookGroup?.items.map((item) => item.title)).toEqual([
      'The Hobbit',
      'The Fellowship of the Ring',
    ]);
    expect(searchBooks).toHaveBeenCalledWith('tolkien', 50);
  });

  it('does not show book groups for one-character searches', async () => {
    service.query.set('d');
    TestBed.flushEffects();
    await vi.advanceTimersByTimeAsync(200);
    TestBed.flushEffects();

    expect(service.groups().find((group) => group.kind === 'book')).toBeUndefined();
    expect(searchBooks).not.toHaveBeenCalled();
  });

  it('keeps the searching state active until the book request completes', async () => {
    const response = new Subject<{
      content: AppBookSummary[];
      page: number;
      size: number;
      totalElements: number;
      totalPages: number;
      hasNext: boolean;
      hasPrevious: boolean;
    }>();
    searchBooks.mockReturnValue(response.asObservable());

    service.query.set('dune');
    TestBed.flushEffects();

    expect(service.isSearching()).toBe(true);

    await vi.advanceTimersByTimeAsync(200);
    TestBed.flushEffects();

    expect(searchBooks).toHaveBeenCalledWith('dune', 50);
    expect(service.isSearching()).toBe(true);

    response.next({
      content: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
      hasNext: false,
      hasPrevious: false,
    });
    response.complete();
    TestBed.flushEffects();

    expect(service.isSearching()).toBe(false);
  });

  it('returns no groups when the query is empty', () => {
    service.query.set('');

    expect(service.groups()).toEqual([]);
    expect(service.visibleItems()).toEqual([]);
  });

  it('uses square audiobook metadata and audiobook thumbnails for audiobook results', async () => {
    urlHelper.getAudiobookThumbnailUrl.mockReturnValue('/audio-thumb.jpg');
    books.set([
      makeBook(4, 'Audio Sample', ['Narrator'], {
        primaryFile: { id: 4, bookId: 4, bookType: 'AUDIOBOOK' },
        metadata: {
          bookId: 4,
          title: 'Audio Sample',
          authors: ['Narrator'],
          audiobookCoverUpdatedOn: 'audio-updated',
        },
      }),
    ]);

    service.query.set('audio');
    TestBed.flushEffects();
    await vi.advanceTimersByTimeAsync(200);
    TestBed.flushEffects();

    const book = service.groups().find((group) => group.kind === 'book')?.items[0];

    expect(book?.bookMeta?.isAudiobook).toBe(true);
    expect(book?.bookMeta?.thumbnailUrl).toBe('/audio-thumb.jpg');
    expect(urlHelper.getAudiobookThumbnailUrl).toHaveBeenCalledWith(4, 'audio-updated');
    expect(urlHelper.getThumbnailUrl).not.toHaveBeenCalled();
  });
});
