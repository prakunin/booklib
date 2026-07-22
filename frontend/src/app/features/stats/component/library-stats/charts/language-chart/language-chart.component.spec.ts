import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {TranslocoService} from '@jsverse/transloco';
import {LanguageChartComponent} from './language-chart.component';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

describe('LanguageChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('uses the top server language facets and their display labels', () => {
    const languages = Array.from({length: 17}, (_, index) => ({code: `l${index}`, label: `Language ${index}`, count: 20 - index}));
    TestBed.configureTestingModule({providers: [
      {provide: LibraryStatsApiService, useValue: {facets: signal({languages}), data: signal({totalBooks: 100})}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const component = TestBed.runInInjectionContext(() => new LanguageChartComponent());
    expect(component.totalBooks()).toBe(100);
    expect(component.languageStats()).toHaveLength(15);
    expect(component.chartData().labels?.[0]).toBe('Language 0');
  });
});
