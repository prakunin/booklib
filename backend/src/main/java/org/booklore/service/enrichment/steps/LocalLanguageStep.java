package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.EnrichmentStepHandler;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads the language the local catalog lists this exact file under.
 * <p>
 * Same cost and certainty as {@link LocalCatalogStep}: no network, and the match is on the archive
 * and entry the book already records rather than on a title guess.
 */
@Slf4j
@Component
@Order(11)
@RequiredArgsConstructor
public class LocalLanguageStep implements EnrichmentStepHandler {

    private final LocalCatalogSource catalogSource;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.LOCAL_LANGUAGE;
    }

    @Override
    public boolean supports(EnrichmentContext context) {
        return context.isStepAllowed(type())
                // LOCAL_CATALOG reads the same contents-index row and contributes its language too.
                // Skip the duplicate query when both standard local steps are selected.
                && !context.isStepAllowed(EnrichmentStepType.LOCAL_CATALOG)
                && context.getSourceArchive() != null
                && context.getSourceArchiveEntry() != null
                && catalogSource.isAvailable(context.getLibraryId());
    }

    @Override
    public void run(EnrichmentContext context) {
        Optional<String> language = catalogSource.lookupLanguage(
                context.getLibraryId(), context.getSourceArchive(), context.getSourceArchiveEntry());
        if (language.isEmpty()) {
            return;
        }
        context.addContribution(
                MetadataProvider.FlibustaLocal,
                BookMetadata.builder()
                        .bookId(context.bookId())
                        .language(language.get())
                        .build(),
                EnrichmentConfidence.HIGH);
        context.note("Local catalog supplied the language");
    }
}
