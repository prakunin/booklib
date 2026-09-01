import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around the Foliate custom element runtime,
// DOM container bootstrapping, route-derived file loading, and the large injected reader service
// graph so the ebook reader can be tested without a browser-backed integration harness.
describe('EbookReaderComponent', () => {
  it.todo('needs loader seams to verify Foliate initialization, font loading, and view setup failure handling');
  it.todo('needs orchestration seams to verify route-based book loading, selection and note flows, and destroy-time cleanup');
});
