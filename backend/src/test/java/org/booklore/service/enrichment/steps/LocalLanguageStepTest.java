package org.booklore.service.enrichment.steps;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.EnrichmentContext;
import org.booklore.service.enrichment.catalog.LocalCatalogSource;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalLanguageStepTest {

    private final LocalCatalogSource catalogSource = mock(LocalCatalogSource.class);
    private final LocalLanguageStep step = new LocalLanguageStep(catalogSource);

    private EnrichmentContext context(String archive, String entry) {
        Book book = Book.builder().id(42L).build();
        return new EnrichmentContext(book, 7L, archive, entry,
                EnrichmentRequest.builder()
                        .steps(EnumSet.of(EnrichmentStepType.LOCAL_LANGUAGE))
                        .build());
    }

    @Test
    void contributesTheCatalogLanguage() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupLanguage(7L, "a.zip", "1.fb2")).thenReturn(Optional.of("ru"));
        EnrichmentContext context = context("a.zip", "1.fb2");

        step.run(context);

        assertThat(context.getContributions()).containsKey(MetadataProvider.FlibustaLocal);
        assertThat(context.getContributions().get(MetadataProvider.FlibustaLocal).getLanguage())
                .isEqualTo("ru");
    }

    @Test
    void contributesNothingWhenTheCatalogHasNoLanguage() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);
        when(catalogSource.lookupLanguage(7L, "a.zip", "1.fb2")).thenReturn(Optional.empty());
        EnrichmentContext context = context("a.zip", "1.fb2");

        step.run(context);

        assertThat(context.getContributions()).isEmpty();
    }

    @Test
    void isUnsupportedWithoutAnArchiveKey() {
        when(catalogSource.isAvailable(7L)).thenReturn(true);

        assertThat(step.supports(context(null, null))).isFalse();
        assertThat(step.supports(context("a.zip", "1.fb2"))).isTrue();
    }
}
