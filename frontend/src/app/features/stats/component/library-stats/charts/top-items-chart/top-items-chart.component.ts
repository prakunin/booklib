import {Component, effect, inject, Input, OnInit} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData, TooltipItem} from 'chart.js';
import {BehaviorSubject, Observable} from 'rxjs';
import {Select} from 'primeng/select';
import {ReadStatus} from '../../../../../book/model/book.model';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {NgClass, AsyncPipe} from '@angular/common';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';
import {CountedOption} from '../../../../../book/model/app-book.model';

interface ItemStats {
  name: string;
  count: number;
  statusBreakdown: Record<ReadStatus, number>;
}

interface DataTypeOption {
  label: string;
  value: DataType;
  icon: string;
  color: string;
}

type DataType = 'authors' | 'categories' | 'publishers' | 'tags' | 'moods' | 'series';
type ItemChartData = ChartData<'bar', number[], string>;

const DATA_TYPE_DEFS: { key: string; value: DataType; icon: string; color: string }[] = [
  {key: 'authors', value: 'authors', icon: 'pi-th-large', color: '#2563EB'},
  {key: 'categories', value: 'categories', icon: 'pi-user', color: '#0D9488'},
  {key: 'series', value: 'series', icon: 'pi-tag', color: '#DB2777'},
  {key: 'publishers', value: 'publishers', icon: 'pi-building', color: '#7C3AED'},
  {key: 'tags', value: 'tags', icon: 'pi-bookmark', color: '#EAB308'},
  {key: 'moods', value: 'moods', icon: 'pi-heart', color: '#EA580C'}
];

@Component({
  selector: 'app-top-items-chart',
  standalone: true,
  imports: [
    AsyncPipe,
    NgClass,FormsModule, BaseChartDirective, Select, TranslocoDirective],
  templateUrl: './top-items-chart.component.html',
  styleUrls: ['./top-items-chart.component.scss']
})
export class TopItemsChartComponent implements OnInit {
  @Input() initialDataType: DataType | null = null;

  public readonly chartType = 'bar' as const;
  public readonly chartData$: Observable<ItemChartData>;
  public chartOptions: ChartConfiguration<'bar'>['options'];
  public dataTypeOptions: DataTypeOption[];
  public selectedDataType: DataTypeOption;

  public totalItems = 0;
  public totalBooks = 0;
  public insights: { icon: string; label: string; value: string }[] = [];

  private readonly libraryStats = inject(LibraryStatsApiService);
  private readonly t = inject(TranslocoService);
  private readonly syncChartEffect = effect(() => {
    this.libraryStats.data();
    this.processData();
  });
  private readonly chartDataSubject: BehaviorSubject<ItemChartData>;
  private lastCalculatedStats: ItemStats[] = [];

  constructor() {
    this.dataTypeOptions = DATA_TYPE_DEFS.map(def => ({
      label: this.t.translate(`statsLibrary.topItems.dataTypes.${def.key}`),
      value: def.value,
      icon: def.icon,
      color: def.color
    }));
    this.selectedDataType = this.dataTypeOptions[0];
    this.chartDataSubject = new BehaviorSubject<ItemChartData>({
      labels: [],
      datasets: []
    });
    this.chartData$ = this.chartDataSubject.asObservable();
    this.initChartOptions();
  }

  ngOnInit(): void {
    if (this.initialDataType) {
      const initialOption = this.dataTypeOptions.find(opt => opt.value === this.initialDataType);
      if (initialOption) {
        this.selectedDataType = initialOption;
        this.initChartOptions();
      }
    }
  }

  onDataTypeChange(): void {
    this.initChartOptions();
    this.processData();
  }

  private initChartOptions(): void {
    const currentColor = this.selectedDataType.color;
    this.chartOptions = {
      responsive: true,
      maintainAspectRatio: false,
      indexAxis: 'y',
      layout: {
        padding: {top: 10, right: 20, bottom: 10, left: 10}
      },
      scales: {
        x: {
          stacked: true,
          beginAtZero: true,
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            precision: 0,
            stepSize: 1
          },
          grid: {
          },
          border: {display: false},
          title: {
            display: true,
            text: this.t.translate('statsLibrary.topItems.axisNumberOfBooks'),
            font: {
              family: "'Inter', sans-serif",
              size: 12,
              weight: 500
            }
          }
        },
        y: {
          stacked: true,
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            maxTicksLimit: 25
          },
          grid: {
            display: false
          },
          border: {display: false}
        }
      },
      plugins: {
        legend: {
          display: true,
          position: 'bottom',
          labels: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            padding: 15,
            usePointStyle: true,
            pointStyle: 'rectRounded'
          }
        },
        tooltip: {
          enabled: true,
          borderColor: currentColor,
          borderWidth: 2,
          cornerRadius: 8,
          displayColors: true,
          padding: 12,
          titleFont: {size: 14, weight: 'bold'},
          bodyFont: {size: 12},
          callbacks: {
            title: (context) => {
              const dataIndex = context[0].dataIndex;
              return this.lastCalculatedStats[dataIndex]?.name || 'Unknown';
            },
            label: this.formatTooltipLabel.bind(this)
          }
        },
        datalabels: {
          display: false
        }
      },
      interaction: {
        intersect: true,
        mode: 'nearest',
        axis: 'y'
      }
    };
  }

  private processData(): void {
    const stats = this.calculateStats(this.optionsForSelectedType());
    this.updateChartData(stats);
  }

  private updateChartData(stats: ItemStats[]): void {
    try {
      this.lastCalculatedStats = stats;
      this.totalItems = stats.length;
      this.totalBooks = stats.reduce((sum, s) => sum + s.count, 0);

      const labels = stats.map(s => this.truncateTitle(s.name, 30));

      const datasets = [{
        label: this.selectedDataType.label,
        data: stats.map(item => item.count),
        backgroundColor: this.selectedDataType.color,
        borderColor: this.selectedDataType.color,
        borderWidth: 1,
        borderRadius: 4,
        barPercentage: 0.85,
        categoryPercentage: 0.8,
        hoverBorderWidth: 2,
      }];

      this.chartDataSubject.next({
        labels,
        datasets
      });

      this.generateInsights(stats);
    } catch (error) {
      console.error('Error updating items chart data:', error);
    }
  }

  private generateInsights(stats: ItemStats[]): void {
    this.insights = [];
    if (stats.length === 0) return;

    const typeName = this.selectedDataType.label.toLowerCase().slice(0, -1); // Remove 's' for singular

    // 1. Top item
    const top = stats[0];
    this.insights.push({
      icon: 'pi-trophy',
      label: this.t.translate('statsLibrary.topItems.insightTop', {type: typeName}),
      value: this.t.translate('statsLibrary.topItems.insightTopValue', {name: top.name, count: top.count})
    });

    // 2. Top 5 concentration
    if (stats.length >= 5) {
      const top5Books = stats.slice(0, 5).reduce((sum, s) => sum + s.count, 0);
      const totalAllBooks = this.libraryStats.data()?.totalBooks ?? 0;
      if (totalAllBooks > 0) {
        const concentration = Math.round((top5Books / totalAllBooks) * 100);
        this.insights.push({
          icon: 'pi-chart-pie',
          label: this.t.translate('statsLibrary.topItems.insightTop5Coverage'),
          value: this.t.translate('statsLibrary.topItems.insightTop5CoverageValue', {percent: concentration})
        });
      }
    }

    // 3. Average books per item
    if (stats.length > 0) {
      const avgBooks = (this.totalBooks / stats.length).toFixed(1);
      this.insights.push({
        icon: 'pi-book',
        label: this.t.translate('statsLibrary.topItems.insightAvgPer', {type: typeName}),
        value: this.t.translate('statsLibrary.topItems.insightAvgPerValue', {avg: avgBooks})
      });
    }
  }

  private calculateStats(options: CountedOption[]): ItemStats[] {
    return options
      .filter(item => item.name?.trim())
      .map(item => {
        const statusBreakdown = this.createEmptyStatusBreakdown();
        statusBreakdown[ReadStatus.UNSET] = item.count;
        return {name: item.name.trim(), count: item.count, statusBreakdown};
      })
      .slice(0, 15);
  }

  private optionsForSelectedType(): CountedOption[] {
    const facets = this.libraryStats.facets();
    return facets?.[this.selectedDataType.value] ?? [];
  }

  private createEmptyStatusBreakdown(): Record<ReadStatus, number> {
    return {
      [ReadStatus.READ]: 0,
      [ReadStatus.READING]: 0,
      [ReadStatus.RE_READING]: 0,
      [ReadStatus.UNREAD]: 0,
      [ReadStatus.PARTIALLY_READ]: 0,
      [ReadStatus.PAUSED]: 0,
      [ReadStatus.WONT_READ]: 0,
      [ReadStatus.ABANDONED]: 0,
      [ReadStatus.UNSET]: 0
    };
  }

  private formatTooltipLabel(context: TooltipItem<'bar'>): string {
    const value = context.parsed.x;
    if (value === 0) {
      return '';
    }

    const statusLabel = context.dataset.label || this.t.translate('statsLibrary.pageCount.axisBooks');
    return value === 1
      ? this.t.translate('statsLibrary.topItems.tooltipBook', {status: statusLabel, value})
      : this.t.translate('statsLibrary.topItems.tooltipBooks', {status: statusLabel, value});
  }

  private truncateTitle(title: string, maxLength: number): string {
    return title.length > maxLength ? title.substring(0, maxLength) + '...' : title;
  }
}
