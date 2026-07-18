import {DestroyRef, inject, Injectable} from '@angular/core';
import {Book} from '../model/book.model';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {BOOKS_QUERY_KEY, bookRecommendationsQueryPrefix} from './book-query-keys';
import {
  addBookToCache,
  invalidateAppBooksQueries,
  invalidateBookDetailQueries,
  invalidateBooksQuery,
  patchAppBooksCoverInCache,
  patchBooksInCache,
  removeBookQueries,
} from './book-query-cache';

@Injectable({
  providedIn: 'root',
})
export class BookSocketService {
  private queryClient = inject(QueryClient);
  private destroyRef = inject(DestroyRef);
  private readonly appBooksInvalidationDebounceMs = 500;
  // Ceiling on how long a continuous burst can defer a flush, so a sustained import still refreshes
  // the visible list periodically instead of only once the burst finally pauses.
  private readonly appBooksInvalidationMaxWaitMs = 2000;
  private appBooksInvalidationTimer: ReturnType<typeof setTimeout> | null = null;
  private appBooksInvalidationMaxWaitTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.clearAppBooksInvalidationTimers());
  }

  handleNewlyCreatedBook(book: Book): void {
    addBookToCache(this.queryClient, book);
    this.debouncedInvalidateAppBooks();
  }

  // A large import (e.g. INPX) emits one book-add event per book. Invalidating the paginated
  // app-books list on each event would refetch every loaded page on every event, turning an
  // import into O(N) full-list refetches and freezing the tab. Coalesce the burst into a trailing
  // invalidation, but keep a max-wait ceiling so a burst that never pauses (events arriving faster
  // than the debounce window for minutes) still flushes periodically rather than never.
  private debouncedInvalidateAppBooks(): void {
    if (this.appBooksInvalidationTimer !== null) {
      clearTimeout(this.appBooksInvalidationTimer);
    }
    this.appBooksInvalidationTimer = setTimeout(
      () => this.flushAppBooksInvalidation(),
      this.appBooksInvalidationDebounceMs
    );
    if (this.appBooksInvalidationMaxWaitTimer === null) {
      this.appBooksInvalidationMaxWaitTimer = setTimeout(
        () => this.flushAppBooksInvalidation(),
        this.appBooksInvalidationMaxWaitMs
      );
    }
  }

  private flushAppBooksInvalidation(): void {
    this.clearAppBooksInvalidationTimers();
    invalidateAppBooksQueries(this.queryClient);
  }

  private clearAppBooksInvalidationTimers(): void {
    if (this.appBooksInvalidationTimer !== null) {
      clearTimeout(this.appBooksInvalidationTimer);
      this.appBooksInvalidationTimer = null;
    }
    if (this.appBooksInvalidationMaxWaitTimer !== null) {
      clearTimeout(this.appBooksInvalidationMaxWaitTimer);
      this.appBooksInvalidationMaxWaitTimer = null;
    }
  }

  handleRemovedBookIds(removedBookIds: number[]): void {
    invalidateBooksQuery(this.queryClient);
    removeBookQueries(this.queryClient, removedBookIds);
  }

  handleBookUpdate(updatedBook: Book): void {
    patchBooksInCache(this.queryClient, [updatedBook]);
  }

  handleMultipleBookUpdates(updatedBooks: Book[]): void {
    patchBooksInCache(this.queryClient, updatedBooks);
  }

  handleBookMetadataUpdate(bookId: number): void {
    invalidateBooksQuery(this.queryClient);
    invalidateBookDetailQueries(this.queryClient, [bookId]);
  }

  handleBookRecommendationsUpdate(bookId: number): void {
    this.queryClient.invalidateQueries({queryKey: bookRecommendationsQueryPrefix(bookId)});
  }

  handleMultipleBookCoverPatches(patches: { id: number; coverUpdatedOn: string }[]): void {
    if (!patches || patches.length === 0) return;
    const patchMap = new Map(patches.map(p => [p.id, p.coverUpdatedOn]));
    this.queryClient.setQueryData<Book[]>(BOOKS_QUERY_KEY, current =>
      current?.map(book => {
        const coverUpdatedOn = patchMap.get(book.id);
        return coverUpdatedOn && book.metadata
          ? {...book, metadata: {...book.metadata, coverUpdatedOn}}
          : book;
      }) ?? current
    );
    patchAppBooksCoverInCache(this.queryClient, patches);
    invalidateBookDetailQueries(this.queryClient, patches.map(p => p.id));
  }
}
