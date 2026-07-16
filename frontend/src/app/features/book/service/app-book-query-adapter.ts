import type {AppBookFilters, AppBookSort} from '../model/app-book.model';
import {SortDirection, type SortOption} from '../model/sort.model';
import type {BookFilterMode} from '../../settings/user-management/user.service';
import {EntityType} from '../components/book-browser/book-browser-entity-type';

const FILTER_PARAM_NAMES: Readonly<Record<string, keyof AppBookFilters>> = {
  author: 'authors',
  category: 'category',
  series: 'series',
  bookType: 'fileType',
  readStatus: 'status',
  personalRating: 'personalRating',
  publisher: 'publisher',
  matchScore: 'matchScore',
  library: 'libraries',
  shelf: 'shelves',
  shelfStatus: 'shelfStatus',
  tag: 'tag',
  publishedDate: 'publishedDate',
  fileSize: 'fileSize',
  amazonRating: 'amazonRating',
  goodreadsRating: 'goodreadsRating',
  hardcoverRating: 'hardcoverRating',
  lubimyczytacRating: 'lubimyczytacRating',
  ranobedbRating: 'ranobedbRating',
  audibleRating: 'audibleRating',
  language: 'language',
  pageCount: 'pageCount',
  mood: 'mood',
  ageRating: 'ageRating',
  contentRating: 'contentRating',
  narrator: 'narrator',
  comicCharacter: 'comicCharacter',
  comicTeam: 'comicTeam',
  comicLocation: 'comicLocation',
  comicCreator: 'comicCreator',
};

const SERVER_SORT_FIELDS = new Set([
  'title', 'seriesName', 'seriesNumber', 'lastReadTime', 'personalRating', 'addedOn',
  'publisher', 'publishedDate', 'readStatus', 'dateFinished', 'readingProgress',
  'amazonRating', 'amazonReviewCount', 'goodreadsRating', 'goodreadsReviewCount',
  'hardcoverRating', 'hardcoverReviewCount', 'ranobedbRating', 'narrator', 'pageCount',
]);

export function toAppBookFilters(
  entityId: number,
  entityType: EntityType,
  selected: Record<string, string[]> | null,
  mode: BookFilterMode,
): AppBookFilters {
  const filters: AppBookFilters = {
    filterMode: mode === 'single' ? 'or' : mode,
  };

  if (!Number.isNaN(entityId)) {
    if (entityType === EntityType.LIBRARY) filters.libraryId = entityId;
    if (entityType === EntityType.SHELF) filters.shelfId = entityId;
    if (entityType === EntityType.MAGIC_SHELF) filters.magicShelfId = entityId;
  }
  if (entityType === EntityType.UNSHELVED) filters.unshelved = true;

  for (const [legacyName, values] of Object.entries(selected ?? {})) {
    const appName = FILTER_PARAM_NAMES[legacyName];
    if (!appName || values.length === 0) continue;
    (filters as Record<string, unknown>)[appName] = values.map(String);
  }

  return filters;
}

export function toAppBookSort(criteria: SortOption[]): AppBookSort {
  const supportedCriteria = criteria.filter(criterion => SERVER_SORT_FIELDS.has(criterion.field));
  if (supportedCriteria.length === 0) {
    return {field: 'addedOn', dir: 'desc'};
  }

  return {
    field: supportedCriteria
      .map(criterion => criterion.direction === SortDirection.DESCENDING
        ? `-${criterion.field}`
        : criterion.field)
      .join(','),
    dir: 'asc',
  };
}
