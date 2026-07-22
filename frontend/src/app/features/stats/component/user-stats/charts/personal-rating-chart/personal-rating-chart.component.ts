import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {Tooltip} from 'primeng/tooltip';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {toSignal} from '@angular/core/rxjs-interop';
import {UserStatsService} from '../../../../../settings/user-management/user-stats.service';

interface RatingStats {
  ratingRange: string;
  count: number;
  averageRating: number;
}

const CHART_COLORS = [
  '#DC2626', // Red (rating 1)
  '#EA580C', // Red-orange (rating 2)
  '#F59E0B', // Orange (rating 3)
  '#EAB308', // Yellow-orange (rating 4)
  '#FACC15', // Yellow (rating 5)
  '#BEF264', // Yellow-green (rating 6)
  '#65A30D', // Green (rating 7)
  '#16A34A', // Green (rating 8)
  '#059669', // Teal-green (rating 9)
  '#2563EB'  // Blue (rating 10)
] as const;

const CHART_DEFAULTS = {
  borderWidth: 1,
  hoverBorderWidth: 2,
} as const;

const RATING_RANGES = [
  {range: '1', min: 1, max: 1},
  {range: '2', min: 2, max: 2},
  {range: '3', min: 3, max: 3},
  {range: '4', min: 4, max: 4},
  {range: '5', min: 5, max: 5},
  {range: '6', min: 6, max: 6},
  {range: '7', min: 7, max: 7},
  {range: '8', min: 8, max: 8},
  {range: '9', min: 9, max: 9},
  {range: '10', min: 10, max: 10}
] as const;

type RatingChartData = ChartData<'bar', number[], string>;

@Component({
  selector: 'app-personal-rating-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './personal-rating-chart.component.html',
  styleUrls: ['./personal-rating-chart.component.scss']
})
export class PersonalRatingChartComponent {
  private readonly userStatsService = inject(UserStatsService);
  private readonly t = inject(TranslocoService);
  private readonly distributions = toSignal(this.userStatsService.getBookDistributions(), {initialValue: null});
  private readonly ratingStats = computed<RatingStats[]>(() =>
    (this.distributions()?.ratingDistribution ?? []).map(item => ({
      ratingRange: String(item.rating),
      count: item.count,
      averageRating: item.rating
    }))
  );

  public readonly chartType = 'bar' as const;

  public readonly chartOptions: ChartConfiguration['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 25}
    },
    plugins: {
      legend: {display: false},
      tooltip: {
        enabled: true,
        borderWidth: 1,
        cornerRadius: 6,
        displayColors: true,
        padding: 12,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 13},
        callbacks: {
          title: (context) => this.t.translate('statsUser.personalRating.tooltipTitle', {label: context[0].label}),
          label: (context) => {
            const value = context.parsed.y;
            const key = value === 1 ? 'statsUser.personalRating.tooltipBook' : 'statsUser.personalRating.tooltipBooks';
            return this.t.translate(key, {value});
          }
        }
      },
      datalabels: {display: false}
    },
    scales: {
      x: {
        title: {
          display: true,
          text: this.t.translate('statsUser.personalRating.axisPersonalRating'),
          font: {
            family: "'Inter', sans-serif",
            size: 12
          }
        },
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        grid: {display: false},
        border: {display: false}
      },
      y: {
        title: {
          display: true,
          text: this.t.translate('statsUser.personalRating.axisNumberOfBooks'),
          font: {
            family: "'Inter', sans-serif",
            size: 12
          }
        },
        beginAtZero: true,
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 11
          },
          stepSize: 1,
          maxTicksLimit: 8
        },
        grid: {
        },
        border: {display: false}
      }
    }
  };

  public readonly chartData = computed<RatingChartData>(() => {
    try {
      const stats = this.ratingStats();
      const allLabels = RATING_RANGES.map(r => r.range);
      const dataValues = allLabels.map(label => {
        const stat = stats.find(s => s.ratingRange === label);
        return stat ? stat.count : 0;
      });
      const colors = allLabels.map((_, index) => CHART_COLORS[index % CHART_COLORS.length]);

      return {
        labels: allLabels,
        datasets: [{
          label: this.t.translate('statsUser.personalRating.booksByPersonalRating'),
          data: dataValues,
          backgroundColor: colors,
          borderColor: colors.map(color => color),
          borderWidth: 1,
          borderRadius: 4,
          barPercentage: 0.8,
          categoryPercentage: 0.6
        }]
      };
    } catch (error) {
      console.error('Error updating personal rating chart data:', error);
      return {
        labels: [],
        datasets: [{
          label: this.t.translate('statsUser.personalRating.booksByPersonalRating'),
          data: [],
          backgroundColor: [...CHART_COLORS],
          ...CHART_DEFAULTS
        }]
      };
    }
  });

}
