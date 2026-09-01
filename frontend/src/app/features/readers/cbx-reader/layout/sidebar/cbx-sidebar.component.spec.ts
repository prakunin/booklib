import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around animation timing, signal-driven
// sidebar state, and note/bookmark editing callbacks so open/close and sidebar interaction flows
// can be asserted without the full reader shell runtime.
describe('CbxSidebarComponent', () => {
  it.todo('needs animation seams to verify delayed close behavior and overlay interactions');
  it.todo('needs sidebar-service seams to verify page, bookmark, note, and search interactions');
});
