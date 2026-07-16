import {DatePipe} from '@angular/common';
import {ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {TranslocoDirective, TranslocoPipe} from '@jsverse/transloco';
import {Button} from 'primeng/button';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {ProgressBar} from 'primeng/progressbar';
import {ProgressSpinner} from 'primeng/progressspinner';
import {TableModule} from 'primeng/table';
import {Tag} from 'primeng/tag';
import {catchError, EMPTY, switchMap, takeWhile, timer} from 'rxjs';
import {InpxArchiveScanTask} from './inpx-archive.model';
import {InpxArchiveService} from './inpx-archive.service';

@Component({
  selector: 'app-inpx-scan-queue',
  standalone: true,
  templateUrl: './inpx-scan-queue.component.html',
  styleUrl: './inpx-scan-queue.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, Button, ProgressBar, ProgressSpinner, TableModule, Tag, TranslocoDirective, TranslocoPipe],
})
export class InpxScanQueueComponent {
  private readonly service = inject(InpxArchiveService);
  private readonly config = inject(DynamicDialogConfig);
  private readonly ref = inject(DynamicDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly libraryId = (this.config.data as {libraryId: number}).libraryId;

  readonly tasks = signal<InpxArchiveScanTask[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly activeCount = computed(() => this.tasks().filter(task => task.status === 'SCANNING').length);
  readonly queuedCount = computed(() => this.tasks().filter(task => task.status === 'QUEUED').length);

  constructor() {
    timer(0, 2000).pipe(
      switchMap(() => this.service.getScanQueue(this.libraryId).pipe(
        catchError(() => {
          this.loading.set(false);
          this.loadFailed.set(true);
          return EMPTY;
        }),
      )),
      // Stop polling once nothing is running (inclusive of the final idle snapshot) instead of
      // hammering archive-scans every 2s for the whole time the dialog stays open.
      takeWhile(tasks => tasks.some(task => task.status === 'QUEUED' || task.status === 'SCANNING'), true),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(tasks => {
      this.tasks.set(tasks);
      this.loading.set(false);
      this.loadFailed.set(false);
    });
  }

  close(): void {
    this.ref.close();
  }

  progress(task: InpxArchiveScanTask): number {
    if (task.totalBooks === 0) {
      return task.status === 'COMPLETED' ? 100 : 0;
    }
    return Math.min(100, Math.round(task.processedBooks / task.totalBooks * 100));
  }

  statusSeverity(task: InpxArchiveScanTask): 'success' | 'danger' | 'info' | 'warn' | 'secondary' {
    switch (task.status) {
      case 'COMPLETED': return task.failedBooks > 0 ? 'warn' : 'success';
      case 'FAILED': return 'danger';
      case 'SKIPPED': return 'secondary';
      case 'QUEUED':
      case 'SCANNING': return 'info';
      default: return 'info';
    }
  }
}
