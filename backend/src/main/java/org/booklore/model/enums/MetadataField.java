package org.booklore.model.enums;

/**
 * Names the individual metadata fields whose provenance is recorded in {@code book_metadata_field_source}.
 * <p>
 * A constant exists only where both halves of the write path can speak about the field: the merger
 * resolves it from a provider chain, and {@code BookMetadataUpdater.handleFieldUpdate} writes it as a
 * single scalar. That excludes the collection-valued fields (authors, categories, moods, tags) and the
 * cover, which are written by their own methods and have no single provider to attribute, and the
 * comic, audiobook and rating fields that no provider chain resolves.
 * <p>
 * This is a plain identity enum on purpose: its constants are persisted as strings, so renaming one
 * orphans every row that already carries the old name.
 */
public enum MetadataField {
    TITLE,
    SUBTITLE,
    DESCRIPTION,
    PUBLISHER,
    PUBLISHED_DATE,
    SERIES_NAME,
    SERIES_NUMBER,
    SERIES_TOTAL,
    ISBN_13,
    ISBN_10,
    LANGUAGE,
    PAGE_COUNT,
    ASIN,
    GOODREADS_ID,
    COMICVINE_ID,
    HARDCOVER_ID,
    HARDCOVER_BOOK_ID,
    GOOGLE_ID,
    LUBIMYCZYTAC_ID,
    RANOBEDB_ID,
    AUDIBLE_ID,
    AMAZON_RATING,
    AMAZON_REVIEW_COUNT,
    GOODREADS_RATING,
    GOODREADS_REVIEW_COUNT,
    HARDCOVER_RATING,
    HARDCOVER_REVIEW_COUNT,
    LUBIMYCZYTAC_RATING,
    RANOBEDB_RATING,
    AUDIBLE_RATING,
    AUDIBLE_REVIEW_COUNT
}
