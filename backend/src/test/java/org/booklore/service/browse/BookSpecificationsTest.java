package org.booklore.service.browse;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.booklore.exception.APIException;
import org.booklore.model.entity.BookEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookSpecificationsTest {

    @Test
    void emptyAccessibleLibrarySetMatchesNothing() {
        Root<BookEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate disjunction = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(disjunction);

        Predicate result = BookSpecifications.inLibraries(Collections.emptySet())
                .toPredicate(root, query, cb);

        assertThat(result).isSameAs(disjunction);
    }

    @Test
    void nullLibrarySetKeepsAdminCatalogUnrestricted() {
        Root<BookEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Predicate result = BookSpecifications.inLibraries(null)
                .toPredicate(root, query, cb);

        assertThat(result).isSameAs(conjunction);
    }

    // --- Shared deep-stub harness for the multi-value filter builders ---

    @SuppressWarnings("unchecked")
    private Root<BookEntity> deepRoot() {
        return mock(Root.class, RETURNS_DEEP_STUBS);
    }

    private CriteriaQuery<?> deepQuery() {
        return mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
    }

    private CriteriaBuilder deepCb() {
        return mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
    }

    @Nested
    @DisplayName("withFileTypes")
    class WithFileTypesTest {

        @Test
        void emptyList_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withFileTypes(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void invalidType_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withFileTypes(List.of("bogus"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid fileType values")
                    .hasMessageContaining("bogus");
        }

        @Test
        void orMode_doesNotNegate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            BookSpecifications.withFileTypes(List.of("epub", "pdf"), "or").toPredicate(root, query, cb);

            verify(cb, never()).not(any());
        }

        @Test
        void notMode_negatesCombinedPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withFileTypes(List.of("epub"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void andMode_combinesOnePredicatePerType() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate andMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.and(captor.capture())).thenReturn(andMarker);

            Predicate result = BookSpecifications.withFileTypes(List.of("epub", "pdf", "cbx"), "and").toPredicate(root, query, cb);

            assertThat(result).isSameAs(andMarker);
            assertThat(captor.getValue()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("withReadStatuses")
    class WithReadStatusesTest {

        @Test
        void nullUserId_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withReadStatuses(List.of("READ"), null, "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void unknownStatus_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withReadStatuses(List.of("BOGUS"), 1L, "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid status values");
        }

        @Test
        void realStatusOnly_orMode_negatesOnlyForInternalNoEntryCheck() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            BookSpecifications.withReadStatuses(List.of("READ"), 1L, "or").toPredicate(root, query, cb);

            // cb.not() is invoked once internally to build the (unused, since hasUnset is
            // false) "no progress entry" predicate; the outer combined predicate itself is
            // not negated because mode is "or" rather than "not".
            verify(cb, times(1)).not(any());
        }

        @Test
        void unsetOnly_notMode_negatesCombined() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withReadStatuses(List.of("UNSET"), 1L, "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void unsetAndReal_combinesWithOr() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate orMarker = mock(Predicate.class);
            when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(orMarker);

            Predicate result = BookSpecifications.withReadStatuses(List.of("UNSET", "READ"), 1L, "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(orMarker);
        }
    }

    @Nested
    @DisplayName("withMinRating / withMaxRating")
    class RatingBoundsTest {

        @Test
        void withMinRating_buildsInSubquery() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Predicate result = BookSpecifications.withMinRating(3, 1L).toPredicate(root, query, cb);

            assertThat(result).isNotNull();
        }

        @Test
        void withMaxRating_zero_findsUnratedBooks() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withMaxRating(0, 1L).toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withMaxRating_nonZero_doesNotNegate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            BookSpecifications.withMaxRating(3, 1L).toPredicate(root, query, cb);

            verify(cb, never()).not(any());
        }
    }

    @Nested
    @DisplayName("scalar metadata field filters (language/series/publisher/narrator)")
    class ScalarMetadataFieldTest {

        @Test
        void withAuthor_singleValue_delegatesToWithAuthorsOr() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Predicate result = BookSpecifications.withAuthor("Tolkien").toPredicate(root, query, cb);

            assertThat(result).isNotNull();
            verify(cb, never()).not(any());
        }

        @Test
        void withAuthor_nullValue_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withAuthor(null).toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withAuthors_andMode_oneExistsPerAuthor() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate andMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.and(captor.capture())).thenReturn(andMarker);

            Predicate result = BookSpecifications.withAuthors(List.of("Tolkien", "Sanderson"), "and").toPredicate(root, query, cb);

            assertThat(result).isSameAs(andMarker);
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        void withAuthors_notMode_negatesExists() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withAuthors(List.of("Tolkien"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withAuthors_emptyValues_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withAuthors(List.of(" ", ""), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withLanguages_notMode_negatesInPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Predicate result = BookSpecifications.withLanguages(List.of("en", "fr"), "not").toPredicate(root, query, cb);

            assertThat(result).isNotNull();
        }

        @Test
        void withLanguage_null_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withLanguage(null).toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void inSeriesMulti_orMode_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Predicate result = BookSpecifications.inSeries("Mistborn").toPredicate(root, query, cb);

            assertThat(result).isNotNull();
        }

        @Test
        void withCategories_andMode_combinesExistsPerCategory() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate andMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.and(captor.capture())).thenReturn(andMarker);

            Predicate result = BookSpecifications.withCategories(List.of("Fantasy", "Sci-Fi"), "and").toPredicate(root, query, cb);

            assertThat(result).isSameAs(andMarker);
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        void withCategory_singleValue_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withCategory("Fantasy").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withPublishers_notMode_negatesInPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withPublishers(List.of("Tor"), "not").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withPublisher_singleValue_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withPublisher("Tor").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withTags_orMode_returnsExistsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withTags(List.of("cozy"), "or").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withTag_singleValue_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withTag("cozy").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withMoods_orMode_returnsExistsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withMoods(List.of("dark"), "or").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withMood_singleValue_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withMood("dark").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withNarrators_orMode_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withNarrators(List.of("Stephen Fry"), "or").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withNarrator_singleValue_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withNarrator("Stephen Fry").toPredicate(root, query, cb)).isNotNull();
        }
    }

    @Nested
    @DisplayName("unshelved")
    class UnshelvedTest {

        @Test
        void unshelved_negatesExistsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.unshelved().toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }
    }

    @Nested
    @DisplayName("bucketed range filters")
    class BucketedRangeFilterTest {

        @Test
        void withAgeRatings_emptyList_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withAgeRatings(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withAgeRatings_allBuckets_combineWithOr() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate orMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.or(captor.capture())).thenReturn(orMarker);

            Predicate result = BookSpecifications.withAgeRatings(
                    List.of("0", "6", "10", "13", "16", "18", "21"), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(orMarker);
            assertThat(captor.getValue()).hasSize(7);
        }

        @Test
        void withAgeRatings_notMode_negatesCombined() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withAgeRatings(List.of("18"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withAgeRatings_invalidBucket_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withAgeRatings(List.of("99"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid ageRating bucket ID");
        }

        @Test
        void withContentRatings_notMode_negatesInPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withContentRatings(List.of("PG-13"), "not").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withContentRatings_emptyList_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withContentRatings(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withMatchScores_allBuckets_combineWithOr() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate orMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.or(captor.capture())).thenReturn(orMarker);

            Predicate result = BookSpecifications.withMatchScores(
                    List.of("0", "1", "2", "3", "4", "5", "6"), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(orMarker);
            assertThat(captor.getValue()).hasSize(7);
        }

        @Test
        void withMatchScores_invalidBucket_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withMatchScores(List.of("7"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid matchScore bucket ID");
        }

        @Test
        void withPublishedYears_emptyYears_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withPublishedYears(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withPublishedYears_unparseableYears_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withPublishedYears(List.of(" "), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withPublishedYears_notMode_negatesYearInPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withPublishedYears(List.of("2020"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withFileSizes_invalidBucket_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withFileSizes(List.of("8"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid fileSize bucket ID");
        }

        @Test
        void withFileSizes_notMode_negatesExists() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withFileSizes(List.of("0", "7"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withFileSizes_emptyList_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withFileSizes(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withPersonalRatings_nullUserId_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withPersonalRatings(List.of("5"), null, "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withPersonalRatings_emptyValues_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withPersonalRatings(List.of(), 1L, "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void withPersonalRatings_notMode_negatesInSubquery() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withPersonalRatings(List.of("4", "5"), 1L, "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withPageCounts_allBuckets_combineWithOr() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate orMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.or(captor.capture())).thenReturn(orMarker);

            Predicate result = BookSpecifications.withPageCounts(
                    List.of("0", "1", "2", "3", "4", "5", "6"), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(orMarker);
            assertThat(captor.getValue()).hasSize(7);
        }

        @Test
        void withPageCounts_invalidBucket_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withPageCounts(List.of("7"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid pageCount bucket ID");
        }
    }

    @Nested
    @DisplayName("provider rating wrappers (delegate to buildRatingRangeSpec)")
    class ProviderRatingWrapperTest {

        @Test
        void withAmazonRatings_allBuckets_combineWithOr() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate orMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.or(captor.capture())).thenReturn(orMarker);

            Predicate result = BookSpecifications.withAmazonRatings(
                    List.of("0", "1", "2", "3", "4", "5"), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(orMarker);
            assertThat(captor.getValue()).hasSize(6);
        }

        @Test
        void withAmazonRatings_invalidBucket_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withAmazonRatings(List.of("9"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid amazonRating bucket ID");
        }

        @Test
        void withGoodreadsRatings_notMode_negatesCombined() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withGoodreadsRatings(List.of("4"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withHardcoverRatings_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withHardcoverRatings(List.of("3"), "or").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withLubimyczytacRatings_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withLubimyczytacRatings(List.of("3"), "or").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withRanobedbRatings_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withRanobedbRatings(List.of("3"), "or").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withAudibleRatings_returnsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withAudibleRatings(List.of("3"), "or").toPredicate(root, query, cb)).isNotNull();
        }
    }

    @Nested
    @DisplayName("withShelfStatus")
    class WithShelfStatusTest {

        @Test
        void shelvedOnly_usesHasShelvesPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate hasShelves = mock(Predicate.class);
            when(cb.isNotEmpty(any())).thenReturn(hasShelves);

            Predicate result = BookSpecifications.withShelfStatus(List.of("shelved"), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(hasShelves);
        }

        @Test
        void unshelvedOnly_negatesHasShelvesPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notHasShelves = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notHasShelves);

            Predicate result = BookSpecifications.withShelfStatus(List.of("unshelved"), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notHasShelves);
        }

        @Test
        void bothValues_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withShelfStatus(List.of("shelved", "unshelved"), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void invalidValue_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withShelfStatus(List.of("bogus"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid shelfStatus values");
        }

        @Test
        void emptyValues_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withShelfStatus(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void notMode_negatesTheModeSelection() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate hasShelves = mock(Predicate.class);
            Predicate notMarker = mock(Predicate.class);
            when(cb.isNotEmpty(any())).thenReturn(hasShelves);
            when(cb.not(hasShelves)).thenReturn(notMarker);

            Predicate result = BookSpecifications.withShelfStatus(List.of("shelved"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }
    }

    @Nested
    @DisplayName("comic collection filters (characters/teams/locations)")
    class ComicCollectionFilterTest {

        @Test
        void withComicCharacters_orMode_returnsExistsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            assertThat(BookSpecifications.withComicCharacters(List.of("Batman"), "or").toPredicate(root, query, cb)).isNotNull();
        }

        @Test
        void withComicTeams_notMode_negatesExists() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withComicTeams(List.of("Justice League"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void withComicLocations_emptyValues_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withComicLocations(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }
    }

    @Nested
    @DisplayName("inShelves")
    class InShelvesTest {

        @Test
        void emptyList_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.inShelves(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void unparseableIds_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.inShelves(List.of(" "), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void invalidId_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.inShelves(List.of("abc"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid shelf values");
        }

        @Test
        void andMode_combinesOneExistsPerShelf() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate andMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.and(captor.capture())).thenReturn(andMarker);

            Predicate result = BookSpecifications.inShelves(List.of("1", "2"), "and").toPredicate(root, query, cb);

            assertThat(result).isSameAs(andMarker);
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        void notMode_negatesExists() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.inShelves(List.of("1"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }
    }

    @Nested
    @DisplayName("inLibraries(List, mode)")
    class InLibrariesListModeTest {

        @Test
        void emptyList_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.inLibraries(List.of(), "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void andMode_multipleIds_returnsDisjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate disjunction = mock(Predicate.class);
            when(cb.disjunction()).thenReturn(disjunction);

            Predicate result = BookSpecifications.inLibraries(List.of("1", "2"), "and").toPredicate(root, query, cb);

            assertThat(result).isSameAs(disjunction);
        }

        @Test
        void andMode_singleId_equalsThatLibrary() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate equalMarker = mock(Predicate.class);
            when(cb.equal(any(), eq(1L))).thenReturn(equalMarker);

            Predicate result = BookSpecifications.inLibraries(List.of("1"), "and").toPredicate(root, query, cb);

            assertThat(result).isSameAs(equalMarker);
        }

        @Test
        void notMode_negatesInPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.inLibraries(List.of("1", "2"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }
    }

    @Nested
    @DisplayName("withComicCreators")
    class WithComicCreatorsTest {

        @Test
        void nullValues_returnsConjunction() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.withComicCreators(null, "or").toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }

        @Test
        void nameOnly_noRole_returnsExistsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Predicate result = BookSpecifications.withComicCreators(List.of("Jim Lee"), "or").toPredicate(root, query, cb);

            assertThat(result).isNotNull();
        }

        @Test
        void nameWithValidRole_returnsExistsPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Predicate result = BookSpecifications.withComicCreators(List.of("Jim Lee:penciller"), "or").toPredicate(root, query, cb);

            assertThat(result).isNotNull();
        }

        @Test
        void nameWithInvalidRole_throwsApiException() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            Specification<BookEntity> spec = BookSpecifications.withComicCreators(List.of("Jim Lee:bogus"), "or");

            assertThatThrownBy(() -> spec.toPredicate(root, query, cb))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Invalid comic creator role");
        }

        @Test
        void andMode_combinesOnePredicatePerCreator() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate andMarker = mock(Predicate.class);
            ArgumentCaptor<Predicate[]> captor = ArgumentCaptor.forClass(Predicate[].class);
            when(cb.and(captor.capture())).thenReturn(andMarker);

            Predicate result = BookSpecifications.withComicCreators(List.of("Jim Lee", "Scott Snyder"), "and").toPredicate(root, query, cb);

            assertThat(result).isSameAs(andMarker);
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        void notMode_negatesCombinedPredicate() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();
            Predicate notMarker = mock(Predicate.class);
            when(cb.not(any())).thenReturn(notMarker);

            Predicate result = BookSpecifications.withComicCreators(List.of("Jim Lee"), "not").toPredicate(root, query, cb);

            assertThat(result).isSameAs(notMarker);
        }

        @Test
        void allRoleAliases_areAccepted() {
            Root<BookEntity> root = deepRoot();
            CriteriaQuery<?> query = deepQuery();
            CriteriaBuilder cb = deepCb();

            for (String role : List.of("penciller", "inker", "colorist", "letterer", "coverartist", "editor")) {
                Predicate result = BookSpecifications.withComicCreators(List.of("Someone:" + role), "or").toPredicate(root, query, cb);
                assertThat(result).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("combine")
    class CombineTest {

        @Test
        void skipsNullSpecs_andAndsTheRest() {
            Root<BookEntity> root = mock(Root.class);
            CriteriaQuery<?> query = mock(CriteriaQuery.class);
            CriteriaBuilder cb = mock(CriteriaBuilder.class);
            Predicate conjunction = mock(Predicate.class);
            Predicate specPredicate = mock(Predicate.class);
            Predicate andResult = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);
            when(cb.and(conjunction, specPredicate)).thenReturn(andResult);

            org.springframework.data.jpa.domain.Specification<BookEntity> nonNullSpec =
                    (r, q, criteriaBuilder) -> specPredicate;

            Predicate result = BookSpecifications.combine(null, nonNullSpec).toPredicate(root, query, cb);

            assertThat(result).isSameAs(andResult);
        }

        @Test
        void noSpecs_returnsConjunction() {
            Root<BookEntity> root = mock(Root.class);
            CriteriaQuery<?> query = mock(CriteriaQuery.class);
            CriteriaBuilder cb = mock(CriteriaBuilder.class);
            Predicate conjunction = mock(Predicate.class);
            when(cb.conjunction()).thenReturn(conjunction);

            Predicate result = BookSpecifications.combine().toPredicate(root, query, cb);

            assertThat(result).isSameAs(conjunction);
        }
    }

    @Nested
    @DisplayName("getOrCreateJoin reuse")
    class GetOrCreateJoinTest {

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void reusesExistingJoin_whenAttributeAndTypeMatch() {
            Root<BookEntity> root = mock(Root.class, RETURNS_DEEP_STUBS);
            CriteriaQuery<?> query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
            CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);

            jakarta.persistence.criteria.Join existingJoin =
                    mock(jakarta.persistence.criteria.Join.class, RETURNS_DEEP_STUBS);
            jakarta.persistence.metamodel.Attribute attribute =
                    mock(jakarta.persistence.metamodel.Attribute.class);
            when(attribute.getName()).thenReturn("metadata");
            when(existingJoin.getAttribute()).thenReturn(attribute);
            when(existingJoin.getJoinType()).thenReturn(jakarta.persistence.criteria.JoinType.INNER);
            when(root.getJoins()).thenReturn(java.util.Set.of(existingJoin));

            BookSpecifications.withLanguage("en").toPredicate(root, query, cb);

            verify(root, never()).join(anyString(), any(jakarta.persistence.criteria.JoinType.class));
        }
    }
}
