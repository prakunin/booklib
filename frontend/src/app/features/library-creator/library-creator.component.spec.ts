import {describe, it} from 'vitest';

// NOTE(frontend-seam): This component currently couples DynamicDialog config/ref data,
// router navigation, live library/icon services, format-count HTTP reads, drag-drop, and
// transloco-backed option initialization in one large standalone surface. Honest coverage
// needs a narrower presenter seam or a purpose-built harness, not a brittle all-up mock stack.
describe('LibraryCreatorComponent', () => {
  it.todo('needs a dialog-and-library seam to verify create vs edit initialization and duplicate-name handling');
  it.todo('needs a stable interaction seam to verify format selection, folder updates, and submit payload assembly');
});
