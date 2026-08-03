import {computed, inject, Injectable, signal} from '@angular/core';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {invalidateBookQueries} from '../../book/service/book-query-cache';
import {EnrichmentProgressEvent} from '../model/enrichment.model';

/**
 * Holds what the enrichment worker is currently reporting, and makes sure the UI actually shows it.
 *
 * The invalidation is the load-bearing part: the query client is configured with
 * `staleTime: Infinity`, so nothing refetches on its own and a book enriched in the background would
 * keep rendering its old metadata until something invalidated it.
 */
@Injectable({providedIn: 'root'})
export class EnrichmentProgressService {
  private readonly queryClient = inject(QueryClient);

  private readonly currentEvent = signal<EnrichmentProgressEvent | null>(null);

  readonly progress = computed(() => this.currentEvent());

  readonly isRunning = computed(() => {
    const event = this.currentEvent();
    return event !== null && !event.finished;
  });

  handleProgress(event: EnrichmentProgressEvent): void {
    this.currentEvent.set(event);
    if (event.bookChanged) {
      invalidateBookQueries(this.queryClient, [event.bookId]);
    }
  }

  clear(): void {
    this.currentEvent.set(null);
  }
}
