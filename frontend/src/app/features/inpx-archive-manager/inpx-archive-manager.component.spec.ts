import {TestBed} from '@angular/core/testing';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {of, Subject} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {getTranslocoModule} from '../../core/testing/transloco-testing';
import {InpxArchiveManagerComponent} from './inpx-archive-manager.component';
import {InpxArchiveService} from './inpx-archive.service';
import type {InpxArchive, InpxArchiveScanTask} from './inpx-archive.model';
import {DialogLauncherService} from '../../shared/services/dialog-launcher.service';

describe('InpxArchiveManagerComponent', () => {
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
  const archiveService = {
    getArchives: vi.fn(() => of([archive])),
    getScanQueue: vi.fn(() => of([] as InpxArchiveScanTask[])),
    rescan: vi.fn(() => of(undefined)),
  };
  const dialogLauncher = {
    openInpxScanQueueDialog: vi.fn(() => Promise.resolve(null)),
  };

  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    archiveService.getArchives.mockReset().mockReturnValue(of([archive]));
    archiveService.getScanQueue.mockReset().mockReturnValue(of([]));
    archiveService.rescan.mockReset().mockReturnValue(of(undefined));
    TestBed.configureTestingModule({
      imports: [InpxArchiveManagerComponent, getTranslocoModule()],
      providers: [
        {provide: InpxArchiveService, useValue: archiveService},
        {provide: DynamicDialogConfig, useValue: {data: {libraryId: 7}}},
        {provide: DynamicDialogRef, useValue: {close: vi.fn()}},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: DialogLauncherService, useValue: dialogLauncher},
      ],
    });
  });

  it('opens the scan queue for the current library', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);

    fixture.componentInstance.openScanQueue();

    expect(dialogLauncher.openInpxScanQueueDialog).toHaveBeenCalledWith(7);
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

  it('queues a full archive rescan and marks the row active immediately', () => {
    const fixture = TestBed.createComponent(InpxArchiveManagerComponent);

    fixture.componentInstance.rescan(archive);

    expect(archiveService.rescan).toHaveBeenCalledWith(7, 'new.zip');
    expect(fixture.componentInstance.archives()[0].status).toBe('QUEUED');
    expect(fixture.componentInstance.isActive(fixture.componentInstance.archives()[0])).toBe(true);
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
