import {Injectable} from '@angular/core';
import {VirtualGridMetrics} from '../../../../shared/util/virtual-grid.util';
import {VIEW_MODES} from './book-browser-query-params.service';

export const INITIAL_LOADING_GRID_ITEM_COUNT = 24;

@Injectable({
  providedIn: 'root'
})
export class BookBrowserGridLayoutService {
  readonly gridGap = 21;
  readonly desktopBaseCardWidth = 135;
  readonly desktopBaseCardHeight = 220;
  readonly desktopMinScale = 0.5;
  readonly desktopMaxScale = 1.5;

  private readonly cardAspectRatio = 7 / 5;
  private readonly mobileTitleBarHeight = 32;
  private readonly audiobookTitleBarHeight = 31;

  desktopBaseWidth(audiobookOnly: boolean): number {
    return audiobookOnly
      ? this.desktopBaseCardWidth * 1.1
      : this.desktopBaseCardWidth;
  }

  minCardWidth(isMobile: boolean, audiobookOnly: boolean, scaleFactor: number): number {
    if (isMobile) {
      return 1;
    }

    return Math.round(this.desktopBaseWidth(audiobookOnly) * scaleFactor);
  }

  cardSizeForWidth(width: number, audiobookOnly: boolean): { width: number; height: number } {
    const cardWidth = Math.round(width);
    if (audiobookOnly) {
      return {width: cardWidth, height: cardWidth + this.audiobookTitleBarHeight};
    }

    return {
      width: cardWidth,
      height: Math.round(cardWidth * (this.desktopBaseCardHeight / this.desktopBaseCardWidth)),
    };
  }

  mobileCardSizeForWidth(width: number, audiobookOnly: boolean): { width: number; height: number } {
    const cardWidth = Math.round(width);
    const coverHeight = audiobookOnly
      ? cardWidth
      : Math.floor(cardWidth * this.cardAspectRatio);
    return {width: cardWidth, height: coverHeight + this.mobileTitleBarHeight};
  }

  estimateItemHeight(width: number, isMobile: boolean, audiobookOnly: boolean): number {
    return isMobile
      ? this.mobileCardSizeForWidth(width, audiobookOnly).height
      : this.cardSizeForWidth(width, audiobookOnly).height;
  }

  minimumLoadingItemCount(
    {viewportHeight, columns, itemHeight, gap}: VirtualGridMetrics,
    showLoadingPlaceholder: boolean,
    currentViewMode: string | undefined
  ): number {
    if (!showLoadingPlaceholder || currentViewMode !== VIEW_MODES.GRID) {
      return 0;
    }
    if (viewportHeight <= 0 || itemHeight <= 0) {
      return INITIAL_LOADING_GRID_ITEM_COUNT;
    }

    const visibleRows = Math.ceil((viewportHeight + gap) / (itemHeight + gap));
    return Math.max(INITIAL_LOADING_GRID_ITEM_COUNT, (visibleRows + 1) * columns);
  }
}
