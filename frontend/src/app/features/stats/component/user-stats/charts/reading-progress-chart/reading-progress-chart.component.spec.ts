import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {of} from 'rxjs';
import {TranslocoService} from '@jsverse/transloco';
import {UserStatsService} from '../../../../../settings/user-management/user-stats.service';
import {ReadingProgressChartComponent} from './reading-progress-chart.component';

describe('ReadingProgressChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('renders server progress buckets without loading books', () => {
    const ranges = [
      {range: '0%', min: 0, max: 0, count: 1},
      {range: '1-25%', min: 0.1, max: 25, count: 2},
      {range: '26-50%', min: 26, max: 50, count: 3},
      {range: '51-75%', min: 51, max: 75, count: 4},
      {range: '76-99%', min: 76, max: 99, count: 5},
      {range: '100%', min: 100, max: 100, count: 6}
    ];
    TestBed.configureTestingModule({providers: [
      {provide: UserStatsService, useValue: {getBookDistributions: () => of({
        ratingDistribution: [], progressDistribution: ranges, statusDistribution: []
      })}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const component = TestBed.runInInjectionContext(() => new ReadingProgressChartComponent());
    expect(component.chartData().labels).toEqual(ranges.map(item => item.range));
    expect(component.chartData().datasets[0]?.data).toEqual([1, 2, 3, 4, 5, 6]);
  });
});
