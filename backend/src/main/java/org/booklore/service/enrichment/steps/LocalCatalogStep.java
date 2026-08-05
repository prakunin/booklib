package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.EnrichmentStepHandler;
import org.booklore.service.enrichment.catalog.CatalogBookMetadata;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads the description a library's local catalog holds for this exact file.
 * <p>
 * First and cheapest: no network, and the match is by the archive and entry the book already
 * records rather than by a title guess, so a hit is as certain as metadata gets — hence HIGH
 * confidence.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class LocalCatalogStep implements EnrichmentStepHandler {

    private final LocalCatalogSource catalogSource;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.LOCAL_CATALOG;
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
        Optional<CatalogBookMetadata> catalogMetadata = catalogSource.lookupBookMetadata(
                context.getLibraryId(), context.getSourceArchive(), context.getSourceArchiveEntry());
        Optional<String> description = catalogSource.lookupDescription(
                context.getLibraryId(), context.getSourceArchive(), context.getSourceArchiveEntry());
        CatalogBookMetadata identity = catalogMetadata.orElse(null);
        boolean hasIdentity = identity != null
                && (identity.title() != null || !identity.authors().isEmpty());
        boolean hasContentsMetadata = hasIdentity || identity != null && identity.language() != null;
        if (!hasContentsMetadata && description.isEmpty()) {
            return;
        }
        context.addContribution(
                MetadataProvider.FlibustaLocal,
                BookMetadata.builder()
                        .bookId(context.bookId())
                        .title(identity == null ? null : identity.title())
                        .authors(identity == null ? null : identity.authors())
                        .language(identity == null ? null : identity.language())
                        .description(description.orElse(null))
                        .build(),
                EnrichmentConfidence.HIGH);
        context.note(hasIdentity
                ? "Local catalog supplied exact book identity"
                : hasContentsMetadata
                        ? "Local catalog supplied exact book contents metadata"
                        : "Local catalog supplied a description");
    }
}
