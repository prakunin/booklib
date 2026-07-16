import {ChangeDetectionStrategy, Component, DestroyRef, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {DatePipe} from '@angular/common';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {TableModule} from 'primeng/table';
import {Button} from 'primeng/button';
import {Tag} from 'primeng/tag';
import {ProgressSpinner} from 'primeng/progressspinner';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {catchError, EMPTY, exhaustMap, filter, finalize, map, Subscription, take, tap, timer} from 'rxjs';
import {InpxArchive, InpxArchiveScanStatus, InpxArchiveScanTask} from './inpx-archive.model';
import {InpxArchiveService} from './inpx-archive.service';
import {DialogLauncherService} from '../../shared/services/dialog-launcher.service';

@Component({
  selector: 'app-inpx-archive-manager',
  standalone: true,
  templateUrl: './inpx-archive-manager.component.html',
  styleUrl: './inpx-archive-manager.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, TableModule, Button, Tag, ProgressSpinner, TranslocoDirective, TranslocoPipe],
})
export class InpxArchiveManagerComponent {
  private readonly service = inject(InpxArchiveService);
  private readonly config = inject(DynamicDialogConfig);
  private readonly ref = inject(DynamicDialogRef);
  private readonly messages = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialogLauncher = inject(DialogLauncherService);
  private readonly libraryId = (this.config.data as {libraryId: number}).libraryId;

  readonly archives = signal<InpxArchive[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  private pollSubscription: Subscription | null = null;
  private rescanVersion = 0;

  constructor() {
    this.load();
  }

  close(): void {
    this.ref.close();
  }

  openScanQueue(): void {
    void this.dialogLauncher.openInpxScanQueueDialog(this.libraryId);
  }

  rescan(archive: InpxArchive): void {
    if (this.isActive(archive)) {
      return;
    }
    this.archives.update(items => items.map(item => item.archiveName === archive.archiveName
      ? {...item, status: 'QUEUED', errorMessage: null}
      : item));
    this.service.rescan(this.libraryId, archive.archiveName).subscribe({
      next: () => {
        this.rescanVersion++;
        this.messages.add({
          severity: 'info',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('book.inpxArchives.scanQueued', {name: archive.archiveName}),
        });
        this.pollUntilIdle();
      },
      error: () => {
        this.messages.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.t.translate('book.inpxArchives.scanFailed'),
        });
        this.load();
      },
    });
  }

  isActive(archive: InpxArchive): boolean {
    return archive.status === 'QUEUED' || archive.status === 'SCANNING';
  }

  statusSeverity(archive: InpxArchive): 'success' | 'danger' | 'info' | 'secondary' {
    switch (archive.status) {
      case 'COMPLETED': return 'success';
      case 'FAILED': return 'danger';
      case 'QUEUED':
      case 'SCANNING': return 'info';
      default: return 'secondary';
    }
  }

  formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
    return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  }

  load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.service.getArchives(this.libraryId).pipe(
      finalize(() => this.loading.set(false)),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: archives => this.archives.set(archives),
      error: () => this.loadFailed.set(true),
    });
  }

  private pollUntilIdle(): void {
    if (this.pollSubscription) {
      return;
    }

    this.pollSubscription = timer(1000, 2000).pipe(
      exhaustMap(() => {
        const requestVersion = this.rescanVersion;
        return this.service.getScanQueue(this.libraryId).pipe(
          map(tasks => ({tasks, requestVersion})),
          catchError(() => {
            this.loadFailed.set(true);
            return EMPTY;
          }),
        );
      }),
      tap(({tasks}) => {
        this.loadFailed.set(false);
        this.applyTaskStates(tasks);
      }),
      filter(({tasks, requestVersion}) =>
        requestVersion === this.rescanVersion && !tasks.some(task => this.isTaskActive(task))),
      take(1),
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.pollSubscription = null),
    ).subscribe({
      next: () => this.load(),
    });
  }

  private applyTaskStates(tasks: InpxArchiveScanTask[]): void {
    const tasksByArchive = new Map<string, InpxArchiveScanTask[]>();
    for (const task of tasks) {
      const archiveTasks = tasksByArchive.get(task.archiveName) ?? [];
      archiveTasks.push(task);
      tasksByArchive.set(task.archiveName, archiveTasks);
    }

    this.archives.update(archives => archives.map(archive => {
      const archiveTasks = tasksByArchive.get(archive.archiveName);
      if (!archiveTasks?.length) {
        return archive;
      }
      const failed = archiveTasks.find(task => task.status === 'FAILED');
      return {
        ...archive,
        status: this.aggregateStatus(archiveTasks),
        errorMessage: failed?.errorMessage ?? null,
      };
    }));
  }

  private aggregateStatus(tasks: InpxArchiveScanTask[]): InpxArchiveScanStatus {
    if (tasks.some(task => task.status === 'FAILED')) return 'FAILED';
    if (tasks.some(task => task.status === 'SCANNING')) return 'SCANNING';
    if (tasks.some(task => task.status === 'QUEUED')) return 'QUEUED';
    if (tasks.every(task => task.status === 'COMPLETED' || task.status === 'SKIPPED')) return 'COMPLETED';
    return 'IDLE';
  }

  private isTaskActive(task: InpxArchiveScanTask): boolean {
    return task.status === 'QUEUED' || task.status === 'SCANNING';
  }
}
