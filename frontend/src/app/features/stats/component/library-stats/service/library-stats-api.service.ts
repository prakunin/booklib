import {computed, inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {lastValueFrom} from 'rxjs';
import {API_CONFIG} from '../../../../../core/config/api-config';
import {AuthService} from '../../../../../shared/service/auth.service';
import {AppFilterOptions} from '../../../../book/model/app-book.model';
import {LibraryFilterService} from './library-filter.service';

export interface MonthlyCount {
  year: number;
  month: number;
  count: number;
}

export interface AuthorStat {
  name: string;
  bookCount: number;
  totalPages: number;
  averageRating: number;
  readCount: number;
}

export interface BookFlowCount {
  year: number;
  quarter: number;
  readStatus: string;
  personalRating: number | null;
  count: number;
}

export interface PublicationRatingCount {
  year: number;
  personalRating: number;
  count: number;
}

export interface PageRatingCount {
  pageCount: number;
  personalRating: number;
  readStatus: string;
  count: number;
}

export interface RatingTasteCount {
  personalRating: number;
  metadataRating: number | null;
  goodreadsRating: number | null;
  amazonRating: number | null;
  hardcoverRating: number | null;
  lubimyczytacRating: number | null;
  ranobedbRating: number | null;
  count: number;
}

export interface LibraryStatsSnapshot {
  totalBooks: number;
  totalSizeKb: number;
  totalAuthors: number;
  totalSeries: number;
  totalPublishers: number;
  averageDaysToFinish: number;
  facets: AppFilterOptions;
  booksAddedByMonth: MonthlyCount[];
  booksFinishedByMonth: MonthlyCount[];
  authorStats: AuthorStat[];
  bookFlow: BookFlowCount[];
  publicationRatings: PublicationRatingCount[];
  pageRatings: PageRatingCount[];
  ratingTaste: RatingTasteCount[];
}

@Injectable({providedIn: 'root'})
export class LibraryStatsApiService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly libraryFilterService = inject(LibraryFilterService);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/app/stats/library`;

  private readonly query = injectQuery(() => {
    const libraryId = this.libraryFilterService.selectedLibrary();
    return {
      queryKey: ['app-library-stats', libraryId ?? 'all'] as const,
      queryFn: () => {
        const params = libraryId == null
          ? undefined
          : new HttpParams().set('libraryId', libraryId.toString());
        return lastValueFrom(this.http.get<LibraryStatsSnapshot>(this.url, {params}));
      },
      enabled: !!this.authService.token(),
      staleTime: 10 * 60_000,
      refetchOnWindowFocus: false,
    };
  });

  readonly data = computed(() => this.query.data() ?? null);
  readonly isLoading = computed(() => this.query.isPending());
  readonly error = computed(() => this.query.error());
  readonly facets = computed(() => this.data()?.facets ?? null);
}
