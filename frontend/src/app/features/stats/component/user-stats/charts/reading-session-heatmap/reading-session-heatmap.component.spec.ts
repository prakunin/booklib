import {describe, expect, it} from 'vitest';
import {parseLocalDateOnly} from './reading-session-heatmap-date.util';

describe('ReadingSessionHeatmapComponent helpers', () => {
  it('parses date-only heatmap values as local dates', () => {
    const date = parseLocalDateOnly('2026-01-15');

    expect(date.getFullYear()).toBe(2026);
    expect(date.getMonth()).toBe(0);
    expect(date.getDate()).toBe(15);
  });
});
