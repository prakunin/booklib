import {computed, inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {lastValueFrom} from 'rxjs';
import {API_CONFIG} from '../../../../../core/config/api-config';
import {AuthService} from '../../../../../shared/service/auth.service';
import {LibraryStatsSnapshot} from '../../library-stats/service/library-stats-api.service';

/** Server-side catalog aggregates used by book-oriented charts on Reading Stats. */
@Injectable({providedIn: 'root'})
export class UserBookStatsService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/app/stats/library`;

  private readonly query = injectQuery(() => ({
    queryKey: ['app-user-book-stats'] as const,
    queryFn: () => lastValueFrom(this.http.get<LibraryStatsSnapshot>(this.url)),
    enabled: !!this.authService.token(),
    staleTime: 10 * 60_000,
    refetchOnWindowFocus: false,
  }));

  readonly data = computed(() => this.query.data() ?? null);
  readonly facets = computed(() => this.data()?.facets ?? null);
  readonly isLoading = computed(() => this.query.isPending());
}
