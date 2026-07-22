import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {TranslocoService} from '@jsverse/transloco';
import {BookFormatsChartComponent} from './book-formats-chart.component';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

describe('BookFormatsChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('renders server format facets without loading books', () => {
    const facets = signal({fileTypes: [{name: 'EPUB', count: 4}, {name: 'PDF', count: 3}]});
    TestBed.configureTestingModule({providers: [
      {provide: LibraryStatsApiService, useValue: {facets, data: signal({totalBooks: 7})}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const component = TestBed.runInInjectionContext(() => new BookFormatsChartComponent());
    expect(component.totalBooks()).toBe(7);
    expect(component.formatStats()).toEqual([
      {format: 'EPUB', count: 4, percentage: 4 / 7 * 100},
      {format: 'PDF', count: 3, percentage: 3 / 7 * 100}
    ]);
    expect(component.chartData().datasets[0]?.data).toEqual([4, 3]);
  });
});
