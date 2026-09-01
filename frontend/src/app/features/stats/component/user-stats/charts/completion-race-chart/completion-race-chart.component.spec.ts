import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around the UserStatsService stream, date
// normalization, and Chart.js line-series assembly so yearly race aggregation can be tested
// without leaning on a full chart runtime.
describe('CompletionRaceChartComponent', () => {
  it.todo('needs service and transformation seams to verify year changes, session grouping, and fastest/slowest book summaries');
  it.todo('needs chart-adapter seams to verify line dataset generation and translated tooltip callbacks');
});
