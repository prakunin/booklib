import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around signal/effect synchronization,
// virtual-scroller behavior, router/route query-param state, and cross-service selection logic so
// author browsing can be tested without a full browser shell harness.
describe('AuthorBrowserComponent', () => {
  it.todo('needs state-sync seams to verify paged query controls and active-filter counting');
  it.todo('needs browser-shell seams to verify selection, scroll restoration, route state, and thumbnail cache-busting behavior');
});
