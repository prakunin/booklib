import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {Tooltip} from 'primeng/tooltip';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {toSignal} from '@angular/core/rxjs-interop';
import {UserStatsService} from '../../../../../settings/user-management/user-stats.service';

type SurvivalChartData = ChartData<'line', number[], string>;

interface SurvivalMetrics {
  totalStarted: number;
  completionRate: number;
  medianDropout: string;
  dangerZoneRange: string;
  dangerZoneDrop: string;
  chartData: SurvivalChartData;
}

const THRESHOLDS = [0, 10, 25, 50, 75, 90, 100];

@Component({
  selector: 'app-reading-survival-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './reading-survival-chart.component.html',
  styleUrls: ['./reading-survival-chart.component.scss']
})
export class ReadingSurvivalChartComponent {
  private readonly userStatsService = inject(UserStatsService);
  private readonly t = inject(TranslocoService);
  private readonly distributions = toSignal(this.userStatsService.getBookDistributions(), {initialValue: null});
  private readonly survivalMetrics = computed<SurvivalMetrics>(() => this.calculateSurvivalMetrics());

  public readonly chartType = 'line' as const;
  public readonly totalStarted = computed(() => this.survivalMetrics().totalStarted);
  public readonly completionRate = computed(() => this.survivalMetrics().completionRate);
  public readonly medianDropout = computed(() => this.survivalMetrics().medianDropout);
  public readonly dangerZoneRange = computed(() => this.survivalMetrics().dangerZoneRange);
  public readonly dangerZoneDrop = computed(() => this.survivalMetrics().dangerZoneDrop);

  public readonly chartOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 10, bottom: 10, left: 10, right: 10}
    },
    plugins: {
      legend: {display: false},
      tooltip: {
        enabled: true,
        borderColor: '#e91e63',
        borderWidth: 1,
        cornerRadius: 6,
        padding: 12,
        titleFont: {size: 13, weight: 'bold'},
        bodyFont: {size: 12},
        callbacks: {
          title: (context) => this.t.translate('statsUser.readingSurvival.tooltipProgress', {label: context[0].label}),
          label: (context) => this.t.translate('statsUser.readingSurvival.tooltipSurvival', {value: (context.parsed.y ?? 0).toFixed(1)})
        }
      },
      datalabels: {display: false}
    },
    scales: {
      x: {
        title: {
          display: true,
          text: this.t.translate('statsUser.readingSurvival.axisProgressThreshold'),
          font: {family: "'Inter', sans-serif", size: 12, weight: 'bold'}
        },
        ticks: {
          font: {family: "'Inter', sans-serif", size: 11}
        },
        border: {display: false}
      },
      y: {
        min: 0,
        max: 100,
        title: {
          display: true,
          text: this.t.translate('statsUser.readingSurvival.axisBooksSurviving'),
          font: {family: "'Inter', sans-serif", size: 12, weight: 'bold'}
        },
        ticks: {
          font: {family: "'Inter', sans-serif", size: 11},
          callback: (value) => `${value}%`
        },
        border: {display: false}
      }
    }
  };

  public readonly chartData = computed(() => this.survivalMetrics().chartData);

  private calculateSurvivalMetrics(): SurvivalMetrics {
    const ranges = this.distributions()?.progressDistribution ?? [];
    const totalStarted = ranges.filter(range => range.max > 0).reduce((sum, range) => sum + range.count, 0);

    if (totalStarted === 0) {
      return this.emptyMetrics();
    }

    const survivalValues = THRESHOLDS.map(threshold => {
      const survived = ranges.reduce((sum, range) => {
        if (range.max < threshold || range.max === 0) return sum;
        if (range.min >= threshold) return sum + range.count;
        const width = Math.max(1, range.max - range.min);
        return sum + range.count * (range.max - threshold) / width;
      }, 0);
      return (survived / totalStarted) * 100;
    });

    const completionRate = Math.round(survivalValues.at(-1)!);

    const medianIdx = survivalValues.findIndex(v => v < 50);
    const medianDropout = medianIdx === -1
      ? '100%+'
      : medianIdx === 0
        ? `${THRESHOLDS[0]}%`
        : `${THRESHOLDS[medianIdx - 1]}-${THRESHOLDS[medianIdx]}%`;

    let maxDrop = 0;
    let dangerIdx = 0;
    for (let i = 1; i < survivalValues.length; i++) {
      const drop = survivalValues[i - 1] - survivalValues[i];
      if (drop > maxDrop) {
        maxDrop = drop;
        dangerIdx = i;
      }
    }
    const dangerZoneRange = `${THRESHOLDS[dangerIdx - 1]}-${THRESHOLDS[dangerIdx]}%`;
    const dangerZoneDrop = `-${maxDrop.toFixed(0)}%`;

    const labels = THRESHOLDS.map(t => `${t}%`);
    return {
      totalStarted,
      completionRate,
      medianDropout,
      dangerZoneRange,
      dangerZoneDrop,
      chartData: {
        labels,
        datasets: [{
          label: this.t.translate('statsUser.readingSurvival.survivalRate'),
          data: survivalValues,
          borderColor: '#e91e63',
          backgroundColor: 'rgba(233, 30, 99, 0.15)',
          fill: true,
          stepped: true,
          pointRadius: 5,
          pointHoverRadius: 7,
          pointBackgroundColor: '#e91e63',
          pointBorderWidth: 2,
          borderWidth: 2
        }]
      }
    };
  }

  private emptyMetrics(): SurvivalMetrics {
    return {
      totalStarted: 0,
      completionRate: 0,
      medianDropout: '',
      dangerZoneRange: '',
      dangerZoneDrop: '',
      chartData: {labels: [], datasets: []}
    };
  }

}
