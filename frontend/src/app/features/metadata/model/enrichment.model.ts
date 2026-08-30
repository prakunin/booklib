/**
 * The steps the pipeline can run, cheapest first. The agent is the only one measured in minutes,
 * which is why it is gated behind its own flag rather than being just another checkbox.
 */
export type EnrichmentStepType =
  | 'LOCAL_CATALOG'
  | 'LOCAL_LANGUAGE'
  | 'LOCAL_COMPILATION'
  | 'WORK_CACHE'
  | 'PROVIDERS'
  | 'AGENT_IDENTITY'
  | 'PROVIDERS_RETRY'
  | 'REVIEWS'
  | 'AUTHOR_BIO';

/**
 * A ceiling on what a run may write. It can only ever restrict: a low-confidence result is a
 * suggestion under every policy.
 */
export type EnrichmentWritePolicy = 'AUTO' | 'AUTO_IF_EMPTY' | 'PROPOSE';

export type EnrichmentScope = 'BOOK' | 'BOOKS' | 'LIBRARY';

export interface EnrichmentRequest {
  scope: EnrichmentScope;
  libraryId?: number;
  bookIds?: number[];
  steps?: EnrichmentStepType[];
  writePolicy: EnrichmentWritePolicy;
  agentAllowed: boolean;
}

export interface EnrichmentJob {
  jobId: string;
}

export interface EnrichmentProgress {
  jobId: string;
  total: number;
  done: number;
  skipped: number;
  failed: number;
  cancelled: number;
  outstanding: number;
  finished: boolean;
}

/**
 * Pushed by the worker as it moves through a job. Sent to the user who asked, never broadcast.
 */
export interface EnrichmentProgressEvent {
  jobId: string;
  bookId: number;
  total: number;
  completed: number;
  outstanding: number;
  finished: boolean;
  bookChanged: boolean;
  notes: string[];
}

export type EnrichmentQueueStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'FAILED' | 'CANCELLED';

export type EnrichmentQueueOverview = Record<EnrichmentQueueStatus, number>;

/**
 * Steps offered in the dialog. AGENT_IDENTITY and PROVIDERS_RETRY are absent on purpose: they are
 * driven by the agent toggle, and offering them as independent checkboxes would let a user ask for
 * a retry with no identity to retry with.
 */
export const SELECTABLE_ENRICHMENT_STEPS: readonly EnrichmentStepType[] = [
  'LOCAL_CATALOG',
  'LOCAL_LANGUAGE',
  'LOCAL_COMPILATION',
  'WORK_CACHE',
  'PROVIDERS',
  'REVIEWS',
  'AUTHOR_BIO',
] as const;

/**
 * The steps that read only the library's local catalog: no network, no provider, no agent. Offered
 * as one preset because "apply what the catalog holds" is a single intent, and assembling it by
 * unticking five provider boxes every time is how a user ends up letting a provider overwrite the
 * very field they came to fix.
 */
export const LOCAL_CATALOG_ENRICHMENT_STEPS: readonly EnrichmentStepType[] = [
  'LOCAL_CATALOG',
  'LOCAL_LANGUAGE',
  'LOCAL_COMPILATION',
  'REVIEWS',
  'AUTHOR_BIO',
] as const;
