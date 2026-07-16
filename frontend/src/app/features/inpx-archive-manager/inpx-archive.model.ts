export type InpxArchiveScanStatus = 'IDLE' | 'QUEUED' | 'SCANNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
export type InpxArchiveScanPhase = 'QUEUED' | 'IMPORTING' | 'METADATA_AND_COVERS' | 'COMPLETED' | 'FAILED';

export interface InpxArchive {
  archiveName: string;
  sizeBytes: number;
  fb2Count: number;
  importedBookCount: number;
  coveredBookCount: number;
  fileModifiedAt: string;
  addedAt: string | null;
  lastScannedAt: string | null;
  status: InpxArchiveScanStatus;
  errorMessage: string | null;
}

export interface InpxArchiveScanTask {
  libraryId: number;
  archiveName: string;
  status: InpxArchiveScanStatus;
  phase: InpxArchiveScanPhase;
  totalBooks: number;
  processedBooks: number;
  remainingBooks: number;
  addedBooks: number;
  coversGenerated: number;
  failedBooks: number;
  queuedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
}
