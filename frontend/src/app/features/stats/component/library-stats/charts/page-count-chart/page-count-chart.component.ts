import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

interface PageRange {
  label: string;
  min: number;
  max: number;
  color: string;
}

interface PageStats {
  range: string;
  count: number;
  color: string;
}

type PageChartData = ChartData<'bar', number[], string>;

const PAGE_RANGES: PageRange[] = [
  {label: '<50', min: 0, max: 49, color: '#06B6D4'},
  {label: '50-99', min: 50, max: 99, color: '#0EA5E9'},
  {label: '100-199', min: 100, max: 199, color: '#3B82F6'},
  {label: '200-399', min: 200, max: 399, color: '#6366F1'},
  {label: '400-599', min: 400, max: 599, color: '#8B5CF6'},
  {label: '600-999', min: 600, max: 999, color: '#A855F7'},
  {label: '1000+', min: 1000, max: Infinity, color: '#D946EF'}
];

@Component({
  selector: 'app-page-count-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './page-count-chart.component.html',
  styleUrls: ['./page-count-chart.component.scss']
})
export class PageCountChartComponent {
  private readonly libraryStats = inject(LibraryStatsApiService);
  private readonly t = inject(TranslocoService);

  public readonly chartType = 'bar' as const;
  public readonly totalBooks = computed(() => (this.libraryStats.facets()?.pageCounts ?? []).reduce((sum, item) => sum + item.count, 0));

  public readonly chartOptions: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 10, bottom: 10}
    },
    plugins: {
      legend: {display: false},
      tooltip: {
        enabled: true,
        borderColor: '#8B5CF6',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 13, weight: 'bold'},
        bodyFont: {size: 11},
        callbacks: {
          title: (context) => this.t.translate('statsLibrary.pageCount.tooltipTitle', {label: context[0].label}),
          label: (context) => {
            const value = context.parsed.y;
            return value === 1
              ? this.t.translate('statsLibrary.pageCount.tooltipLabel', {value})
              : this.t.translate('statsLibrary.pageCount.tooltipLabelPlural', {value});
          }
        }
      },
      datalabels: {display: false}
    },
    scales: {
      x: {
        title: {
          display: true,
          text: this.t.translate('statsLibrary.pageCount.axisPageCount'),
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 10
          }
        },
        grid: {display: false},
        border: {display: false}
      },
      y: {
        title: {
          display: true,
          text: this.t.translate('statsLibrary.pageCount.axisBooks'),
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        beginAtZero: true,
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 10
          },
          stepSize: 1,
          maxTicksLimit: 6
        },
        grid: {
        },
        border: {display: false}
      }
    }
  };

  public readonly chartData = computed<PageChartData>(() => {
    const stats = this.calculatePageStats();
    if (stats.every(item => item.count === 0)) {
      return {labels: [], datasets: []};
    }
    const labels = stats.map(s => s.range);
    const data = stats.map(s => s.count);
    const colors = stats.map(s => s.color);

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors,
        borderWidth: 1,
        borderRadius: 4,
        barPercentage: 0.8,
        categoryPercentage: 0.7
      }]
    };
  });

  private calculatePageStats(): PageStats[] {
    const counts = new Map((this.libraryStats.facets()?.pageCounts ?? []).map(item => [Number(item.name), item.count]));
    return PAGE_RANGES.map((range, index) => ({
      range: range.label,
      count: counts.get(index) ?? 0,
      color: range.color
    }));
  }
}
