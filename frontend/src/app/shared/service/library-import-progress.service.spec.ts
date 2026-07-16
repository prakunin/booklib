import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';

import {LibraryImportProgressService} from './library-import-progress.service';

describe('LibraryImportProgressService', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('tracks progress from start through completion and clear', () => {
    TestBed.configureTestingModule({providers: [LibraryImportProgressService]});
    const service = TestBed.inject(LibraryImportProgressService);

    service.start('Main Library', 2);
    service.attachLibrary(7);
    service.recordBookAdded('First');

    expect(service.state()).toEqual({
      active: true,
      libraryId: 7,
      libraryName: 'Main Library',
      expectedCount: 2,
      processedCount: 1,
      currentBookTitle: 'First',
      status: 'IN_PROGRESS',
      cancellable: false,
    });

    service.fail();

    expect(service.state().status).toBe('ERROR');

    service.start('Main Library', 2);
    service.recordBookAdded('First');
    service.recordBookAdded('Second');

    expect(service.state().status).toBe('COMPLETED');

    service.clear();

    expect(service.hasActiveImport()).toBe(false);
  });

  it('applies absolute counters from a scan progress event', () => {
    TestBed.configureTestingModule({providers: [LibraryImportProgressService]});
    const service = TestBed.inject(LibraryImportProgressService);

    service.applyScanProgress({
      libraryId: 7,
      libraryName: 'Flibusta',
      total: 421000,
      processed: 120000,
      added: 119000,
      skipped: 1000,
      status: 'RUNNING',
    });

    const state = service.state();
    expect(state.active).toBe(true);
    expect(state.libraryId).toBe(7);
    expect(state.libraryName).toBe('Flibusta');
    expect(state.expectedCount).toBe(421000);
    expect(state.processedCount).toBe(120000);
    expect(state.status).toBe('IN_PROGRESS');
    expect(state.cancellable).toBe(true);
  });

  it('maps a cancelled scan to the CANCELLED status and stops being cancellable', () => {
    TestBed.configureTestingModule({providers: [LibraryImportProgressService]});
    const service = TestBed.inject(LibraryImportProgressService);

    service.applyScanProgress({
      libraryId: 7, libraryName: 'Flibusta', total: 421000, processed: 120000,
      added: 119000, skipped: 1000, status: 'CANCELLED',
    });

    expect(service.state().status).toBe('CANCELLED');
    expect(service.state().cancellable).toBe(false);
  });

  it('maps a failed scan to the ERROR status', () => {
    TestBed.configureTestingModule({providers: [LibraryImportProgressService]});
    const service = TestBed.inject(LibraryImportProgressService);

    service.applyScanProgress({
      libraryId: 7, libraryName: 'Flibusta', total: 0, processed: 0,
      added: 0, skipped: 0, status: 'FAILED',
    });

    expect(service.state().status).toBe('ERROR');
  });
});
