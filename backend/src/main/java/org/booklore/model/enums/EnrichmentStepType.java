package org.booklore.model.enums;

/**
 * The kinds of work the enrichment pipeline can do for a book, ordered by cost.
 * <p>
 * There is deliberately no step for re-reading the book file or the INPX row: both were read at
 * ingest and their result <em>is</em> the metadata already stored on the book, which the pipeline
 * takes as its baseline. Re-reading files when they change is what the existing
 * {@link TaskType#REFRESH_LIBRARY_METADATA} task is for.
 */
public enum EnrichmentStepType {

    /** Descriptions from a library's local catalog. Milliseconds, exact-key match. */
    LOCAL_CATALOG,

    /** The language the local catalog lists a book under. Local, exact-key, no network. */
    LOCAL_LANGUAGE,

    /** The omnibus a constituent work belongs to, mapped onto series name and number. */
    LOCAL_COMPILATION,

    /** Identity already resolved for this work, reused across its editions. */
    WORK_CACHE,

    /** The existing provider parsers — Amazon, Goodreads, Google, … Seconds, network-bound. */
    PROVIDERS,

    /** The agent CLI, asked only for identity. Minutes, and rate-limited. */
    AGENT_IDENTITY,

    /** Providers again, now that the agent supplied an id to look up. */
    PROVIDERS_RETRY,

    /** Reader reviews, from the local catalog and from providers. */
    REVIEWS,

    /** Author biographies, from the local catalog and from the author metadata service. */
    AUTHOR_BIO
}
