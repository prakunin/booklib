import {ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {DatePipe} from '@angular/common';
import {ActivatedRoute, Router} from '@angular/router';
import {TableModule} from 'primeng/table';
import {Button} from 'primeng/button';
import {Tag} from 'primeng/tag';
import {ProgressSpinner} from 'primeng/progressspinner';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {catchError, EMPTY, exhaustMap, filter, finalize, map, Subscription, take, tap, timer} from 'rxjs';
import {InpxArchive, InpxArchiveScanStatus, InpxArchiveScanTask, LocalCatalogStatus} from './inpx-archive.model';
import {InpxArchiveService} from './inpx-archive.service';
import {DialogLauncherService} from '../../shared/services/dialog-launcher.service';
import {TaskProgressPayload, TaskService, TaskStatus, TaskType} from '../settings/task-management/task.service';
import {AppButtonComponent} from '../../shared/ui/button/app-button.component';

@Component({
  selector: 'app-inpx-archive-manager',
  standalone: true,
  templateUrl: './inpx-archive-manager.component.html',
  styleUrl: './inpx-archive-manager.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, TableModule, Button, Tag, ProgressSpinner, TranslocoDirective, AppButtonComponent],
})
export class InpxArchiveManagerComponent {
  private readonly service = inject(InpxArchiveService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialogLauncher = inject(DialogLauncherService);
  private readonly taskService = inject(TaskService);
  readonly libraryId = Number(this.route.snapshot.paramMap.get('libraryId'));

  readonly archives = signal<InpxArchive[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  private pollSubscription: Subscription | null = null;
  private calculationPollSubscription: Subscription | null = null;
  private rescanVersion = 0;

  readonly localCatalogStatus = signal<LocalCatalogStatus | null>(null);
  readonly localCatalogLoading = signal(true);
  readonly localCatalogLoadFailed = signal(false);
  readonly backfillStarting = signal(false);
  readonly backfillProgress = signal<TaskProgressPayload | null>(null);
  readonly backfillRunning = computed(() => this.backfillProgress()?.taskStatus === TaskStatus.IN_PROGRESS);

  constructor() {
    this.load();
    this.loadLocalCatalogStatus();
    this.taskService.taskProgress$.pipe(
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(progress => {
      if (progress?.taskType !== TaskType.LOCAL_CATALOG_BACKFILL) {
        return;
      }
      this.backfillProgress.set(progress);
      if (progress.taskStatus !== TaskStatus.IN_PROGRESS) {
        // The frame that ends the run is also the only place its reason is ever offered. The
        // template renders `backfillProgress()?.message` inside `@if (backfillRunning())`, and
        // `backfillRunning()` is IN_PROGRESS, so this very frame tears the message element out
        // again — and a refusal the backfill raised on purpose ("the index is being rebuilt, start
        // again once indexing has finished") would then be indistinguishable from a completed run.
        // Toasting it is what keeps the two apart; `detail` is the backend's own text rather than a
        // translated string because it names the library and the state that caused the refusal.
        if (progress.taskStatus === TaskStatus.FAILED) {
          this.messages.add({
            severity: 'error',
            summary: this.t.translate('book.inpxArchives.localCatalog.backfillRunFailed'),
            detail: progress.message,
          });
        }
        this.loadLocalCatalogStatus();
      }
    });
  }

  loadLocalCatalogStatus(): void {
    this.localCatalogLoading.set(true);
    this.localCatalogLoadFailed.set(false);
    this.service.getLocalCatalogStatus(this.libraryId).pipe(
      finalize(() => this.localCatalogLoading.set(false)),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: status => this.localCatalogStatus.set(status),
      error: () => this.localCatalogLoadFailed.set(true),
    });
  }

  startBackfill(): void {
    if (this.backfillStarting() || this.backfillRunning()) {
      return;
    }
    this.backfillStarting.set(true);
    this.taskService.startTask({
      taskType: TaskType.LOCAL_CATALOG_BACKFILL,
      triggeredByCron: false,
      options: {libraryId: this.libraryId},
    }).pipe(
      finalize(() => this.backfillStarting.set(false)),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: () => {
        this.messages.add({
          severity: 'info',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('book.inpxArchives.localCatalog.backfillQueued'),
        });
      },
      error: () => {
        this.messages.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.t.translate('book.inpxArchives.localCatalog.backfillFailed'),
        });
      },
    });
  }

  openScanQueue(): void {
    void this.dialogLauncher.openInpxScanQueueDialog(this.libraryId);
  }

  openArchive(archive: InpxArchive): void {
    void this.router.navigate(['/library', this.libraryId, 'archives', archive.archiveName, 'books']);
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
      next: archives => {
        this.archives.set(archives);
        if (this.hasPendingCalculations(archives)) {
          this.pollCalculations();
        }
      },
      error: () => this.loadFailed.set(true),
    });
  }

  private pollCalculations(): void {
    if (this.calculationPollSubscription) {
      return;
    }
    this.calculationPollSubscription = timer(2000, 3000).pipe(
      exhaustMap(() => this.service.getArchives(this.libraryId).pipe(catchError(() => EMPTY))),
      tap(archives => this.archives.set(archives)),
      filter(archives => !this.hasPendingCalculations(archives)),
      take(1),
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.calculationPollSubscription = null),
    ).subscribe();
  }

  private hasPendingCalculations(archives: InpxArchive[]): boolean {
    return archives.some(archive => archive.fb2Count === null
      || archive.importedBookCount === null
      || archive.coveredBookCount === null);
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
