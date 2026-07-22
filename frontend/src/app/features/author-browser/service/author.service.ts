import {computed, DestroyRef, effect, inject, Injectable, signal} from '@angular/core';
import {HttpClient, HttpHeaders, HttpParams} from '@angular/common/http';
import {lastValueFrom, Observable} from 'rxjs';
import {map} from 'rxjs/operators';
import {SseClient} from 'ngx-sse-client';
import {API_CONFIG} from '../../../core/config/api-config';
import {AuthorSummary, AuthorDetails, AuthorSearchResult, AuthorMatchRequest, AuthorUpdateRequest, AuthorPhotoResult, AuthorFilters, DEFAULT_AUTHOR_FILTERS} from '../model/author.model';
import {Book} from '../../book/model/book.model';
import {AuthService} from '../../../shared/service/auth.service';
import {injectInfiniteQuery, injectQuery, QueryClient} from '@tanstack/angular-query-experimental';
import {AppBookSummary, AppPageResponse} from '../../book/model/app-book.model';
import {summaryToBook} from '../../book/service/app-books-api.service';
import {AUTHOR_CATEGORIES_QUERY_KEY, AUTHORS_QUERY_KEY} from './author-query-keys';
import {invalidateAuthorsQuery, patchAuthorInCache, removeAuthorsFromCache} from './author-query-cache';

const AUTHOR_PAGE_SIZE = 50;
const AUTHOR_BOOK_PAGE_SIZE = 50;

export type AuthorBrowserSort = 'name' | 'book-count' | 'matched' | 'recently-added' |
  'recently-read' | 'reading-progress' | 'avg-rating' | 'photo' | 'series-count';
export type AuthorSortDirection = 'asc' | 'desc';

@Injectable({
  providedIn: 'root'
})
export class AuthorService {

  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly sseClient = inject(SseClient);
  private readonly queryClient = inject(QueryClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/authors`;
  private readonly browserUrl = `${API_CONFIG.BASE_URL}/api/v1/app/authors`;
  private readonly appBooksUrl = `${API_CONFIG.BASE_URL}/api/v1/app/books`;
  private readonly mediaBaseUrl = `${API_CONFIG.BASE_URL}/api/v1/media`;
  private readonly token = this.authService.token;
  private readonly browserEnabled = signal(false);
  private readonly browserSearch = signal('');
  private readonly browserFilters = signal<AuthorFilters>({...DEFAULT_AUTHOR_FILTERS});
  private readonly browserSort = signal<AuthorBrowserSort>('name');
  private readonly browserSortDirection = signal<AuthorSortDirection>('asc');
  private readonly authorInvalidationDebounceMs = 200;
  private authorInvalidationTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly authorsQuery = injectInfiniteQuery(() => ({
    queryKey: [
      ...AUTHORS_QUERY_KEY,
      this.browserSearch(),
      this.browserFilters(),
      this.browserSort(),
      this.browserSortDirection(),
    ] as const,
    queryFn: ({pageParam}: {pageParam: number}) => lastValueFrom(
      this.http.get<AppPageResponse<AuthorSummary>>(this.browserUrl, {
        params: this.buildBrowserParams(pageParam),
      })
    ),
    initialPageParam: 0,
    getNextPageParam: (lastPage: AppPageResponse<AuthorSummary>) =>
      lastPage.hasNext ? lastPage.page + 1 : undefined,
    enabled: !!this.token() && this.browserEnabled(),
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
  }));

  private readonly categoriesQuery = injectQuery(() => ({
    queryKey: AUTHOR_CATEGORIES_QUERY_KEY,
    queryFn: () => lastValueFrom(this.http.get<string[]>(`${this.browserUrl}/categories`)),
    enabled: !!this.token() && this.browserEnabled(),
    staleTime: 5 * 60_000,
  }));

  allAuthors = computed(() => this.authorsQuery.data()?.pages.flatMap(page => page.content) ?? []);
  totalAuthors = computed(() => this.authorsQuery.data()?.pages[0]?.totalElements ?? this.allAuthors().length);
  authorCategories = computed(() => this.categoriesQuery.data() ?? []);
  isAuthorsLoading = computed(() => this.authorsQuery.isLoading());
  isAuthorsError = computed(() => this.authorsQuery.isError());
  isFetchingNextPage = computed(() => this.authorsQuery.isFetchingNextPage());

  constructor() {
    this.destroyRef.onDestroy(() => this.clearAuthorInvalidationTimer());

    effect(() => {
      const token = this.token();
      if (token === null) {
        this.queryClient.removeQueries({queryKey: AUTHORS_QUERY_KEY});
        this.queryClient.removeQueries({queryKey: AUTHOR_CATEGORIES_QUERY_KEY, exact: true});
      }
    });
  }

  enableBrowser(): void {
    this.browserEnabled.set(true);
  }

  setBrowserSearch(value: string): void {
    this.browserSearch.set(value);
  }

  setBrowserFilters(filters: AuthorFilters): void {
    this.browserFilters.set({...filters});
  }

  setBrowserSort(sort: AuthorBrowserSort, direction: AuthorSortDirection): void {
    this.browserSort.set(sort);
    this.browserSortDirection.set(direction);
  }

  fetchNextPage(): void {
    if (this.authorsQuery.hasNextPage() && !this.authorsQuery.isFetchingNextPage()) {
      this.authorsQuery.fetchNextPage();
    }
  }

  invalidateAuthors(): void {
    invalidateAuthorsQuery(this.queryClient);
  }

  refreshAuthorPages(): void {
    this.queryClient.invalidateQueries({queryKey: AUTHORS_QUERY_KEY});
  }

  handleNewlyCreatedBook(book: Book): void {
    if (!book.metadata?.authors?.length) {
      return;
    }
    this.debouncedInvalidateAuthors();
  }

  private debouncedInvalidateAuthors(): void {
    this.clearAuthorInvalidationTimer();

    this.authorInvalidationTimer = setTimeout(() => {
      this.authorInvalidationTimer = null;
      this.invalidateAuthors();
    }, this.authorInvalidationDebounceMs);
  }

  private clearAuthorInvalidationTimer(): void {
    if (this.authorInvalidationTimer === null) {
      return;
    }

    clearTimeout(this.authorInvalidationTimer);
    this.authorInvalidationTimer = null;
  }

  patchAuthorInCache(authorId: number, fields: Partial<AuthorSummary>): void {
    patchAuthorInCache(this.queryClient, authorId, fields);
  }

  removeAuthorsFromCache(authorIds: Iterable<number>): void {
    removeAuthorsFromCache(this.queryClient, authorIds);
  }

  private buildBrowserParams(page: number): HttpParams {
    return buildAuthorBrowserParams(
      page,
      this.browserSearch(),
      this.browserFilters(),
      this.browserSort(),
      this.browserSortDirection(),
    );
  }

  getAuthorDetails(authorId: number): Observable<AuthorDetails> {
    return this.http.get<AuthorDetails>(`${this.baseUrl}/${authorId}`);
  }

  getAuthorBooks(authorName: string, page: number): Observable<AppPageResponse<Book>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', AUTHOR_BOOK_PAGE_SIZE.toString())
      .set('sort', 'title')
      .set('dir', 'asc')
      .append('authors', authorName);
    return this.http.get<AppPageResponse<AppBookSummary>>(this.appBooksUrl, {params}).pipe(
      map(response => ({...response, content: response.content.map(summaryToBook)})),
    );
  }

  getAuthorByName(name: string): Observable<AuthorDetails> {
    return this.http.get<AuthorDetails>(`${this.baseUrl}/by-name`, {params: {name}});
  }

  searchAuthorMetadata(authorId: number, query: string, region: string, asin?: string): Observable<AuthorSearchResult[]> {
    const params: Record<string, string> = {region};
    if (asin) {
      params['asin'] = asin;
    } else {
      params['q'] = query;
    }
    return this.http.get<AuthorSearchResult[]>(`${this.baseUrl}/${authorId}/search-metadata`, {params});
  }

  matchAuthor(authorId: number, request: AuthorMatchRequest): Observable<AuthorDetails> {
    return this.http.post<AuthorDetails>(`${this.baseUrl}/${authorId}/match`, request);
  }

  quickMatchAuthor(authorId: number, region: string = 'us'): Observable<AuthorDetails> {
    return this.http.post<AuthorDetails>(`${this.baseUrl}/${authorId}/quick-match`, null, {
      params: {region}
    });
  }

  autoMatchAuthors(authorIds: number[]): Observable<AuthorSummary> {
    const token = this.authService.getInternalAccessToken();
    const headers = new HttpHeaders()
      .set('Content-Type', 'application/json')
      .set('Authorization', `Bearer ${token}`);

    return this.sseClient.stream(
      `${this.baseUrl}/auto-match`,
      {keepAlive: false, reconnectionDelay: 1000, responseType: 'event'},
      {headers, body: authorIds, withCredentials: true},
      'POST'
    ).pipe(
      map(event => {
        if (event.type === 'error') {
          throw new Error((event as ErrorEvent).message);
        }
        return JSON.parse((event as MessageEvent).data) as AuthorSummary;
      })
    );
  }

  updateAuthor(authorId: number, request: AuthorUpdateRequest): Observable<AuthorDetails> {
    return this.http.put<AuthorDetails>(`${this.baseUrl}/${authorId}`, request);
  }

  unmatchAuthors(authorIds: number[]): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/unmatch`, authorIds);
  }

  deleteAuthors(authorIds: number[]): Observable<void> {
    return this.http.delete<void>(this.baseUrl, {body: authorIds});
  }

  searchAuthorPhotos(authorId: number, query: string): Observable<AuthorPhotoResult> {
    const token = this.authService.getInternalAccessToken();
    const headers = new HttpHeaders()
      .set('Content-Type', 'application/json')
      .set('Authorization', `Bearer ${token}`);

    return this.sseClient.stream(
      `${this.baseUrl}/${authorId}/search-photos`,
      {keepAlive: false, reconnectionDelay: 1000, responseType: 'event'},
      {headers, params: {q: query}, withCredentials: true},
      'GET'
    ).pipe(
      map(event => {
        if (event.type === 'error') {
          throw new Error((event as ErrorEvent).message);
        }
        return JSON.parse((event as MessageEvent).data) as AuthorPhotoResult;
      })
    );
  }

  uploadAuthorPhotoFromUrl(authorId: number, imageUrl: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${authorId}/photo/url`, null, {
      params: {url: imageUrl}
    });
  }

  getUploadAuthorPhotoUrl(authorId: number): string {
    return `${this.baseUrl}/${authorId}/photo/upload`;
  }

  getAuthorPhotoUrl(authorId: number, cacheBuster?: number): string {
    return this.buildAuthorMediaUrl(`${this.mediaBaseUrl}/author/${authorId}/photo`, cacheBuster);
  }

  getAuthorThumbnailUrl(authorId: number, cacheBuster?: number): string {
    return this.buildAuthorMediaUrl(`${this.mediaBaseUrl}/author/${authorId}/thumbnail`, cacheBuster);
  }

  private buildAuthorMediaUrl(baseUrl: string, cacheBuster?: number): string {
    const token = this.authService.getInternalAccessToken();
    const params = new URLSearchParams();
    if (token) {
      params.set('token', token);
    }
    if (cacheBuster !== undefined) {
      params.set('t', String(cacheBuster));
    }
    const queryString = params.toString();
    return queryString ? `${baseUrl}?${queryString}` : baseUrl;
  }
}

export function buildAuthorBrowserParams(
  page: number,
  searchValue: string,
  filters: AuthorFilters,
  browserSort: AuthorBrowserSort,
  direction: AuthorSortDirection,
): HttpParams {
  const sort = toApiAuthorSort(browserSort);
  let params = new HttpParams()
    .set('page', page.toString())
    .set('size', AUTHOR_PAGE_SIZE.toString())
    .set('sort', sort)
    .set('dir', direction);

  const search = searchValue.trim();
  if (search) params = params.set('search', search);
  if (filters.matchStatus !== 'all') params = params.set('matchStatus', filters.matchStatus);
  if (filters.photoStatus !== 'all') params = params.set('hasPhoto', filters.photoStatus === 'has-photo');
  if (filters.readStatus !== 'all') params = params.set('readStatus', filters.readStatus);
  if (filters.bookCount !== 'all') params = params.set('bookCount', filters.bookCount);
  if (filters.library !== 'all') params = params.set('libraryId', filters.library.toString());
  if (filters.genre !== 'all') params = params.set('genre', filters.genre);
  return params;
}

export function toApiAuthorSort(sort: AuthorBrowserSort): string {
  switch (sort) {
    case 'book-count': return 'bookCount';
    case 'recently-added': return 'recentlyAdded';
    case 'recently-read': return 'lastReadTime';
    case 'reading-progress': return 'readProgress';
    case 'avg-rating': return 'avgRating';
    case 'series-count': return 'seriesCount';
    default: return sort;
  }
}
