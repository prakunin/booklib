package org.booklore.service.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookReview;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.service.enrichment.catalog.CatalogReview;
import org.booklore.service.metadata.MetadataRefreshService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what an enrichment run writes and what it merely suggests.
 * <p>
 * The mechanism is two merges over the same per-field priority table. The first sees only
 * high-confidence contributions and is what gets written; the second sees everything and is offered
 * for review. Reusing {@code MetadataMerger} twice rather than tracking which provider won each
 * field keeps one implementation of field priority in the codebase instead of two that can disagree.
 * <p>
 * A consequence worth stating: a low-confidence provider ranked above a high-confidence one still
 * loses the write. Rank decides preference among equals; confidence decides whether a value is
 * trustworthy enough to store without a human looking at it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnrichmentResolver {

    private final MetadataRefreshService metadataRefreshService;

    public EnrichmentOutcome resolve(EnrichmentContext context, MetadataRefreshOptions options) {
        Map<MetadataProvider, BookMetadata> all = sanitized(context);
        attachCatalogReviews(context, all);
        if (all.isEmpty()) {
            return outcome(context, null, null);
        }

        EnrichmentWritePolicy policy = context.getRequest().getWritePolicy();
        Map<MetadataProvider, BookMetadata> trusted = highConfidenceOnly(context, all);

        BookMetadata applied = null;
        if (policy.writesAnything() && !trusted.isEmpty()) {
            // AUTO_IF_EMPTY is implemented by the replace mode the merger already understands, so
            // "only fill blanks" behaves identically here and in a manual refresh.
            options.setReplaceMode(policy.replaceMode());
            applied = metadataRefreshService.buildFetchMetadata(
                    context.existingMetadata(), context.bookId(), options, trusted);
        }

        BookMetadata proposed = null;
        boolean everythingWasTrusted = trusted.size() == all.size();
        if (!policy.writesAnything() || !everythingWasTrusted) {
            // A proposal is reviewed as a whole, so it shows the complete resolved record rather
            // than only the fields that happened to be blank.
            options.setReplaceMode(MetadataReplaceMode.REPLACE_ALL);
            proposed = metadataRefreshService.buildFetchMetadata(
                    context.existingMetadata(), context.bookId(), options, all);
        }

        return outcome(context, applied, proposed);
    }

    /**
     * Removes every numeric field from the agent's contribution.
     * <p>
     * This is enforced here rather than by configuration because configuration can be changed: no
     * arrangement of the per-field priority table may let the agent supply a rating, a review count
     * or a page count. A parser can be checked against the page it scraped; an agent's number cannot
     * be checked against anything, and once stored it is indistinguishable from a measured one.
     */
    private Map<MetadataProvider, BookMetadata> sanitized(EnrichmentContext context) {
        Map<MetadataProvider, BookMetadata> sanitized = new LinkedHashMap<>(context.getContributions());
        BookMetadata agent = sanitized.get(MetadataProvider.Agent);
        if (agent == null) {
            return sanitized;
        }
        agent.setPageCount(null);
        agent.setRating(null);
        agent.setAmazonRating(null);
        agent.setAmazonReviewCount(null);
        agent.setGoodreadsRating(null);
        agent.setGoodreadsReviewCount(null);
        agent.setHardcoverRating(null);
        agent.setHardcoverReviewCount(null);
        agent.setDoubanRating(null);
        agent.setDoubanReviewCount(null);
        agent.setLubimyczytacRating(null);
        agent.setRanobedbRating(null);
        agent.setAudibleRating(null);
        agent.setAudibleReviewCount(null);
        return sanitized;
    }

    /**
     * Reviews ride along on the local catalog's contribution because the merger already collects
     * {@code bookReviews} from every entry in the map — giving them their own contribution would
     * overwrite the description that came from the same source.
     */
    private void attachCatalogReviews(EnrichmentContext context, Map<MetadataProvider, BookMetadata> contributions) {
        List<CatalogReview> catalogReviews = context.getCatalogReviews();
        if (catalogReviews.isEmpty()) {
            return;
        }
        BookMetadata local = contributions.computeIfAbsent(MetadataProvider.FlibustaLocal,
                provider -> BookMetadata.builder().bookId(context.bookId()).build());
        List<BookReview> reviews = new ArrayList<>();
        if (local.getBookReviews() != null) {
            reviews.addAll(local.getBookReviews());
        }
        catalogReviews.forEach(review -> reviews.add(BookReview.builder()
                .metadataProvider(MetadataProvider.FlibustaLocal)
                .reviewerName(review.reviewerName())
                .body(review.body())
                .date(review.postedAt())
                .build()));
        local.setBookReviews(reviews);
        context.getConfidences().putIfAbsent(MetadataProvider.FlibustaLocal, EnrichmentConfidence.HIGH);
    }

    private Map<MetadataProvider, BookMetadata> highConfidenceOnly(EnrichmentContext context,
                                                                   Map<MetadataProvider, BookMetadata> all) {
        Map<MetadataProvider, BookMetadata> trusted = new LinkedHashMap<>();
        all.forEach((provider, metadata) -> {
            if (context.getConfidences().get(provider) == EnrichmentConfidence.HIGH) {
                trusted.put(provider, metadata);
            }
        });
        return trusted;
    }

    private EnrichmentOutcome outcome(EnrichmentContext context, BookMetadata applied, BookMetadata proposed) {
        return EnrichmentOutcome.builder()
                .bookId(context.bookId())
                .applied(applied)
                .proposed(proposed)
                .stepsRun(Set.copyOf(context.getStepsRun()))
                .notes(List.copyOf(context.getNotes()))
                .build();
    }
}
