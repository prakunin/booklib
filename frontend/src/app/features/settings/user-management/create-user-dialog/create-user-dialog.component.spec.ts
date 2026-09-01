import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around the large reactive form, admin
// permission fan-out side effects, and dialog-close behavior so the create-user workflow can be
// tested without mounting the full Optimus UI form stack.
describe('CreateUserDialogComponent', () => {
  it.todo('needs form seams to verify validator setup, admin permission propagation, and library selection shaping');
  it.todo('needs mutation seams to verify invalid-form warnings and create-user success and failure flows');
});
