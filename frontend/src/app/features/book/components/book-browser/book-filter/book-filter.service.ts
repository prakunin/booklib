import {computed, inject, Injectable, Signal} from '@angular/core';
import {
  AGE_RATING_OPTIONS,
  FILE_SIZE_RANGES,
  Filter,
  FilterType,
  MATCH_SCORE_RANGES,
  NUMERIC_ID_FILTER_TYPES,
  PAGE_COUNT_RANGES,
  RATING_OPTIONS_10,
  RATING_RANGES_5,
  READ_STATUS_LABELS,
  RangeConfig,
} from './book-filter.config';
import {AppBooksApiService} from '../../../service/app-books-api.service';
import {AppFilterOptions, CountedOption} from '../../../model/app-book.model';

const OPTION_KEYS: Readonly<Record<FilterType, keyof AppFilterOptions>> = {
  author: 'authors',
  category: 'categories',
  series: 'series',
  bookType: 'fileTypes',
  readStatus: 'readStatuses',
  personalRating: 'personalRatings',
  publisher: 'publishers',
  matchScore: 'matchScores',
  library: 'libraries',
  shelf: 'shelves',
  shelfStatus: 'shelfStatuses',
  tag: 'tags',
  publishedDate: 'publishedYears',
  fileSize: 'fileSizes',
  amazonRating: 'amazonRatings',
  goodreadsRating: 'goodreadsRatings',
  hardcoverRating: 'hardcoverRatings',
  lubimyczytacRating: 'lubimyczytacRatings',
  ranobedbRating: 'ranobedbRatings',
  audibleRating: 'audibleRatings',
  language: 'languages',
  pageCount: 'pageCounts',
  mood: 'moods',
  ageRating: 'ageRatings',
  contentRating: 'contentRatings',
  narrator: 'narrators',
  comicCharacter: 'comicCharacters',
  comicTeam: 'comicTeams',
  comicLocation: 'comicLocations',
  comicCreator: 'comicCreators',
};

const RANGE_OPTIONS: Partial<Record<FilterType, readonly RangeConfig[]>> = {
  personalRating: RATING_OPTIONS_10,
  matchScore: MATCH_SCORE_RANGES,
  fileSize: FILE_SIZE_RANGES,
  amazonRating: RATING_RANGES_5,
  goodreadsRating: RATING_RANGES_5,
  hardcoverRating: RATING_RANGES_5,
  lubimyczytacRating: RATING_RANGES_5,
  ranobedbRating: RATING_RANGES_5,
  audibleRating: RATING_RANGES_5,
  pageCount: PAGE_COUNT_RANGES,
  ageRating: AGE_RATING_OPTIONS,
};

@Injectable({providedIn: 'root'})
export class BookFilterService {
  private readonly appBooksApi = inject(AppBooksApiService);

  createFilterSignals(): Record<FilterType, Signal<Filter[]>> {
    const result = {} as Record<FilterType, Signal<Filter[]>>;
    for (const type of Object.keys(OPTION_KEYS) as FilterType[]) {
      result[type] = computed(() => this.optionsFor(type));
    }
    return result;
  }

  processFilterValue(key: string, value: unknown): unknown {
    if (NUMERIC_ID_FILTER_TYPES.has(key as FilterType) && typeof value === 'string') {
      const id = value.includes(':') ? value.slice(0, value.indexOf(':')) : value;
      return Number(id);
    }
    return value;
  }

  isNumericFilter(filterType: string): boolean {
    return NUMERIC_ID_FILTER_TYPES.has(filterType as FilterType);
  }

  private optionsFor(type: FilterType): Filter[] {
    const options = this.appBooksApi.filterOptions();
    if (!options) return [];

    if (type === 'language') {
      return options.languages.map(option => ({
        value: {id: option.code, name: option.label},
        bookCount: option.count,
      }));
    }

    const counted = options[OPTION_KEYS[type]] as CountedOption[];
    return counted.map(option => this.toFilter(type, option));
  }

  private toFilter(type: FilterType, option: CountedOption): Filter {
    const range = RANGE_OPTIONS[type]?.find(item => String(item.id) === option.name);
    if (range) {
      return {
        value: {id: range.id, name: range.label, sortIndex: range.sortIndex},
        bookCount: option.count,
      };
    }

    if (type === 'readStatus') {
      return {
        value: {id: option.name, name: READ_STATUS_LABELS[option.name as keyof typeof READ_STATUS_LABELS] ?? option.name},
        bookCount: option.count,
      };
    }

    if (type === 'library' || type === 'shelf') {
      const separator = option.name.indexOf(':');
      const id = separator >= 0 ? option.name.slice(0, separator) : option.name;
      const name = separator >= 0 ? option.name.slice(separator + 1) : option.name;
      return {value: {id: Number(id), name}, bookCount: option.count};
    }

    return {value: {id: option.name, name: option.name}, bookCount: option.count};
  }
}
