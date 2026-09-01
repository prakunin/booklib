import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around dialog payload setup, reactive-form
// state mutation, autocomplete widgets, and chained metadata-plus-cover uploads so bulk update
// branching can be asserted without the full dialog and Optimus UI runtime.
describe('BulkMetadataUpdateComponent', () => {
  it.todo('needs form seams to verify clear-field toggles, manual autocomplete entry, and payload construction');
  it.todo('needs upload seams to verify metadata success, partial cover-upload failure, and loading-state transitions');
});
