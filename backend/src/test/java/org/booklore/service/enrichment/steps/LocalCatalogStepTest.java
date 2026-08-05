package org.booklore.service.enrichment.steps;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.catalog.CatalogBookMetadata;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalCatalogStepTest {

    private final LocalCatalogSource catalogSource = mock(LocalCatalogSource.class);
    private final LocalCatalogStep step = new LocalCatalogStep(catalogSource);

    @Test
    void contributesExactIdentityAndDescriptionAtHighConfidence() {
        EnrichmentContext context = context();
        when(catalogSource.lookupBookMetadata(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.of(new CatalogBookMetadata(
                        "Correct title", List.of("Correct Author"), "ru")));
        when(catalogSource.lookupDescription(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.of("Catalog description"));

        step.run(context);

        BookMetadata contribution = context.getContributions().get(MetadataProvider.FlibustaLocal);
        assertThat(contribution.getTitle()).isEqualTo("Correct title");
        assertThat(contribution.getAuthors()).containsExactly("Correct Author");
        assertThat(contribution.getLanguage()).isEqualTo("ru");
        assertThat(contribution.getDescription()).isEqualTo("Catalog description");
        assertThat(context.getConfidences().get(MetadataProvider.FlibustaLocal))
                .isEqualTo(EnrichmentConfidence.HIGH);
    }

    @Test
    void keepsDescriptionEnrichmentWhenOnlyALegacyPayloadExists() {
        EnrichmentContext context = context();
        when(catalogSource.lookupBookMetadata(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.of(new CatalogBookMetadata(null, List.of(), "ru")));
        when(catalogSource.lookupDescription(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.of("Catalog description"));

        step.run(context);

        BookMetadata contribution = context.getContributions().get(MetadataProvider.FlibustaLocal);
        assertThat(contribution.getTitle()).isNull();
        assertThat(contribution.getAuthors()).isEmpty();
        assertThat(contribution.getDescription()).isEqualTo("Catalog description");
    }

    @Test
    void keepsLanguageEnrichmentWhenIdentityAndDescriptionAreAbsent() {
        EnrichmentContext context = context();
        when(catalogSource.lookupBookMetadata(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.of(new CatalogBookMetadata(null, List.of(), "ru")));
        when(catalogSource.lookupDescription(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.empty());

        step.run(context);

        BookMetadata contribution = context.getContributions().get(MetadataProvider.FlibustaLocal);
        assertThat(contribution.getLanguage()).isEqualTo("ru");
        assertThat(contribution.getTitle()).isNull();
        assertThat(contribution.getAuthors()).isEmpty();
    }

    @Test
    void contributesNothingWhenNeitherCatalogSourceHasData() {
        EnrichmentContext context = context();
        when(catalogSource.lookupBookMetadata(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.empty());
        when(catalogSource.lookupDescription(7L, "books.zip", "1.fb2"))
                .thenReturn(Optional.empty());

        step.run(context);

        assertThat(context.getContributions()).isEmpty();
    }

    private EnrichmentContext context() {
        return new EnrichmentContext(
                Book.builder().id(42L).build(), 7L, "books.zip", "1.fb2",
                EnrichmentRequest.builder()
                        .steps(EnumSet.of(EnrichmentStepType.LOCAL_CATALOG))
                        .build());
    }
}
