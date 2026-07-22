import {HttpClient} from '@angular/common/http';
import {TestBed} from '@angular/core/testing';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {SseClient} from 'ngx-sse-client';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {of} from 'rxjs';

import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';
import {Book} from '../../book/model/book.model';
import {AUTHORS_QUERY_KEY} from './author-query-keys';
import {AuthorService, buildAuthorBrowserParams, toApiAuthorSort} from './author.service';

function makeBook(authors?: string[]): Book {
  return {
    id: 1,
    libraryId: 1,
    libraryName: 'Library',
    metadata: {
      bookId: 1,
      title: 'Book 1',
      authors,
    },
  };
}

describe('AuthorService', () => {
  const httpClient = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  };

  const authService = {
    token: vi.fn(),
    getInternalAccessToken: vi.fn(),
  };

  const sseClient = {
    stream: vi.fn(),
  };

  const queryClient = {
    removeQueries: vi.fn(),
    invalidateQueries: vi.fn(),
  };

  let service: AuthorService;

  beforeEach(() => {
    httpClient.get.mockReset();
    httpClient.post.mockReset();
    httpClient.put.mockReset();
    httpClient.delete.mockReset();
    authService.token.mockReset();
    authService.getInternalAccessToken.mockReset();
    sseClient.stream.mockReset();
    queryClient.removeQueries.mockReset();
    queryClient.invalidateQueries.mockReset();

    authService.token.mockReturnValue('token-123');
    authService.getInternalAccessToken.mockReturnValue('token-123');
    httpClient.get.mockReturnValue(of([]));
    httpClient.post.mockReturnValue(of({}));
    httpClient.put.mockReturnValue(of({}));
    httpClient.delete.mockReturnValue(of(undefined));
    sseClient.stream.mockReturnValue(of({type: 'message', data: '{"id":7,"name":"Ada"}'}));

    TestBed.configureTestingModule({
      providers: [
        AuthorService,
        {provide: HttpClient, useValue: httpClient},
        {provide: AuthService, useValue: authService},
        {provide: SseClient, useValue: sseClient},
        {provide: QueryClient, useValue: queryClient},
      ],
    });

    service = TestBed.inject(AuthorService);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('builds metadata search params with q or asin depending on the request', () => {
    service.searchAuthorMetadata(9, 'Ada', 'us');

    expect(httpClient.get).toHaveBeenCalledWith(
      `${API_CONFIG.BASE_URL}/api/v1/authors/9/search-metadata`,
      {params: {region: 'us', q: 'Ada'}},
    );

    service.searchAuthorMetadata(9, 'Ada', 'us', 'B000123');

    expect(httpClient.get).toHaveBeenCalledWith(
      `${API_CONFIG.BASE_URL}/api/v1/authors/9/search-metadata`,
      {params: {region: 'us', asin: 'B000123'}},
    );
  });

  it('uses the default region for quick matches and preserves custom regions', () => {
    service.quickMatchAuthor(11);
    service.quickMatchAuthor(11, 'gb');

    expect(httpClient.post).toHaveBeenNthCalledWith(
      1,
      `${API_CONFIG.BASE_URL}/api/v1/authors/11/quick-match`,
      null,
      {params: {region: 'us'}},
    );
    expect(httpClient.post).toHaveBeenNthCalledWith(
      2,
      `${API_CONFIG.BASE_URL}/api/v1/authors/11/quick-match`,
      null,
      {params: {region: 'gb'}},
    );
  });

  it('loads a paginated author book page from the app catalog API', () => {
    service.getAuthorBooks('Андрей Федин', 2);

    expect(httpClient.get).toHaveBeenCalledWith(
      `${API_CONFIG.BASE_URL}/api/v1/app/books`,
      {params: expect.anything()},
    );
    const params = httpClient.get.mock.calls.at(-1)?.[1]?.params;
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('50');
    expect(params.get('sort')).toBe('title');
    expect(params.get('dir')).toBe('asc');
    expect(params.getAll('authors')).toEqual(['Андрей Федин']);
  });

  it('coalesces author query invalidation when newly created books introduce authors', () => {
    vi.useFakeTimers();

    service.handleNewlyCreatedBook(makeBook(['Ada Lovelace']));
    service.handleNewlyCreatedBook(makeBook(['Grace Hopper']));
    service.handleNewlyCreatedBook(makeBook(['Katherine Johnson']));

    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();

    vi.advanceTimersByTime(199);
    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({queryKey: AUTHORS_QUERY_KEY});
    expect(queryClient.invalidateQueries).toHaveBeenCalledTimes(2);
  });

  it('clears pending author invalidation when the service is destroyed', () => {
    vi.useFakeTimers();

    service.handleNewlyCreatedBook(makeBook(['Ada Lovelace']));
    TestBed.resetTestingModule();
    vi.advanceTimersByTime(200);

    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();
  });

  it('does not invalidate authors when the newly created book has no authors', () => {
    service.handleNewlyCreatedBook(makeBook([]));
    service.handleNewlyCreatedBook(makeBook(undefined));

    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();
  });

  it('maps SSE auto-match responses and throws on error events', () => {
    sseClient.stream.mockReturnValueOnce(of({type: 'message', data: '{"id":3,"name":"Grace"}'}));

    const results: unknown[] = [];
    service.autoMatchAuthors([1, 2]).subscribe(value => results.push(value));

    expect(sseClient.stream).toHaveBeenCalledWith(
      `${API_CONFIG.BASE_URL}/api/v1/authors/auto-match`,
      expect.objectContaining({keepAlive: false, reconnectionDelay: 1000, responseType: 'event'}),
      expect.objectContaining({
        body: [1, 2],
        withCredentials: true,
      }),
      'POST',
    );
    expect(results).toEqual([{id: 3, name: 'Grace'}]);

    sseClient.stream.mockReturnValueOnce(of({type: 'error', message: 'boom'}));

    const errors: Error[] = [];
    service.autoMatchAuthors([7]).subscribe({
      next: () => undefined,
      error: error => errors.push(error),
    });

    expect(errors).toHaveLength(1);
    expect(errors[0]).toEqual(new Error('boom'));
  });

  it('builds tokenized author media urls with and without cache busters', () => {
    expect(service.getAuthorPhotoUrl(5)).toBe(`${API_CONFIG.BASE_URL}/api/v1/media/author/5/photo?token=token-123`);
    expect(service.getAuthorPhotoUrl(5, 88)).toBe(`${API_CONFIG.BASE_URL}/api/v1/media/author/5/photo?token=token-123&t=88`);
    expect(service.getAuthorThumbnailUrl(5)).toBe(`${API_CONFIG.BASE_URL}/api/v1/media/author/5/thumbnail?token=token-123`);
    expect(service.getAuthorThumbnailUrl(5, 99)).toBe(`${API_CONFIG.BASE_URL}/api/v1/media/author/5/thumbnail?token=token-123&t=99`);

    authService.getInternalAccessToken.mockReturnValue(null);
    expect(service.getAuthorPhotoUrl(5)).toBe(`${API_CONFIG.BASE_URL}/api/v1/media/author/5/photo`);
    expect(service.getAuthorPhotoUrl(5, 88)).toBe(`${API_CONFIG.BASE_URL}/api/v1/media/author/5/photo?t=88`);
    expect(service.getAuthorThumbnailUrl(5, 99)).toBe(`${API_CONFIG.BASE_URL}/api/v1/media/author/5/thumbnail?t=99`);
  });

  it('maps browser sort fields to app API fields', () => {
    expect(toApiAuthorSort('name')).toBe('name');
    expect(toApiAuthorSort('book-count')).toBe('bookCount');
    expect(toApiAuthorSort('recently-added')).toBe('recentlyAdded');
    expect(toApiAuthorSort('recently-read')).toBe('lastReadTime');
    expect(toApiAuthorSort('reading-progress')).toBe('readProgress');
    expect(toApiAuthorSort('avg-rating')).toBe('avgRating');
    expect(toApiAuthorSort('series-count')).toBe('seriesCount');
  });

  it('builds paginated author browser params with server-side filters', () => {
    const params = buildAuthorBrowserParams(2, '  Ada  ', {
      matchStatus: 'matched',
      photoStatus: 'has-photo',
      readStatus: 'some-read',
      bookCount: '6-10',
      library: 7,
      genre: 'Science',
    }, 'reading-progress', 'desc');

    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('50');
    expect(params.get('search')).toBe('Ada');
    expect(params.get('sort')).toBe('readProgress');
    expect(params.get('dir')).toBe('desc');
    expect(params.get('matchStatus')).toBe('matched');
    expect(params.get('hasPhoto')).toBe('true');
    expect(params.get('readStatus')).toBe('some-read');
    expect(params.get('bookCount')).toBe('6-10');
    expect(params.get('libraryId')).toBe('7');
    expect(params.get('genre')).toBe('Science');
  });
});
