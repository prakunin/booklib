export type InpxArchiveScanStatus = 'IDLE' | 'QUEUED' | 'SCANNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
export type InpxArchiveScanPhase = 'QUEUED' | 'IMPORTING' | 'METADATA_AND_COVERS' | 'COMPLETED' | 'FAILED';

export interface InpxArchive {
  archiveName: string;
  sizeBytes: number;
  fb2Count: number | null;
  importedBookCount: number | null;
  coveredBookCount: number | null;
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

export type LocalCatalogSourceType = 'REVIEW' | 'AUTHOR_BIO' | 'COMPILATION' | 'COMPILATION_PART' | 'LANGUAGE';

/**
 * What a local catalog attached to a library has indexed, and how much of the library's metadata it
 * has actually filled. `authorsWithBiography` is global (authors are not library-scoped), not specific
 * to this library — see `LocalCatalogStatusDto` on the backend.
 */
export interface LocalCatalogStatus {
  configured: boolean;
  catalogPath: string | null;
  indexedEntries: Record<LocalCatalogSourceType, number>;
  totalBooks: number;
  booksWithDescription: number;
  localReviews: number;
  authorsWithBiography: number;
}
