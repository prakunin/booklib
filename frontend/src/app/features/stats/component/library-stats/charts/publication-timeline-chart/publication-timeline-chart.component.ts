import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

interface DecadeStats {
  decade: string;
  label: string;
  count: number;
  color: string;
}

interface TimelineInsights {
  oldestBook: { title: string; year: number } | null;
  newestBook: { title: string; year: number } | null;
  averageYear: number;
  medianYear: number;
  totalWithDate: number;
  timeSpan: number;
  peakDecade: string;
  peakDecadeCount: number;
  centuryBreakdown: { c21: number; c20: number; older: number };
  goldenEra: { start: number; end: number; count: number };
  mostCommonYear: { year: number; count: number };
  rarityScore: number;
}

type TimelineChartData = ChartData<'bar', number[], string>;

// Color gradient from warm (old) to cool (new)
const DECADE_COLORS: Record<string, string> = {
  'pre1900': '#92400e',
  '1900s': '#b45309',
  '1910s': '#c2410c',
  '1920s': '#d97706',
  '1930s': '#e5932d',
  '1940s': '#eab308',
  '1950s': '#a3e635',
  '1960s': '#4ade80',
  '1970s': '#22d3ee',
  '1980s': '#38bdf8',
  '1990s': '#60a5fa',
  '2000s': '#818cf8',
  '2010s': '#a78bfa',
  '2020s': '#c084fc'
};

@Component({
  selector: 'app-publication-timeline-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './publication-timeline-chart.component.html',
  styleUrls: ['./publication-timeline-chart.component.scss']
})
export class PublicationTimelineChartComponent {
  private readonly libraryStats = inject(LibraryStatsApiService);
  private readonly t = inject(TranslocoService);
  private readonly yearCounts = computed(() => new Map(
    (this.libraryStats.facets()?.publishedYears ?? [])
      .map(item => [Number(item.name), item.count] as const)
      .filter(([year]) => Number.isInteger(year) && year >= 1000 && year <= new Date().getFullYear() + 1)
  ));
  private readonly decadeStats = computed(() => this.calculateDecadeStats(this.yearCounts()));

  public readonly chartType = 'bar' as const;
  public chartOptions: ChartConfiguration<'bar'>['options'];
  public readonly insights = computed(() => {
    return this.totalBooks() > 0 ? this.calculateInsights(this.yearCounts()) : null;
  });
  public readonly totalBooks = computed(() => Array.from(this.yearCounts().values()).reduce((sum, count) => sum + count, 0));
  public readonly chartData = computed<TimelineChartData>(() => {
    const stats = this.decadeStats();
    if (stats.length === 0) {
      return {labels: [], datasets: []};
    }

    const labels = stats.map(s => s.label);
    const data = stats.map(s => s.count);
    const colors = stats.map(s => s.color);

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors,
        borderColor: colors,
        borderWidth: 1,
        borderRadius: 4,
        barPercentage: 0.8,
        categoryPercentage: 0.85
      }]
    };
  });

  constructor() {
    this.initChartOptions();
  }

  private initChartOptions(): void {
    this.chartOptions = {
      responsive: true,
      maintainAspectRatio: false,
      indexAxis: 'y',
      layout: {
        padding: {top: 10, right: 20, bottom: 10, left: 10}
      },
      scales: {
        x: {
          beginAtZero: true,
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            precision: 0,
            stepSize: 1
          },
          grid: {
          },
          border: {display: false},
          title: {
            display: true,
            text: this.t.translate('statsLibrary.publicationTimeline.axisNumberOfBooks'),
            font: {
              family: "'Inter', sans-serif",
              size: 12,
              weight: 500
            }
          }
        },
        y: {
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            }
          },
          grid: {
            display: false
          },
          border: {display: false}
        }
      },
      plugins: {
        legend: {
          display: false
        },
        tooltip: {
          enabled: true,
          borderColor: '#a78bfa',
          borderWidth: 2,
          cornerRadius: 8,
          padding: 12,
          titleFont: {size: 13, weight: 'bold'},
          bodyFont: {size: 11},
          callbacks: {
            label: (context) => {
              const value = context.parsed.x;
              return value === 1
                ? this.t.translate('statsLibrary.publicationTimeline.tooltipBook', {value})
                : this.t.translate('statsLibrary.publicationTimeline.tooltipBooks', {value});
            }
          }
        },
        datalabels: {
          display: false
        }
      }
    };
  }

  private calculateDecadeStats(yearCounts: Map<number, number>): DecadeStats[] {
    const decadeCounts = new Map<string, number>();
    for (const [year, count] of yearCounts) {
      const decadeKey = this.getDecadeKey(year);
      decadeCounts.set(decadeKey, (decadeCounts.get(decadeKey) || 0) + count);
    }

    // Define decade order
    const decadeOrder = [
      'pre1900', '1900s', '1910s', '1920s', '1930s', '1940s',
      '1950s', '1960s', '1970s', '1980s', '1990s', '2000s', '2010s', '2020s'
    ];

    const decadeLabels: Record<string, string> = {
      'pre1900': 'Pre-1900',
      '1900s': '1900s',
      '1910s': '1910s',
      '1920s': '1920s',
      '1930s': '1930s',
      '1940s': '1940s',
      '1950s': '1950s',
      '1960s': '1960s',
      '1970s': '1970s',
      '1980s': '1980s',
      '1990s': '1990s',
      '2000s': '2000s',
      '2010s': '2010s',
      '2020s': '2020s'
    };

    return decadeOrder
      .filter(decade => decadeCounts.has(decade))
      .map(decade => ({
        decade,
        label: decadeLabels[decade],
        count: decadeCounts.get(decade) || 0,
        color: DECADE_COLORS[decade]
      }));
  }

  private getDecadeKey(year: number): string {
    if (year < 1900) return 'pre1900';
    if (year >= 2020) return '2020s';
    const decade = Math.floor(year / 10) * 10;
    return `${decade}s`;
  }

  private calculateInsights(yearCounts: Map<number, number>): TimelineInsights {
    const years = Array.from(yearCounts.keys()).sort((a, b) => a - b);
    const decadeCounts = this.computeDecadeCounts(yearCounts);
    const centuryBreakdown = this.computeCenturyBreakdown(yearCounts);
    const total = Array.from(yearCounts.values()).reduce((sum, count) => sum + count, 0);

    const oldest = years.length ? {title: '—', year: years[0]} : null;
    const newest = years.length ? {title: '—', year: years.at(-1)!} : null;
    const {peakDecade, peakDecadeCount} = this.computePeakDecade(decadeCounts);
    const timeSpan = oldest && newest ? newest.year - oldest.year : 0;

    return {
      oldestBook: oldest,
      newestBook: newest,
      averageYear: this.computeAverageYear(yearCounts, total),
      medianYear: this.computeMedianYear(years, yearCounts, total),
      totalWithDate: total,
      timeSpan,
      peakDecade,
      peakDecadeCount,
      centuryBreakdown,
      goldenEra: this.computeGoldenEra(years, yearCounts),
      mostCommonYear: this.computeMostCommonYear(yearCounts),
      rarityScore: this.computeRarityScore(decadeCounts, total)
    };
  }

  private computeDecadeCounts(yearCounts: Map<number, number>): Map<string, number> {
    const decadeCounts = new Map<string, number>();
    for (const [year, count] of yearCounts) {
      const decadeKey = this.getDecadeKey(year);
      decadeCounts.set(decadeKey, (decadeCounts.get(decadeKey) || 0) + count);
    }
    return decadeCounts;
  }

  private computeCenturyBreakdown(yearCounts: Map<number, number>): { c21: number; c20: number; older: number } {
    let c21 = 0, c20 = 0, older = 0;
    for (const [year, count] of yearCounts) {
      if (year >= 2000) c21 += count;
      else if (year >= 1900) c20 += count;
      else older += count;
    }
    return {c21, c20, older};
  }

  private computeAverageYear(yearCounts: Map<number, number>, total: number): number {
    if (total <= 0) return 0;
    const weightedSum = Array.from(yearCounts).reduce((sum, [year, count]) => sum + year * count, 0);
    return Math.round(weightedSum / total);
  }

  private computeMedianYear(years: number[], yearCounts: Map<number, number>, total: number): number {
    let cumulative = 0;
    return years.find(year => (cumulative += yearCounts.get(year) ?? 0) >= total / 2) ?? 0;
  }

  private computePeakDecade(decadeCounts: Map<string, number>): { peakDecade: string; peakDecadeCount: number } {
    let peakDecade = '';
    let peakDecadeCount = 0;
    for (const [decade, count] of decadeCounts) {
      if (count > peakDecadeCount) {
        peakDecade = decade === 'pre1900' ? 'Pre-1900' : decade;
        peakDecadeCount = count;
      }
    }
    return {peakDecade, peakDecadeCount};
  }

  // Golden Era: best 20-year window
  private computeGoldenEra(years: number[], yearCounts: Map<number, number>): { start: number; end: number; count: number } {
    let goldenEra = {start: 0, end: 0, count: 0};
    for (const windowStart of years) {
      const windowEnd = windowStart + 19;
      const windowCount = years
        .filter(y => y >= windowStart && y <= windowEnd)
        .reduce((sum, year) => sum + (yearCounts.get(year) ?? 0), 0);
      if (windowCount > goldenEra.count) {
        goldenEra = {start: windowStart, end: windowEnd, count: windowCount};
      }
    }
    return goldenEra;
  }

  private computeMostCommonYear(yearCounts: Map<number, number>): { year: number; count: number } {
    let mostCommonYear = {year: 0, count: 0};
    for (const [y, c] of yearCounts) {
      if (c > mostCommonYear.count) {
        mostCommonYear = {year: y, count: c};
      }
    }
    return mostCommonYear;
  }

  // Rarity Score: % of books in decades with fewer than 3 books
  private computeRarityScore(decadeCounts: Map<string, number>, total: number): number {
    let rareBooks = 0;
    for (const count of decadeCounts.values()) {
      if (count < 3) rareBooks += count;
    }
    return total > 0 ? Math.round((rareBooks / total) * 100) : 0;
  }
}
