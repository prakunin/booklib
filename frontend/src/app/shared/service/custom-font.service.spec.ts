import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs a stable Angular Query harness plus a controllable
// FontFace/document.fonts environment so query cache mutation and browser font registration can be
// asserted without changing runtime code.
describe('CustomFontService', () => {
  it.todo('needs a query-client and font-face seam to validate cache updates after upload and deletion');
  it.todo('needs a controllable browser font registry seam to validate loadFontFace and removeFontFace behavior');
});
