import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

interface FormatStats {
  format: string;
  count: number;
  percentage: number;
}

type FormatChartData = ChartData<'pie', number[], string>;

const FORMAT_COLORS: Record<string, string> = {
  'PDF': '#E11D48',    // Rose
  'EPUB': '#0D9488',   // Teal
  'CBX': '#7C3AED',    // Violet
  'FB2': '#F59E0B',    // Amber
  'MOBI': '#2563EB',   // Blue
  'AZW3': '#16A34A'    // Green
};

@Component({
  selector: 'app-book-formats-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './book-formats-chart.component.html',
  styleUrls: ['./book-formats-chart.component.scss']
})
export class BookFormatsChartComponent {
  private readonly libraryStats = inject(LibraryStatsApiService);
  private readonly t = inject(TranslocoService);

  public readonly chartType = 'pie' as const;
  public readonly formatStats = computed<FormatStats[]>(() => {
    const formats = this.libraryStats.facets()?.fileTypes ?? [];
    const total = formats.reduce((sum, item) => sum + item.count, 0);
    return formats.map(item => ({
      format: item.name,
      count: item.count,
      percentage: total > 0 ? item.count / total * 100 : 0
    }));
  });
  public readonly totalBooks = computed(() => this.libraryStats.data()?.totalBooks ?? 0);

  public readonly chartOptions: ChartConfiguration<'pie'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 10, bottom: 10}
    },
    plugins: {
      legend: {
        display: true,
        position: 'right',
        labels: {
          font: {
            family: "'Inter', sans-serif",
            size: 12
          },
          usePointStyle: true,
          pointStyle: 'circle',
          padding: 15
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#E11D48',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 12},
        callbacks: {
          label: (context) => {
            const value = context.parsed;
            const total = context.dataset.data.reduce((a: number, b: number) => a + b, 0);
            const percentage = ((value / total) * 100).toFixed(1);
            return this.t.translate('statsLibrary.bookFormats.tooltipLabel', {label: context.label, value, percentage});
          }
        }
      },
      datalabels: {
        display: false
      }
    }
  };

  public readonly chartData = computed<FormatChartData>(() => {
    const stats = this.formatStats();
    if (stats.length === 0) {
      return {labels: [], datasets: []};
    }

    const labels = stats.map(s => s.format);
    const data = stats.map(s => s.count);
    const colors = stats.map(s => FORMAT_COLORS[s.format] || '#6B7280');

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors
      }]
    };
  });

}
