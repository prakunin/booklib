import {computed, effect, inject, Injectable} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {HttpClient} from '@angular/common/http';
import {catchError, forkJoin, lastValueFrom, map, Observable, of, switchMap} from 'rxjs';
import {tap} from 'rxjs/operators';
import {injectQuery, queryOptions, QueryClient} from '@tanstack/angular-query-experimental';

import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';
import {IconType} from '../../../shared/icons/icon-selection';
import {AppBooksApiService} from '../../book/service/app-books-api.service';

export interface MagicShelf {
  id?: number | null;
  name: string;
  icon?: string | null;
  iconType?: IconType | null;
  filterJson: string;
  isPublic?: boolean;
}

const MAGIC_SHELVES_QUERY_KEY = ['magicShelves'] as const;

@Injectable({
  providedIn: 'root',
})
export class MagicShelfService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/magic-shelves`;

  private readonly http = inject(HttpClient);
  private readonly appBooksApi = inject(AppBooksApiService);
  private readonly authService = inject(AuthService);
  private readonly queryClient = inject(QueryClient);
  private readonly token = this.authService.token;

  private readonly shelvesQuery = injectQuery(() => ({
    ...this.getShelvesQueryOptions(),
    enabled: !!this.token(),
  }));

  readonly shelves = computed(() => this.shelvesQuery.data() ?? []);

  readonly shelvesError = computed<string | null>(() => {
    if (!this.token() || !this.shelvesQuery.isError()) {
      return null;
    }

    const error = this.shelvesQuery.error();
    return error instanceof Error ? error.message : 'Failed to load magic shelves';
  });

  readonly isShelvesLoading = computed(() => !!this.token() && this.shelvesQuery.isPending());

  constructor() {
    effect(() => {
      const token = this.token();
      if (token === null) {
        this.queryClient.removeQueries({queryKey: MAGIC_SHELVES_QUERY_KEY});
      }
    });
  }

  private getShelvesQueryOptions() {
    return queryOptions({
      queryKey: MAGIC_SHELVES_QUERY_KEY,
      queryFn: () => lastValueFrom(this.http.get<MagicShelf[]>(this.url))
    });
  }

  saveShelf(data: {
    id?: number;
    name: string | null;
    icon: string | null;
    iconType?: IconType | null;
    group: unknown;
    isPublic?: boolean | null;
  }): Observable<MagicShelf> {
    const payload: MagicShelf = {
      id: data.id,
      name: data.name ?? '',
      icon: data.icon,
      iconType: data.iconType,
      filterJson: JSON.stringify(data.group),
      isPublic: data.isPublic ?? false
    };

    return this.http.post<MagicShelf>(this.url, payload).pipe(
      tap(() => {
        void this.queryClient.invalidateQueries({queryKey: MAGIC_SHELVES_QUERY_KEY, exact: true});
      })
    );
  }

  findShelfById(id: number): MagicShelf | undefined {
    return this.shelves().find(shelf => shelf.id === id);
  }

  getBookCountValue(shelfId: number): number {
    return this.bookCountByMagicShelfId().get(shelfId) ?? 0;
  }

  readonly bookCountByMagicShelfId = toSignal(
    toObservable(this.shelves).pipe(
      switchMap(shelves => {
        const withIds = shelves.filter((shelf): shelf is MagicShelf & {id: number} => shelf.id != null);
        if (withIds.length === 0) return of(new Map<number, number>());
        return forkJoin(withIds.map(shelf => this.appBooksApi.getCount({magicShelfId: shelf.id}).pipe(
          map(count => [shelf.id, count] as const),
          catchError(() => of([shelf.id, 0] as const)),
        ))).pipe(map(entries => new Map(entries)));
      }),
    ),
    {initialValue: new Map<number, number>()},
  );

  deleteShelf(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`).pipe(
      tap(() => {
        void this.queryClient.invalidateQueries({queryKey: MAGIC_SHELVES_QUERY_KEY, exact: true});
      })
    );
  }
}
