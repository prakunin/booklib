package org.booklore.service.enrichment;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.service.enrichment.catalog.CatalogReview;
import org.booklore.service.metadata.MetadataRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrichmentResolverTest {

    private final MetadataRefreshService metadataRefreshService = mock(MetadataRefreshService.class);
    private final EnrichmentResolver resolver = new EnrichmentResolver(metadataRefreshService);

    private MetadataRefreshOptions options;

    @BeforeEach
    void setUp() {
        options = MetadataRefreshOptions.builder().build();
        when(metadataRefreshService.buildFetchMetadata(any(), anyLong(), any(), any()))
                .thenAnswer(invocation -> BookMetadata.builder().bookId(invocation.getArgument(1)).build());
    }

    private EnrichmentContext context(EnrichmentWritePolicy policy) {
        return context(policy, BookMetadata.builder().bookId(1L).title("Существующее").build());
    }

    private EnrichmentContext context(EnrichmentWritePolicy policy, BookMetadata existing) {
        Book book = Book.builder().id(1L).metadata(existing).build();
        return new EnrichmentContext(book, 7L, "a.zip", "1.fb2",
                EnrichmentRequest.builder()
                        .scope(EnrichmentRequest.Scope.BOOK)
                        .writePolicy(policy)
                        .agentAllowed(true)
                        .build());
    }

    @SuppressWarnings("unchecked")
    private List<Map<MetadataProvider, BookMetadata>> capturedMaps(int expectedCalls) {
        ArgumentCaptor<Map<MetadataProvider, BookMetadata>> captor = ArgumentCaptor.forClass(Map.class);
        verify(metadataRefreshService, times(expectedCalls))
                .buildFetchMetadata(any(), anyLong(), any(), captor.capture());
        return captor.getAllValues();
    }

    @Nested
    class ConfidenceDecidesWhatIsWritten {

        @Test
        void writesOnlyHighConfidenceContributionsAndProposesTheRest() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addContribution(MetadataProvider.FlibustaLocal,
                    BookMetadata.builder().description("Точное совпадение").build(), EnrichmentConfidence.HIGH);
            context.addContribution(MetadataProvider.Amazon,
                    BookMetadata.builder().title("Догадка").build(), EnrichmentConfidence.MEDIUM);

            EnrichmentOutcome outcome = resolver.resolve(context, options);

            assertThat(outcome.getApplied()).isNotNull();
            assertThat(outcome.getProposed()).isNotNull();

            List<Map<MetadataProvider, BookMetadata>> maps = capturedMaps(2);
            assertThat(maps.get(0)).containsOnlyKeys(MetadataProvider.FlibustaLocal);
            assertThat(maps.get(1)).containsOnlyKeys(MetadataProvider.FlibustaLocal, MetadataProvider.Amazon);
        }

        @Test
        void proposesNothingWhenEverythingWasTrusted() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addContribution(MetadataProvider.FlibustaLocal,
                    BookMetadata.builder().description("Точное совпадение").build(), EnrichmentConfidence.HIGH);

            EnrichmentOutcome outcome = resolver.resolve(context, options);

            assertThat(outcome.getApplied()).isNotNull();
            assertThat(outcome.getProposed()).isNull();
        }

        @Test
        void writesNothingWhenNoContributionIsTrusted() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addContribution(MetadataProvider.Amazon,
                    BookMetadata.builder().title("Догадка").build(), EnrichmentConfidence.MEDIUM);

            EnrichmentOutcome outcome = resolver.resolve(context, options);

            assertThat(outcome.getApplied()).isNull();
            assertThat(outcome.getProposed()).isNotNull();
        }

        @Test
        void returnsNothingAtAllWhenNoStepFoundAnything() {
            EnrichmentOutcome outcome = resolver.resolve(context(EnrichmentWritePolicy.AUTO), options);

            assertThat(outcome.changedAnything()).isFalse();
            verify(metadataRefreshService, never()).buildFetchMetadata(any(), anyLong(), any(), any());
        }
    }

    @Nested
    class WritePolicyIsACeiling {

        @Test
        void proposeWritesNothingEvenWithHighConfidence() {
            EnrichmentContext context = context(EnrichmentWritePolicy.PROPOSE);
            context.addContribution(MetadataProvider.FlibustaLocal,
                    BookMetadata.builder().description("Точное совпадение").build(), EnrichmentConfidence.HIGH);

            EnrichmentOutcome outcome = resolver.resolve(context, options);

            assertThat(outcome.getApplied()).isNull();
            assertThat(outcome.getProposed()).isNotNull();
        }

        @Test
        void autoIfEmptyResolvesTheWriteWithReplaceMissing() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO_IF_EMPTY);
            context.addContribution(MetadataProvider.FlibustaLocal,
                    BookMetadata.builder().description("Точное совпадение").build(), EnrichmentConfidence.HIGH);

            resolver.resolve(context, options);

            ArgumentCaptor<MetadataRefreshOptions> captor = ArgumentCaptor.forClass(MetadataRefreshOptions.class);
            verify(metadataRefreshService).buildFetchMetadata(any(), anyLong(), captor.capture(), any());
            assertThat(captor.getValue().getReplaceMode()).isEqualTo(MetadataReplaceMode.REPLACE_MISSING);
        }

        @Test
        void autoResolvesTheWriteWithReplaceAll() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addContribution(MetadataProvider.FlibustaLocal,
                    BookMetadata.builder().description("Точное совпадение").build(), EnrichmentConfidence.HIGH);

            resolver.resolve(context, options);

            assertThat(options.getReplaceMode()).isEqualTo(MetadataReplaceMode.REPLACE_ALL);
        }
    }

    /**
     * The one rule that must hold no matter how the per-field priority table is configured. A parser
     * can be checked against the page it scraped; an agent's number cannot be checked against
     * anything, and once stored it is indistinguishable from a measured one.
     */
    @Nested
    class TheAgentNeverSuppliesNumbers {

        @Test
        void stripsEveryNumericFieldFromTheAgentContribution() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addContribution(MetadataProvider.Agent, BookMetadata.builder()
                    .title("Оригинальное название")
                    .description("Описание")
                    .pageCount(432)
                    .rating(4.5)
                    .amazonRating(4.4)
                    .amazonReviewCount(1200)
                    .goodreadsRating(4.3)
                    .goodreadsReviewCount(9000)
                    .hardcoverRating(4.2)
                    .hardcoverReviewCount(11)
                    .doubanRating(9.1)
                    .doubanReviewCount(12)
                    .lubimyczytacRating(7.7)
                    .ranobedbRating(8.8)
                    .audibleRating(4.1)
                    .audibleReviewCount(13)
                    .build(), EnrichmentConfidence.LOW);

            resolver.resolve(context, options);

            BookMetadata agent = capturedMaps(1).getFirst().get(MetadataProvider.Agent);
            assertThat(agent).isNotNull();
            assertThat(agent.getTitle()).isEqualTo("Оригинальное название");
            assertThat(agent.getDescription()).isEqualTo("Описание");
            assertThat(agent.getPageCount()).isNull();
            assertThat(agent.getRating()).isNull();
            assertThat(agent.getAmazonRating()).isNull();
            assertThat(agent.getAmazonReviewCount()).isNull();
            assertThat(agent.getGoodreadsRating()).isNull();
            assertThat(agent.getGoodreadsReviewCount()).isNull();
            assertThat(agent.getHardcoverRating()).isNull();
            assertThat(agent.getHardcoverReviewCount()).isNull();
            assertThat(agent.getDoubanRating()).isNull();
            assertThat(agent.getDoubanReviewCount()).isNull();
            assertThat(agent.getLubimyczytacRating()).isNull();
            assertThat(agent.getRanobedbRating()).isNull();
            assertThat(agent.getAudibleRating()).isNull();
            assertThat(agent.getAudibleReviewCount()).isNull();
        }

        @Test
        void leavesOtherProvidersNumbersAlone() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addContribution(MetadataProvider.GoodReads,
                    BookMetadata.builder().goodreadsRating(4.3).pageCount(432).build(), EnrichmentConfidence.HIGH);

            resolver.resolve(context, options);

            BookMetadata goodreads = capturedMaps(1).getFirst().get(MetadataProvider.GoodReads);
            assertThat(goodreads.getGoodreadsRating()).isEqualTo(4.3);
            assertThat(goodreads.getPageCount()).isEqualTo(432);
        }
    }

    @Nested
    class CatalogReviews {

        @Test
        void rideAlongWithTheLocalContributionWithoutLosingItsDescription() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addContribution(MetadataProvider.FlibustaLocal,
                    BookMetadata.builder().description("Аннотация").build(), EnrichmentConfidence.HIGH);
            context.addCatalogReviews(List.of(
                    new CatalogReview("Читатель", "Хорошая книга.", Instant.parse("2015-05-01T00:00:00Z"))));

            resolver.resolve(context, options);

            BookMetadata local = capturedMaps(1).getFirst().get(MetadataProvider.FlibustaLocal);
            assertThat(local.getDescription()).isEqualTo("Аннотация");
            assertThat(local.getBookReviews()).singleElement().satisfies(review -> {
                assertThat(review.getBody()).isEqualTo("Хорошая книга.");
                assertThat(review.getMetadataProvider()).isEqualTo(MetadataProvider.FlibustaLocal);
            });
        }

        @Test
        void areStillCarriedWhenNoOtherStepContributedAnything() {
            EnrichmentContext context = context(EnrichmentWritePolicy.AUTO);
            context.addCatalogReviews(List.of(new CatalogReview(null, "Аноним.", null)));

            EnrichmentOutcome outcome = resolver.resolve(context, options);

            assertThat(outcome.getApplied()).isNotNull();
            assertThat(capturedMaps(1).getFirst()).containsKey(MetadataProvider.FlibustaLocal);
        }
    }
}
