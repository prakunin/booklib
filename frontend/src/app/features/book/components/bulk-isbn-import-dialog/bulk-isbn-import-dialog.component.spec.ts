import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around file-reader input, paced async
// import loops, metadata lookup retries, and library-service state so ISBN parsing and import
// progress can be asserted without browser file APIs and timing-heavy runtime behavior.
describe('BulkIsbnImportDialogComponent', () => {
  it.todo('needs parser seams to verify pasted-text parsing, file parsing, duplicate removal, and already-existing ISBN filtering');
  it.todo('needs async-loop seams to verify import progress, cancellation, retry timing, and summary transitions');
});
