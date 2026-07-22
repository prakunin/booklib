import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {sortStrings} from '../../../../../../shared/util/string-sort.util';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

interface MonthlyData {
  month: string;
  label: string;
  added: number;
  finished: number;
  cumulativeAdded: number;
  cumulativeFinished: number;
}

interface JourneyInsights {
  totalAdded: number;
  totalFinished: number;
  currentBacklog: number;
  backlogPercent: number;
  avgTimeToFinishDays: number;
  mostProductiveMonth: string;
  mostProductiveCount: number;
  busiestAcquisitionMonth: string;
  busiestAcquisitionCount: number;
  finishRate: number;
  recentActivity: string;
  longestStreak: number;
}

type JourneyChartData = ChartData<'line', number[], string>;

@Component({
  selector: 'app-reading-journey-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './reading-journey-chart.component.html',
  styleUrls: ['./reading-journey-chart.component.scss']
})
export class ReadingJourneyChartComponent {
  private readonly libraryStats = inject(LibraryStatsApiService);
  private readonly t = inject(TranslocoService);
  private readonly monthlyData = computed(() => this.calculateMonthlyData());

  public readonly chartType = 'line' as const;
  public chartOptions: ChartConfiguration<'line'>['options'];
  public readonly insights = computed(() => {
    const monthlyData = this.monthlyData();
    return monthlyData.length > 0 ? this.calculateInsights(monthlyData) : null;
  });
  public readonly totalBooks = computed(() => this.libraryStats.data()?.totalBooks ?? 0);
  public readonly dateRange = computed(() => {
    const monthlyData = this.monthlyData();
    if (monthlyData.length === 0) {
      return '';
    }

    return `${monthlyData[0].label} - ${monthlyData.at(-1)!.label}`;
  });
  public readonly chartData = computed<JourneyChartData>(() => {
    const monthlyData = this.monthlyData();
    if (monthlyData.length === 0) {
      return {labels: [], datasets: []};
    }

    const labels = monthlyData.map(d => d.label);
    const addedData = monthlyData.map(d => d.cumulativeAdded);
    const finishedData = monthlyData.map(d => d.cumulativeFinished);

    return {
      labels,
      datasets: [
        {
          label: this.t.translate('statsLibrary.readingJourney.legendBooksAdded'),
          data: addedData,
          borderColor: '#3b82f6',
          backgroundColor: 'rgba(59, 130, 246, 0.1)',
          pointBackgroundColor: '#3b82f6',
          fill: true,
          order: 2
        },
        {
          label: this.t.translate('statsLibrary.readingJourney.legendBooksFinished'),
          data: finishedData,
          borderColor: '#10b981',
          backgroundColor: 'rgba(16, 185, 129, 0.2)',
          pointBackgroundColor: '#10b981',
          fill: true,
          order: 1
        }
      ]
    };
  });

  constructor() {
    this.initChartOptions();
  }

  private initChartOptions(): void {
    this.chartOptions = {
      responsive: true,
      maintainAspectRatio: false,
      layout: {
        padding: {top: 20, right: 20, bottom: 10, left: 10}
      },
      scales: {
        x: {
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 10
            },
            maxRotation: 45,
            minRotation: 45,
            autoSkip: true,
            maxTicksLimit: 24
          },
          grid: {
          },
          border: {display: false},
          title: {
            display: true,
            text: this.t.translate('statsLibrary.readingJourney.axisMonth'),
            font: {
              family: "'Inter', sans-serif",
              size: 12,
              weight: 500
            }
          }
        },
        y: {
          beginAtZero: true,
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            precision: 0
          },
          grid: {
          },
          border: {display: false},
          title: {
            display: true,
            text: this.t.translate('statsLibrary.readingJourney.axisCumulativeBooks'),
            font: {
              family: "'Inter', sans-serif",
              size: 12,
              weight: 500
            }
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
              size: 12
            },
            padding: 20,
            usePointStyle: true,
            pointStyle: 'circle'
          }
        },
        tooltip: {
          enabled: true,
          borderColor: '#10b981',
          borderWidth: 2,
          cornerRadius: 8,
          padding: 12,
          titleFont: {size: 13, weight: 'bold'},
          bodyFont: {size: 11},
          callbacks: {
            title: (context) => context[0].label,
            afterBody: (context) => {
              const dataIndex = context[0].dataIndex;
              const addedValue = context[0].chart.data.datasets[0].data[dataIndex] as number;
              const finishedValue = context[0].chart.data.datasets[1].data[dataIndex] as number;
              const backlog = addedValue - finishedValue;
              return [`\n${this.t.translate('statsLibrary.readingJourney.tooltipBacklog', {count: backlog})}`];
            }
          }
        },
        datalabels: {
          display: false
        }
      },
      elements: {
        line: {
          tension: 0.3,
          borderWidth: 3
        },
        point: {
          radius: 3,
          hoverRadius: 6,
          borderWidth: 2
        }
      },
      interaction: {
        intersect: false,
        mode: 'index'
      }
    };
  }

  private calculateMonthlyData(): MonthlyData[] {
    const snapshot = this.libraryStats.data();
    if (!snapshot) return [];
    const monthlyAdded = new Map(snapshot.booksAddedByMonth.map(item => [this.monthKey(item.year, item.month), item.count]));
    const monthlyFinished = new Map(snapshot.booksFinishedByMonth.map(item => [this.monthKey(item.year, item.month), item.count]));

    // Get all unique months and sort them
    const allMonths = new Set([...monthlyAdded.keys(), ...monthlyFinished.keys()]);
    const sortedMonths = sortStrings(Array.from(allMonths));

    if (sortedMonths.length === 0) {
      return [];
    }

    // Fill in gaps and calculate cumulative values
    const firstMonth = sortedMonths[0];
    const lastMonth = sortedMonths.at(-1)!;
    const allMonthsRange = this.getMonthRange(firstMonth, lastMonth);

    let cumulativeAdded = 0;
    let cumulativeFinished = 0;

    return allMonthsRange.map(month => {
      const added = monthlyAdded.get(month) || 0;
      const finished = monthlyFinished.get(month) || 0;
      cumulativeAdded += added;
      cumulativeFinished += finished;

      return {
        month,
        label: this.formatMonthLabel(month),
        added,
        finished,
        cumulativeAdded,
        cumulativeFinished
      };
    });
  }

  private monthKey(year: number, month: number): string {
    return `${year}-${String(month).padStart(2, '0')}`;
  }

  private getMonthRange(start: string, end: string): string[] {
    const months: string[] = [];
    const [startYear, startMonth] = start.split('-').map(Number);
    const [endYear, endMonth] = end.split('-').map(Number);

    let year = startYear;
    let month = startMonth;

    while (year < endYear || (year === endYear && month <= endMonth)) {
      months.push(`${year}-${String(month).padStart(2, '0')}`);
      month++;
      if (month > 12) {
        month = 1;
        year++;
      }
    }

    return months;
  }

  private formatMonthLabel(monthKey: string): string {
    const [year, month] = monthKey.split('-');
    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return `${monthNames[Number.parseInt(month, 10) - 1]} ${year}`;
  }

  private calculateInsights(monthlyData: MonthlyData[]): JourneyInsights {
    const totalAdded = monthlyData.reduce((sum, item) => sum + item.added, 0);
    const totalFinished = monthlyData.reduce((sum, item) => sum + item.finished, 0);
    const currentBacklog = totalAdded - totalFinished;
    const backlogPercent = totalAdded > 0 ? Math.round((currentBacklog / totalAdded) * 100) : 0;
    const avgTimeToFinishDays = this.libraryStats.data()?.averageDaysToFinish ?? 0;

    // Find most productive reading month
    let mostProductiveMonth = 'N/A';
    let mostProductiveCount = 0;
    let busiestAcquisitionMonth = 'N/A';
    let busiestAcquisitionCount = 0;

    for (const data of monthlyData) {
      if (data.finished > mostProductiveCount) {
        mostProductiveCount = data.finished;
        mostProductiveMonth = data.label;
      }
      if (data.added > busiestAcquisitionCount) {
        busiestAcquisitionCount = data.added;
        busiestAcquisitionMonth = data.label;
      }
    }

    // Finish rate (books finished per month on average)
    const finishRate = monthlyData.length > 0
      ? +(totalFinished / monthlyData.length).toFixed(1)
      : 0;

    // Recent activity
    const recentFinished = monthlyData.slice(-3).reduce((sum, item) => sum + item.finished, 0);
    const recentActivity = recentFinished > 0
      ? this.t.translate('statsLibrary.readingJourney.recentActivityBooks', {count: recentFinished})
      : this.t.translate('statsLibrary.readingJourney.recentActivityNone');

    // Longest reading streak (consecutive months with finished books)
    let longestStreak = 0;
    let currentStreak = 0;
    for (const data of monthlyData) {
      if (data.finished > 0) {
        currentStreak++;
        longestStreak = Math.max(longestStreak, currentStreak);
      } else {
        currentStreak = 0;
      }
    }

    return {
      totalAdded,
      totalFinished,
      currentBacklog,
      backlogPercent,
      avgTimeToFinishDays,
      mostProductiveMonth,
      mostProductiveCount,
      busiestAcquisitionMonth,
      busiestAcquisitionCount,
      finishRate,
      recentActivity,
      longestStreak
    };
  }
}
