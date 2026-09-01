import {ageRatingRanges, fileSizeRanges, matchScoreRanges, pageCountRanges, ratingRanges} from '../book-filter/book-filter.config';
import {Book, ComicMetadata, ReadStatus} from '../../../model/book.model';
import {BookFilterMode} from '../../../../settings/user-management/user.service';

export function isRatingInRange(rating: number | undefined | null, rangeId: string | number): boolean {
  if (rating == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = ratingRanges.find(r => r.id === numericId);
  if (!range) return false;
  return rating >= range.min && rating < range.max;
}

export function isRatingInRange10(rating: number | undefined | null, rangeId: string | number): boolean {
  if (rating == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  return Math.round(rating) === numericId;
}

export function isFileSizeInRange(fileSizeKb: number | undefined, rangeId: string | number): boolean {
  if (fileSizeKb == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = fileSizeRanges.find(r => r.id === numericId);
  if (!range) return false;
  return fileSizeKb >= range.min && fileSizeKb < range.max;
}

export function isPageCountInRange(pageCount: number | undefined, rangeId: string | number): boolean {
  if (pageCount == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = pageCountRanges.find(r => r.id === numericId);
  if (!range) return false;
  return pageCount >= range.min && pageCount < range.max;
}

export function isMatchScoreInRange(score: number | undefined | null, rangeId: string | number): boolean {
  if (score == null) return false;
  const normalizedScore = score > 1 ? score / 100 : score;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = matchScoreRanges.find(r => r.id === numericId);
  if (!range) return false;
  return normalizedScore >= range.min && normalizedScore < range.max;
}

export function isAgeRatingInRange(ageRating: number | undefined | null, rangeId: string | number): boolean {
  if (ageRating == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = ageRatingRanges.find(r => r.id === numericId);
  if (!range) return false;
  return ageRating >= range.min && ageRating < range.max;
}

export function doesBookMatchReadStatus(book: Book, selected: unknown[]): boolean {
  const status = book.readStatus ?? ReadStatus.UNSET;
  return selected.includes(status);
}

function matchByMode(filterValues: unknown[], effectiveMode: BookFilterMode, predicate: (val: unknown) => unknown): boolean {
  return effectiveMode === 'or' ? filterValues.some(predicate) : filterValues.every(predicate);
}

function matchesListField(filterValues: unknown[], effectiveMode: BookFilterMode, list: readonly string[] | null | undefined): boolean {
  return matchByMode(filterValues, effectiveMode, val => list?.includes(val as string));
}

function matchesValueField(filterValues: unknown[], effectiveMode: BookFilterMode, value: unknown): boolean {
  return effectiveMode === 'or' ? filterValues.includes(value) : filterValues.every(val => value === val);
}

function matchesPublishedYear(book: Book, filterValues: unknown[]): boolean {
  const bookYear = book.metadata?.publishedDate
    ? new Date(book.metadata.publishedDate).getFullYear()
    : null;
  return bookYear ? filterValues.some(val => val == bookYear || val == bookYear.toString()) : false;
}

function collectComicCreators(comic: ComicMetadata): string[] {
  const allCreators: string[] = [];
  const roles: [string[] | undefined, string][] = [
    [comic.pencillers, 'penciller'],
    [comic.inkers, 'inker'],
    [comic.colorists, 'colorist'],
    [comic.letterers, 'letterer'],
    [comic.coverArtists, 'coverArtist'],
    [comic.editors, 'editor']
  ];
  for (const [names, role] of roles) {
    if (names) {
      for (const name of names) {
        allCreators.push(`${name}:${role}`);
      }
    }
  }
  return allCreators;
}

export function doesBookMatchFilter(
  book: Book,
  filterType: string,
  filterValues: unknown[],
  mode: BookFilterMode
): boolean {
  if (!Array.isArray(filterValues) || filterValues.length === 0) {
    return mode === 'or';
  }

  const effectiveMode = mode === 'not' ? 'or' : mode;

  switch (filterType) {
    case 'author':
      return matchesListField(filterValues, effectiveMode, book.metadata?.authors);
    case 'category':
      return matchesListField(filterValues, effectiveMode, book.metadata?.categories);
    case 'series':
      return matchesValueField(filterValues, effectiveMode, book.metadata?.seriesName?.trim());
    case 'bookType':
      return book.isPhysical ? filterValues.includes('PHYSICAL') : filterValues.includes(book.primaryFile?.bookType);
    case 'readStatus':
      return doesBookMatchReadStatus(book, filterValues);
    case 'personalRating':
      return filterValues.some(range => isRatingInRange10(book.personalRating, range as string | number));
    case 'publisher':
      return matchesValueField(filterValues, effectiveMode, book.metadata?.publisher);
    case 'matchScore':
      return filterValues.some(range => isMatchScoreInRange(book.metadataMatchScore, range as string | number));
    case 'library':
      return matchByMode(filterValues, effectiveMode, val => val == book.libraryId);
    case 'shelf':
      return matchByMode(filterValues, effectiveMode, val => book.shelves?.some(s => s.id == val));
    case 'shelfStatus': {
      const shelved = book.shelves && book.shelves.length > 0 ? 'shelved' : 'unshelved';
      return filterValues.includes(shelved);
    }
    case 'tag':
      return matchesListField(filterValues, effectiveMode, book.metadata?.tags);
    case 'publishedDate':
      return matchesPublishedYear(book, filterValues);
    case 'fileSize':
      return filterValues.some(range => isFileSizeInRange(book.fileSizeKb, range as string | number));
    case 'amazonRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.amazonRating, range as string | number));
    case 'goodreadsRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.goodreadsRating, range as string | number));
    case 'hardcoverRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.hardcoverRating, range as string | number));
    case 'lubimyczytacRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.lubimyczytacRating, range as string | number));
    case 'ranobedbRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.ranobedbRating, range as string | number));
    case 'audibleRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.audibleRating, range as string | number));
    case 'language':
      return filterValues.includes(book.metadata?.language);
    case 'pageCount':
      return filterValues.some(range => isPageCountInRange(book.metadata?.pageCount ?? undefined, range as string | number));
    case 'mood':
      return matchesListField(filterValues, effectiveMode, book.metadata?.moods);
    case 'ageRating':
      return filterValues.some(range => isAgeRatingInRange(book.metadata?.ageRating, range as string | number));
    case 'contentRating':
      return filterValues.includes(book.metadata?.contentRating);
    case 'narrator':
      return filterValues.includes(book.metadata?.narrator);
    case 'comicCharacter':
      return matchesListField(filterValues, effectiveMode, book.metadata?.comicMetadata?.characters);
    case 'comicTeam':
      return matchesListField(filterValues, effectiveMode, book.metadata?.comicMetadata?.teams);
    case 'comicLocation':
      return matchesListField(filterValues, effectiveMode, book.metadata?.comicMetadata?.locations);
    case 'comicCreator': {
      const comic = book.metadata?.comicMetadata;
      if (!comic) return false;
      return matchesListField(filterValues, effectiveMode, collectComicCreators(comic));
    }
    default:
      return false;
  }
}

export function filterBooksByFilters(
  books: Book[],
  activeFilters: Record<string, unknown[]> | null,
  mode: BookFilterMode,
  excludeFilterType?: string
): Book[] {
  if (!activeFilters) return books;

  const filterEntries = Object.entries(activeFilters)
    .filter(([type]) => type !== excludeFilterType);

  if (filterEntries.length === 0) return books;

  return books.filter(book => matchesAllFilters(book, filterEntries, mode));
}

function matchesAllFilters(
  book: Book,
  filterEntries: [string, unknown[]][],
  mode: BookFilterMode
): boolean {
  if (mode === 'or') {
    for (const [filterType, filterValues] of filterEntries) {
      if (doesBookMatchFilter(book, filterType, filterValues, mode)) {
        return true;
      }
    }

    return false;
  }

  if (mode === 'not') {
    for (const [filterType, filterValues] of filterEntries) {
      if (doesBookMatchFilter(book, filterType, filterValues, mode)) {
        return false;
      }
    }

    return true;
  }

  for (const [filterType, filterValues] of filterEntries) {
    if (!doesBookMatchFilter(book, filterType, filterValues, mode)) {
      return false;
    }
  }

  return true;
}
