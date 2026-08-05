/**
 * `BookMetadata.fieldSources` as the API sends it: the backend `MetadataField` constant name mapped
 * to the `MetadataProvider` name whose value is currently stored in that field.
 *
 * A field with no entry has no recorded source. That is the normal state — for everything a user
 * typed, and for everything filled before provenance was recorded — and it must render as nothing at
 * all, never as "unknown" or "manual".
 */
export type MetadataFieldSources = Readonly<Record<string, string>>;

/**
 * Metadata editor form control → the `MetadataField` constant whose row describes it.
 *
 * Only the fields the backend merger resolves from a *chain of providers* are here. The provider
 * identifiers and ratings (`goodreadsRating`, `asin`, `googleId`, …) are bound to exactly one provider
 * by construction, so their row can only ever name the provider already in the field's own label —
 * badging them would repeat the label nineteen times and say nothing.
 *
 * The collection fields (authors, genres, moods, tags) and the cover have no rows at all: the backend
 * merges them across providers, so there is no single winner to attribute.
 */
export const METADATA_SOURCE_FIELD_BY_CONTROL: Readonly<Record<string, string>> = {
  title: 'TITLE',
  subtitle: 'SUBTITLE',
  description: 'DESCRIPTION',
  publisher: 'PUBLISHER',
  publishedDate: 'PUBLISHED_DATE',
  seriesName: 'SERIES_NAME',
  seriesNumber: 'SERIES_NUMBER',
  seriesTotal: 'SERIES_TOTAL',
  isbn13: 'ISBN_13',
  isbn10: 'ISBN_10',
  language: 'LANGUAGE',
  pageCount: 'PAGE_COUNT',
};

/**
 * The provider behind one editor field, or null when nothing is recorded for it.
 */
export function metadataSourceFor(
  sources: MetadataFieldSources | null | undefined,
  controlName: string | null | undefined,
): string | null {
  if (!sources || !controlName) {
    return null;
  }
  const field = METADATA_SOURCE_FIELD_BY_CONTROL[controlName];
  return field ? (sources[field] ?? null) : null;
}
