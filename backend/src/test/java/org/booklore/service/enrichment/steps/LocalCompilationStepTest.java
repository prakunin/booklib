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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
    void contributesTheOmnibusTitleAsSeriesAndThePartAsNumber() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupContainingCompilation(7L, "a.zip", "13023.fb2"))
                .thenReturn(Optional.of(new CompilationMembership("omnibus.zip", "13026.fb2", 3)));
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
        when(catalogSource.lookupContainingCompilation(7L, "a.zip", "13023.fb2"))
                .thenReturn(Optional.of(new CompilationMembership("omnibus.zip", "13026.fb2", 3)));
        when(bookFileRepository.findTitleBySourceArchiveEntry(7L, "omnibus.zip", "13026.fb2"))
                .thenReturn(List.of());
        EnrichmentContext context = context();

        step.run(context);

        assertThat(context.getContributions()).isEmpty();
    }

    @Test
    void contributesNothingWhenTheBookIsNotPartOfAnyCompilation() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupContainingCompilation(7L, "a.zip", "13023.fb2"))
                .thenReturn(Optional.empty());
        EnrichmentContext context = context();

        step.run(context);

        assertThat(context.getContributions()).isEmpty();
    }
}
