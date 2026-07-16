import {beforeEach, describe, expect, it, vi} from 'vitest';
import {InfiniteData, QueryClient} from '@tanstack/angular-query-experimental';

import {Book, BookMetadata} from '../model/book.model';
import {AppBookSummary, AppPageResponse} from '../model/app-book.model';
import {
  addBookToCache,
  invalidateBookDetailQueries,
  invalidateBookQueries,
  invalidateBooksQuery,
  patchAppBooksFieldsInCache,
  patchBookFieldsInCache,
  patchBookInCacheWith,
  patchBookMetadataInCache,
  patchBooksInCache,
  removeBookQueries,
  removeBooksFromCache
} from './book-query-cache';
import {
  BOOKS_QUERY_KEY,
  bookDetailQueryKey,
  bookDetailQueryPrefix,
  bookRecommendationsQueryKey
} from './book-query-keys';

function makeBook(id: number, overrides: Partial<Book> = {}): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Test Library',
    metadata: {
      bookId: id,
      title: `Book ${id}`
    },
    ...overrides
  };
}

describe('book-query-cache', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
  });

  it('adds new books and replaces existing entries by id', () => {
    const firstBook = makeBook(1);
    const secondBook = makeBook(2);
    const updatedSecondBook = makeBook(2, {
      libraryName: 'Updated Library',
      metadata: {
        bookId: 2,
        title: 'Updated Book 2'
      }
    });
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [firstBook]);

    addBookToCache(queryClient, secondBook);
    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([firstBook, secondBook]);

    addBookToCache(queryClient, updatedSecondBook);
    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([firstBook, updatedSecondBook]);

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('invalidates the full books query and book detail queries', () => {
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    invalidateBooksQuery(queryClient);
    invalidateBookDetailQueries(queryClient, [1, 1, 2]);
    invalidateBookQueries(queryClient, [3, 3]);

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: BOOKS_QUERY_KEY, exact: true});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(1)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(2)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(3)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('patches list entries and invalidates matching detail queries', () => {
    const firstBook = makeBook(1);
    const secondBook = makeBook(2);
    const updatedSecondBook = makeBook(2, {
      libraryName: 'Updated Library'
    });
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [firstBook, secondBook]);

    patchBooksInCache(queryClient, [updatedSecondBook]);

    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([firstBook, updatedSecondBook]);
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(2)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('patches metadata, selected fields, and updater callbacks in the list cache', () => {
    const firstBook = makeBook(1, {
      metadata: {
        bookId: 1,
        title: 'Original Title',
        authors: ['Old Author']
      }
    });
    const secondBook = makeBook(2, {
      metadata: {
        bookId: 2,
        title: 'Second'
      },
      libraryName: 'Library A'
    });
    const thirdBook = makeBook(3, {
      metadata: {
        bookId: 3,
        title: 'Third'
      }
    });
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [firstBook, secondBook, thirdBook]);

    const updatedMetadata: BookMetadata = {
      bookId: 1,
      title: 'Updated Title'
    };

    patchBookMetadataInCache(queryClient, 1, updatedMetadata);
    patchBookFieldsInCache(queryClient, [
      {bookId: 2, fields: {libraryName: 'Updated Library'}},
      {bookId: 3, fields: {personalRating: 4}}
    ]);
    patchBookInCacheWith(queryClient, 1, book => ({
      ...book,
      metadata: {
        ...(book.metadata ?? {bookId: book.id}),
        authors: ['New Author']
      }
    }));

    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([
      {
        ...firstBook,
        metadata: {
          ...firstBook.metadata,
          title: 'Updated Title',
          authors: ['New Author']
        }
      },
      {
        ...secondBook,
        libraryName: 'Updated Library'
      },
      {
        ...thirdBook,
        personalRating: 4
      }
    ]);
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(1)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(2)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(3)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('removes detail and recommendation queries for deleted books', () => {
    const firstBook = makeBook(1);
    const secondBook = makeBook(2);

    queryClient.setQueryData(bookDetailQueryKey(1, false), firstBook);
    queryClient.setQueryData(bookDetailQueryKey(1, true), firstBook);
    queryClient.setQueryData(bookRecommendationsQueryKey(1, 20), [secondBook]);
    queryClient.setQueryData(bookDetailQueryKey(2, false), secondBook);

    removeBookQueries(queryClient, [1]);

    expect(queryClient.getQueryData(bookDetailQueryKey(1, false))).toBeUndefined();
    expect(queryClient.getQueryData(bookDetailQueryKey(1, true))).toBeUndefined();
    expect(queryClient.getQueryData(bookRecommendationsQueryKey(1, 20))).toBeUndefined();
    expect(queryClient.getQueryData(bookDetailQueryKey(2, false))).toEqual(secondBook);
  });

  it('removes deleted books from the list cache and associated queries', () => {
    const firstBook = makeBook(1);
    const secondBook = makeBook(2);
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, [firstBook, secondBook]);
    queryClient.setQueryData(bookDetailQueryKey(1, false), firstBook);
    queryClient.setQueryData(bookRecommendationsQueryKey(1, 20), [secondBook]);

    removeBooksFromCache(queryClient, [1]);

    expect(queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([secondBook]);
    expect(queryClient.getQueryData(bookDetailQueryKey(1, false))).toBeUndefined();
    expect(queryClient.getQueryData(bookRecommendationsQueryKey(1, 20))).toBeUndefined();
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('ignores an empty remove request', () => {
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData');
    const removeQueriesSpy = vi.spyOn(queryClient, 'removeQueries');

    removeBooksFromCache(queryClient, []);

    expect(setQueryDataSpy).not.toHaveBeenCalled();
    expect(removeQueriesSpy).not.toHaveBeenCalled();
  });

  describe('app-books surgical field patching', () => {
    const APP_BOOKS_KEY = ['app-books', {libraryId: 1}];

    function makeSummary(id: number, overrides: Partial<AppBookSummary> = {}): AppBookSummary {
      return {
        id,
        title: `Book ${id}`,
        authors: [],
        thumbnailUrl: null,
        readStatus: null,
        personalRating: null,
        seriesName: null,
        seriesNumber: null,
        libraryId: 1,
        addedOn: null,
        lastReadTime: null,
        readProgress: null,
        primaryFileId: null,
        primaryFileType: null,
        primaryFileName: null,
        coverUpdatedOn: null,
        audiobookCoverUpdatedOn: null,
        isPhysical: null,
        publisher: null,
        categories: null,
        tags: null,
        moods: null,
        language: null,
        narrator: null,
        isbn13: null,
        isbn10: null,
        publishedDate: null,
        pageCount: null,
        ageRating: null,
        contentRating: null,
        metadataMatchScore: null,
        fileSizeKb: null,
        amazonRating: null,
        amazonReviewCount: null,
        goodreadsRating: null,
        goodreadsReviewCount: null,
        hardcoverRating: null,
        hardcoverReviewCount: null,
        ranobedbRating: null,
        lubimyczytacRating: null,
        audibleRating: null,
        audibleReviewCount: null,
        allMetadataLocked: null,
        ...overrides
      };
    }

    function seedAppBooks(...summaries: AppBookSummary[]): void {
      const data: InfiniteData<AppPageResponse<AppBookSummary>> = {
        pages: [{
          content: summaries,
          page: 0,
          size: 50,
          totalElements: summaries.length,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false
        }],
        pageParams: [0]
      };
      queryClient.setQueryData<InfiniteData<AppPageResponse<AppBookSummary>>>(APP_BOOKS_KEY, data);
    }

    function contentOf(): AppBookSummary[] {
      return queryClient.getQueryData<InfiniteData<AppPageResponse<AppBookSummary>>>(APP_BOOKS_KEY)!.pages[0].content;
    }

    // Capture the predicate passed to the app-books invalidateQueries call so we can assert
    // exactly which views the reconcile step would refetch.
    function capturedAppBooksPredicate(spy: ReturnType<typeof vi.spyOn>): (q: {queryKey: readonly unknown[]}) => boolean {
      const call = spy.mock.calls.find(c => {
        const arg = c[0] as {queryKey?: readonly unknown[]; predicate?: unknown};
        return arg?.queryKey?.[0] === 'app-books' && typeof arg.predicate === 'function';
      });
      return (call![0] as {predicate: (q: {queryKey: readonly unknown[]}) => boolean}).predicate;
    }

    it('patches summary fields in place and reconciles only via a predicate', () => {
      seedAppBooks(makeSummary(1), makeSummary(2, {personalRating: 3}));
      const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

      patchBookFieldsInCache(queryClient, [
        {bookId: 1, fields: {readStatus: 'READ', personalRating: 5, lastReadTime: '2026-01-01T00:00:00Z'}},
        {bookId: 2, fields: {epubProgress: {cfi: 'x', percentage: 42}}}
      ]);

      const content = contentOf();
      expect(content[0]).toMatchObject({readStatus: 'READ', personalRating: 5, lastReadTime: '2026-01-01T00:00:00Z'});
      expect(content[1]).toMatchObject({readProgress: 42, personalRating: 3});

      // The heavy list is never blanket-invalidated; reconcile is gated by a predicate...
      expect(invalidateQueriesSpy).not.toHaveBeenCalledWith({queryKey: ['app-books']});
      expect(invalidateQueriesSpy).toHaveBeenCalledWith(
        expect.objectContaining({queryKey: ['app-books'], predicate: expect.any(Function)})
      );
      // ...and the cheap derived aggregates still refresh.
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-catalog-summary']});
    });

    it('reconciles a status-filtered view but not an unfiltered view after a read-status change', () => {
      seedAppBooks(makeSummary(1));
      const spy = vi.spyOn(queryClient, 'invalidateQueries');

      patchBookFieldsInCache(queryClient, [{bookId: 1, fields: {readStatus: 'READ'}}]);
      const predicate = capturedAppBooksPredicate(spy);

      // A view filtered by read status must refetch (the book may no longer belong)...
      expect(predicate({queryKey: ['app-books', {status: ['UNREAD']}, {field: 'addedOn', dir: 'desc'}, '']})).toBe(true);
      // ...but the default unfiltered view keeps the instant in-place patch.
      expect(predicate({queryKey: ['app-books', {libraryId: 1}, {field: 'addedOn', dir: 'desc'}, '']})).toBe(false);
    });

    it('reconciles when the active sort key depends on the changed field', () => {
      seedAppBooks(makeSummary(1));
      const spy = vi.spyOn(queryClient, 'invalidateQueries');

      patchBookFieldsInCache(queryClient, [{bookId: 1, fields: {personalRating: 5}}]);
      const predicate = capturedAppBooksPredicate(spy);

      // Sorted by personalRating (even inside a multi-sort with a leading '-') → reorder needed.
      expect(predicate({queryKey: ['app-books', {}, {field: 'title,-personalRating', dir: 'asc'}, '']})).toBe(true);
      // A personal-rating range filter also changes membership.
      expect(predicate({queryKey: ['app-books', {minRating: 4}, {field: 'addedOn', dir: 'desc'}, '']})).toBe(true);
      // Sorted only by addedOn → no reconcile.
      expect(predicate({queryKey: ['app-books', {}, {field: 'addedOn', dir: 'desc'}, '']})).toBe(false);
    });

    it('never reconciles for a progress-only update', () => {
      seedAppBooks(makeSummary(1));
      const spy = vi.spyOn(queryClient, 'invalidateQueries');

      patchBookFieldsInCache(queryClient, [{bookId: 1, fields: {epubProgress: {cfi: 'x', percentage: 10}}}]);
      const predicate = capturedAppBooksPredicate(spy);

      expect(predicate({queryKey: ['app-books', {status: ['UNREAD']}, {field: 'readStatus', dir: 'asc'}, '']})).toBe(false);
    });

    it('clears readProgress when a progress reset is patched', () => {
      seedAppBooks(makeSummary(1, {readProgress: 80}));

      patchAppBooksFieldsInCache(queryClient, [{
        bookId: 1,
        fields: {epubProgress: undefined, pdfProgress: undefined, cbxProgress: undefined, audiobookProgress: undefined}
      }]);

      expect(contentOf()[0].readProgress).toBeNull();
    });

    it('leaves unrelated summaries untouched and preserves object identity', () => {
      const untouched = makeSummary(2, {personalRating: 3});
      seedAppBooks(makeSummary(1), untouched);

      patchAppBooksFieldsInCache(queryClient, [{bookId: 1, fields: {personalRating: 5}}]);

      expect(contentOf()[1]).toBe(untouched);
    });

    it('skips the cache rewrite when the update projects to no summary fields', () => {
      const before = makeSummary(1);
      seedAppBooks(before);

      // dateFinished is not an AppBookSummary field → empty projection → no churn.
      patchAppBooksFieldsInCache(queryClient, [{bookId: 1, fields: {dateFinished: '2026-01-01'}}]);

      expect(contentOf()[0]).toBe(before);
    });

    it('does nothing when there is no app-books cache to patch', () => {
      expect(() => patchAppBooksFieldsInCache(queryClient, [{bookId: 1, fields: {personalRating: 5}}])).not.toThrow();
      expect(queryClient.getQueryData(APP_BOOKS_KEY)).toBeUndefined();
    });
  });
});
