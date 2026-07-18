import {TestBed} from '@angular/core/testing';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {Book} from '../model/book.model';
import {BOOKS_QUERY_KEY, bookDetailQueryKey, bookRecommendationsQueryKey} from './book-query-keys';
import {BookSocketService} from './book-socket.service';

function makeBook(id: number, overrides: Partial<Book> = {}): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Library',
    metadata: {
      bookId: id,
      title: `Book ${id}`,
      coverUpdatedOn: '2026-03-01T00:00:00Z',
    },
    ...overrides,
  };
}

describe('BookSocketService', () => {
  let service: BookSocketService;
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();

    TestBed.configureTestingModule({
      providers: [
        BookSocketService,
        {provide: QueryClient, useValue: queryClient},
      ],
    });

    service = TestBed.inject(BookSocketService);
  });

  afterEach(() => {
    queryClient.clear();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('adds newly created books into the list cache when it exists', () => {
    const existing = makeBook(1);
    const created = makeBook(2);
    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [existing]);

    service.handleNewlyCreatedBook(created);

    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([existing, created]);
  });

  const appBooksInvalidationCount = (invalidateSpy: {mock: {calls: unknown[][]}}) =>
    invalidateSpy.mock.calls.filter(call => {
      const arg = call[0] as {queryKey?: unknown[]} | undefined;
      return Array.isArray(arg?.queryKey) && arg.queryKey[0] === 'app-books';
    }).length;

  it('coalesces a short burst of book-add events into a single trailing invalidation', () => {
    vi.useFakeTimers();
    try {
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      // A short burst (events closer together than the 500ms debounce, well under the max-wait).
      for (let id = 1; id <= 8; id++) {
        service.handleNewlyCreatedBook(makeBook(id));
        vi.advanceTimersByTime(50);
      }

      // Nothing has fired yet: the trailing timer keeps being reset.
      expect(appBooksInvalidationCount(invalidateSpy)).toBe(0);

      // After the burst settles, exactly one invalidation covers all 8 additions.
      vi.advanceTimersByTime(500);
      expect(appBooksInvalidationCount(invalidateSpy)).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('still flushes periodically during a continuous import that never pauses (max-wait)', () => {
    vi.useFakeTimers();
    try {
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      // A sustained burst that never leaves a 500ms gap would, with a pure trailing debounce, never
      // flush; the 2000ms max-wait must force periodic refreshes so the visible list is not starved.
      for (let id = 1; id <= 60; id++) {
        service.handleNewlyCreatedBook(makeBook(id));
        vi.advanceTimersByTime(100); // 60 events over 6000ms, always < 500ms apart
      }

      const during = appBooksInvalidationCount(invalidateSpy);
      // ~one flush per 2000ms max-wait window while the burst is ongoing — far fewer than 60, but
      // crucially non-zero (no multi-minute starvation), and nowhere near one-per-event.
      expect(during).toBeGreaterThanOrEqual(2);
      expect(during).toBeLessThan(10);

      // Everything eventually flushes once the burst ends, still coalesced (not one-per-event).
      vi.advanceTimersByTime(2500);
      const total = appBooksInvalidationCount(invalidateSpy);
      expect(total).toBeGreaterThanOrEqual(during);
      expect(total).toBeLessThan(10);
    } finally {
      vi.useRealTimers();
    }
  });

  it('invalidates the books query and removes detail queries for removed ids', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const kept = makeBook(2);
    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [makeBook(1), kept]);
    queryClient.setQueryData(bookDetailQueryKey(1, false), makeBook(1));
    queryClient.setQueryData(bookRecommendationsQueryKey(1, 20), [kept]);

    service.handleRemovedBookIds([1]);

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: BOOKS_QUERY_KEY, exact: true});
    expect(queryClient.getQueryData(bookDetailQueryKey(1, false))).toBeUndefined();
    expect(queryClient.getQueryData(bookRecommendationsQueryKey(1, 20))).toBeUndefined();
  });

  it('patches updated books directly into the list cache', () => {
    const original = makeBook(7, {libraryName: 'Original'});
    const updated = makeBook(7, {libraryName: 'Updated'});
    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [original]);

    service.handleBookUpdate(updated);

    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([updated]);
  });

  it('patches multiple cover timestamps and invalidates their detail queries', () => {
    const first = makeBook(3);
    const second = makeBook(4, {
      metadata: {
        bookId: 4,
        title: 'Book 4',
      },
    });
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [first, second]);

    service.handleMultipleBookCoverPatches([{id: 3, coverUpdatedOn: '2026-03-26T12:34:00Z'}]);

    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([
      {
        ...first,
        metadata: {
          ...first.metadata,
          coverUpdatedOn: '2026-03-26T12:34:00Z',
        },
      },
      second,
    ]);
    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['books', 'detail', 3]});
  });

  it('invalidates recommendations when queued computation completes', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    service.handleBookRecommendationsUpdate(12);

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['books', 'recommendations', 12]});
  });

  it('ignores empty cover patch lists', () => {
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData');

    service.handleMultipleBookCoverPatches([]);

    expect(setQueryDataSpy).not.toHaveBeenCalled();
  });
});
