import {describe, it} from 'vitest';

// NOTE(frontend-seam): Honest coverage for this component needs an Optimus UI FileUpload host seam,
// controllable library/settings signals, and a stable upload-progress harness for `HttpRequest`
// event streams and dialog close behavior. The current surface mixes view-child state, effects,
// progress events, and UI messaging too tightly for a reliable low-noise unit spec tonight.
describe('BookUploaderComponent', () => {
  it.todo('needs a file-upload seam to verify destination validation, duplicate filtering, and size failures');
  it.todo('needs an upload-progress seam to verify request batching, status transitions, and bookdrop close behavior');
});
