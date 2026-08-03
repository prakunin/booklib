package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.EnrichmentStepHandler;
import org.booklore.service.enrichment.catalog.CompilationMembership;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Turns catalog compilation membership into a series: the omnibus becomes the series name and the
 * work's position in it becomes the number.
 * <p>
 * This is a deliberate modelling compromise. A constituent novel frequently belongs to a real series
 * of its own, and calling the anthology its series is only defensible because the enrichment write
 * policy for bulk runs is {@code AUTO_IF_EMPTY} — the name lands solely where no series exists. The
 * step never invents a name: if the omnibus is not in the library, there is nothing to call the
 * series and it contributes nothing.
 */
@Slf4j
@Component
@Order(12)
@RequiredArgsConstructor
public class LocalCompilationStep implements EnrichmentStepHandler {

    private final LocalCatalogSource catalogSource;
    private final BookFileRepository bookFileRepository;

    @Override
    public EnrichmentStepType type() {
        return EnrichmentStepType.LOCAL_COMPILATION;
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
        Optional<CompilationMembership> membership = catalogSource.lookupContainingCompilation(
                context.getLibraryId(), context.getSourceArchive(), context.getSourceArchiveEntry());
        if (membership.isEmpty()) {
            return;
        }
        CompilationMembership found = membership.get();
        List<String> titles = bookFileRepository.findTitleBySourceArchiveEntry(
                context.getLibraryId(), found.compilationArchive(), found.compilationEntry());
        String seriesName = titles.stream()
                .filter(title -> title != null && !title.isBlank())
                .findFirst()
                .orElse(null);
        if (seriesName == null) {
            return;
        }
        context.addContribution(
                MetadataProvider.FlibustaLocal,
                BookMetadata.builder()
                        .bookId(context.bookId())
                        .seriesName(seriesName)
                        .seriesNumber((float) found.part())
                        .build(),
                EnrichmentConfidence.HIGH);
        context.note("Local catalog placed this book in the compilation " + seriesName);
    }
}
