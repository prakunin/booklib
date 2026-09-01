import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around dialog config input,
// library-query reactivity, placeholder resolution, and file-operation side effects so
// preview generation and move submission can be asserted without reproducing the full runtime graph.
describe('FileMoverComponent', () => {
  it.todo('needs a query-and-dialog seam to verify preview generation and target-library path updates');
  it.todo('needs a file-operations seam to verify move submission and toast behavior without coupling to runtime app settings effects');
});
