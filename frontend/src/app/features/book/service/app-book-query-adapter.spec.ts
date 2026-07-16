import {describe, expect, it} from 'vitest';

import {EntityType} from '../components/book-browser/book-browser-entity-type';
import {SortDirection} from '../model/sort.model';
import {toAppBookFilters, toAppBookSort} from './app-book-query-adapter';

describe('app book query adapter', () => {
  it('maps entity context and legacy facet names', () => {
    expect(toAppBookFilters(7, EntityType.LIBRARY, {
      author: ['Ursula Le Guin'],
      bookType: ['EPUB'],
      shelf: ['12'],
    }, 'and')).toEqual({
      libraryId: 7,
      authors: ['Ursula Le Guin'],
      fileType: ['EPUB'],
      shelves: ['12'],
      filterMode: 'and',
    });
  });

  it('maps unshelved context without an entity id', () => {
    expect(toAppBookFilters(Number.NaN, EntityType.UNSHELVED, null, 'single')).toEqual({
      unshelved: true,
      filterMode: 'or',
    });
  });

  it('encodes every supported sort criterion in priority order', () => {
    expect(toAppBookSort([
      {field: 'title', label: 'Title', direction: SortDirection.ASCENDING},
      {field: 'addedOn', label: 'Added', direction: SortDirection.DESCENDING},
    ])).toEqual({field: 'title,-addedOn', dir: 'asc'});
  });

  it('drops obsolete unsupported persisted sort fields and falls back safely', () => {
    expect(toAppBookSort([
      {field: 'filePath', label: 'Path', direction: SortDirection.ASCENDING},
    ])).toEqual({field: 'addedOn', dir: 'desc'});
  });
});
