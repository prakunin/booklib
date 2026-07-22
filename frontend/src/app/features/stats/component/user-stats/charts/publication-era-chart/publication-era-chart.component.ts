import {Component, effect, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {Tooltip} from 'primeng/tooltip';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {sortStrings} from '../../../../../../shared/util/string-sort.util';
import {UserBookStatsService} from '../../service/user-book-stats.service';

@Component({
  selector: 'app-publication-era-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './publication-era-chart.component.html',
  styleUrls: ['./publication-era-chart.component.scss']
})
export class PublicationEraChartComponent {
  private readonly userBookStats = inject(UserBookStatsService);
  private readonly t = inject(TranslocoService);
  private readonly syncChartEffect = effect(() => {
    this.processData();
  });

  public readonly chartType = 'line' as const;
  public hasData = false;
  public bestDecade = '';
  public bestAvgRating = 0;
  public totalRated = 0;

  private readonly DECADE_COLORS = [
    '#e91e63', '#9c27b0', '#673ab7', '#3f51b5',
    '#2196f3', '#00bcd4', '#009688', '#4caf50',
    '#8bc34a', '#ff9800'
  ];

  public chartData: ChartData<'line', number[], string> = {labels: [], datasets: []};

  public readonly chartOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    animation: {duration: 400},
    layout: {padding: {top: 10, right: 10}},
    plugins: {
      legend: {
        display: true, position: 'bottom',
        labels: {font: {family: "'Inter', sans-serif", size: 11}, boxWidth: 12, padding: 12}
      },
      tooltip: {
        enabled: true, borderWidth: 1, cornerRadius: 6, padding: 10,
        callbacks: {
          label: (ctx) => {
            return `${ctx.dataset.label}: ${ctx.parsed.y} books`;
          }
        }
      },
      datalabels: {display: false}
    },
    scales: {
      x: {
        ticks: {font: {size: 11}},
        title: {display: true, text: 'Rating Range', font: {size: 11}}
      },
      y: {
        beginAtZero: true,
        ticks: {font: {size: 11}, stepSize: 1},
        title: {display: true, text: 'Books', font: {size: 11}}
      }
    },
    interaction: {mode: 'index', intersect: false}
  };

  private processData(): void {
    const ratings = this.userBookStats.data()?.publicationRatings ?? [];
    if (ratings.length < 3) {
      this.hasData = false;
      this.bestDecade = '';
      this.bestAvgRating = 0;
      this.totalRated = 0;
      this.chartData = {labels: [], datasets: []};
      return;
    }

    const decadeData = new Map<string, Map<number, number>>();
    const decadeAvg = new Map<string, {total: number; count: number}>();
    const ratingLabels = ['1-2', '3-4', '5-6', '7-8', '9-10'];
    this.totalRated = 0;
    for (const item of ratings) {
      const pubYear = item.year;
      if (pubYear < 1900 || pubYear > 2030) continue;
      const decade = `${Math.floor(pubYear / 10) * 10}s`;
      const bucket = Math.min(4, Math.floor((item.personalRating - 1) / 2));
      if (!decadeData.has(decade)) decadeData.set(decade, new Map());
      const buckets = decadeData.get(decade)!;
      buckets.set(bucket, (buckets.get(bucket) ?? 0) + item.count);
      const average = decadeAvg.get(decade) ?? {total: 0, count: 0};
      average.total += item.personalRating * item.count;
      average.count += item.count;
      decadeAvg.set(decade, average);
      this.totalRated += item.count;
    }
    if (decadeData.size === 0) return;
    const decades = sortStrings([...decadeData.keys()]);
    const best = [...decadeAvg.entries()]
      .map(([decade, value]) => [decade, value.total / value.count] as const)
      .sort((a, b) => b[1] - a[1])[0];
    this.bestDecade = best?.[0] ?? '';
    this.bestAvgRating = Math.round((best?.[1] ?? 0) * 10) / 10;
    this.chartData = {
      labels: ratingLabels,
      datasets: decades.map((decade, index) => ({
        label: decade,
        data: ratingLabels.map((_, bucket) => decadeData.get(decade)?.get(bucket) ?? 0),
        borderColor: this.DECADE_COLORS[index % this.DECADE_COLORS.length],
        backgroundColor: this.DECADE_COLORS[index % this.DECADE_COLORS.length] + '20',
        borderWidth: 2.5,
        pointRadius: 5,
        pointBackgroundColor: this.DECADE_COLORS[index % this.DECADE_COLORS.length],
        tension: 0.3,
        fill: false
      }))
    };
    this.hasData = true;
  }
}
