import {describe, it} from 'vitest';

// NOTE(frontend-seam): This component orchestrates async font discovery/loading, dialog-driven
// uploads, confirmation flows, browser font registration, and toast reporting. Real coverage
// needs a dedicated font-runtime seam around `CustomFontService` and the dialog/confirm stack.
describe('CustomFontsComponent', () => {
  it.todo('needs a custom-font runtime seam to verify load success vs failure and browser font readiness');
  it.todo('needs a dialog-confirm seam to verify quota checks, upload additions, and delete flows');
});
