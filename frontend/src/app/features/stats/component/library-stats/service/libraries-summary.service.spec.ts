import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {LibrariesSummaryService} from './libraries-summary.service';
import {LibraryStatsApiService, LibraryStatsSnapshot} from './library-stats-api.service';

describe('LibrariesSummaryService', () => {
  afterEach(() => TestBed.resetTestingModule());

  function create(stats: LibraryStatsSnapshot | null) {
    TestBed.configureTestingModule({providers: [
      LibrariesSummaryService,
      {provide: LibraryStatsApiService, useValue: {data: signal(stats)}}
    ]});
    return TestBed.inject(LibrariesSummaryService);
  }

  it('returns zeroes before the server snapshot arrives', () => {
    expect(create(null).booksSummary()).toEqual({
      totalBooks: 0, totalSizeKb: 0, totalAuthors: 0, totalSeries: 0, totalPublishers: 0
    });
  });

  it('uses exact server totals and formats size', () => {
    const service = create({
      totalBooks: 42, totalSizeKb: 1024 * 1024, totalAuthors: 12, totalSeries: 4, totalPublishers: 3,
      averageDaysToFinish: 0,
      facets: {} as LibraryStatsSnapshot['facets'], booksAddedByMonth: [], booksFinishedByMonth: [],
      authorStats: [], bookFlow: [], publicationRatings: [], pageRatings: [], ratingTaste: []
    });
    expect(service.booksSummary()).toEqual({
      totalBooks: 42, totalSizeKb: 1024 * 1024, totalAuthors: 12, totalSeries: 4, totalPublishers: 3
    });
    expect(service.formattedSize()).toBe('1.00 GB');
  });
});
