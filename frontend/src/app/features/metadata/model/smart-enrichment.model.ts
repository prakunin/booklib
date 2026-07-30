export type SmartEnrichmentStage = 'RESOLVING' | 'VERIFYING' | 'COMPLETED' | 'FAILED';

/**
 * What the agent claims about the work behind the file. None of it is verified — it is shown so the
 * user can judge whether the match is right before applying anything.
 */
export interface ResolvedWorkIdentity {
  originalTitle: string | null;
  originalAuthor: string | null;
  originalLanguage: string | null;
  editionTitle: string | null;
  editionAuthor: string | null;
  editionLanguage: string | null;
  firstPublishedYear: number | null;
  goodreadsUrl: string | null;
  reportedRating: number | null;
  description: string | null;
  descriptionLanguage: string | null;
  descriptionSourceUrl: string | null;
  publisher: string | null;
  publishedDate: string | null;
  isbn13: string | null;
  isbn10: string | null;
  pageCount: number | null;
  seriesName: string | null;
  seriesNumber: number | null;
  seriesTotal: number | null;
  genres: string[] | null;
  sources: string[] | null;
}

/**
 * `reported` comes from the agent, `verified` from the Goodreads parser fetching the same id.
 * Only `verified` is ever proposed; the pair is shown so a drifting agent is visible.
 */
export interface RatingVerification {
  reported: number | null;
  verified: number | null;
  agrees: boolean;
}

export interface MetadataFieldProposal {
  field: string;
  currentValue: string | null;
  proposedValue: string;
  source: string;
  sourceUrl: string | null;
  locked: boolean;
}

/**
 * What the dialog does with the fields the user ticked: write them straight to the book, or hand
 * them back so an open editor can fill its form and let the user review before saving.
 */
export type SmartEnrichmentApplyMode = 'save' | 'form';

export interface SmartEnrichmentDialogResult {
  proposals: MetadataFieldProposal[];
}

export interface SmartEnrichmentEvent {
  stage: SmartEnrichmentStage;
  message: string | null;
  identity: ResolvedWorkIdentity | null;
  ratingVerification: RatingVerification | null;
  proposals: MetadataFieldProposal[];
}
