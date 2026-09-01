import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around dialog payload bootstrapping,
// autocomplete/search streams, and metadata copy/reset helpers so picker decisions can be
// asserted without the full bookdrop dialog and Optimus UI form runtime.
describe('BookdropFileMetadataPickerComponent', () => {
  it.todo('needs dialog and form seams to verify metadata bootstrap, reset, and apply behavior');
  it.todo('needs search seams to verify candidate loading, selection changes, and duplicate-handling paths');
});
