import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around Chart.js scatter datasets, translated
// tooltip callbacks, and stats-service loading so outlier filtering and dominant-archetype selection
// can be tested without binding to chart rendering internals.
describe('SessionArchetypesChartComponent', () => {
  it.todo('needs chart-data seams to verify scatter dataset grouping and dominant archetype calculations');
});
