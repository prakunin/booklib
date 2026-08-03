package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.EnrichmentStepHandler;
import org.booklore.service.metadata.MetadataRefreshService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Runs the providers a second time, now that the agent has supplied an identifier to look up.
 * <p>
 * This is the whole point of paying for an agent call: a Russian translation with a digitiser's
 * invented title cannot be found by title search, but its Goodreads id can be fetched directly once
 * something works out what the book is.
 * <p>
 * The results are recorded at MEDIUM, never HIGH, and that is not conservatism for its own sake. The
 * provider confirms what sits behind the identifier; it says nothing about whether this file is that
 * book, which rests entirely on the agent's unverified claim. So an agent-driven resolution always
 * ends up as a suggestion.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class ProvidersRetryStep implements EnrichmentStepHandler {

    private final MetadataRefreshService metadataRefreshService;
    private final AppSettingService appSettingService;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.PROVIDERS_RETRY;
    }

    @Override
    public boolean supports(EnrichmentContext context) {
        return context.isStepAllowed(type()) && discoveredIdentifiers(context) != null;
    }

    @Override
    public void run(EnrichmentContext context) {
        BookMetadata discovered = discoveredIdentifiers(context);
        if (discovered == null) {
            return;
        }
        List<MetadataProvider> providers = metadataRefreshService.prepareProviders(
                metadataRefreshService.resolveMetadataRefreshOptions(
                        context.getLibraryId(), appSettingService.getAppSettings()));
        if (providers.isEmpty()) {
            return;
        }
        Map<MetadataProvider, BookMetadata> fetched;
        try {
            fetched = metadataRefreshService.fetchMetadataForBook(providers, lookupBook(context, discovered));
        } catch (Exception e) {
            log.warn("Provider retry failed for book {}: {}", context.bookId(), e.getMessage());
            context.note("Provider retry failed: " + e.getMessage());
            return;
        }
        fetched.forEach((provider, metadata) -> {
            if (metadata != null) {
                context.addContribution(provider, metadata, EnrichmentConfidence.MEDIUM);
            }
        });
        context.note("Re-queried providers using the identifier the agent resolved");
    }

    /**
     * @return identifiers the agent produced that the book did not already have, or null when the
     * agent added nothing new to look up
     */
    private BookMetadata discoveredIdentifiers(EnrichmentContext context) {
        BookMetadata agent = context.getContributions().get(MetadataProvider.Agent);
        if (agent == null) {
            return null;
        }
        BookMetadata existing = context.existingMetadata();
        String isbn13 = newValue(existing == null ? null : existing.getIsbn13(), agent.getIsbn13());
        String isbn10 = newValue(existing == null ? null : existing.getIsbn10(), agent.getIsbn10());
        String goodreadsId = newValue(existing == null ? null : existing.getGoodreadsId(), agent.getGoodreadsId());
        if (isbn13 == null && isbn10 == null && goodreadsId == null) {
            return null;
        }
        return BookMetadata.builder()
                .isbn13(isbn13)
                .isbn10(isbn10)
                .goodreadsId(goodreadsId)
                .build();
    }

    private String newValue(String existing, String discovered) {
        if (discovered == null || discovered.isBlank()) {
            return null;
        }
        return discovered.equalsIgnoreCase(existing) ? null : discovered;
    }

    /**
     * The parsers read identifiers off the book they are handed, so the retry needs a copy carrying
     * the resolved ones — never the stored book, which a lookup must not mutate.
     */
    private Book lookupBook(EnrichmentContext context, BookMetadata discovered) {
        BookMetadata existing = context.existingMetadata();
        BookMetadata lookup = BookMetadata.builder()
                .bookId(context.bookId())
                .title(existing == null ? context.getBook().getTitle() : existing.getTitle())
                .authors(existing == null ? null : existing.getAuthors())
                .isbn13(discovered.getIsbn13())
                .isbn10(discovered.getIsbn10())
                .goodreadsId(discovered.getGoodreadsId())
                .build();
        return Book.builder()
                .id(context.bookId())
                .title(context.getBook().getTitle())
                .metadata(lookup)
                .build();
    }
}
