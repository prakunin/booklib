import {describe, expect, it} from 'vitest';

import {METADATA_SOURCE_FIELD_BY_CONTROL, metadataSourceFor} from './metadata-field-source';
import {
  METADATA_PROVIDER_LABEL_KEYS,
  metadataProviderLabelKey,
  metadataProviderSeverity,
  metadataProviderTagColor,
} from './metadata-provider-display';

describe('metadataSourceFor', () => {
  const sources = {TITLE: 'GoodReads', ISBN_13: 'FlibustaLocal'};

  it('finds the provider behind an editor field', () => {
    expect(metadataSourceFor(sources, 'title')).toBe('GoodReads');
    expect(metadataSourceFor(sources, 'isbn13')).toBe('FlibustaLocal');
  });

  it('reports nothing for a field with no row, which is the normal case', () => {
    expect(metadataSourceFor(sources, 'publisher')).toBeNull();
  });

  it('reports nothing when the endpoint attached no sources at all', () => {
    expect(metadataSourceFor(undefined, 'title')).toBeNull();
    expect(metadataSourceFor(null, 'title')).toBeNull();
    expect(metadataSourceFor({}, 'title')).toBeNull();
  });

  it('reports nothing for fields the backend never attributes', () => {
    // Collections are merged across providers, so no single provider owns them.
    for (const control of ['authors', 'categories', 'moods', 'tags', 'thumbnailUrl']) {
      expect(metadataSourceFor({TITLE: 'GoodReads'}, control)).toBeNull();
    }
  });

  it('maps only the fields the backend resolves from a provider chain', () => {
    expect(Object.keys(METADATA_SOURCE_FIELD_BY_CONTROL).sort()).toEqual([
      'description',
      'isbn10',
      'isbn13',
      'language',
      'pageCount',
      'publishedDate',
      'publisher',
      'seriesName',
      'seriesNumber',
      'seriesTotal',
      'subtitle',
      'title',
    ]);
  });
});

describe('metadata provider display', () => {
  it('keeps one table of provider colours, shared with the review tags', () => {
    expect(metadataProviderSeverity('amazon')).toBe('warn');
    expect(metadataProviderSeverity('FlibustaLocal')).toBe('info');
    expect(metadataProviderSeverity('GoodReads')).toBe('success');
    expect(metadataProviderSeverity(undefined)).toBe('success');
  });

  it('derives the shared/ui tag colour from that same table', () => {
    expect(metadataProviderTagColor('amazon')).toBe('amber');
    expect(metadataProviderTagColor('FlibustaLocal')).toBe('sky');
    expect(metadataProviderTagColor('GoodReads')).toBe('green');
  });

  it('translates only the provider names that mean nothing to a reader', () => {
    expect(metadataProviderLabelKey('FlibustaLocal')).toBe(METADATA_PROVIDER_LABEL_KEYS['flibustalocal']);
    expect(metadataProviderLabelKey('GoodReads')).toBeNull();
    expect(metadataProviderLabelKey('Google')).toBeNull();
    expect(metadataProviderLabelKey(undefined)).toBeNull();
  });
});
