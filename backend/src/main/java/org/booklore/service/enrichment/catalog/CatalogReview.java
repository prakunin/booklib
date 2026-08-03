package org.booklore.service.enrichment.catalog;

import java.time.Instant;

/**
 * One reader review from a local catalog. The reviewer name is frequently blank in the source data,
 * which is why it is nullable rather than required.
 */
public record CatalogReview(String reviewerName, String body, Instant postedAt) {
}
