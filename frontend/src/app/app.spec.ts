import {describe, it} from 'vitest';

// NOTE(frontend-seam): the root App component is bootstrapped with the full provider graph
// (router, query client, websocket dispatcher); a meaningful shell test needs those seams stubbed.
describe('App', () => {
  it.todo('needs bootstrap seams to verify the root shell renders and wires the global dispatcher');
});
