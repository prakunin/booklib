import {inject, Injectable} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {Book} from '../../book/model/book.model';
import {ScrollerConfig, ScrollerType} from '../models/dashboard-config.model';
import {DashboardConfigService} from './dashboard-config.service';
import {AppBooksApiService} from '../../book/service/app-books-api.service';
import {catchError, forkJoin, map, Observable, of, switchMap} from 'rxjs';

const DEFAULT_MAX_ITEMS = 20;

@Injectable({
  providedIn: 'root'
})
export class DashboardBookService {
  private readonly appBooksApi = inject(AppBooksApiService);
  private readonly configService = inject(DashboardConfigService);

  /**
   * Computed map of scroller ID to its filtered book list.
   * This centralizes all dashboard filtering logic and keeps it reactive.
   */
  readonly scrollerBooksMap = toSignal(
    toObservable(this.configService.config).pipe(
      switchMap(config => {
        const enabled = config.scrollers.filter(scroller => scroller.enabled);
        if (enabled.length === 0) return of(new Map<string, Book[]>());
        return forkJoin(enabled.map(scroller => this.loadScroller(scroller).pipe(
          catchError(() => of([])),
          map(books => [scroller.id, books] as const),
        ))).pipe(map(entries => new Map(entries)));
      }),
    ),
    {initialValue: new Map<string, Book[]>()},
  );

  private loadScroller(config: ScrollerConfig): Observable<Book[]> {
    const limit = config.maxItems || DEFAULT_MAX_ITEMS;
    switch (config.type) {
      case ScrollerType.LAST_READ:
        return this.appBooksApi.getContinueReading(limit);
      case ScrollerType.LAST_LISTENED:
        return this.appBooksApi.getContinueListening(limit);
      case ScrollerType.LATEST_ADDED:
        return this.appBooksApi.getRecentlyAdded(limit);
      case ScrollerType.RANDOM:
        return this.appBooksApi.getRandom(limit);
      case ScrollerType.MAGIC_SHELF:
        if (config.magicShelfId == null) return of([]);
        return this.appBooksApi.getPage(
          {magicShelfId: config.magicShelfId},
          {
            field: config.sortField || 'addedOn',
            dir: config.sortDirection === 'asc' ? 'asc' : 'desc',
          },
          limit,
        );
      default:
        return of([]);
    }
  }
}
