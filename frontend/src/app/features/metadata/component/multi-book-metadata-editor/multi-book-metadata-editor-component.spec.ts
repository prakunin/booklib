import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around Angular Query detail loading,
// signal-based book navigation, and dialog-hosted tab composition so multi-book metadata editing
// can be asserted without the live query client and dialog runtime.
describe('MultiBookMetadataEditorComponent', () => {
  it.todo('needs query seams to verify filtered-book selection and current-book detail loading');
  it.todo('needs navigation seams to verify next/previous boundaries and dialog close behavior');
});
