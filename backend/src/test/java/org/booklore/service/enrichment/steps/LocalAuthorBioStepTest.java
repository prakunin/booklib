package org.booklore.service.enrichment.steps;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAuthorBioStepTest {

    private final LocalCatalogSource catalogSource = mock(LocalCatalogSource.class);
    private final LocalAuthorBioStep step = new LocalAuthorBioStep(catalogSource);

    @Test
    void usesExactCatalogAuthorsWhenUnlockedIdentityIsBeingCorrected() {
        EnrichmentContext context = context(false);
        context.addContribution(MetadataProvider.FlibustaLocal,
                BookMetadata.builder().authors(List.of("Correct Author")).build(),
                EnrichmentConfidence.HIGH);
        when(catalogSource.isAvailable(7L)).thenReturn(true);

        if (step.supports(context)) {
            step.run(context);
        }

        verify(catalogSource).lookupAuthorBio(7L, "Correct Author");
        verify(catalogSource, never()).lookupAuthorBio(7L, "Wrong Author");
    }

    @Test
    void keepsStoredAuthorsForAnAuthorsLockedBook() {
        EnrichmentContext context = context(true);
        context.addContribution(MetadataProvider.FlibustaLocal,
                BookMetadata.builder().authors(List.of("Catalog Author")).build(),
                EnrichmentConfidence.HIGH);
        when(catalogSource.isAvailable(7L)).thenReturn(true);

        if (step.supports(context)) {
            step.run(context);
        }

        verify(catalogSource).lookupAuthorBio(7L, "Wrong Author");
        verify(catalogSource, never()).lookupAuthorBio(7L, "Catalog Author");
    }

    private EnrichmentContext context(boolean authorsLocked) {
        BookMetadata metadata = BookMetadata.builder()
                .authors(List.of("Wrong Author"))
                .authorsLocked(authorsLocked)
                .build();
        return new EnrichmentContext(
                Book.builder().id(11L).metadata(metadata).build(),
                7L,
                "books.zip",
                "1.fb2",
                EnrichmentRequest.builder()
                        .scope(EnrichmentRequest.Scope.BOOK)
                        .steps(Set.of(EnrichmentStepType.AUTHOR_BIO))
                        .build());
    }
}
