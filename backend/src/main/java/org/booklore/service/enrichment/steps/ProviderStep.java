package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.MetadataRefreshOptions;
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
 * Runs the existing provider parsers and records what each one returned.
 * <p>
 * Reuses {@link MetadataRefreshService} rather than re-implementing the fetch: which providers are
 * enabled, how each is configured and how a book is turned into a query already live there, and a
 * second copy of that logic would drift.
 * <p>
 * Confidence is decided per provider by whether the result is corroborated by an identifier. A
 * result carrying the same ISBN, ASIN or Goodreads id the book already had is about this book; a
 * result matched on title and author alone is probably about this book, which is a materially
 * different claim and must not be written without review.
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class ProviderStep implements EnrichmentStepHandler {

    private final MetadataRefreshService metadataRefreshService;
    private final AppSettingService appSettingService;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.PROVIDERS;
    }

    @Override
    public boolean supports(EnrichmentContext context) {
        return context.isStepAllowed(type()) && !providers(context).isEmpty();
    }

    @Override
    public void run(EnrichmentContext context) {
        List<MetadataProvider> providers = providers(context);
        Map<MetadataProvider, BookMetadata> fetched;
        try {
            fetched = metadataRefreshService.fetchMetadataForBook(providers, context.getBook());
        } catch (Exception e) {
            // Scrapers fail for reasons outside our control — a layout change, a rate limit, a
            // network blip. None of them is a reason to abandon what the cheaper steps found.
            log.warn("Provider fetch failed for book {}: {}", context.bookId(), e.getMessage());
            context.note("Providers failed: " + e.getMessage());
            return;
        }
        fetched.forEach((provider, metadata) -> {
            if (metadata == null) {
                return;
            }
            context.addContribution(provider, metadata, confidenceOf(context, metadata));
        });
    }

    private EnrichmentConfidence confidenceOf(EnrichmentContext context, BookMetadata fetched) {
        BookMetadata existing = context.existingMetadata();
        if (existing == null) {
            return EnrichmentConfidence.MEDIUM;
        }
        boolean identifierAgrees =
                sameValue(existing.getIsbn13(), fetched.getIsbn13())
                        || sameValue(existing.getIsbn10(), fetched.getIsbn10())
                        || sameValue(existing.getAsin(), fetched.getAsin())
                        || sameValue(existing.getGoodreadsId(), fetched.getGoodreadsId());
        return identifierAgrees ? EnrichmentConfidence.HIGH : EnrichmentConfidence.MEDIUM;
    }

    private boolean sameValue(String existing, String fetched) {
        return existing != null && !existing.isBlank() && existing.equalsIgnoreCase(fetched);
    }

    private List<MetadataProvider> providers(EnrichmentContext context) {
        return metadataRefreshService.prepareProviders(refreshOptions(context));
    }

    MetadataRefreshOptions refreshOptions(EnrichmentContext context) {
        return metadataRefreshService.resolveMetadataRefreshOptions(
                context.getLibraryId(), appSettingService.getAppSettings());
    }
}
