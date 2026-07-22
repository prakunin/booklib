import {AfterViewInit, Component, effect, ElementRef, inject, ViewChild} from '@angular/core';
import {Tooltip} from 'primeng/tooltip';
import {ReadStatus} from '../../../../../book/model/book.model';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {StatsChartThemeService} from '../../../shared/stats-chart-theme.service';
import {UserBookStatsService} from '../../service/user-book-stats.service';
import {BookFlowCount} from '../../../library-stats/service/library-stats-api.service';

interface SankeyNode {
  id: string;
  label: string;
  column: number;
  y: number;
  height: number;
  color: string;
  count: number;
}

interface SankeyLink {
  source: SankeyNode;
  target: SankeyNode;
  value: number;
  color: string;
}

@Component({
  selector: 'app-book-flow-chart',
  standalone: true,
  imports: [Tooltip, TranslocoDirective],
  templateUrl: './book-flow-chart.component.html',
  styleUrls: ['./book-flow-chart.component.scss']
})
export class BookFlowChartComponent implements AfterViewInit {
  private static readonly TOTAL_HEIGHT = 420;
  private static readonly NODE_PADDING = 6;

  @ViewChild('flowCanvas', {static: false}) canvasRef!: ElementRef<HTMLCanvasElement>;

  private readonly userBookStats = inject(UserBookStatsService);
  private readonly t = inject(TranslocoService);
  private readonly chartTheme = inject(StatsChartThemeService);
  private readonly syncChartEffect = effect(() => {
    this.chartTheme.themeRevision();

    if (this.userBookStats.isLoading()) {
      this.dataReady = false;
      return;
    }

    this.processData();
    this.dataReady = true;
    this.tryRender();
  });

  public hasData = false;
  public totalBooks = 0;
  public topQuarter = '';
  public topStatus = '';
  public completionRate = '';

  private nodes: SankeyNode[] = [];
  private links: SankeyLink[] = [];
  private canvasReady = false;
  private dataReady = false;

  ngAfterViewInit(): void {
    this.canvasReady = true;
    this.tryRender();
  }

  private tryRender(): void {
    if (this.canvasReady && this.dataReady && this.hasData) {
      requestAnimationFrame(() => this.draw());
    }
  }

  private processData(): void {
    this.hasData = false;
    this.totalBooks = 0;
    this.topQuarter = '';
    this.topStatus = '';
    this.completionRate = '';
    this.nodes = [];
    this.links = [];

    const snapshot = this.userBookStats.data();
    if (!snapshot || snapshot.totalBooks === 0) {
      return;
    }

    const {quarterMap, statusMap, ratingMap, quarterToStatus, statusToRating} = this.aggregateBookFlow(snapshot.bookFlow);

    if (quarterMap.size === 0) return;
    this.hasData = true;
    this.totalBooks = snapshot.totalBooks;

    const sortedQuarters = [...quarterMap.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 8);

    this.topQuarter = sortedQuarters[0]?.[0] || '';
    const topStatusEntry = [...statusMap.entries()].sort((a, b) => b[1] - a[1])[0];
    this.topStatus = topStatusEntry?.[0] || '';

    const readCount = statusMap.get('Read') || 0;
    this.completionRate = snapshot.totalBooks > 0 ? Math.round((readCount / snapshot.totalBooks) * 100) + '%' : '0%';

    const quarterColors = ['#42a5f5', '#26c6da', '#66bb6a', '#ffa726', '#ab47bc', '#ef5350', '#ec407a', '#7e57c2'];
    const statusColors: Record<string, string> = {
      'Read': '#66bb6a', 'Reading': '#42a5f5', 'Unread': '#78909c',
      'Paused': '#ffa726', 'Abandoned': '#ef5350', 'Other': '#ab47bc'
    };
    const ratingColors: Record<string, string> = {
      'Rated 4-5': '#66bb6a', 'Rated 3': '#ffc107',
      'Rated 1-2': '#ef5350', 'Unrated': '#78909c'
    };

    const quarterNodes = this.createColumnNodes(sortedQuarters, 0, quarterColors);
    const statusEntries = [...statusMap.entries()].sort((a, b) => b[1] - a[1]);
    const statusNodes = this.createColumnNodes(statusEntries, 1, statusColors);
    const ratingEntries = [...ratingMap.entries()].sort((a, b) => b[1] - a[1]);
    const ratingNodes = this.createColumnNodes(ratingEntries, 2, ratingColors);

    this.nodes = [...quarterNodes, ...statusNodes, ...ratingNodes];
    this.links = this.buildLinks(quarterToStatus, statusToRating);
  }

  private ratingBucketFor(personalRating: number | null): string {
    if (personalRating == null || personalRating <= 0) return 'Unrated';
    if (personalRating >= 8) return 'Rated 4-5';
    if (personalRating >= 6) return 'Rated 3';
    return 'Rated 1-2';
  }

  private aggregateBookFlow(bookFlow: BookFlowCount[]): {
    quarterMap: Map<string, number>;
    statusMap: Map<string, number>;
    ratingMap: Map<string, number>;
    quarterToStatus: Map<string, Map<string, number>>;
    statusToRating: Map<string, Map<string, number>>;
  } {
    const quarterMap = new Map<string, number>();
    const statusMap = new Map<string, number>();
    const ratingMap = new Map<string, number>();
    const quarterToStatus = new Map<string, Map<string, number>>();
    const statusToRating = new Map<string, Map<string, number>>();

    for (const item of bookFlow) {
      const quarter = `${item.year} Q${item.quarter}`;
      const status = this.statusBucket(item.readStatus as ReadStatus);
      const ratingBucket = this.ratingBucketFor(item.personalRating);
      quarterMap.set(quarter, (quarterMap.get(quarter) ?? 0) + item.count);
      statusMap.set(status, (statusMap.get(status) ?? 0) + item.count);
      ratingMap.set(ratingBucket, (ratingMap.get(ratingBucket) ?? 0) + item.count);
      const quarterStatuses = quarterToStatus.get(quarter) ?? new Map<string, number>();
      quarterStatuses.set(status, (quarterStatuses.get(status) ?? 0) + item.count);
      quarterToStatus.set(quarter, quarterStatuses);
      const statusRatings = statusToRating.get(status) ?? new Map<string, number>();
      statusRatings.set(ratingBucket, (statusRatings.get(ratingBucket) ?? 0) + item.count);
      statusToRating.set(status, statusRatings);
    }

    return {quarterMap, statusMap, ratingMap, quarterToStatus, statusToRating};
  }

  private createColumnNodes(
    entries: [string, number][],
    column: number,
    colors: Record<string, string> | string[]
  ): SankeyNode[] {
    const total = entries.reduce((s, e) => s + e[1], 0);
    let currentY = 30;
    return entries.map(([id, count], i) => {
      const height = Math.max(10, (count / total) * (BookFlowChartComponent.TOTAL_HEIGHT - entries.length * BookFlowChartComponent.NODE_PADDING));
      const node: SankeyNode = {
        id, label: id, column,
        y: currentY, height,
        color: Array.isArray(colors) ? colors[i % colors.length] : (colors[id] || '#78909c'),
        count
      };
      currentY += height + BookFlowChartComponent.NODE_PADDING;
      return node;
    });
  }

  private buildLinks(
    quarterToStatus: Map<string, Map<string, number>>,
    statusToRating: Map<string, Map<string, number>>
  ): SankeyLink[] {
    return [
      ...this.linkColumns(quarterToStatus, 0, 1),
      ...this.linkColumns(statusToRating, 1, 2),
    ];
  }

  private linkColumns(
    mapping: Map<string, Map<string, number>>,
    sourceCol: number,
    targetCol: number
  ): SankeyLink[] {
    const links: SankeyLink[] = [];
    const findNode = (id: string, col: number) => this.nodes.find(n => n.id === id && n.column === col);
    for (const [sourceId, targetCounts] of mapping) {
      const sourceNode = findNode(sourceId, sourceCol);
      if (!sourceNode) continue;
      for (const [targetId, count] of targetCounts) {
        const targetNode = findNode(targetId, targetCol);
        if (targetNode) {
          links.push({source: sourceNode, target: targetNode, value: count, color: sourceNode.color});
        }
      }
    }
    return links;
  }

  private statusBucket(status: ReadStatus): string {
    switch (status) {
      case ReadStatus.READ: return 'Read';
      case ReadStatus.READING:
      case ReadStatus.RE_READING: return 'Reading';
      case ReadStatus.UNREAD:
      case ReadStatus.UNSET: return 'Unread';
      case ReadStatus.PAUSED: return 'Paused';
      case ReadStatus.ABANDONED:
      case ReadStatus.WONT_READ: return 'Abandoned';
      default: return 'Other';
    }
  }

  private draw(): void {
    const canvas = this.canvasRef?.nativeElement;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const rect = canvas.parentElement!.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = rect.width * dpr;
    canvas.height = 480 * dpr;
    canvas.style.width = rect.width + 'px';
    canvas.style.height = '480px';
    ctx.scale(dpr, dpr);

    const width = rect.width;
    const nodeWidth = 18;
    const leftMargin = 100;
    const rightMargin = 110;
    const colX = [leftMargin, width * 0.45, width - rightMargin];
    const colors = this.chartTheme.colors();

    ctx.fillStyle = colors.textMuted;
    ctx.font = '11px Inter, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(this.t.translate('statsUser.bookFlow.colAdded'), colX[0] + nodeWidth / 2, 18);
    ctx.fillText(this.t.translate('statsUser.bookFlow.colStatus'), colX[1] + nodeWidth / 2, 18);
    ctx.fillText(this.t.translate('statsUser.bookFlow.colRating'), colX[2] + nodeWidth / 2, 18);

    const sourceOffsets = new Map<string, number>();
    const targetOffsets = new Map<string, number>();

    for (const link of this.links) {
      const sKey = `${link.source.id}-${link.source.column}`;
      const tKey = `${link.target.id}-${link.target.column}`;
      const sOff = sourceOffsets.get(sKey) || 0;
      const tOff = targetOffsets.get(tKey) || 0;

      const linkHeight = Math.max(1, (link.value / link.source.count) * link.source.height);
      const tLinkHeight = Math.max(1, (link.value / link.target.count) * link.target.height);

      const x0 = colX[link.source.column] + nodeWidth;
      const y0 = link.source.y + sOff + linkHeight / 2;
      const x1 = colX[link.target.column];
      const y1 = link.target.y + tOff + tLinkHeight / 2;

      ctx.beginPath();
      ctx.moveTo(x0, y0 - linkHeight / 2);
      const cpx = (x0 + x1) / 2;
      ctx.bezierCurveTo(cpx, y0 - linkHeight / 2, cpx, y1 - tLinkHeight / 2, x1, y1 - tLinkHeight / 2);
      ctx.lineTo(x1, y1 + tLinkHeight / 2);
      ctx.bezierCurveTo(cpx, y1 + tLinkHeight / 2, cpx, y0 + linkHeight / 2, x0, y0 + linkHeight / 2);
      ctx.closePath();

      ctx.fillStyle = link.color + '55';
      ctx.fill();
      ctx.strokeStyle = link.color + '30';
      ctx.lineWidth = 0.5;
      ctx.stroke();

      sourceOffsets.set(sKey, sOff + linkHeight);
      targetOffsets.set(tKey, tOff + tLinkHeight);
    }

    for (const node of this.nodes) {
      const x = colX[node.column];

      ctx.fillStyle = node.color;
      ctx.globalAlpha = 0.92;
      ctx.beginPath();
      ctx.roundRect(x, node.y, nodeWidth, node.height, 3);
      ctx.fill();
      ctx.globalAlpha = 1;

      ctx.strokeStyle = colors.grid;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(x, node.y, nodeWidth, node.height, 3);
      ctx.stroke();

      ctx.fillStyle = colors.text;
      ctx.font = '11px Inter, sans-serif';
      const labelX = node.column === 2 ? x + nodeWidth + 8 : x - 8;
      ctx.textAlign = node.column === 2 ? 'left' : 'right';
      ctx.textBaseline = 'middle';
      const label = node.label.length > 18 ? node.label.substring(0, 16) + '..' : node.label;
      ctx.fillText(`${label} (${node.count})`, labelX, node.y + node.height / 2);
    }
  }
}
