import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around DOMPurify preview generation,
// batch-upload side effects, dialog closing, and drag/drop DOM events so the SVG-management
// flow can be proven without changing runtime code or reproducing browser-only behavior inline.
describe('IconPickerComponent', () => {
  it.todo('needs a browser-event seam to verify SVG preview, validation, and drag-to-delete flows');
  it.todo('needs an upload-result seam to verify partial-success batch messaging and cache refresh behavior');
});
