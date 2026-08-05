package org.booklore.service.metadata;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookReview;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.BookReviewEntity;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.util.BookUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BookReviewUpdateServiceTest {

    private BookReviewUpdateService service;

    @BeforeEach
    void setUp() {
        service = new BookReviewUpdateService();
    }

    private BookMetadataEntity entityWithReviews(Set<BookReviewEntity> reviews) {
        return BookMetadataEntity.builder()
                .reviewsLocked(false)
                .reviews(reviews)
                .build();
    }

    private BookReview review(MetadataProvider provider, String name, Instant date) {
        return BookReview.builder()
                .metadataProvider(provider)
                .reviewerName(name)
                .date(date)
                .build();
    }

    @Nested
    class ReviewLockRespect {

        @Test
        void skipsUpdateWhenReviewsLocked() {
            BookMetadataEntity entity = BookMetadataEntity.builder()
                    .reviewsLocked(true)
                    .reviews(new HashSet<>())
                    .build();
            BookMetadata metadata = BookMetadata.builder()
                    .bookReviews(List.of(review(MetadataProvider.Amazon, "r1", Instant.now())))
                    .build();

            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).isEmpty();
        }

        @Test
        void skipsUpdateWhenReviewsLockedIsNull() {
            BookMetadataEntity entity = BookMetadataEntity.builder()
                    .reviewsLocked(null)
                    .reviews(new HashSet<>())
                    .build();
            BookMetadata metadata = BookMetadata.builder()
                    .bookReviews(List.of(review(MetadataProvider.Amazon, "r1", Instant.now())))
                    .build();

            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).hasSize(1);
        }
    }

    @Nested
    class ClearFlags {

        @Test
        void clearsReviewsWhenClearFlagSet() {
            BookReviewEntity existing = BookReviewEntity.builder()
                    .metadataProvider(MetadataProvider.Amazon)
                    .reviewerName("existing")
                    .build();
            BookMetadataEntity entity = entityWithReviews(new HashSet<>(Set.of(existing)));

            MetadataClearFlags flags = new MetadataClearFlags();
            flags.setReviews(true);

            service.updateBookReviews(BookMetadata.builder().build(), entity, flags, true);

            assertThat(entity.getReviews()).isEmpty();
        }
    }

    @Nested
    class NullAndEmptyReviewHandling {

        @Test
        void skipsWhenBookReviewsNull() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            BookMetadata metadata = BookMetadata.builder().bookReviews(null).build();

            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).isEmpty();
        }

        @Test
        void filtersNullReviewsInList() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            List<BookReview> reviews = new ArrayList<>();
            reviews.add(null);
            reviews.add(review(MetadataProvider.Amazon, "valid", Instant.now()));

            BookMetadata metadata = BookMetadata.builder().bookReviews(reviews).build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).hasSize(1);
        }

        @Test
        void filtersReviewsWithNullProvider() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            BookReview noProvider = BookReview.builder().reviewerName("orphan").build();

            BookMetadata metadata = BookMetadata.builder()
                    .bookReviews(List.of(noProvider))
                    .build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).isEmpty();
        }
    }

    @Nested
    class MergeVsReplace {

        @Test
        void mergeAddsToExistingReviews() {
            BookReviewEntity existing = BookReviewEntity.builder()
                    .metadataProvider(MetadataProvider.Amazon)
                    .reviewerName("old")
                    .date(Instant.now())
                    .build();
            BookMetadataEntity entity = entityWithReviews(new HashSet<>(Set.of(existing)));

            BookMetadata metadata = BookMetadata.builder()
                    .bookReviews(List.of(review(MetadataProvider.GoodReads, "new", Instant.now())))
                    .build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).hasSize(2);
        }

        @Test
        void replaceRemovesExistingReviews() {
            BookReviewEntity existing = BookReviewEntity.builder()
                    .metadataProvider(MetadataProvider.Amazon)
                    .reviewerName("old")
                    .date(Instant.now())
                    .build();
            BookMetadataEntity entity = entityWithReviews(new HashSet<>(Set.of(existing)));

            BookMetadata metadata = BookMetadata.builder()
                    .bookReviews(List.of(review(MetadataProvider.GoodReads, "replacement", Instant.now())))
                    .build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), false);

            assertThat(entity.getReviews()).hasSize(1);
            assertThat(entity.getReviews().iterator().next().getReviewerName()).isEqualTo("replacement");
        }
    }

    @Nested
    class ProviderScopedReplacement {

        @Test
        void reRunningTheSameProviderReplacesRatherThanAccumulates() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            List<BookReview> reviews = List.of(review(MetadataProvider.FlibustaLocal, "r1", Instant.now()));

            service.addReviewsToBook(reviews, entity);
            service.addReviewsToBook(reviews, entity);

            assertThat(entity.getReviews()).hasSize(1);
        }

        @Test
        void aProviderReplacesOnlyItsOwnReviews() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());

            service.addReviewsToBook(List.of(review(MetadataProvider.GoodReads, "goodreads", Instant.now())), entity);
            service.addReviewsToBook(List.of(review(MetadataProvider.FlibustaLocal, "flibusta", Instant.now())), entity);

            assertThat(entity.getReviews())
                    .extracting(BookReviewEntity::getMetadataProvider)
                    .containsExactlyInAnyOrder(MetadataProvider.GoodReads, MetadataProvider.FlibustaLocal);
        }
    }

    @Nested
    class PerProviderLimit {

        @Test
        void limitsToFiveReviewsPerProvider() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            Instant base = Instant.now();

            List<BookReview> sevenReviews = IntStream.range(0, 7)
                    .mapToObj(i -> review(MetadataProvider.Amazon, "r" + i, base.minus(i, ChronoUnit.DAYS)))
                    .toList();

            BookMetadata metadata = BookMetadata.builder().bookReviews(sevenReviews).build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).hasSize(5);
        }

        @Test
        void limitsApplyPerProviderIndependently() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            Instant base = Instant.now();

            List<BookReview> reviews = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                reviews.add(review(MetadataProvider.Amazon, "az" + i, base.minus(i, ChronoUnit.DAYS)));
            }
            for (int i = 0; i < 6; i++) {
                reviews.add(review(MetadataProvider.GoodReads, "gr" + i, base.minus(i, ChronoUnit.DAYS)));
            }

            BookMetadata metadata = BookMetadata.builder().bookReviews(reviews).build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            long amazonCount = entity.getReviews().stream()
                    .filter(r -> r.getMetadataProvider() == MetadataProvider.Amazon)
                    .count();
            long goodreadsCount = entity.getReviews().stream()
                    .filter(r -> r.getMetadataProvider() == MetadataProvider.GoodReads)
                    .count();

            assertThat(amazonCount).isEqualTo(5);
            assertThat(goodreadsCount).isEqualTo(5);
        }

        @Test
        void keepsMostRecentReviewsWhenLimitApplied() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            Instant base = Instant.now();

            List<BookReview> reviews = IntStream.range(0, 7)
                    .mapToObj(i -> review(MetadataProvider.Amazon, "r" + i, base.minus(i, ChronoUnit.DAYS)))
                    .toList();

            BookMetadata metadata = BookMetadata.builder().bookReviews(reviews).build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            Set<String> keptNames = new HashSet<>();
            entity.getReviews().forEach(r -> keptNames.add(r.getReviewerName()));

            assertThat(keptNames).contains("r0", "r1", "r2", "r3", "r4").doesNotContain("r6");
        }

        @Test
        void handlesNullDatesInLimitSorting() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());

            List<BookReview> reviews = new ArrayList<>();
            reviews.add(review(MetadataProvider.Amazon, "dated", Instant.now()));
            reviews.add(review(MetadataProvider.Amazon, "undated", null));

            BookMetadata metadata = BookMetadata.builder().bookReviews(reviews).build();
            service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

            assertThat(entity.getReviews()).hasSize(2);
        }
    }

    @Nested
    class AddReviewsToBook {

        @Test
        void addsReviewsViaConvenienceMethod() {
            BookMetadataEntity entity = entityWithReviews(new HashSet<>());
            List<BookReview> reviews = List.of(
                    review(MetadataProvider.Amazon, "r1", Instant.now()),
                    review(MetadataProvider.GoodReads, "r2", Instant.now())
            );

            service.addReviewsToBook(reviews, entity);

            assertThat(entity.getReviews()).hasSize(2);
        }

        @Test
        void addReviewsToBookRespectsLock() {
            BookMetadataEntity entity = BookMetadataEntity.builder()
                    .reviewsLocked(true)
                    .reviews(new HashSet<>())
                    .build();

            service.addReviewsToBook(
                    List.of(review(MetadataProvider.Amazon, "r1", Instant.now())),
                    entity
            );

            assertThat(entity.getReviews()).isEmpty();
        }
    }

    @Test
    void reviewEntityFieldsAreMappedCorrectly() {
        BookMetadataEntity entity = entityWithReviews(new HashSet<>());
        Instant date = Instant.now();
        BookReview review = BookReview.builder()
                .metadataProvider(MetadataProvider.Hardcover)
                .reviewerName("Jane")
                .title("Great Book")
                .rating(4.5f)
                .date(date)
                .body("Loved it")
                .spoiler(true)
                .followersCount(100)
                .textReviewsCount(50)
                .country("US")
                .build();

        BookMetadata metadata = BookMetadata.builder().bookReviews(List.of(review)).build();
        service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

        BookReviewEntity created = entity.getReviews().iterator().next();
        assertThat(created.getMetadataProvider()).isEqualTo(MetadataProvider.Hardcover);
        assertThat(created.getReviewerName()).isEqualTo("Jane");
        assertThat(created.getTitle()).isEqualTo("Great Book");
        assertThat(created.getRating()).isEqualTo(4.5f);
        assertThat(created.getDate()).isEqualTo(date);
        assertThat(created.getBody()).isEqualTo("Loved it");
        assertThat(created.getSpoiler()).isTrue();
        assertThat(created.getFollowersCount()).isEqualTo(100);
        assertThat(created.getTextReviewsCount()).isEqualTo(50);
        assertThat(created.getCountry()).isEqualTo("US");
        assertThat(created.getBookMetadata()).isSameAs(entity);
    }

    @Test
    void reviewEntityFieldsAreTruncatedCorrectly() {
        BookMetadataEntity entity = entityWithReviews(new HashSet<>());
        BookReview review = BookReview.builder()
                .metadataProvider(MetadataProvider.Hardcover)
                .reviewerName("A".repeat(1024))
                .title("A".repeat(1024))
                .country("A".repeat(1024))
                .build();

        BookMetadata metadata = BookMetadata.builder().bookReviews(List.of(review)).build();
        service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

        BookReviewEntity created = entity.getReviews().iterator().next();
        assertThat(created.getReviewerName()).hasSize(512);
        assertThat(created.getTitle()).hasSize(512);
        assertThat(created.getCountry()).hasSize(512);
    }

    @Test
    void reviewEntityFieldsAtExactLengthAreTruncatedCorrectly() {
        BookMetadataEntity entity = entityWithReviews(new HashSet<>());
        BookReview review = BookReview.builder()
                .metadataProvider(MetadataProvider.Hardcover)
                .reviewerName("A".repeat(512))
                .title("A".repeat(512))
                .country("A".repeat(512))
                .build();

        BookMetadata metadata = BookMetadata.builder().bookReviews(List.of(review)).build();
        service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

        BookReviewEntity created = entity.getReviews().iterator().next();
        assertThat(created.getReviewerName()).hasSize(512);
        assertThat(created.getTitle()).hasSize(512);
        assertThat(created.getCountry()).hasSize(512);
    }

    /**
     * {@code body} is the one review field that lands in a {@code TEXT} column rather than a
     * {@code VARCHAR(512)}, and {@code TEXT}'s 65,535 is a byte budget: 40,000 Cyrillic characters is
     * 80,000 bytes. It used to be passed through untouched, so a long catalog review rolled back the
     * whole enrichment transaction and cost the book its description, language, series and author
     * biographies as well. The assertion is on bytes because a character count would pass against the
     * unbounded code too.
     */
    @Test
    void reviewBodyIsBoundedByTheTextColumnsByteBudget() {
        BookMetadataEntity entity = entityWithReviews(new HashSet<>());
        BookReview review = BookReview.builder()
                .metadataProvider(MetadataProvider.FlibustaLocal)
                .body("я".repeat(40_000))
                .build();

        BookMetadata metadata = BookMetadata.builder().bookReviews(List.of(review)).build();
        service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

        BookReviewEntity created = entity.getReviews().iterator().next();
        assertThat(created.getBody().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(BookUtils.TEXT_MAX_UTF8_BYTES);
        assertThat(created.getBody()).startsWith("я").doesNotContain("�");
    }

    @Test
    void reviewBodyThatAlreadyFitsIsKeptVerbatim() {
        BookMetadataEntity entity = entityWithReviews(new HashSet<>());
        BookReview review = BookReview.builder()
                .metadataProvider(MetadataProvider.FlibustaLocal)
                .body("Отличная книга 📚")
                .build();

        BookMetadata metadata = BookMetadata.builder().bookReviews(List.of(review)).build();
        service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

        assertThat(entity.getReviews().iterator().next().getBody()).isEqualTo("Отличная книга 📚");
    }

    @Test
    void reviewEntityFieldsNullAreNotTruncated() {
        BookMetadataEntity entity = entityWithReviews(new HashSet<>());
        BookReview review = BookReview.builder()
                .metadataProvider(MetadataProvider.Hardcover)
                .reviewerName(null)
                .title(null)
                .country(null)
                .build();

        BookMetadata metadata = BookMetadata.builder().bookReviews(List.of(review)).build();
        service.updateBookReviews(metadata, entity, new MetadataClearFlags(), true);

        BookReviewEntity created = entity.getReviews().iterator().next();
        assertThat(created.getReviewerName()).isNull();
        assertThat(created.getTitle()).isNull();
        assertThat(created.getCountry()).isNull();
    }
}
