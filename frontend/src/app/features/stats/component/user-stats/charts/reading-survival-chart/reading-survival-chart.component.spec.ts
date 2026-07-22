import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {of} from 'rxjs';
import {TranslocoService} from '@jsverse/transloco';
import {UserStatsService} from '../../../../../settings/user-management/user-stats.service';
import {ReadingSurvivalChartComponent} from './reading-survival-chart.component';

describe('ReadingSurvivalChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('derives a survival curve from compact server progress ranges', () => {
    TestBed.configureTestingModule({providers: [
      {provide: UserStatsService, useValue: {getBookDistributions: () => of({
        ratingDistribution: [], statusDistribution: [], progressDistribution: [
          {range: '0%', min: 0, max: 0, count: 2},
          {range: '1-25%', min: 0.1, max: 25, count: 2},
          {range: '26-50%', min: 26, max: 50, count: 1},
          {range: '76-99%', min: 76, max: 99, count: 1},
          {range: '100%', min: 100, max: 100, count: 1}
        ]
      })}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const component = TestBed.runInInjectionContext(() => new ReadingSurvivalChartComponent());
    expect(component.totalStarted()).toBe(5);
    expect(component.completionRate()).toBe(20);
    expect(component.chartData().labels).toEqual(['0%', '10%', '25%', '50%', '75%', '90%', '100%']);
  });
});
