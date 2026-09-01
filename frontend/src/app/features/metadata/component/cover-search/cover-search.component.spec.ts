import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around dialog-driven book bootstrapping,
// remote cover-search streams, file-upload handling, and toast side effects so cover selection
// behavior can be asserted without the full overlay and browser file runtime.
describe('CoverSearchComponent', () => {
  it.todo('needs search seams to verify lookup, result selection, and empty-state messaging');
  it.todo('needs upload seams to verify local file import, cover replacement, and error reporting paths');
});
