import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around signal-driven effect execution,
// matrix-chart cell sizing, and translated tooltip formatting so completion heatmap aggregation
// can be asserted without a live chart layout engine.
describe('ReadingHeatmapChartComponent', () => {
  it.todo('needs effect and transformation seams to verify year-month counting across the rolling ten-year window');
  it.todo('needs chart-layout seams to verify year labels, alpha scaling, and matrix cell sizing callbacks deterministically');
});
