package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.EnrichmentStepHandler;
import org.booklore.service.enrichment.catalog.CatalogReview;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Collects reader reviews from the local catalog.
 * <p>
 * Reviews are not a metadata field and never go through the per-field priority table — they are
 * appended to the book's review list by the resolver, subject to the same lock as any other review
 * source.
 */
@Slf4j
@Component
@Order(60)
@RequiredArgsConstructor
public class LocalReviewsStep implements EnrichmentStepHandler {

    private final LocalCatalogSource catalogSource;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.REVIEWS;
    }

    @Override
    public boolean supports(EnrichmentContext context) {
        return context.isStepAllowed(type())
                && context.getSourceArchive() != null
                && context.getSourceArchiveEntry() != null
                && catalogSource.isAvailable(context.getLibraryId());
    }

    @Override
    public void run(EnrichmentContext context) {
        List<CatalogReview> reviews = catalogSource.lookupReviews(
                context.getLibraryId(), context.getSourceArchive(), context.getSourceArchiveEntry());
        if (reviews.isEmpty()) {
            return;
        }
        context.addCatalogReviews(reviews);
        context.note("Local catalog supplied " + reviews.size() + " review(s)");
    }
}
