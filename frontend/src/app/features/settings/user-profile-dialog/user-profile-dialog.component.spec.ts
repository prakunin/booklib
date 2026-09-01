import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around the user-service signal effect,
// Optimus UI password controls, and toast/dialog side effects so profile edit and password-change
// branches can be asserted without mounting the full dialog runtime.
describe('UserProfileDialogComponent', () => {
  it.todo('needs user-signal seams to verify profile bootstrap, edit toggling, no-change exits, and update failures');
  it.todo('needs form and dialog seams to verify password validation, password-change outcomes, and close behavior');
});
