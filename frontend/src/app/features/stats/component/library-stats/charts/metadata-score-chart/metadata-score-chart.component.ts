import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

interface ScoreStats {
  range: string;
  count: number;
  percentage: number;
  color: string;
}

type ScoreChartData = ChartData<'doughnut', number[], string>;
type ScoreRangeKey = 'excellent' | 'good' | 'fair' | 'poor' | 'veryPoor';

const SCORE_RANGE_DEFS: { key: ScoreRangeKey; min: number; max: number; color: string }[] = [
  {key: 'excellent', min: 90, max: 100, color: '#16A34A'},
  {key: 'good', min: 70, max: 89, color: '#22C55E'},
  {key: 'fair', min: 50, max: 69, color: '#F59E0B'},
  {key: 'poor', min: 25, max: 49, color: '#F97316'},
  {key: 'veryPoor', min: 0, max: 24, color: '#DC2626'}
];

@Component({
  selector: 'app-metadata-score-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './metadata-score-chart.component.html',
  styleUrls: ['./metadata-score-chart.component.scss']
})
export class MetadataScoreChartComponent {
  private readonly libraryStats = inject(LibraryStatsApiService);
  private readonly t = inject(TranslocoService);

  public readonly chartType = 'doughnut' as const;
  public readonly scoreStats = computed(() => this.calculateScoreStats());
  public readonly totalBooks = computed(() => this.scoreStats().reduce((sum, item) => sum + item.count, 0));
  public readonly averageScore = computed(() => {
    const midpoints = [98, 92, 85, 75, 60, 40, 15];
    const options = this.libraryStats.facets()?.matchScores ?? [];
    const total = options.reduce((sum, item) => sum + item.count, 0);
    if (total === 0) return 0;
    return Math.round(options.reduce((sum, item) => sum + (midpoints[Number(item.name)] ?? 0) * item.count, 0) / total);
  });

  public readonly chartOptions: ChartConfiguration<'doughnut'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    cutout: '60%',
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
            size: 11
          },
          usePointStyle: true,
          pointStyle: 'circle',
          padding: 12,
          boxWidth: 8
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#16A34A',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 13, weight: 'bold'},
        bodyFont: {size: 11},
        callbacks: {
          label: (context) => {
            const value = context.parsed;
            const total = context.dataset.data.reduce((a: number, b: number) => a + b, 0);
            const percentage = ((value / total) * 100).toFixed(1);
            return this.t.translate('statsLibrary.metadataScore.tooltipLabel', {value, percentage});
          }
        }
      },
      datalabels: {
        display: false
      }
    }
  };

  public readonly chartData = computed<ScoreChartData>(() => {
    const stats = this.scoreStats();
    if (stats.length === 0) {
      return {labels: [], datasets: []};
    }

    const labels = stats.map(s => s.range);
    const data = stats.map(s => s.count);
    const colors = stats.map(s => s.color);

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors
      }]
    };
  });

  private calculateScoreStats(): ScoreStats[] {
    const source = new Map((this.libraryStats.facets()?.matchScores ?? []).map(item => [Number(item.name), item.count]));
    const counts = [
      (source.get(0) ?? 0) + (source.get(1) ?? 0),
      (source.get(2) ?? 0) + (source.get(3) ?? 0),
      source.get(4) ?? 0,
      source.get(5) ?? 0,
      source.get(6) ?? 0
    ];
    const total = counts.reduce((sum, count) => sum + count, 0);
    return SCORE_RANGE_DEFS
      .map((range, index) => ({
        range: this.t.translate(`statsLibrary.metadataScore.${range.key}`),
        count: counts[index],
        percentage: total > 0 ? counts[index] / total * 100 : 0,
        color: range.color
      }))
      .filter(stat => stat.count > 0);
  }
}
