package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.EnrichmentStepHandler;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Collects author biographies from the local catalog, one per author of the book.
 */
@Slf4j
@Component
@Order(70)
@RequiredArgsConstructor
public class LocalAuthorBioStep implements EnrichmentStepHandler {

    private final LocalCatalogSource catalogSource;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.AUTHOR_BIO;
    }

    @Override
    public boolean supports(EnrichmentContext context) {
        return context.isStepAllowed(type())
                && !authorsOf(context).isEmpty()
                && catalogSource.isAvailable(context.getLibraryId());
    }

    @Override
    public void run(EnrichmentContext context) {
        for (String author : authorsOf(context)) {
            catalogSource.lookupAuthorBio(context.getLibraryId(), author)
                    .ifPresent(bio -> context.addAuthorBio(author, bio));
        }
        if (!context.getAuthorBios().isEmpty()) {
            context.note("Local catalog supplied " + context.getAuthorBios().size() + " author biography(ies)");
        }
    }

    private List<String> authorsOf(EnrichmentContext context) {
        BookMetadata existing = context.existingMetadata();
        if (existing != null && !Boolean.TRUE.equals(existing.getAuthorsLocked())) {
            BookMetadata local = context.getContributions().get(MetadataProvider.FlibustaLocal);
            if (local != null && local.getAuthors() != null && !local.getAuthors().isEmpty()) {
                return local.getAuthors();
            }
        }
        if (existing == null || existing.getAuthors() == null) {
            return List.of();
        }
        return existing.getAuthors().stream()
                .filter(author -> author != null && !author.isBlank())
                .toList();
    }
}
