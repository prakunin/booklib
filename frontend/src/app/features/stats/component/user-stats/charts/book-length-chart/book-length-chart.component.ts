import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {Tooltip} from 'primeng/tooltip';
import {ChartConfiguration, ChartData, ScatterDataPoint} from 'chart.js';
import {ReadStatus} from '../../../../../book/model/book.model';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {UserBookStatsService} from '../../service/user-book-stats.service';

interface BookScatterPoint extends ScatterDataPoint {
  bookTitle: string;
  readStatus: string;
  count: number;
}

type LengthChartData = ChartData<'scatter', BookScatterPoint[], string>;

interface BookLengthMetrics {
  totalRatedBooks: number;
  sweetSpot: string;
  highestRatedLength: string;
  chartData: LengthChartData;
}

const STATUS_COLORS: Record<string, { bg: string; border: string }> = {
  'read': {bg: 'rgba(76, 175, 80, 0.7)', border: '#4caf50'},
  'reading': {bg: 'rgba(33, 150, 243, 0.7)', border: '#2196f3'},
  'abandoned': {bg: 'rgba(244, 67, 54, 0.7)', border: '#f44336'},
  'other': {bg: 'rgba(158, 158, 158, 0.7)', border: '#9e9e9e'}
};

@Component({
  selector: 'app-book-length-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './book-length-chart.component.html',
  styleUrls: ['./book-length-chart.component.scss']
})
export class BookLengthChartComponent {
  private readonly userBookStats = inject(UserBookStatsService);
  private readonly t = inject(TranslocoService);
  private readonly metrics = computed<BookLengthMetrics>(() => this.calculateMetrics());

  public readonly chartType = 'scatter' as const;
  public readonly sweetSpot = computed(() => this.metrics().sweetSpot);
  public readonly highestRatedLength = computed(() => this.metrics().highestRatedLength);
  public readonly totalRatedBooks = computed(() => this.metrics().totalRatedBooks);

  public readonly chartOptions: ChartConfiguration<'scatter'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 10, right: 20, bottom: 10, left: 10}
    },
    scales: {
      x: {
        title: {
          display: true,
          text: this.t.translate('statsUser.bookLength.axisPageCount'),
          font: {family: "'Inter', sans-serif", size: 12, weight: 'bold'}
        },
        ticks: {
          font: {family: "'Inter', sans-serif", size: 11}
        }
      },
      y: {
        min: 0,
        max: 10,
        title: {
          display: true,
          text: this.t.translate('statsUser.bookLength.axisPersonalRating'),
          font: {family: "'Inter', sans-serif", size: 12, weight: 'bold'}
        },
        ticks: {
          stepSize: 1,
          font: {family: "'Inter', sans-serif", size: 11}
        }
      }
    },
    plugins: {
      legend: {
        display: true,
        position: 'top',
        labels: {
          font: {family: "'Inter', sans-serif", size: 11},
          usePointStyle: true,
          pointStyle: 'circle',
          padding: 15
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#00bcd4',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 13, weight: 'bold'},
        bodyFont: {size: 11},
        callbacks: {
          title: (context) => {
            const point = context[0].raw as BookScatterPoint;
            return point.bookTitle || this.t.translate('statsUser.bookLength.tooltipUnknownBook');
          },
          label: (context) => {
            const point = context.raw as BookScatterPoint;
            return [
              this.t.translate('statsUser.bookLength.tooltipPages', {count: point.x}),
              this.t.translate('statsUser.bookLength.tooltipRating', {rating: point.y}),
              this.t.translate('statsUser.bookLength.tooltipStatus', {status: point.readStatus})
            ];
          }
        }
      },
      datalabels: {display: false}
    },
    elements: {
      point: {
        radius: 6,
        hoverRadius: 9,
        borderWidth: 2
      }
    }
  };

  public readonly chartData = computed(() => this.metrics().chartData);

  private calculateMetrics(): BookLengthMetrics {
    const aggregates = this.userBookStats.data()?.pageRatings ?? [];
    if (aggregates.length === 0) {
      return this.emptyMetrics();
    }
    const grouped = new Map<string, {label: string; points: BookScatterPoint[]}>();
    for (const item of aggregates) {
      const status = item.readStatus as ReadStatus;
      const key = this.getStatusKey(status);
      const label = this.getStatusLabel(status);
      if (!grouped.has(key)) grouped.set(key, {label, points: []});
      grouped.get(key)!.points.push({
        x: item.pageCount,
        y: item.personalRating,
        count: item.count,
        bookTitle: `${item.count} books`,
        readStatus: label
      });
    }
    const datasets = [...grouped.entries()].map(([key, value]) => {
      const colors = STATUS_COLORS[key] ?? STATUS_COLORS['other'];
      const count = value.points.reduce((sum, point) => sum + point.count, 0);
      return {
        label: `${value.label} (${count})`,
        data: value.points,
        backgroundColor: colors.bg,
        borderColor: colors.border,
        pointRadius: 6,
        pointHoverRadius: 9,
        pointBorderWidth: 2
      };
    });
    const ranges = [
      {label: '0-100', min: 0, max: 100}, {label: '101-200', min: 101, max: 200},
      {label: '201-300', min: 201, max: 300}, {label: '301-400', min: 301, max: 400},
      {label: '401-500', min: 401, max: 500}, {label: '501+', min: 501, max: Number.MAX_SAFE_INTEGER}
    ];
    let sweetSpot = '-';
    let bestAverage = 0;
    for (const range of ranges) {
      const values = aggregates.filter(item => item.pageCount >= range.min && item.pageCount <= range.max);
      const count = values.reduce((sum, item) => sum + item.count, 0);
      if (count < 2) continue;
      const average = values.reduce((sum, item) => sum + item.personalRating * item.count, 0) / count;
      if (average > bestAverage) {
        bestAverage = average;
        sweetSpot = `${range.label} pages (avg ${average.toFixed(1)})`;
      }
    }
    const highest = [...aggregates].sort((a, b) => b.personalRating - a.personalRating || b.count - a.count)[0];
    return {
      totalRatedBooks: aggregates.reduce((sum, item) => sum + item.count, 0),
      sweetSpot,
      highestRatedLength: highest ? `${highest.pageCount} pages` : '-',
      chartData: {datasets}
    };
  }

  private getStatusKey(status: ReadStatus): string {
    switch (status) {
      case ReadStatus.READ:
      case ReadStatus.PARTIALLY_READ: return 'read';
      case ReadStatus.READING:
      case ReadStatus.RE_READING: return 'reading';
      case ReadStatus.ABANDONED:
      case ReadStatus.WONT_READ: return 'abandoned';
      default: return 'other';
    }
  }

  private getStatusLabel(status: ReadStatus): string {
    const key = this.getStatusKey(status);
    return this.t.translate(`statsUser.bookLength.status${key[0].toUpperCase()}${key.slice(1)}`);
  }

  private emptyMetrics(): BookLengthMetrics {
    return {
      totalRatedBooks: 0,
      sweetSpot: '',
      highestRatedLength: '',
      chartData: {datasets: []}
    };
  }
}
