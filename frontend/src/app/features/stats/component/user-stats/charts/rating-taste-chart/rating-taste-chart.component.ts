import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {Tooltip} from 'primeng/tooltip';
import {ChartConfiguration, ChartData, ScatterDataPoint} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {UserBookStatsService} from '../../service/user-book-stats.service';
import {RatingTasteCount} from '../../../library-stats/service/library-stats-api.service';

interface TasteQuadrant {
  name: string;
  description: string;
  count: number;
  color: string;
  icon: string;
}

interface BookDataPoint extends ScatterDataPoint {
  bookTitle: string;
  personalRating: number;
  personalRatingNormalized: number;
  externalRating: number;
  quadrant: string;
  weight: number;
}

type RatingTasteChartData = ChartData<'scatter', BookDataPoint[], string>;

interface RatingTasteMetrics {
  quadrants: TasteQuadrant[];
  totalRatedBooks: number;
  averageDeviation: number;
  chartData: RatingTasteChartData;
}

@Component({
  selector: 'app-rating-taste-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './rating-taste-chart.component.html',
  styleUrls: ['./rating-taste-chart.component.scss']
})
export class RatingTasteChartComponent {
  private readonly userBookStats = inject(UserBookStatsService);
  private readonly t = inject(TranslocoService);
  private readonly metrics = computed<RatingTasteMetrics>(() => this.calculateMetrics());

  public readonly chartType = 'scatter' as const;
  public readonly quadrants = computed(() => this.metrics().quadrants);
  public readonly totalRatedBooks = computed(() => this.metrics().totalRatedBooks);
  public readonly averageDeviation = computed(() => this.metrics().averageDeviation);

  public readonly chartOptions: ChartConfiguration<'scatter'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 20, right: 20, bottom: 10, left: 10}
    },
    scales: {
      x: {
        min: 0,
        max: 5,
        title: {
          display: true,
          text: this.t.translate('statsUser.ratingTaste.axisExternalRating'),
          font: {
            family: "'Inter', sans-serif",
            size: 12,
            weight: 500
          }
        },
        ticks: {
          stepSize: 1,
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        grid: {
          drawTicks: true
        }
      },
      y: {
        min: 0,
        max: 5,
        title: {
          display: true,
          text: this.t.translate('statsUser.ratingTaste.axisPersonalRating'),
          font: {
            family: "'Inter', sans-serif",
            size: 12,
            weight: 500
          }
        },
        ticks: {
          stepSize: 1,
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        grid: {
          drawTicks: true
        }
      }
    },
    plugins: {
      legend: {
        display: true,
        position: 'top',
        labels: {
          font: {
            family: "'Inter', sans-serif",
            size: 11
          },
          usePointStyle: true,
          pointStyle: 'circle',
          padding: 15
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#9c27b0',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 13, weight: 'bold'},
        bodyFont: {size: 11},
        callbacks: {
          title: (context) => {
            const point = context[0].raw as BookDataPoint;
            return point.bookTitle || this.t.translate('statsUser.ratingTaste.tooltipUnknownBook');
          },
          label: (context) => {
            const point = context.raw as BookDataPoint;
            const diff = point.personalRatingNormalized - point.externalRating;
            const diffText = diff > 0 ? `+${diff.toFixed(1)}` : diff.toFixed(1);
            return [
              this.t.translate('statsUser.ratingTaste.tooltipYourRating', {rating: point.personalRating, normalized: point.personalRatingNormalized.toFixed(1)}),
              this.t.translate('statsUser.ratingTaste.tooltipExternalRating', {rating: point.externalRating.toFixed(1)}),
              this.t.translate('statsUser.ratingTaste.tooltipDifference', {diff: diffText}),
              this.t.translate('statsUser.ratingTaste.tooltipCategory', {category: point.quadrant})
            ];
          }
        }
      }
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

  private calculateMetrics(): RatingTasteMetrics {
    const ratings = this.userBookStats.data()?.ratingTaste ?? [];
    if (ratings.length === 0) {
      return this.emptyMetrics();
    }
    const dataPoints = this.categorizeRatings(ratings);
    const totalRatedBooks = ratings.reduce((sum, item) => sum + item.count, 0);
    const chartData = this.buildChartData(dataPoints);
    const {quadrants, averageDeviation} = this.calculateStatistics(dataPoints);

    return {
      quadrants,
      totalRatedBooks,
      averageDeviation,
      chartData
    };
  }

  private categorizeRatings(ratings: RatingTasteCount[]): Map<string, BookDataPoint[]> {
    const categories = new Map<string, BookDataPoint[]>([
      [this.t.translate('statsUser.ratingTaste.quadrantHiddenGems'), []],
      [this.t.translate('statsUser.ratingTaste.quadrantPopularFavorites'), []],
      [this.t.translate('statsUser.ratingTaste.quadrantOverrated'), []],
      [this.t.translate('statsUser.ratingTaste.quadrantAgreedMisses'), []]
    ]);

    ratings.forEach(item => {
      const externalRatings = [
        item.goodreadsRating, item.amazonRating, item.hardcoverRating,
        item.lubimyczytacRating, item.ranobedbRating
      ].filter((rating): rating is number => rating != null && rating > 0);
      const externalRating = externalRatings.length > 0
        ? externalRatings.reduce((sum, rating) => sum + rating, 0) / externalRatings.length
        : item.metadataRating ?? 0;
      if (externalRating <= 0) return;
      const personalRating = item.personalRating;
      const personalRatingNormalized = personalRating / 2;
      let quadrant: string;
      if (personalRatingNormalized >= 3 && externalRating >= 3) {
        quadrant = this.t.translate('statsUser.ratingTaste.quadrantPopularFavorites');
      } else if (personalRatingNormalized >= 3 && externalRating < 3) {
        quadrant = this.t.translate('statsUser.ratingTaste.quadrantHiddenGems');
      } else if (personalRatingNormalized < 3 && externalRating >= 3) {
        quadrant = this.t.translate('statsUser.ratingTaste.quadrantOverrated');
      } else {
        quadrant = this.t.translate('statsUser.ratingTaste.quadrantAgreedMisses');
      }

      const dataPoint: BookDataPoint = {
        x: externalRating,
        y: personalRatingNormalized,
        bookTitle: `${item.count} books`,
        personalRating,
        personalRatingNormalized,
        externalRating,
        quadrant,
        weight: item.count
      };

      categories.get(quadrant)!.push(dataPoint);
    });

    return categories;
  }

  private buildChartData(dataPoints: Map<string, BookDataPoint[]>): RatingTasteChartData {
    const pf = this.t.translate('statsUser.ratingTaste.quadrantPopularFavorites');
    const hg = this.t.translate('statsUser.ratingTaste.quadrantHiddenGems');
    const or = this.t.translate('statsUser.ratingTaste.quadrantOverrated');
    const am = this.t.translate('statsUser.ratingTaste.quadrantAgreedMisses');
    const quadrantColors: Record<string, { bg: string, border: string }> = {
      [pf]: {bg: 'rgba(76, 175, 80, 0.7)', border: '#4caf50'},
      [hg]: {bg: 'rgba(156, 39, 176, 0.7)', border: '#9c27b0'},
      [or]: {bg: 'rgba(255, 152, 0, 0.7)', border: '#ff9800'},
      [am]: {bg: 'rgba(158, 158, 158, 0.7)', border: '#9e9e9e'}
    };

    const datasets = Array.from(dataPoints.entries())
      .filter(([, points]) => points.length > 0)
      .map(([label, points]) => ({
        label: `${label} (${points.reduce((sum, point) => sum + point.weight, 0)})`,
        data: points,
        backgroundColor: quadrantColors[label].bg,
        borderColor: quadrantColors[label].border,
        pointRadius: 6,
        pointHoverRadius: 9,
        pointBorderWidth: 2
      }));

    return {datasets};
  }

  private calculateStatistics(dataPoints: Map<string, BookDataPoint[]>): Pick<RatingTasteMetrics, 'quadrants' | 'averageDeviation'> {
    const pfKey = this.t.translate('statsUser.ratingTaste.quadrantPopularFavorites');
    const hgKey = this.t.translate('statsUser.ratingTaste.quadrantHiddenGems');
    const orKey = this.t.translate('statsUser.ratingTaste.quadrantOverrated');
    const amKey = this.t.translate('statsUser.ratingTaste.quadrantAgreedMisses');
    const quadrantInfo: Record<string, { description: string, icon: string, color: string }> = {
      [pfKey]: {
        description: this.t.translate('statsUser.ratingTaste.quadrantDescPopularFavorites'),
        icon: '⭐',
        color: '#4caf50'
      },
      [hgKey]: {
        description: this.t.translate('statsUser.ratingTaste.quadrantDescHiddenGems'),
        icon: '💎',
        color: '#9c27b0'
      },
      [orKey]: {
        description: this.t.translate('statsUser.ratingTaste.quadrantDescOverrated'),
        icon: '📉',
        color: '#ff9800'
      },
      [amKey]: {
        description: this.t.translate('statsUser.ratingTaste.quadrantDescAgreedMisses'),
        icon: '👎',
        color: '#9e9e9e'
      }
    };

    const quadrants = Array.from(dataPoints.entries()).map(([name, points]) => ({
      name,
      description: quadrantInfo[name].description,
      count: points.reduce((sum, point) => sum + point.weight, 0),
      color: quadrantInfo[name].color,
      icon: quadrantInfo[name].icon
    }));

    // Calculate average deviation from external ratings (using normalized personal rating)
    let totalDeviation = 0;
    let totalPoints = 0;
    dataPoints.forEach(points => {
      points.forEach(point => {
        totalDeviation += Math.abs(point.personalRatingNormalized - point.externalRating) * point.weight;
        totalPoints += point.weight;
      });
    });
    const averageDeviation = totalPoints > 0 ? totalDeviation / totalPoints : 0;

    return {quadrants, averageDeviation};
  }

  private emptyMetrics(): RatingTasteMetrics {
    return {
      quadrants: [],
      totalRatedBooks: 0,
      averageDeviation: 0,
      chartData: {datasets: []}
    };
  }
}
