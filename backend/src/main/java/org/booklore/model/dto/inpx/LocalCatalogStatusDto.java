package org.booklore.model.dto.inpx;

import org.booklore.model.enums.LocalCatalogSourceType;

import java.util.Map;

/**
 * What a local catalog attached to a library has indexed, and how much of the library's metadata it
 * has actually filled.
 * <p>
 * Deliberately no per-field provenance: {@code BookMetadataEntity} carries no column recording which
 * provider wrote a given field, only the {@code *_locked} booleans, so a claim like "this description
 * came from the local catalog" cannot be made honestly without a schema change. This reports only
 * recorded aggregates — what the catalog has indexed, and coverage counts — never an inference from
 * the presence of an index row.
 *
 * @param configured whether the library has a metadata sidecar path configured at all
 * @param catalogPath the configured sidecar path, or {@code null} when unset
 * @param indexedEntries one entry per user-visible catalog source type, always including zeros;
 *                        internal index-version markers are not exposed
 * @param totalBooks non-deleted books in this library
 * @param booksWithDescription non-deleted books in this library whose metadata description is
 *                              non-null and non-blank; the coverage number that climbs as the
 *                              backfill runs
 * @param localReviews {@code BookReviewEntity} rows sourced from the local catalog
 *                      ({@code metadataProvider = FlibustaLocal}) belonging to books in this library
 * @param authorsWithBiography authors with a non-blank description. Authors are not library-scoped in
 *                              this schema, so this count is global, not specific to the library named
 *                              in the request path
 */
public record LocalCatalogStatusDto(
        boolean configured,
        String catalogPath,
        Map<LocalCatalogSourceType, Long> indexedEntries,
        long totalBooks,
        long booksWithDescription,
        long localReviews,
        long authorsWithBiography) {
}
