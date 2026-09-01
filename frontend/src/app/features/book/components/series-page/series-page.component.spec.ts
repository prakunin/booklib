import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around route-param signals, Angular Query
// detail loading, selection state, and the large series dashboard template so the series page
// control flow can be asserted without mounting the full browser and query runtime.
describe('SeriesPageComponent', () => {
  it.todo('needs route and query seams to verify filtered series books, cover-book resolution, and description loading');
  it.todo('needs interaction seams to verify selection, menu actions, metadata flows, and overflow handling');
});
