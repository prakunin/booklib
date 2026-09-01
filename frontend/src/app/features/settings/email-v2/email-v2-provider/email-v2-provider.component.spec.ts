import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around current-user bootstrapping, shared
// provider permission logic, dialog close streams, and confirm/toast side effects so provider
// management can be asserted without the full Optimus UI table runtime.
describe('EmailV2ProviderComponent', () => {
  it.todo('needs service seams to verify provider loading, default assignment, sharing toggles, and delete/save outcomes');
  it.todo('needs table-edit seams to verify row editing and create-provider dialog refresh behavior');
});
