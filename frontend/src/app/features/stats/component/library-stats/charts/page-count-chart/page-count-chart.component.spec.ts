import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {TranslocoService} from '@jsverse/transloco';
import {PageCountChartComponent} from './page-count-chart.component';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

describe('PageCountChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('renders the seven server-side page-count buckets in order', () => {
    const pageCounts = [0, 1, 2, 3, 4, 5, 6].map(name => ({name: String(name), count: name + 2}));
    TestBed.configureTestingModule({providers: [
      {provide: LibraryStatsApiService, useValue: {facets: signal({pageCounts})}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const component = TestBed.runInInjectionContext(() => new PageCountChartComponent());
    expect(component.totalBooks()).toBe(35);
    expect(component.chartData().labels).toEqual(['<50', '50-99', '100-199', '200-399', '400-599', '600-999', '1000+']);
    expect(component.chartData().datasets[0]?.data).toEqual([2, 3, 4, 5, 6, 7, 8]);
  });
});
