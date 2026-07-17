import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {createAuthServiceStub, createQueryClientHarness} from '../../../core/testing/query-testing';
import {AuthService} from '../../../shared/service/auth.service';
import {AppBookSummary} from '../model/app-book.model';
import {AppBooksApiService} from './app-books-api.service';

function summary(id: number): AppBookSummary {
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
    isPhysical: false,
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
  };
}

describe('AppBooksApiService', () => {
  let service: AppBooksApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    const queryHarness = createQueryClientHarness();
    TestBed.configureTestingModule({
      providers: [
        ...queryHarness.providers,
        AppBooksApiService,
        {provide: AuthService, useValue: createAuthServiceStub()},
      ],
    });
    service = TestBed.inject(AppBooksApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('does not report loading while the paginated query is disabled', () => {
    expect(service.isLoading()).toBe(false);
  });

  it('loads summaries in bounded sequential batches and preserves requested order', () => {
    const ids = Array.from({length: 501}, (_, index) => index + 1);
    let result: number[] = [];

    service.getBooksByIds(ids).subscribe(books => {
      result = books.map(book => book.id);
    });

    const first = http.expectOne(request => request.url.endsWith('/api/v1/app/books/summaries'));
    expect(first.request.body).toEqual(ids.slice(0, 500));
    first.flush(ids.slice(0, 500).reverse().map(summary));

    const second = http.expectOne(request => request.url.endsWith('/api/v1/app/books/summaries'));
    expect(second.request.body).toEqual([501]);
    second.flush([summary(501)]);

    expect(result).toEqual(ids);
  });

  it('uses the bounded quick-search endpoint without requesting a page count', () => {
    let titles: (string | null)[] = [];

    service.quickSearchBooks('  Dune  ', 80).subscribe(results => {
      titles = results.map(result => result.title);
    });

    const request = http.expectOne(req => req.url.endsWith('/api/v1/app/books/quick-search'));
    expect(request.request.params.get('q')).toBe('Dune');
    expect(request.request.params.get('limit')).toBe('50');
    request.flush([{
      id: 1,
      title: 'Dune',
      authors: ['Frank Herbert'],
      seriesName: null,
      seriesNumber: null,
      publishedDate: null,
      primaryFileType: 'EPUB',
      primaryFileName: 'Dune.epub',
      coverUpdatedOn: null,
      audiobookCoverUpdatedOn: null,
    }]);

    expect(titles).toEqual(['Dune']);
  });
});
