package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.WorkIdentityEntity;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.EnrichmentStepHandler;
import org.booklore.service.enrichment.work.WorkIdentityService;
import org.booklore.service.enrichment.work.WorkKeys;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reuses an identity already resolved for another edition of the same work.
 * <p>
 * This is what keeps a bulk run affordable: in an INPX library the same work recurs dozens of times,
 * and without this each copy would pay for its own agent call.
 * <p>
 * A hit is demoted one confidence step unless an identifier corroborates it. Normalization can
 * collapse genuinely different works — same author, same title, different contents, which is exactly
 * what compilations and reissues look like — so reuse alone is a reason to suggest, not to write.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class WorkCacheStep implements EnrichmentStepHandler {

    private final WorkIdentityService workIdentityService;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.WORK_CACHE;
    }

    @Override
    public boolean supports(EnrichmentContext context) {
        return context.isStepAllowed(type()) && workKey(context) != null;
    }

    @Override
    public void run(EnrichmentContext context) {
        Optional<WorkIdentityEntity> work = workIdentityService.find(workKey(context));
        if (work.isEmpty()) {
            return;
        }
        WorkIdentityEntity identity = work.get();
        EnrichmentConfidence confidence = corroborated(context, identity)
                ? identity.getConfidence()
                : identity.getConfidence().demoted();

        context.addContribution(MetadataProvider.Agent, BookMetadata.builder()
                .bookId(context.bookId())
                .description(identity.getDescription())
                .isbn13(identity.getIsbn13())
                .isbn10(identity.getIsbn10())
                .goodreadsId(identity.getGoodreadsId())
                .build(), confidence);
        context.note("Reused the identity already resolved for this work");
    }

    private boolean corroborated(EnrichmentContext context, WorkIdentityEntity identity) {
        BookMetadata existing = context.existingMetadata();
        if (existing == null) {
            return false;
        }
        return sameValue(existing.getIsbn13(), identity.getIsbn13())
                || sameValue(existing.getIsbn10(), identity.getIsbn10())
                || sameValue(existing.getGoodreadsId(), identity.getGoodreadsId());
    }

    private boolean sameValue(String left, String right) {
        return left != null && !left.isBlank() && left.equalsIgnoreCase(right);
    }

    private String workKey(EnrichmentContext context) {
        BookMetadata metadata = context.existingMetadata();
        if (metadata == null) {
            return null;
        }
        String author = metadata.getAuthors() == null || metadata.getAuthors().isEmpty()
                ? null
                : metadata.getAuthors().getFirst();
        return WorkKeys.of(author, metadata.getTitle());
    }
}
