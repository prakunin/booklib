import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {TranslocoService} from '@jsverse/transloco';
import {MetadataScoreChartComponent} from './metadata-score-chart.component';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

describe('MetadataScoreChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('combines materialized score buckets into the five chart ranges', () => {
    const matchScores = [0, 1, 2, 3, 4, 5, 6].map(name => ({name: String(name), count: name + 1}));
    TestBed.configureTestingModule({providers: [
      {provide: LibraryStatsApiService, useValue: {facets: signal({matchScores})}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const component = TestBed.runInInjectionContext(() => new MetadataScoreChartComponent());
    expect(component.scoreStats().map(item => item.count)).toEqual([3, 7, 5, 6, 7]);
    expect(component.totalBooks()).toBe(28);
    expect(component.chartData().datasets[0]?.data).toEqual([3, 7, 5, 6, 7]);
  });
});
