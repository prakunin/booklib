import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around Chart.js matrix registration,
// translated tooltip callbacks, streak-stat loading, and year navigation so heatmap processing can
// be exercised without relying on matrix controller rendering internals.
describe('ReadingSessionHeatmapComponent', () => {
  it.todo('needs chart-runtime and streak seams to verify heatmap data shaping and milestone calculations deterministically');
});
