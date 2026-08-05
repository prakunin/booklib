package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataMergerTest {

    private final MetadataMerger merger = new MetadataMerger();

    private static MetadataRefreshOptions.FieldProvider chain(MetadataProvider... providers) {
        MetadataRefreshOptions.FieldProvider.FieldProviderBuilder builder = MetadataRefreshOptions.FieldProvider.builder();
        if (providers.length > 0) builder.p1(providers[0]);
        if (providers.length > 1) builder.p2(providers[1]);
        if (providers.length > 2) builder.p3(providers[2]);
        if (providers.length > 3) builder.p4(providers[3]);
        return builder.build();
    }

    private static MetadataRefreshOptions optionsFor(MetadataRefreshOptions.FieldOptions fieldOptions) {
        return MetadataRefreshOptions.builder()
                .fieldOptions(fieldOptions)
                .replaceMode(MetadataReplaceMode.REPLACE_ALL)
                .build();
    }

    private static Map<MetadataProvider, BookMetadata> contributions(Object... providerAndMetadata) {
        Map<MetadataProvider, BookMetadata> map = new LinkedHashMap<>();
        for (int i = 0; i < providerAndMetadata.length; i += 2) {
            map.put((MetadataProvider) providerAndMetadata[i], (BookMetadata) providerAndMetadata[i + 1]);
        }
        return map;
    }

    @Nested
    class CapturesTheWinningProvider {

        @Test
        void namesTheProviderThatSuppliedEachResolvedField() {
            MetadataRefreshOptions options = optionsFor(MetadataRefreshOptions.FieldOptions.builder()
                    .title(chain(MetadataProvider.GoodReads, MetadataProvider.FlibustaLocal))
                    .description(chain(MetadataProvider.GoodReads, MetadataProvider.FlibustaLocal))
                    .build());

            BookMetadata merged = merger.buildFetchMetadata(null, 7L, options, contributions(
                    MetadataProvider.GoodReads, BookMetadata.builder().title("Scraped Title").build(),
                    MetadataProvider.FlibustaLocal, BookMetadata.builder().description("Catalog blurb").build()));

            assertThat(merged.getFieldProviders())
                    .containsEntry(MetadataField.TITLE, MetadataProvider.GoodReads)
                    .containsEntry(MetadataField.DESCRIPTION, MetadataProvider.FlibustaLocal);
        }

        @Test
        void namesTheFirstProviderInTheChainThatHadAValue() {
            MetadataRefreshOptions options = optionsFor(MetadataRefreshOptions.FieldOptions.builder()
                    .description(chain(MetadataProvider.Amazon, MetadataProvider.GoodReads, MetadataProvider.FlibustaLocal))
                    .build());

            BookMetadata merged = merger.buildFetchMetadata(null, 7L, options, contributions(
                    MetadataProvider.Amazon, BookMetadata.builder().build(),
                    MetadataProvider.GoodReads, BookMetadata.builder().description("Second in line").build(),
                    MetadataProvider.FlibustaLocal, BookMetadata.builder().description("Third in line").build()));

            assertThat(merged.getDescription()).isEqualTo("Second in line");
            assertThat(merged.getFieldProviders()).containsEntry(MetadataField.DESCRIPTION, MetadataProvider.GoodReads);
        }

        @Test
        void attributesFieldsBoundToOneProviderToThatProvider() {
            MetadataRefreshOptions options = optionsFor(MetadataRefreshOptions.FieldOptions.builder().build());

            BookMetadata merged = merger.buildFetchMetadata(null, 7L, options, contributions(
                    MetadataProvider.Hardcover, BookMetadata.builder()
                            .hardcoverId("hc-1")
                            .hardcoverBookId("hcb-1")
                            .hardcoverRating(4.5)
                            .build()));

            assertThat(merged.getFieldProviders())
                    .containsEntry(MetadataField.HARDCOVER_ID, MetadataProvider.Hardcover)
                    .containsEntry(MetadataField.HARDCOVER_BOOK_ID, MetadataProvider.Hardcover)
                    .containsEntry(MetadataField.HARDCOVER_RATING, MetadataProvider.Hardcover);
        }

        @Test
        void attributesNothingForAFieldNoProviderSupplied() {
            MetadataRefreshOptions options = optionsFor(MetadataRefreshOptions.FieldOptions.builder()
                    .description(chain(MetadataProvider.GoodReads))
                    .build());

            BookMetadata merged = merger.buildFetchMetadata(null, 7L, options, contributions(
                    MetadataProvider.GoodReads, BookMetadata.builder().build()));

            assertThat(merged.getFieldProviders()).doesNotContainKey(MetadataField.DESCRIPTION);
        }

        @Test
        void attributesNothingForAValueCarriedOverFromTheExistingMetadata() {
            MetadataRefreshOptions options = optionsFor(MetadataRefreshOptions.FieldOptions.builder()
                    .description(chain(MetadataProvider.GoodReads))
                    .build());
            BookMetadata existing = BookMetadata.builder().description("What the book already said").build();

            BookMetadata merged = merger.buildFetchMetadata(existing, 7L, options, contributions(
                    MetadataProvider.GoodReads, BookMetadata.builder().build()));

            assertThat(merged.getDescription()).isEqualTo("What the book already said");
            assertThat(merged.getFieldProviders()).doesNotContainKey(MetadataField.DESCRIPTION);
        }

        @Test
        void attributesNothingForFieldsWrittenByTheirOwnCollectionHandlers() {
            MetadataRefreshOptions options = optionsFor(MetadataRefreshOptions.FieldOptions.builder()
                    .authors(chain(MetadataProvider.GoodReads))
                    .categories(chain(MetadataProvider.GoodReads))
                    .cover(chain(MetadataProvider.GoodReads))
                    .build());

            BookMetadata merged = merger.buildFetchMetadata(null, 7L, options, contributions(
                    MetadataProvider.GoodReads, BookMetadata.builder()
                            .authors(java.util.List.of("Someone"))
                            .categories(java.util.Set.of("Fiction"))
                            .thumbnailUrl("https://example.test/cover.jpg")
                            .build()));

            assertThat(merged.getAuthors()).containsExactly("Someone");
            assertThat(merged.getThumbnailUrl()).isEqualTo("https://example.test/cover.jpg");
            assertThat(merged.getFieldProviders()).isEmpty();
        }
    }
}
