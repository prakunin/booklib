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

/**
 * Turns catalog compilation membership into a series: the omnibus becomes the series name and the
 * work's position in it becomes the number.
 * <p>
 * This is a deliberate modelling compromise. A constituent novel frequently belongs to a real series
 * of its own, and calling the anthology its series is only defensible because the enrichment write
 * policy for bulk runs is {@code AUTO_IF_EMPTY} — the name lands solely where no series exists. The
 * step never invents a name: if the omnibus is not in the library, there is nothing to call the
 * series and it contributes nothing.
 * <p>
 * A work belonging to several omnibuses at once is common — 45% of compilation part keys in the
 * shipped catalog repeat — and there is no confident way to pick a winner among them, so the step
 * contributes nothing rather than guess. The memberships themselves stay in the local catalog index
 * either way, for a future UI to offer as a choice.
 * <p>
 * <strong>The catalog's {@code part} is a 0-based index; BookLib series numbers are 1-based</strong>,
 * so the index is shifted by one on the way in. Measured against the shipped catalog
 * ({@code compilations.json}, 17,398 compilations, 78,907 part keys): every single compilation has a
 * minimum part of 0 — the histogram of minimum-part-per-compilation is {@code [(0, 17398)]}, and the
 * overall part histogram runs {@code 0→17398, 1→27001, 2→21044, 3→15090, …}. Writing the raw index
 * would have numbered the first constituent work of every omnibus "0" and every other work one lower
 * than its true position, under {@code AUTO_IF_EMPTY} where nothing later contradicts it.
 * <p>
 * Note that {@code FlibustaCompilationParser} defaults a missing {@code part} field to 0
 * ({@code part.path("part").asInt(0)}), so a malformed entry is treated as the first work of its
 * omnibus and lands on series number 1. That is the correct floor for a 1-based number: the previous
 * behaviour put such an entry at 0, which is not a valid series position at all.
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
        List<CompilationMembership> memberships = catalogSource.lookupContainingCompilations(
                context.getLibraryId(), context.getSourceArchive(), context.getSourceArchiveEntry());
        if (memberships.isEmpty()) {
            return;
        }
        if (memberships.size() > 1) {
            log.debug("Book {} belongs to {} omnibuses; leaving series unset rather than guessing one",
                    context.bookId(), memberships.size());
            return;
        }
        CompilationMembership found = memberships.get(0);
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
                        .seriesNumber((float) (found.part() + 1))
                        .build(),
                EnrichmentConfidence.HIGH);
        context.note("Local catalog placed this book in the compilation " + seriesName);
    }
}
