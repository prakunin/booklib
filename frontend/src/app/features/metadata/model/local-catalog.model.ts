/**
 * One reader review as the catalog holds it. The reviewer name is frequently blank in the source
 * data, hence optional rather than required.
 */
export interface LocalCatalogReview {
  reviewerName?: string;
  body: string;
  postedAt?: string;
}

/**
 * One end of a compilation relationship. `title` is absent when the catalog records the
 * relationship but holds no listing row for that key.
 */
export interface LocalCatalogCompilationRef {
  archiveName: string;
  entryName: string;
  part: number;
  title?: string;
}

export interface LocalCatalogAuthorBio {
  authorName: string;
  biography: string;
}

/**
 * What the library's local catalog holds for one book, read from the catalog rather than from what
 * enrichment already wrote into the book. `fieldsFromCatalog` is the other half of the answer: the
 * fields whose recorded provenance is the catalog, which is what separates "the catalog has this"
 * from "the catalog gave us this".
 */
export interface LocalCatalogBookView {
  available: boolean;
  sourceArchive?: string;
  sourceArchiveEntry?: string;
  title?: string;
  authors: string[];
  language?: string;
  description?: string;
  reviewCount: number;
  reviews: LocalCatalogReview[];
  containingCompilations: LocalCatalogCompilationRef[];
  compilationParts: LocalCatalogCompilationRef[];
  authorBios: LocalCatalogAuthorBio[];
  /** Backend `MetadataField` constant names, e.g. `DESCRIPTION`. */
  fieldsFromCatalog: string[];
}
