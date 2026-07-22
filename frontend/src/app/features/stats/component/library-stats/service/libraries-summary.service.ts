import {computed, inject, Injectable} from '@angular/core';
import {LibraryStatsApiService} from './library-stats-api.service';

export interface BooksSummary {
  totalBooks: number;
  totalSizeKb: number;
  totalAuthors: number;
  totalSeries: number;
  totalPublishers: number;
}

@Injectable({
  providedIn: 'root'
})
export class LibrariesSummaryService {
  private readonly libraryStats = inject(LibraryStatsApiService);
  readonly booksSummary = computed<BooksSummary>(() => {
    const stats = this.libraryStats.data();
    if (!stats) {
      return {totalBooks: 0, totalSizeKb: 0, totalAuthors: 0, totalSeries: 0, totalPublishers: 0};
    }
    return {
      totalBooks: stats.totalBooks,
      totalSizeKb: stats.totalSizeKb,
      totalAuthors: stats.totalAuthors,
      totalSeries: stats.totalSeries,
      totalPublishers: stats.totalPublishers
    };
  });
  readonly formattedSize = computed(() => this.formatSizeKb(this.booksSummary().totalSizeKb));

  private formatSizeKb(kb: number): string {
    if (!kb) return '0 KB';
    const kilo = 1024;
    const megaKb = kilo; // 1 MB = 1024 KB
    const gigaKb = kilo * megaKb; // 1 GB = 1024 * 1024 KB
    if (kb >= gigaKb) {
      return (kb / gigaKb).toFixed(2) + ' GB';
    }
    if (kb >= megaKb) {
      return (kb / megaKb).toFixed(2) + ' MB';
    }
    return kb + ' KB';
  }
}
