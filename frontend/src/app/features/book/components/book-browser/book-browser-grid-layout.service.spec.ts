import {describe, expect, it} from 'vitest';

import {BookBrowserGridLayoutService, INITIAL_LOADING_GRID_ITEM_COUNT} from './book-browser-grid-layout.service';
import {VIEW_MODES} from './book-browser-query-params.service';

describe('BookBrowserGridLayoutService', () => {
  const service = new BookBrowserGridLayoutService();

  it('derives desktop base width and min card width from media type and scale', () => {
    expect(service.desktopBaseWidth(false)).toBe(135);
    expect(service.desktopBaseWidth(true)).toBeCloseTo(148.5);
    expect(service.minCardWidth(false, false, 1.2)).toBe(162);
    expect(service.minCardWidth(false, true, 1)).toBe(149);
    expect(service.minCardWidth(true, false, 1.5)).toBe(1);
  });

  it('computes desktop and mobile card heights for books and audiobooks', () => {
    expect(service.cardSizeForWidth(150.4, false)).toEqual({width: 150, height: 244});
    expect(service.cardSizeForWidth(150.4, true)).toEqual({width: 150, height: 181});
    expect(service.mobileCardSizeForWidth(120.7, false)).toEqual({width: 121, height: 201});
    expect(service.mobileCardSizeForWidth(120.7, true)).toEqual({width: 121, height: 153});
  });

  it('estimates item height for the active viewport family', () => {
    expect(service.estimateItemHeight(120, false, false)).toBe(196);
    expect(service.estimateItemHeight(120, true, false)).toBe(200);
  });

  it('sizes grid loading placeholders to fill the viewport', () => {
    expect(service.minimumLoadingItemCount({
      viewportWidth: 960,
      viewportHeight: 900,
      columns: 6,
      itemHeight: 200,
      gap: 20,
    }, true, VIEW_MODES.GRID)).toBe(36);
  });

  it('does not reserve grid placeholders outside the loading grid state', () => {
    const metrics = {
      viewportWidth: 960,
      viewportHeight: 900,
      columns: 6,
      itemHeight: 200,
      gap: 20,
    };

    expect(service.minimumLoadingItemCount(metrics, false, VIEW_MODES.GRID)).toBe(0);
    expect(service.minimumLoadingItemCount(metrics, true, VIEW_MODES.TABLE)).toBe(0);
    expect(service.minimumLoadingItemCount({
      ...metrics,
      viewportHeight: 0,
    }, true, VIEW_MODES.GRID)).toBe(INITIAL_LOADING_GRID_ITEM_COUNT);
  });
});
