import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around header-service signals, router
// fallback behavior, and the reader shell template so ebook-header interactions can be tested
// without mounting the full reader layout runtime.
describe('ReaderHeaderComponent', () => {
  it.todo('needs service seams to verify sidebar, notes, search, bookmark, controls, fullscreen, and help actions');
  it.todo('needs browser-history seams to verify dashboard fallback versus close delegation');
});
