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
import org.booklore.service.metadata.smart.WorkIdentityResolver;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Asks the agent which work this file actually contains — the one step measured in minutes.
 * <p>
 * It runs only when nothing cheaper has produced an identifier, because that is the only question it
 * is here to answer. What it returns is recorded at LOW confidence and can never be written
 * automatically: an agent's claim is unverified by construction, and {@code EnrichmentResolver}
 * additionally strips every number from it.
 * <p>
 * The answer is stored against the work rather than the book, so the other editions of it in the
 * library get it for free.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class AgentIdentityStep implements EnrichmentStepHandler {

    private final WorkIdentityResolver workIdentityResolver;
    private final WorkIdentityService workIdentityService;
    private final AgentRateLimiter rateLimiter;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.AGENT_IDENTITY;
    }

    @Override
    public boolean supports(EnrichmentContext context) {
        if (!context.isStepAllowed(type()) || !workIdentityResolver.isAvailable()) {
            return false;
        }
        if (context.hasIdentifier()) {
            // Something cheaper already said which book this is; there is nothing left to ask.
            return false;
        }
        return workKey(context) != null;
    }

    @Override
    public void run(EnrichmentContext context) {
        String workKey = workKey(context);
        Optional<WorkIdentityEntity> resolved = workIdentityService.findOrResolve(workKey, () -> {
            if (!rateLimiter.tryAcquire()) {
                log.info("Agent rate limit reached, deferring book {}", context.bookId());
                context.note("Agent rate limit reached; identity not resolved this run");
                return Optional.empty();
            }
            return workIdentityResolver.resolve(context.getBook());
        });
        if (resolved.isEmpty()) {
            return;
        }
        WorkIdentityEntity identity = resolved.get();
        workIdentityService.link(context.bookId(), identity, EnrichmentConfidence.LOW);

        context.addContribution(MetadataProvider.Agent, BookMetadata.builder()
                .bookId(context.bookId())
                .description(identity.getDescription())
                .isbn13(identity.getIsbn13())
                .isbn10(identity.getIsbn10())
                .goodreadsId(identity.getGoodreadsId())
                .build(), EnrichmentConfidence.LOW);
        context.note("Agent identified the work behind this file");
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
