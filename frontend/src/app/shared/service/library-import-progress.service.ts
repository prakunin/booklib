import {computed, Injectable, signal} from '@angular/core';

export type LibraryImportProgressStatus = 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'ERROR';

export type LibraryScanStatus = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';

export interface LibraryScanProgress {
  libraryId: number;
  libraryName: string;
  total: number;
  processed: number;
  added: number;
  skipped: number;
  status: LibraryScanStatus;
}

export interface LibraryImportProgressState {
  active: boolean;
  libraryId?: number;
  libraryName: string;
  expectedCount: number;
  processedCount: number;
  currentBookTitle?: string;
  status: LibraryImportProgressStatus;
  cancellable: boolean;
}

const EMPTY_STATE: LibraryImportProgressState = {
  active: false,
  libraryName: '',
  expectedCount: 0,
  processedCount: 0,
  status: 'COMPLETED',
  cancellable: false,
};

const SCAN_STATUS_MAP: Record<LibraryScanStatus, LibraryImportProgressStatus> = {
  RUNNING: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  FAILED: 'ERROR',
};

@Injectable({providedIn: 'root'})
export class LibraryImportProgressService {
  private readonly stateSignal = signal<LibraryImportProgressState>(EMPTY_STATE);

  readonly state = this.stateSignal.asReadonly();
  readonly hasActiveImport = computed(() => this.stateSignal().active);

  start(libraryName: string, expectedCount: number): void {
    if (expectedCount <= 0) {
      this.clear();
      return;
    }

    this.stateSignal.set({
      active: false,
      libraryName,
      expectedCount,
      processedCount: 0,
      status: 'IN_PROGRESS',
      cancellable: false,
    });
  }

  attachLibrary(libraryId: number): void {
    const state = this.stateSignal();
    if (state.expectedCount <= 0) return;
    this.stateSignal.set({...state, libraryId});
  }

  recordBookAdded(bookTitle: string): void {
    const state = this.stateSignal();
    if (state.expectedCount <= 0 || state.status !== 'IN_PROGRESS') return;

    const processedCount = Math.min(state.processedCount + 1, state.expectedCount);
    this.stateSignal.set({
      ...state,
      active: true,
      processedCount,
      currentBookTitle: bookTitle,
      status: processedCount >= state.expectedCount
        ? 'COMPLETED'
        : 'IN_PROGRESS',
    });
  }

  fail(): void {
    const state = this.stateSignal();
    if (state.expectedCount <= 0 || state.status !== 'IN_PROGRESS') return;
    if (!state.active) {
      this.clear();
      return;
    }
    this.stateSignal.set({...state, status: 'ERROR'});
  }

  clear(): void {
    this.stateSignal.set(EMPTY_STATE);
  }

  applyScanProgress(progress: LibraryScanProgress): void {
    const status = SCAN_STATUS_MAP[progress.status];
    this.stateSignal.set({
      active: true,
      libraryId: progress.libraryId,
      libraryName: progress.libraryName,
      expectedCount: progress.total,
      processedCount: progress.processed,
      status,
      cancellable: status === 'IN_PROGRESS',
    });
  }
}
