import {Component, effect, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {Tooltip} from 'primeng/tooltip';
import {BehaviorSubject, Observable} from 'rxjs';
import {ChartConfiguration, ChartData} from 'chart.js';
import {ReadStatus} from '../../../../../book/model/book.model';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {AsyncPipe} from '@angular/common';
import {UserBookStatsService} from '../../service/user-book-stats.service';

interface SeriesInfo {
  name: string;
  booksOwned: number;
  booksRead: number;
  booksReading: number;
  booksPartiallyRead: number;
  booksPaused: number;
  booksAbandoned: number;
  booksWontRead: number;
  booksUnread: number;
  totalInSeries: number | null;
  completionPercentage: number;
  avgPersonalRating: number | null;
  avgExternalRating: number | null;
  nextUnread: string | null;
  status: 'completed' | 'in-progress' | 'not-started' | 'abandoned' | 'paused';
}

interface SeriesStats {
  totalSeries: number;
  completedSeries: number;
  inProgressSeries: number;
  notStartedSeries: number;
  avgSeriesCompletion: number;
  mostReadSeries: SeriesInfo | null;
  highestRatedSeries: SeriesInfo | null;
}

type SeriesChartData = ChartData<'bar', number[], string>;

@Component({
  selector: 'app-series-progress-chart',
  standalone: true,
  imports: [
    AsyncPipe,BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './series-progress-chart.component.html',
  styleUrls: ['./series-progress-chart.component.scss']
})
export class SeriesProgressChartComponent {
  private readonly userBookStats = inject(UserBookStatsService);
  private readonly t = inject(TranslocoService);
  private readonly syncChartEffect = effect(() => {
    this.calculateAndUpdateChart();
  });

  public readonly chartType = 'bar' as const;
  public seriesList: SeriesInfo[] = [];
  public filteredSeriesList: SeriesInfo[] = [];
  public stats: SeriesStats | null = null;
  public displayedSeries: SeriesInfo[] = [];
  public chartSeries: SeriesInfo[] = [];

  // Pagination & filtering
  public searchTerm = '';
  public currentPage = 0;
  public readonly PAGE_SIZE = 10;
  public readonly CHART_DISPLAY_COUNT = 8;
  public sortBy: 'progress' | 'rating' | 'books' | 'name' = 'progress';
  public filterStatus: 'all' | 'completed' | 'in-progress' | 'not-started' | 'paused' | 'abandoned' = 'all';

  public readonly chartOptions: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    indexAxis: 'y',
    layout: {
      padding: {top: 10, right: 20, bottom: 10, left: 10}
    },
    scales: {
      x: {
        stacked: true,
        max: 100,
        title: {
          display: true,
          text: this.t.translate('statsUser.seriesProgress.axisCompletion'),
          font: {
            family: "'Inter', sans-serif",
            size: 11,
            weight: 500
          }
        },
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 10
          },
          callback: (value) => `${value}%`
        },
        grid: {
        }
      },
      y: {
        stacked: true,
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 10
          }
        },
        grid: {
          display: false
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
            size: 10
          },
          usePointStyle: true,
          pointStyle: 'rect',
          padding: 12
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#673ab7',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 12, weight: 'bold'},
        bodyFont: {size: 10},
        callbacks: {
          title: (context) => {
            const series = this.chartSeries[context[0].dataIndex];
            return series ? series.name : '';
          },
          afterBody: (context) => {
            const series = this.chartSeries[context[0].dataIndex];
            if (!series) return [];
            const lines = [
              this.t.translate('statsUser.seriesProgress.tooltipRead', {read: series.booksRead, owned: series.booksOwned})
            ];
            if (series.totalInSeries) {
              lines.push(this.t.translate('statsUser.seriesProgress.tooltipSeriesTotal', {total: series.totalInSeries}));
            }
            if (series.avgPersonalRating) {
              lines.push(this.t.translate('statsUser.seriesProgress.tooltipYourRating', {rating: series.avgPersonalRating.toFixed(1)}));
            }
            return lines;
          }
        }
      }
    }
  };

  private readonly chartDataSubject = new BehaviorSubject<SeriesChartData>({
    labels: [],
    datasets: []
  });

  public readonly chartData$: Observable<SeriesChartData> = this.chartDataSubject.asObservable();

  onSearchInput(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) {
      return;
    }

    this.onSearchChange(target.value);
  }

  onFilterSelect(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) {
      return;
    }

    this.onFilterChange(target.value as typeof this.filterStatus);
  }

  onSortSelect(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) {
      return;
    }

    this.onSortChange(target.value as typeof this.sortBy);
  }

  onSearchChange(term: string): void {
    this.searchTerm = term.toLowerCase().trim();
    this.currentPage = 0;
    this.applyFiltersAndSort();
  }

  onSortChange(sortBy: 'progress' | 'rating' | 'books' | 'name'): void {
    this.sortBy = sortBy;
    this.currentPage = 0;
    this.applyFiltersAndSort();
  }

  onFilterChange(status: 'all' | 'completed' | 'in-progress' | 'not-started' | 'paused' | 'abandoned'): void {
    this.filterStatus = status;
    this.currentPage = 0;
    this.applyFiltersAndSort();
  }

  nextPage(): void {
    if (this.hasNextPage()) {
      this.currentPage++;
      this.updateDisplayedSeries();
    }
  }

  prevPage(): void {
    if (this.hasPrevPage()) {
      this.currentPage--;
      this.updateDisplayedSeries();
    }
  }

  hasNextPage(): boolean {
    return (this.currentPage + 1) * this.PAGE_SIZE < this.filteredSeriesList.length;
  }

  hasPrevPage(): boolean {
    return this.currentPage > 0;
  }

  getTotalPages(): number {
    return Math.ceil(this.filteredSeriesList.length / this.PAGE_SIZE);
  }

  private applyFiltersAndSort(): void {
    let filtered = [...this.seriesList];

    // Apply search filter
    if (this.searchTerm) {
      filtered = filtered.filter(s =>
        s.name.toLowerCase().includes(this.searchTerm)
      );
    }

    // Apply status filter
    if (this.filterStatus !== 'all') {
      filtered = filtered.filter(s => s.status === this.filterStatus);
    }

    // Apply sorting
    filtered.sort((a, b) => {
      switch (this.sortBy) {
        case 'progress':
          return b.completionPercentage - a.completionPercentage;
        case 'rating':
          return (b.avgPersonalRating || 0) - (a.avgPersonalRating || 0);
        case 'books':
          return b.booksOwned - a.booksOwned;
        case 'name':
          return a.name.localeCompare(b.name);
        default:
          return 0;
      }
    });

    this.filteredSeriesList = filtered;
    this.updateDisplayedSeries();
    this.updateChartSeries();
    this.updateChartData();
  }

  private calculateAndUpdateChart(): void {
    const series = this.userBookStats.facets()?.series ?? [];
    if (series.length === 0) {
      this.chartDataSubject.next({labels: [], datasets: []});
      this.seriesList = [];
      this.displayedSeries = [];
      this.stats = null;
      return;
    }

    this.seriesList = this.calculateSeriesInfo();
    this.stats = this.calculateSeriesStats(this.seriesList);
    this.currentPage = 0;
    this.applyFiltersAndSort();
  }

  private calculateSeriesInfo(): SeriesInfo[] {
    const facets = this.userBookStats.facets();
    if (!facets) return [];
    const statusCounts = new Map(facets.readStatuses.map(item => [item.name, item.count]));
    const statusTotal = [...statusCounts.values()].reduce((sum, count) => sum + count, 0) || 1;
    const ratio = (status: ReadStatus) => (statusCounts.get(status) ?? 0) / statusTotal;
    return facets.series.map(item => {
      const booksRead = Math.round(item.count * ratio(ReadStatus.READ));
      const booksReading = Math.round(item.count * (ratio(ReadStatus.READING) + ratio(ReadStatus.RE_READING)));
      const booksPartiallyRead = Math.round(item.count * ratio(ReadStatus.PARTIALLY_READ));
      const booksPaused = Math.round(item.count * ratio(ReadStatus.PAUSED));
      const booksAbandoned = Math.round(item.count * ratio(ReadStatus.ABANDONED));
      const booksWontRead = Math.round(item.count * ratio(ReadStatus.WONT_READ));
      const allocated = booksRead + booksReading + booksPartiallyRead + booksPaused + booksAbandoned + booksWontRead;
      const booksUnread = Math.max(0, item.count - allocated);
      const completionPercentage = Math.round(booksRead / item.count * 100);
      const status = this.resolveSeriesStatus(booksReading, booksRead, booksPaused, booksAbandoned);
      return {
        name: item.name,
        booksOwned: item.count,
        booksRead,
        booksReading,
        booksPartiallyRead,
        booksPaused,
        booksAbandoned,
        booksWontRead,
        booksUnread,
        totalInSeries: null,
        completionPercentage,
        avgPersonalRating: null,
        avgExternalRating: null,
        nextUnread: null,
        status
      };
    });
  }

  private resolveSeriesStatus(
    booksReading: number,
    booksRead: number,
    booksPaused: number,
    booksAbandoned: number
  ): SeriesInfo['status'] {
    if (booksReading > 0 || booksRead > 0) return 'in-progress';
    if (booksPaused > 0) return 'paused';
    if (booksAbandoned > 0) return 'abandoned';
    return 'not-started';
  }

  private calculateSeriesStats(seriesList: SeriesInfo[]): SeriesStats {
    const completedSeries = seriesList.filter(s => s.status === 'completed').length;
    const inProgressSeries = seriesList.filter(s => s.status === 'in-progress').length;
    const notStartedSeries = seriesList.filter(s => s.status === 'not-started').length;

    const totalCompletion = seriesList.reduce((sum, s) => sum + s.completionPercentage, 0);
    const avgSeriesCompletion = seriesList.length > 0 ? Math.round(totalCompletion / seriesList.length) : 0;

    const mostReadSeries = [...seriesList].sort((a, b) => b.booksRead - a.booksRead)[0] || null;

    const ratedSeries = seriesList.filter(s => s.avgPersonalRating !== null);
    const highestRatedSeries = ratedSeries.length > 0
      ? [...ratedSeries].sort((a, b) => (b.avgPersonalRating || 0) - (a.avgPersonalRating || 0))[0]
      : null;

    return {
      totalSeries: seriesList.length,
      completedSeries,
      inProgressSeries,
      notStartedSeries,
      avgSeriesCompletion,
      mostReadSeries,
      highestRatedSeries
    };
  }

  private updateDisplayedSeries(): void {
    const start = this.currentPage * this.PAGE_SIZE;
    const end = start + this.PAGE_SIZE;
    this.displayedSeries = this.filteredSeriesList.slice(start, end);
  }

  private updateChartSeries(): void {
    // For chart, show top series by in-progress first, then by books owned
    const chartData = [...this.seriesList]
      .sort((a, b) => {
        const statusOrder = {'in-progress': 0, 'paused': 1, 'not-started': 2, 'completed': 3, 'abandoned': 4};
        const statusDiff = statusOrder[a.status] - statusOrder[b.status];
        if (statusDiff !== 0) return statusDiff;
        return b.booksOwned - a.booksOwned;
      })
      .slice(0, this.CHART_DISPLAY_COUNT);

    this.chartSeries = chartData;
  }

  private updateChartData(): void {
    const labels = this.chartSeries.map(s =>
      s.name.length > 25 ? s.name.substring(0, 22) + '...' : s.name
    );

    const readPercentages = this.chartSeries.map(s =>
      s.booksOwned > 0 ? Math.round((s.booksRead / s.booksOwned) * 100) : 0
    );
    const readingPercentages = this.chartSeries.map(s =>
      s.booksOwned > 0 ? Math.round((s.booksReading / s.booksOwned) * 100) : 0
    );
    const partiallyReadPercentages = this.chartSeries.map(s =>
      s.booksOwned > 0 ? Math.round((s.booksPartiallyRead / s.booksOwned) * 100) : 0
    );
    const pausedPercentages = this.chartSeries.map(s =>
      s.booksOwned > 0 ? Math.round((s.booksPaused / s.booksOwned) * 100) : 0
    );
    const abandonedPercentages = this.chartSeries.map(s =>
      s.booksOwned > 0 ? Math.round((s.booksAbandoned / s.booksOwned) * 100) : 0
    );
    const wontReadPercentages = this.chartSeries.map(s =>
      s.booksOwned > 0 ? Math.round((s.booksWontRead / s.booksOwned) * 100) : 0
    );
    const unreadPercentages = this.chartSeries.map(s =>
      s.booksOwned > 0 ? Math.round((s.booksUnread / s.booksOwned) * 100) : 0
    );

    this.chartDataSubject.next({
      labels,
      datasets: [
        {
          label: this.t.translate('statsUser.seriesProgress.read'),
          data: readPercentages,
          backgroundColor: 'rgba(76, 175, 80, 0.85)',
          borderColor: '#4caf50',
          borderWidth: 1,
          borderRadius: 2
        },
        {
          label: this.t.translate('statsUser.seriesProgress.reading'),
          data: readingPercentages,
          backgroundColor: 'rgba(255, 193, 7, 0.85)',
          borderColor: '#ffc107',
          borderWidth: 1,
          borderRadius: 2
        },
        {
          label: this.t.translate('statsUser.seriesProgress.partiallyRead'),
          data: partiallyReadPercentages,
          backgroundColor: 'rgba(255, 152, 0, 0.85)',
          borderColor: '#ff9800',
          borderWidth: 1,
          borderRadius: 2
        },
        {
          label: this.t.translate('statsUser.seriesProgress.paused'),
          data: pausedPercentages,
          backgroundColor: 'rgba(33, 150, 243, 0.85)',
          borderColor: '#2196f3',
          borderWidth: 1,
          borderRadius: 2
        },
        {
          label: this.t.translate('statsUser.seriesProgress.abandoned'),
          data: abandonedPercentages,
          backgroundColor: 'rgba(239, 83, 80, 0.85)',
          borderColor: '#ef5350',
          borderWidth: 1,
          borderRadius: 2
        },
        {
          label: this.t.translate('statsUser.seriesProgress.wontRead'),
          data: wontReadPercentages,
          backgroundColor: 'rgba(158, 158, 158, 0.6)',
          borderColor: '#9e9e9e',
          borderWidth: 1,
          borderRadius: 2
        },
        {
          label: this.t.translate('statsUser.seriesProgress.unread'),
          data: unreadPercentages,
          backgroundColor: 'rgba(158, 158, 158, 0.3)',
          borderColor: '#9e9e9e',
          borderWidth: 1,
          borderRadius: 2
        }
      ]
    });
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'completed': return 'pi-check-circle';
      case 'in-progress': return 'pi-spinner';
      case 'not-started': return 'pi-clock';
      case 'paused': return 'pi-pause';
      case 'abandoned': return 'pi-times-circle';
      default: return 'pi-question-circle';
    }
  }

  getStatusColor(status: string): string {
    switch (status) {
      case 'completed': return '#4caf50';
      case 'in-progress': return '#ffc107';
      case 'not-started': return '#9e9e9e';
      case 'paused': return '#2196f3';
      case 'abandoned': return '#ef5350';
      default: return '#9e9e9e';
    }
  }
}
