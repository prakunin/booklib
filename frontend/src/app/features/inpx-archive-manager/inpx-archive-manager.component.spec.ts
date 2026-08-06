import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, convertToParamMap, Router} from '@angular/router';
import {ConfirmationService, MessageService} from 'primeng/api';
import {BehaviorSubject, Observable, of, Subject, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {getTranslocoModule} from '../../core/testing/transloco-testing';
import {InpxArchiveManagerComponent} from './inpx-archive-manager.component';
import {InpxArchiveService} from './inpx-archive.service';
import type {InpxArchive, InpxArchiveScanTask, LocalCatalogStatus} from './inpx-archive.model';
import {DialogLauncherService} from '../../shared/services/dialog-launcher.service';
import {TaskProgressPayload, TaskService, TaskStatus, TaskType} from '../settings/task-management/task.service';

describe('InpxArchiveManagerComponent', () => {
  /**
   * The exact text that reaches the browser when the backfill refuses to start against an index
   * somebody else is rebuilding. `LocalCatalogBackfillService.run` raises the sentence;
   * `LocalCatalogBackfillTask.reportFailure` prefixes it before putting it on the wire, and the
   * prefix is part of what the user reads, so the fixture carries it too.
   */
  const REFUSAL_FRAME = 'Local catalog backfill failed: The local catalog index for library 7 is being rebuilt; '
    + 'start the backfill again once indexing has finished';

  const archive: InpxArchive = {
    archiveName: 'new.zip',
    sizeBytes: 2 * 1024 * 1024,
    fb2Count: 100,
    importedBookCount: 90,
    coveredBookCount: 75,
    fileModifiedAt: '2026-07-15T10:00:00Z',
    addedAt: '2026-07-01T10:00:00Z',
    lastScannedAt: null,
    status: 'IDLE',
    errorMessage: null,
  };
  const configuredCatalogStatus: LocalCatalogStatus = {
    configured: true,
    catalogPath: '/mnt/flibusta/catalog.zip',
    indexedEntries: {REVIEW: 4600, AUTHOR_BIO: 1200, COMPILATION: 300, COMPILATION_PART: 900, LANGUAGE: 15},
    totalBooks: 615000,
    booksWithDescription: 5200,
    localReviews: 4600,
    authorsWithBiography: 8000,
  };
  const unconfiguredCatalogStatus: LocalCatalogStatus = {
    configured: false,
    catalogPath: null,
    indexedEntries: {REVIEW: 0, AUTHOR_BIO: 0, COMPILATION: 0, COMPILATION_PART: 0, LANGUAGE: 0},
    totalBooks: 0,
    booksWithDescription: 0,
    localReviews: 0,
    authorsWithBiography: 0,
  };
  const archiveService = {
    getArchives: vi.fn(() => of([archive])),
    getScanQueue: vi.fn(() => of([] as InpxArchiveScanTask[])),
    rescan: vi.fn(() => of(undefined)),
    rescanAll: vi.fn(() => of(undefined)),
    getLocalCatalogStatus: vi.fn(() => of(configuredCatalogStatus)),
  };
  const dialogLauncher = {
    openInpxScanQueueDialog: vi.fn(() => Promise.resolve(null)),
  };
  const router = {
    navigate: vi.fn(() => Promise.resolve(true)),
  };
  const taskService: {
    startTask: ReturnType<typeof vi.fn>;
    taskProgress$: Observable<TaskProgressPayload | null>;
  } = {
    startTask: vi.fn(() => of({taskId: 't1', taskType: 'LOCAL_CATALOG_BACKFILL', status: 'ACCEPTED'})),
    taskProgress$: of(null),
  };
  const messageService = {
    add: vi.fn(),
  };
  const confirmationService = {
    confirm: vi.fn(),
  };

  /**
   * The task id is a parameter because the component now reports a given run's failure once and only
   * once, and it remembers that across component instances — a component field would be wiped by the
   * very remount whose replayed toast is the defect. Each case that toasts therefore uses an id of
   * its own, so the cases stay independent of the order they run in.
   */
  function backfillFrame(taskStatus: TaskStatus, message: string, taskId = 't1'): TaskProgressPayload {
    return {
      taskId,
      taskType: TaskType.LOCAL_CATALOG_BACKFILL,
      message,
      progress: 3,
      taskStatus,
    };
  }

  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    archiveService.getArchives.mockReset().mockReturnValue(of([archive]));
    archiveService.getScanQueue.mockReset().mockReturnValue(of([]));
    archiveService.rescan.mockReset().mockReturnValue(of(undefined));
    archiveService.rescanAll.mockReset().mockReturnValue(of(undefined));
    archiveService.getLocalCatalogStatus.mockReset().mockReturnValue(of(configuredCatalogStatus));
    confirmationService.confirm.mockClear();
    router.navigate.mockClear();
    taskService.startTask.mockReset().mockReturnValue(of({taskId: 't1', taskType: 'LOCAL_CATALOG_BACKFILL', status: 'ACCEPTED'}));
    taskService.taskProgress$ = of(null);
    TestBed.configureTestingModule({
      imports: [InpxArchiveManagerComponent, getTranslocoModule()],
      providers: [
        {provide: InpxArchiveService, useValue: archiveService},
        {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({libraryId: '7'})}}},
        {provide: Router, useValue: router},
        {provide: MessageService, useValue: messageService},
        {provide: ConfirmationService, useValue: confirmationService},
        {provide: DialogLauncherService, useValue: dialogLauncher},
        {provide: TaskService, useValue: taskService},
      ],
    });
  });

  it('shows the local catalog as unconfigured and hides the run button', () => {
    archiveService.getLocalCatalogStatus.mockReturnValue(of(unconfiguredCatalogStatus));
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('No local catalog configured for this library.');
    expect(fixture.nativeElement.querySelector('#run-local-catalog-backfill')).toBeNull();
    fixture.destroy();
  });

  it('shows indexed counts and coverage figures when the local catalog is configured', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Local catalog detected at /mnt/flibusta/catalog.zip');
    expect(text).toContain('300 (900 parts)');
    expect(text).toContain('5200 of 615000 books have a description');
    expect(text).toContain('4600 reviews recorded from the local catalog');
    expect(text).toContain('8000 authors have a biography (global figure, not limited to this library)');
    fixture.destroy();
  });

  it('starts the local catalog backfill for the current library exactly once', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();

    const runButton = fixture.nativeElement.querySelector('#run-local-catalog-backfill') as HTMLButtonElement;
    expect(runButton).not.toBeNull();
    runButton.click();

    expect(taskService.startTask).toHaveBeenCalledTimes(1);
    expect(taskService.startTask).toHaveBeenCalledWith({
      taskType: TaskType.LOCAL_CATALOG_BACKFILL,
      triggeredByCron: false,
      options: {libraryId: 7},
    });
    fixture.destroy();
  });

  /**
   * The backfill's own refusal — "the index is being rebuilt, start again once it has finished" —
   * arrives in the FAILED frame's message and used to go nowhere. The template renders
   * `backfillProgress()?.message` only while `backfillRunning()` is true, and that is
   * `taskStatus === IN_PROGRESS`, so the element holding the reason is torn out by the very frame
   * that carries it. Without a toast a refused run looks exactly like a finished one.
   */
  it('surfaces the reason a backfill run failed once its terminal frame arrives', () => {
    const progress = new Subject<TaskProgressPayload | null>();
    taskService.taskProgress$ = progress;
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();
    archiveService.getLocalCatalogStatus.mockClear();

    progress.next(backfillFrame(TaskStatus.IN_PROGRESS, 'Walking 702511 books'));
    expect(fixture.componentInstance.backfillRunning()).toBe(true);

    progress.next(backfillFrame(TaskStatus.FAILED, REFUSAL_FRAME));

    expect(fixture.componentInstance.backfillRunning()).toBe(false);
    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'The local catalog backfill stopped.',
      detail: REFUSAL_FRAME,
      life: 5000,
    });
    expect(archiveService.getLocalCatalogStatus).toHaveBeenCalledWith(7);
    fixture.destroy();
  });

  /**
   * `TaskService.taskProgress$` is a `BehaviorSubject` and nothing ever pushes a null frame back into
   * it, so it hands every later subscriber the last frame of the last run at the moment it
   * subscribes. This component subscribes in its constructor, so once a run had ended FAILED, every
   * navigation back into the archive manager replayed that frame and popped its toast again — for a
   * run the user was told about once already, possibly hours earlier.
   *
   * The subject here is a `BehaviorSubject` rather than the plain `Subject` the cases above use, and
   * that is the whole point: a plain `Subject` does not replay, so it cannot reproduce this at all,
   * which is why the existing cases were blind to it.
   */
  it('does not repeat a failure toast when the archive manager is opened again', () => {
    const progress = new BehaviorSubject<TaskProgressPayload | null>(null);
    taskService.taskProgress$ = progress;

    const first = TestBed.createComponent(InpxArchiveManagerComponent);
    first.detectChanges();
    progress.next(backfillFrame(TaskStatus.FAILED, REFUSAL_FRAME, 't2'));
    expect(messageService.add).toHaveBeenCalledTimes(1);
    first.destroy();

    messageService.add.mockClear();
    const second = TestBed.createComponent(InpxArchiveManagerComponent);
    second.detectChanges();

    expect(second.componentInstance.backfillProgress()?.taskStatus).toBe(TaskStatus.FAILED);
    expect(messageService.add).not.toHaveBeenCalled();
    second.destroy();
  });

  /**
   * `LocalCatalogBackfillTask.reportFailure` builds the frame as a fixed prefix plus
   * `RuntimeException.getMessage()`, and that is null for plenty of exceptions — an NPE, for one — so
   * string concatenation puts the literal word "null" in front of the user. A translated generic line
   * says no less and reads like the product rather than like a stack trace.
   */
  it('falls back to a translated line when the failure frame carries no reason', () => {
    const progress = new Subject<TaskProgressPayload | null>();
    taskService.taskProgress$ = progress;
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();

    progress.next(backfillFrame(TaskStatus.FAILED, 'Local catalog backfill failed: null', 't3'));

    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'The local catalog backfill stopped.',
      detail: 'It stopped without reporting a reason. The server log records what happened.',
      life: 5000,
    });
    fixture.destroy();
  });

  it('clears the panel without an error toast when a backfill run completes', () => {
    const progress = new Subject<TaskProgressPayload | null>();
    taskService.taskProgress$ = progress;
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();
    archiveService.getLocalCatalogStatus.mockClear();

    progress.next(backfillFrame(TaskStatus.IN_PROGRESS, 'Walking 702511 books'));
    progress.next(backfillFrame(TaskStatus.COMPLETED, 'Enriched 702511 books'));

    expect(fixture.componentInstance.backfillRunning()).toBe(false);
    expect(messageService.add).not.toHaveBeenCalled();
    expect(archiveService.getLocalCatalogStatus).toHaveBeenCalledWith(7);
    fixture.destroy();
  });

  it('opens the scan queue for the current library', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);

    fixture.componentInstance.openScanQueue();

    expect(dialogLauncher.openInpxScanQueueDialog).toHaveBeenCalledWith(7);
    fixture.destroy();
  });

  it('navigates to the selected archive book page', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);

    fixture.componentInstance.openArchive(archive);

    expect(router.navigate).toHaveBeenCalledWith(['/library', 7, 'archives', 'new.zip', 'books']);
    fixture.destroy();
  });

  it('loads and formats archive statistics', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();

    expect(archiveService.getArchives).toHaveBeenCalledWith(7);
    expect(fixture.componentInstance.archives()).toEqual([archive]);
    expect(fixture.componentInstance.formatSize(archive.sizeBytes)).toBe('2.0 MB');
    expect(fixture.componentInstance.statusSeverity(archive)).toBe('secondary');
    fixture.destroy();
  });

  it('polls until background archive calculations finish', () => {
    vi.useFakeTimers();
    archiveService.getArchives
      .mockReturnValueOnce(of([{
        ...archive,
        fb2Count: null,
        importedBookCount: null,
        coveredBookCount: null,
      }]))
      .mockReturnValueOnce(of([archive]));
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.archives()[0].fb2Count).toBeNull();

    vi.advanceTimersByTime(2000);

    expect(archiveService.getArchives).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.archives()).toEqual([archive]);
    fixture.destroy();
  });

  it('queues a full archive rescan and marks the row active immediately', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);

    fixture.componentInstance.rescan(archive);

    expect(archiveService.rescan).toHaveBeenCalledWith(7, 'new.zip');
    expect(fixture.componentInstance.archives()[0].status).toBe('QUEUED');
    expect(fixture.componentInstance.isActive(fixture.componentInstance.archives()[0])).toBe(true);
    fixture.destroy();
  });

  it('confirms and queues one sequential full scan for every idle archive', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();
    fixture.componentInstance.archives.set([
      {...archive, archiveName: 'idle.zip'},
      {...archive, archiveName: 'active.zip', status: 'SCANNING'},
    ]);

    fixture.componentInstance.confirmRescanAll();

    expect(confirmationService.confirm).toHaveBeenCalledWith(expect.objectContaining({
      header: 'Scan every archive?',
      message: expect.stringContaining('1 archive(s)'),
    }));
    confirmationService.confirm.mock.calls[0][0].accept?.();

    expect(archiveService.rescanAll).toHaveBeenCalledWith(7);
    expect(fixture.componentInstance.archives().map(item => item.status)).toEqual(['QUEUED', 'SCANNING']);
    expect(messageService.add).toHaveBeenCalledWith(expect.objectContaining({
      detail: 'Full scan queued for 1 archive(s).',
    }));
    fixture.destroy();
  });

  it('does not offer a bulk scan when every archive is already active', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.componentInstance.archives.set([{...archive, status: 'QUEUED'}]);

    fixture.componentInstance.confirmRescanAll();

    expect(confirmationService.confirm).not.toHaveBeenCalled();
    expect(archiveService.rescanAll).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('restores optimistic rows when the bulk request and recovery reload both fail', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();
    archiveService.rescanAll.mockReturnValue(throwError(() => new Error('queue unavailable')));
    archiveService.getArchives.mockReturnValue(throwError(() => new Error('reload unavailable')));

    fixture.componentInstance.confirmRescanAll();
    confirmationService.confirm.mock.calls[0][0].accept?.();

    expect(fixture.componentInstance.archives()).toEqual([archive]);
    expect(fixture.componentInstance.bulkScanEligibleCount()).toBe(1);
    expect(messageService.add).toHaveBeenCalledWith(expect.objectContaining({
      detail: 'Could not start the full library scan.',
    }));
    fixture.destroy();
  });

  it('does not stack overlapping polls when several archives are rescanned', () => {
    vi.useFakeTimers();
    try {
      archiveService.getScanQueue.mockReturnValue(of([scanTask({status: 'SCANNING'})]));
      const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
      fixture.detectChanges();

      fixture.componentInstance.rescan({...archive, archiveName: 'a.zip', status: 'IDLE'});
      fixture.componentInstance.rescan({...archive, archiveName: 'b.zip', status: 'IDLE'});

      vi.advanceTimersByTime(1000);

      expect(archiveService.getScanQueue).toHaveBeenCalledTimes(1);
      expect(archiveService.getArchives).toHaveBeenCalledTimes(1);
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not orphan a rescan queued while an in-flight poll resolves idle', () => {
    vi.useFakeTimers();

    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();

    const inflightA = new Subject<InpxArchiveScanTask[]>();
    archiveService.getScanQueue.mockReturnValueOnce(inflightA);
    fixture.componentInstance.rescan({...archive, archiveName: 'a.zip', status: 'IDLE'});
    vi.advanceTimersByTime(1000);

    archiveService.getScanQueue.mockReturnValue(of([scanTask({status: 'SCANNING'})]));
    fixture.componentInstance.rescan({...archive, archiveName: 'b.zip', status: 'IDLE'});

    inflightA.next([]);
    inflightA.complete();

    archiveService.getScanQueue.mockClear();
    vi.advanceTimersByTime(2000);

    expect(archiveService.getScanQueue).toHaveBeenCalled();
    expect(archiveService.getArchives).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  it('reloads full archive statistics once after the scan queue becomes idle', () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);
    fixture.detectChanges();
    archiveService.getArchives.mockClear();
    archiveService.getScanQueue.mockReturnValue(of([scanTask({status: 'COMPLETED'})]));

    fixture.componentInstance.rescan(archive);
    vi.advanceTimersByTime(1000);

    expect(archiveService.getScanQueue).toHaveBeenCalledTimes(1);
    expect(archiveService.getArchives).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  function scanTask(overrides: Partial<InpxArchiveScanTask> = {}): InpxArchiveScanTask {
    return {
      libraryId: 7,
      archiveName: 'new.zip',
      status: 'QUEUED',
      phase: 'METADATA_AND_COVERS',
      totalBooks: 100,
      processedBooks: 0,
      remainingBooks: 100,
      addedBooks: 0,
      coversGenerated: 0,
      failedBooks: 0,
      queuedAt: '2026-07-15T10:00:00Z',
      startedAt: null,
      completedAt: null,
      errorMessage: null,
      ...overrides,
    };
  }
});
