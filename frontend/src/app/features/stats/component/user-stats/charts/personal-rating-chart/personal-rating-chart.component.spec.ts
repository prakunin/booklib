import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {of} from 'rxjs';
import {TranslocoService} from '@jsverse/transloco';
import {UserStatsService} from '../../../../../settings/user-management/user-stats.service';
import {PersonalRatingChartComponent} from './personal-rating-chart.component';

describe('PersonalRatingChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('renders the server rating distribution with fixed zero buckets', () => {
    TestBed.configureTestingModule({providers: [
      {provide: UserStatsService, useValue: {getBookDistributions: () => of({
        ratingDistribution: [{rating: 1, count: 1}, {rating: 2, count: 2}, {rating: 10, count: 3}],
        progressDistribution: [], statusDistribution: []
      })}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const component = TestBed.runInInjectionContext(() => new PersonalRatingChartComponent());
    expect(component.chartData().labels).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '10']);
    expect(component.chartData().datasets[0]?.data).toEqual([1, 2, 0, 0, 0, 0, 0, 0, 0, 3]);
  });
});
