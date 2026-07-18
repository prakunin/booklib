import {InfiniteData, QueryClient} from '@tanstack/angular-query-experimental';

import {Book, BookMetadata} from '../model/book.model';
import {AppBookFilters, AppBookSort, AppBookSummary, AppPageResponse} from '../model/app-book.model';
import {BOOKS_QUERY_KEY, bookDetailQueryPrefix, bookRecommendationsQueryPrefix} from './book-query-keys';

const APP_BOOKS_QUERY_PREFIX = ['app-books'] as const;
const APP_FILTER_OPTIONS_QUERY_PREFIX = ['app-filter-options'] as const;
const APP_CATALOG_SUMMARY_QUERY_KEY = ['app-catalog-summary'] as const;
const APP_BOOK_SUMMARIES_QUERY_PREFIX = ['app-book-summaries'] as const;

// Derived aggregates (facet counts, catalog totals, summary lists) that depend on book
// fields but are cheap and often unobserved. Invalidating these is fine even when the
// heavy app-books list itself is patched in place.
export function invalidateAppDerivedQueries(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({queryKey: APP_FILTER_OPTIONS_QUERY_PREFIX});
  void queryClient.invalidateQueries({queryKey: APP_CATALOG_SUMMARY_QUERY_KEY});
  void queryClient.invalidateQueries({queryKey: APP_BOOK_SUMMARIES_QUERY_PREFIX});
}

export function invalidateAppBooksQueries(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({queryKey: APP_BOOKS_QUERY_PREFIX});
  invalidateAppDerivedQueries(queryClient);
}

// --- Full invalidation (refetches from server) ---

export function invalidateBooksQuery(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({queryKey: BOOKS_QUERY_KEY, exact: true});
  invalidateAppBooksQueries(queryClient);
}

export function invalidateBookQueries(queryClient: QueryClient, bookIds: Iterable<number>): void {
  invalidateBooksQuery(queryClient);
  invalidateBookDetailQueries(queryClient, bookIds);
}

export function invalidateBookDetailQueries(queryClient: QueryClient, bookIds: Iterable<number>): void {
  for (const bookId of new Set(bookIds)) {
    void queryClient.invalidateQueries({queryKey: bookDetailQueryPrefix(bookId)});
  }
}

export function removeBookQueries(queryClient: QueryClient, bookIds: Iterable<number>): void {
  for (const bookId of new Set(bookIds)) {
    queryClient.removeQueries({queryKey: bookDetailQueryPrefix(bookId)});
    queryClient.removeQueries({queryKey: bookRecommendationsQueryPrefix(bookId)});
  }
}

export function removeBooksFromCache(queryClient: QueryClient, bookIds: Iterable<number>): void {
  const removedIds = new Set(bookIds);
  if (removedIds.size === 0) {
    return;
  }

  queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current =>
    (current ?? []).filter(book => !removedIds.has(book.id))
  );
  removeBookQueries(queryClient, removedIds);
  invalidateAppBooksQueries(queryClient);
}

// --- Surgical patches (updates cache directly, no list refetch) ---

// Surgical add for the legacy flat ['books'] list. Only patches when that list is already
// cached: a socket add must not fabricate a partial legacy catalog (the paginated app-books
// cache is the source of truth), and fabricating it would rebuild an ever-growing array on
// every event during a bulk import (O(N^2)). The paginated app-books invalidation is coalesced
// by the caller (see BookSocketService.handleNewlyCreatedBook), so it is not triggered here.
export function addBookToCache(queryClient: QueryClient, book: Book): void {
  queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current => {
    if (!current) return current;
    const exists = current.some(b => b.id === book.id);
    return exists ? current.map(b => b.id === book.id ? book : b) : [...current, book];
  });
}

export function patchBooksInCache(queryClient: QueryClient, updatedBooks: Book[]): void {
  const updatedMap = new Map(updatedBooks.map(book => [book.id, book]));
  queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current =>
    (current ?? []).map(book => updatedMap.get(book.id) ?? book)
  );
  invalidateBookDetailQueries(queryClient, updatedBooks.map(b => b.id));
  invalidateAppBooksQueries(queryClient);
}

export function patchBookMetadataInCache(queryClient: QueryClient, bookId: number, metadata: BookMetadata): void {
  queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current =>
    (current ?? []).map(book =>
      book.id === bookId ? {...book, metadata} : book
    )
  );
  invalidateBookDetailQueries(queryClient, [bookId]);
  invalidateAppBooksQueries(queryClient);
}

export function patchBookInCacheWith(queryClient: QueryClient, bookId: number, updater: (book: Book) => Book): void {
  queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current =>
    (current ?? []).map(book => book.id === bookId ? updater(book) : book)
  );
  invalidateBookDetailQueries(queryClient, [bookId]);
  invalidateAppBooksQueries(queryClient);
}

export function patchBookFieldsInCache(queryClient: QueryClient, updates: {bookId: number; fields: Partial<Book>}[]): void {
  const updateMap = new Map(updates.map(u => [u.bookId, u.fields]));
  // Do not create the legacy full-books cache when only the paginated app-books cache exists.
  queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current =>
    current?.map(book => {
      const fields = updateMap.get(book.id);
      return fields ? {...book, ...fields} : book;
    }) ?? current
  );
  // Patch the visible rows in place for instant feedback (no multi-page refetch)...
  patchAppBooksFieldsInCache(queryClient, updates);
  invalidateBookDetailQueries(queryClient, updates.map(u => u.bookId));
  // ...then reconcile only those app-books views whose active filter or sort actually
  // depends on a changed field, so e.g. a status tab drops a book that no longer matches.
  // Views that don't filter/sort by the changed field keep the cheap in-place patch.
  const changedFields = withServerDerivedFields(new Set(updates.flatMap(u => Object.keys(u.fields))));
  void queryClient.invalidateQueries({
    queryKey: APP_BOOKS_QUERY_PREFIX,
    predicate: query => appBooksViewDependsOn(query.queryKey, changedFields),
  });
  invalidateAppDerivedQueries(queryClient);
}

const PROGRESS_KEYS = ['epubProgress', 'pdfProgress', 'cbxProgress', 'audiobookProgress'] as const;

// Book fields mapped onto the AppBookSort.field token their value drives server-side.
const SORT_FIELD_BY_BOOK_FIELD: Record<string, string> = {
  readStatus: 'readStatus',
  personalRating: 'personalRating',
  metadataMatchScore: 'matchScore',
  lastReadTime: 'lastReadTime',
  dateFinished: 'dateFinished',
  // Every per-format progress field feeds the single readingProgress sort key.
  ...Object.fromEntries(PROGRESS_KEYS.map(key => [key, 'readingProgress'])),
};

// Saving progress makes the server recalculate readStatus and stamp dateFinished on completion
// (ReadingProgressService.calculateReadStatus). The client never sends those fields back, so a
// view filtered or sorted by them must reconcile off the progress write alone.
function withServerDerivedFields(changedFields: Set<string>): Set<string> {
  if (!PROGRESS_KEYS.some(key => changedFields.has(key))) return changedFields;
  return new Set([...changedFields, 'readStatus', 'dateFinished']);
}

// True when an app-books query's filter membership or sort order depends on one of the
// changed Book fields — i.e. patching in place is not enough and it must be refetched.
function appBooksViewDependsOn(queryKey: readonly unknown[], changedFields: Set<string>): boolean {
  const filters = queryKey[1] as AppBookFilters | undefined;
  const sort = queryKey[2] as AppBookSort | undefined;

  if (filters) {
    // A magic shelf's rule is evaluated server-side and can match on any field, so membership after
    // a change is not decidable here. A plain shelfId holds explicit book ids and is unaffected.
    if (filters.magicShelfId != null) return true;
    if (changedFields.has('readStatus') && filters.status?.length) return true;
    if (changedFields.has('personalRating') &&
      (filters.personalRating?.length || filters.minRating != null || filters.maxRating != null)) return true;
    if (changedFields.has('metadataMatchScore') && filters.matchScore?.length) return true;
  }

  if (sort?.field) {
    // AppBookSort.field may be a comma-joined multi-sort with a leading '-' for descending.
    const sortTokens = sort.field.split(',').map(token => token.replace(/^-/, ''));
    for (const field of changedFields) {
      const token = SORT_FIELD_BY_BOOK_FIELD[field];
      if (token && sortTokens.includes(token)) return true;
    }
  }
  return false;
}

// Project the subset of Book fields that also live on AppBookSummary so the paginated
// app-books cache can be patched in place instead of triggering a full multi-page refetch.
function bookFieldsToSummaryPatch(fields: Partial<Book>): Partial<AppBookSummary> {
  const patch: Partial<AppBookSummary> = {};
  if ('readStatus' in fields) patch.readStatus = fields.readStatus ?? null;
  if ('personalRating' in fields) patch.personalRating = fields.personalRating ?? null;
  if ('lastReadTime' in fields) patch.lastReadTime = fields.lastReadTime ?? null;
  if ('metadataMatchScore' in fields) patch.metadataMatchScore = fields.metadataMatchScore ?? null;

  // A file-progress update (save or reset) maps to the summary's single readProgress percent.
  for (const key of PROGRESS_KEYS) {
    if (key in fields) {
      const progress = fields[key] as {percentage?: number} | undefined;
      patch.readProgress = progress?.percentage ?? null;
    }
  }
  return patch;
}

export function patchAppBooksFieldsInCache(queryClient: QueryClient, updates: {bookId: number; fields: Partial<Book>}[]): void {
  if (updates.length === 0) return;
  const patchMap = new Map<number, Partial<AppBookSummary>>();
  for (const u of updates) {
    const patch = bookFieldsToSummaryPatch(u.fields);
    if (Object.keys(patch).length > 0) patchMap.set(u.bookId, patch);
  }
  // Nothing summary-visible changed (e.g. a dateFinished-only update) — skip the rewrite.
  if (patchMap.size === 0) return;

  queryClient.setQueriesData<InfiniteData<AppPageResponse<AppBookSummary>>>(
    {queryKey: APP_BOOKS_QUERY_PREFIX},
    current => {
      if (!current) return current;
      return {
        ...current,
        pages: current.pages.map(page => ({
          ...page,
          content: page.content.map(summary => {
            const patch = patchMap.get(summary.id);
            return patch ? {...summary, ...patch} : summary;
          }),
        })),
      };
    }
  );
}

export function patchAppBooksCoverInCache(
  queryClient: QueryClient,
  patches: {id: number; coverUpdatedOn?: string | null; audiobookCoverUpdatedOn?: string | null}[]
): void {
  if (patches.length === 0) return;
  const patchMap = new Map(patches.map(p => [p.id, p]));

  queryClient.setQueriesData<InfiniteData<AppPageResponse<AppBookSummary>>>(
    {queryKey: APP_BOOKS_QUERY_PREFIX},
    (current) => {
      if (!current) return current;
      return {
        ...current,
        pages: current.pages.map(page => ({
          ...page,
          content: page.content.map(summary => {
            const patch = patchMap.get(summary.id);
            if (!patch) return summary;
            return {
              ...summary,
              ...('coverUpdatedOn' in patch ? {coverUpdatedOn: patch.coverUpdatedOn ?? null} : {}),
              ...('audiobookCoverUpdatedOn' in patch ? {audiobookCoverUpdatedOn: patch.audiobookCoverUpdatedOn ?? null} : {}),
            };
          })
        }))
      };
    }
  );
}

export function patchAppBooksMetadataLockInCache(queryClient: QueryClient, bookId: number, allMetadataLocked: boolean): void {
  // Do not create the legacy full-books cache when only the paginated app-books cache exists.
  queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current =>
    current?.map(book =>
      book.id === bookId
        ? {
          ...book,
          metadata: {
            ...(book.metadata ?? {bookId}),
            allMetadataLocked,
          },
        }
        : book
    ) ?? current
  );

  queryClient.setQueriesData<InfiniteData<AppPageResponse<AppBookSummary>>>(
    {queryKey: APP_BOOKS_QUERY_PREFIX},
    current => {
      if (!current) return current;

      return {
        ...current,
        pages: current.pages.map(page => ({
          ...page,
          content: page.content.map(summary =>
            summary.id === bookId ? {...summary, allMetadataLocked} : summary
          ),
        })),
      };
    }
  );
}
