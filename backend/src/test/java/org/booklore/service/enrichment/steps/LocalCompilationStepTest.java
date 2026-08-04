package org.booklore.service.enrichment.steps;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.catalog.CompilationMembership;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalCompilationStepTest {

    private final LocalCatalogSource catalogSource = mock(LocalCatalogSource.class);
    private final BookFileRepository bookFileRepository = mock(BookFileRepository.class);
    private final LocalCompilationStep step = new LocalCompilationStep(catalogSource, bookFileRepository);

    private EnrichmentContext context() {
        Book book = Book.builder().id(42L).build();
        return new EnrichmentContext(book, 7L, "a.zip", "13023.fb2",
                EnrichmentRequest.builder()
                        .steps(EnumSet.of(EnrichmentStepType.LOCAL_COMPILATION))
                        .build());
    }

    @Test
    void contributesTheOmnibusTitleAsSeriesAndThePartAsNumberWhenThereIsExactlyOne() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupContainingCompilations(7L, "a.zip", "13023.fb2"))
                .thenReturn(List.of(new CompilationMembership("omnibus.zip", "13026.fb2", 3)));
        when(bookFileRepository.findTitleBySourceArchiveEntry(7L, "omnibus.zip", "13026.fb2"))
                .thenReturn(List.of("Антология фантастики"));
        EnrichmentContext context = context();

        step.run(context);

        var contribution = context.getContributions().get(MetadataProvider.FlibustaLocal);
        assertThat(contribution.getSeriesName()).isEqualTo("Антология фантастики");
        assertThat(contribution.getSeriesNumber()).isEqualTo(3f);
    }

    @Test
    void contributesNothingWhenTheOmnibusIsNotInTheLibrary() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupContainingCompilations(7L, "a.zip", "13023.fb2"))
                .thenReturn(List.of(new CompilationMembership("omnibus.zip", "13026.fb2", 3)));
        when(bookFileRepository.findTitleBySourceArchiveEntry(7L, "omnibus.zip", "13026.fb2"))
                .thenReturn(List.of());
        EnrichmentContext context = context();

        step.run(context);

        assertThat(context.getContributions()).isEmpty();
    }

    @Test
    void contributesNothingWhenTheBookIsNotPartOfAnyCompilation() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupContainingCompilations(7L, "a.zip", "13023.fb2"))
                .thenReturn(List.of());
        EnrichmentContext context = context();

        step.run(context);

        assertThat(context.getContributions()).isEmpty();
    }

    /**
     * The product rule (2026-08-04): when a work belongs to more than one omnibus, the step must not
     * guess a winner and must contribute no series at all. Three distinct omnibus names are used, not
     * two, so a fix that only special-cases "exactly two" would still fail this.
     */
    @Test
    void contributesNothingWhenTheBookBelongsToSeveralOmnibuses() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupContainingCompilations(7L, "a.zip", "13023.fb2"))
                .thenReturn(List.of(
                        new CompilationMembership("anthology-alpha.zip", "201.fb2", 4),
                        new CompilationMembership("anthology-beta.zip", "202.fb2", 9),
                        new CompilationMembership("anthology-gamma.zip", "203.fb2", 2)));
        EnrichmentContext context = context();

        step.run(context);

        assertThat(context.getContributions()).isEmpty();
        verify(bookFileRepository, never()).findTitleBySourceArchiveEntry(anyLong(), any(), any());
    }
}
