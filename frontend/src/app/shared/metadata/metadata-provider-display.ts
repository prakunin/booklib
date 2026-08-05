import {TagColor} from '../ui/tag/app-tag.variants';

export type MetadataProviderSeverity = 'success' | 'warn' | 'info';

/**
 * The app's single table of provider colours. Reviews tag their source provider and the metadata
 * editor badges the source of each field; two independent tables would drift, so both read this one.
 *
 * Severities are PrimeNG's, because the review tags are still `p-tag`. Anything built on `shared/ui`
 * goes through `metadataProviderTagColor`, which derives its colour from the same severity rather
 * than restating it.
 */
export function metadataProviderSeverity(provider: string | null | undefined): MetadataProviderSeverity {
  switch (provider?.toLowerCase()) {
    case 'amazon':
      return 'warn';
    case 'flibustalocal':
      return 'info';
    default:
      return 'success';
  }
}

const TAG_COLOR_BY_SEVERITY: Readonly<Record<MetadataProviderSeverity, TagColor>> = {
  success: 'green',
  warn: 'amber',
  info: 'sky',
};

export function metadataProviderTagColor(provider: string | null | undefined): TagColor {
  return TAG_COLOR_BY_SEVERITY[metadataProviderSeverity(provider)];
}

/**
 * Providers whose backend enum name is not something to show a human, keyed by the lower-cased name.
 *
 * Provider names are proper nouns — Goodreads, Google, Amazon are shown verbatim and are not
 * translated. Only a name that means nothing outside the codebase gets an entry here.
 */
export const METADATA_PROVIDER_LABEL_KEYS: Readonly<Record<string, string>> = {
  flibustalocal: 'shared.metadataProvider.flibustaLocal',
};

/**
 * The translation key for a provider's label, or null when the provider's own name is the label.
 */
export function metadataProviderLabelKey(provider: string | null | undefined): string | null {
  if (!provider) {
    return null;
  }
  return METADATA_PROVIDER_LABEL_KEYS[provider.toLowerCase()] ?? null;
}
