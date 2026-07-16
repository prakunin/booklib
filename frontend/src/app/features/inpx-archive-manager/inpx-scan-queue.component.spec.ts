import {TestBed} from '@angular/core/testing';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {getTranslocoModule} from '../../core/testing/transloco-testing';
import {InpxArchiveScanTask} from './inpx-archive.model';
import {InpxArchiveService} from './inpx-archive.service';
import {InpxScanQueueComponent} from './inpx-scan-queue.component';

describe('InpxScanQueueComponent', () => {
  const task: InpxArchiveScanTask = {
    libraryId: 7,
    archiveName: 'new.zip',
    status: 'SCANNING',
    phase: 'IMPORTING',
    totalBooks: 100,
    processedBooks: 40,
    remainingBooks: 60,
    addedBooks: 5,
    coversGenerated: 32,
    failedBooks: 1,
    queuedAt: '2026-07-15T10:00:00Z',
    startedAt: '2026-07-15T10:00:01Z',
    completedAt: null,
    errorMessage: null,
  };
  const metadataTask: InpxArchiveScanTask = {
    ...task,
    status: 'QUEUED',
    phase: 'METADATA_AND_COVERS',
    processedBooks: 0,
    remainingBooks: 100,
    addedBooks: 0,
    coversGenerated: 0,
    failedBooks: 0,
    startedAt: null,
  };
  const archiveService = {
    getScanQueue: vi.fn(() => of([task, metadataTask])),
  };

  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [InpxScanQueueComponent, getTranslocoModule()],
      providers: [
        {provide: InpxArchiveService, useValue: archiveService},
        {provide: DynamicDialogConfig, useValue: {data: {libraryId: 7}}},
        {provide: DynamicDialogRef, useValue: {close: vi.fn()}},
      ],
    });
  });

  it('polls and calculates archive progress', async () => {
    const fixture = TestBed.createComponent(InpxScanQueueComponent);
    fixture.detectChanges();

    await vi.waitFor(() => expect(archiveService.getScanQueue).toHaveBeenCalledWith(7));
    expect(fixture.componentInstance.tasks()).toEqual([task, metadataTask]);
    expect(fixture.componentInstance.progress(task)).toBe(40);
    expect(fixture.componentInstance.activeCount()).toBe(1);
    expect(fixture.componentInstance.queuedCount()).toBe(1);
    expect(fixture.componentInstance.statusSeverity(task)).toBe('info');
    fixture.destroy();
  });

  it('stops polling once no scan is active', () => {
    vi.useFakeTimers();
    const scanning: InpxArchiveScanTask = {...task, status: 'SCANNING'};
    const done: InpxArchiveScanTask = {...task, status: 'COMPLETED'};
    archiveService.getScanQueue
      .mockReturnValueOnce(of([scanning]))
      .mockReturnValueOnce(of([done]))
      .mockReturnValue(of([done]));

    const fixture = TestBed.createComponent(InpxScanQueueComponent);
    fixture.detectChanges();

    vi.advanceTimersByTime(0);      // t=0: an active scan -> keep polling
    vi.advanceTimersByTime(2000);   // t=2000: completed -> should stop after this emission
    const callsWhenIdle = archiveService.getScanQueue.mock.calls.length;
    vi.advanceTimersByTime(10000);  // further ticks must not poll anymore

    expect(callsWhenIdle).toBe(2);
    expect(archiveService.getScanQueue.mock.calls.length).toBe(callsWhenIdle);
    fixture.destroy();
  });
});
