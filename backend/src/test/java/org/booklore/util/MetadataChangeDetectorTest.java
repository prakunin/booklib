package org.booklore.util;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.ComicCharacterEntity;
import org.booklore.model.entity.ComicCreatorEntity;
import org.booklore.model.entity.ComicCreatorMappingEntity;
import org.booklore.model.entity.ComicLocationEntity;
import org.booklore.model.entity.ComicMetadataEntity;
import org.booklore.model.entity.ComicTeamEntity;
import org.booklore.model.entity.MoodEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.enums.ComicCreatorRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class MetadataChangeDetectorTest {

    private BookMetadataEntity existingMeta;
    private BookMetadata newMeta;
    private MetadataClearFlags clearFlags;

    @BeforeEach
    void setup() {
        existingMeta = BookMetadataEntity.builder()
                .bookId(1L)
                .title("Original Title")
                .subtitle("Original Subtitle")
                .publisher("Original Publisher")
                .publishedDate(LocalDate.of(2020, Month.JANUARY, 1))
                .description("Original Description")
                .seriesName("Original Series")
                .seriesNumber(1.0f)
                .seriesTotal(5)
                .isbn13("9781234567890")
                .isbn10("1234567890")
                .asin("B012345678")
                .goodreadsId("12345678")
                .comicvineId("987654")
                .hardcoverId("hc123456")
                .googleId("google123")
                .pageCount(300)
                .language("en")
                .amazonRating(4.2)
                .amazonReviewCount(1500)
                .goodreadsRating(4.1)
                .goodreadsReviewCount(25000)
                .hardcoverRating(4.0)
                .hardcoverReviewCount(500)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .publishedDateLocked(false)
                .descriptionLocked(false)
                .seriesNameLocked(false)
                .seriesNumberLocked(false)
                .seriesTotalLocked(false)
                .isbn13Locked(false)
                .isbn10Locked(false)
                .asinLocked(false)
                .goodreadsIdLocked(false)
                .comicvineIdLocked(false)
                .hardcoverIdLocked(false)
                .hardcoverBookIdLocked(false)
                .googleIdLocked(false)
                .pageCountLocked(false)
                .languageLocked(false)
                .amazonRatingLocked(false)
                .amazonReviewCountLocked(false)
                .goodreadsRatingLocked(false)
                .goodreadsReviewCountLocked(false)
                .hardcoverRatingLocked(false)
                .hardcoverReviewCountLocked(false)
                .coverLocked(false)
                .authorsLocked(false)
                .categoriesLocked(false)
                .moodsLocked(false)
                .tagsLocked(false)
                .reviewsLocked(false)
                .authors(List.of(
                        AuthorEntity.builder().id(1L).name("Author One").build(),
                        AuthorEntity.builder().id(2L).name("Author Two").build()
                ))
                .categories(Set.of(
                        CategoryEntity.builder().id(1L).name("Fiction").build(),
                        CategoryEntity.builder().id(2L).name("Mystery").build()
                ))
                .moods(Set.of(
                        MoodEntity.builder().id(1L).name("Dark").build(),
                        MoodEntity.builder().id(2L).name("Suspenseful").build()
                ))
                .tags(Set.of(
                        TagEntity.builder().id(1L).name("Thriller").build(),
                        TagEntity.builder().id(2L).name("Bestseller").build()
                ))
                .build();

        newMeta = BookMetadata.builder()
                .bookId(1L)
                .title("Original Title")
                .subtitle("Original Subtitle")
                .publisher("Original Publisher")
                .publishedDate(LocalDate.of(2020, Month.JANUARY, 1))
                .description("Original Description")
                .seriesName("Original Series")
                .seriesNumber(1.0f)
                .seriesTotal(5)
                .isbn13("9781234567890")
                .isbn10("1234567890")
                .asin("B012345678")
                .goodreadsId("12345678")
                .comicvineId("987654")
                .hardcoverId("hc123456")
                .googleId("google123")
                .pageCount(300)
                .language("en")
                .amazonRating(4.2)
                .amazonReviewCount(1500)
                .goodreadsRating(4.1)
                .goodreadsReviewCount(25000)
                .hardcoverRating(4.0)
                .hardcoverReviewCount(500)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .publishedDateLocked(false)
                .descriptionLocked(false)
                .seriesNameLocked(false)
                .seriesNumberLocked(false)
                .seriesTotalLocked(false)
                .isbn13Locked(false)
                .isbn10Locked(false)
                .asinLocked(false)
                .goodreadsIdLocked(false)
                .comicvineIdLocked(false)
                .hardcoverIdLocked(false)
                .hardcoverBookIdLocked(false)
                .googleIdLocked(false)
                .pageCountLocked(false)
                .languageLocked(false)
                .amazonRatingLocked(false)
                .amazonReviewCountLocked(false)
                .goodreadsRatingLocked(false)
                .goodreadsReviewCountLocked(false)
                .hardcoverRatingLocked(false)
                .hardcoverReviewCountLocked(false)
                .coverLocked(false)
                .authorsLocked(false)
                .categoriesLocked(false)
                .moodsLocked(false)
                .tagsLocked(false)
                .reviewsLocked(false)
                .authors(List.of("Author One", "Author Two"))
                .categories(Set.of("Fiction", "Mystery"))
                .moods(Set.of("Dark", "Suspenseful"))
                .tags(Set.of("Thriller", "Bestseller"))
                .build();

        clearFlags = new MetadataClearFlags();
    }

    @Test
    void testIsDifferent_whenNoChanges_returnsFalse() {
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when no changes exist");
    }

    @ParameterizedTest(name = "{0} field change")
    @MethodSource("fieldChangeProvider")
    @DisplayName("isDifferent() detects changes in all metadata fields")
    void testIsDifferent_whenFieldChanges_returnsTrue(String fieldName, Consumer<BookMetadata> modifier) {
        modifier.accept(newMeta);
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should detect change in " + fieldName);
    }

    static Stream<Arguments> fieldChangeProvider() {
        return Stream.of(
            Arguments.of("title", (Consumer<BookMetadata>) m -> m.setTitle("New Title")),
            Arguments.of("subtitle", (Consumer<BookMetadata>) m -> m.setSubtitle("New Subtitle")),
            Arguments.of("publisher", (Consumer<BookMetadata>) m -> m.setPublisher("New Publisher")),
            Arguments.of("publishedDate", (Consumer<BookMetadata>) m -> m.setPublishedDate(LocalDate.of(2021, Month.JUNE, 15))),
            Arguments.of("description", (Consumer<BookMetadata>) m -> m.setDescription("New Description")),
            Arguments.of("seriesName", (Consumer<BookMetadata>) m -> m.setSeriesName("New Series")),
            Arguments.of("seriesNumber", (Consumer<BookMetadata>) m -> m.setSeriesNumber(2.0f)),
            Arguments.of("seriesTotal", (Consumer<BookMetadata>) m -> m.setSeriesTotal(10)),
            Arguments.of("isbn13", (Consumer<BookMetadata>) m -> m.setIsbn13("9780987654321")),
            Arguments.of("isbn10", (Consumer<BookMetadata>) m -> m.setIsbn10("0987654321")),
            Arguments.of("asin", (Consumer<BookMetadata>) m -> m.setAsin("B098765432")),
            Arguments.of("goodreadsId", (Consumer<BookMetadata>) m -> m.setGoodreadsId("87654321")),
            Arguments.of("comicvineId", (Consumer<BookMetadata>) m -> m.setComicvineId("123456")),
            Arguments.of("hardcoverId", (Consumer<BookMetadata>) m -> m.setHardcoverId("hc654321")),
            Arguments.of("googleId", (Consumer<BookMetadata>) m -> m.setGoogleId("google456")),
            Arguments.of("pageCount", (Consumer<BookMetadata>) m -> m.setPageCount(350)),
            Arguments.of("language", (Consumer<BookMetadata>) m -> m.setLanguage("fr")),
            Arguments.of("amazonRating", (Consumer<BookMetadata>) m -> m.setAmazonRating(4.5)),
            Arguments.of("amazonReviewCount", (Consumer<BookMetadata>) m -> m.setAmazonReviewCount(2000)),
            Arguments.of("goodreadsRating", (Consumer<BookMetadata>) m -> m.setGoodreadsRating(4.3)),
            Arguments.of("goodreadsReviewCount", (Consumer<BookMetadata>) m -> m.setGoodreadsReviewCount(30000)),
            Arguments.of("hardcoverRating", (Consumer<BookMetadata>) m -> m.setHardcoverRating(4.2)),
            Arguments.of("hardcoverReviewCount", (Consumer<BookMetadata>) m -> m.setHardcoverReviewCount(750)),
            Arguments.of("audibleId", (Consumer<BookMetadata>) m -> m.setAudibleId("B098765432")),
            Arguments.of("audibleRating", (Consumer<BookMetadata>) m -> m.setAudibleRating(4.6)),
            Arguments.of("audibleReviewCount", (Consumer<BookMetadata>) m -> m.setAudibleReviewCount(3000))
        );
    }

    @Test
    void testIsDifferent_whenNullClearFlags_returnsTrue() {
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, null);
        assertTrue(result, "Should return true when clear flags is null");
    }

    @Test
    void testIsDifferent_whenTitleLocked_changes_returnsTrue() {
        newMeta.setTitleLocked(true);
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when title lock changes");
    }

    @Test
    void testIsDifferent_whenCoverLocked_changes_returnsTrue() {
        newMeta.setCoverLocked(true);
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when cover lock changes");
    }

    @Test
    void testIsDifferent_whenFieldLocked_changes_returnsTrue() {
        existingMeta.setTitleLocked(true);
        newMeta.setTitleLocked(false);
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when field lock changes");
    }

    @Test
    void testIsDifferent_whenFieldLocked_noChange_returnsFalse() {
        existingMeta.setTitleLocked(true);
        newMeta.setTitleLocked(true);
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when field locks match");
    }

    @Test
    void testHasValueChanges_whenNoChanges_returnsFalse() {
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when no value changes exist");
    }

    @Test
    void testHasValueChanges_whenTitleChanged_returnsTrue() {
        newMeta.setTitle("New Title");
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when title value changes");
    }

    @Test
    void testHasValueChanges_whenLockedFieldChanged_returnsFalse() {
        existingMeta.setTitleLocked(true);
        newMeta.setTitle("New Title");
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when locked field changes");
    }

    @Test
    void testHasValueChanges_whenClearFlagSet_returnsTrue() {
        clearFlags.setTitle(true);
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when clear flag is set (even if values match)");
    }

    @Test
    void testHasValueChangesForFileWrite_whenNoChanges_returnsFalse() {
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when no value changes exist for file write");
    }

    @Test
    void testHasValueChangesForFileWrite_whenTitleChanged_returnsTrue() {
        newMeta.setTitle("New Title");
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when title value changes for file write");
    }

    @Test
    void testHasValueChangesForFileWrite_includesRatingsForFileWrite() {
        newMeta.setAmazonRating(4.8);
        newMeta.setGoodreadsRating(4.4);
        newMeta.setHardcoverRating(4.3);
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when rating fields change for file write");
    }

    @Test
    void testEdgeCase_nullToEmptyString_returnsFalse() {
        existingMeta.setTitle(null);
        newMeta.setTitle("");
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false for null to empty string transition");
    }

    @Test
    void testEdgeCase_nullToEmptyCollection_returnsFalse() {
        BookMetadataEntity testExisting = BookMetadataEntity.builder()
                .bookId(1L)
                .title("Original Title")
                .subtitle("Original Subtitle")
                .publisher("Original Publisher")
                .authors(null) // null authors, this is what we're testing
                .authorsLocked(false)
                .categories(Set.of(CategoryEntity.builder().id(1L).name("Fiction").build()))
                .categoriesLocked(false)
                .moods(Set.of(MoodEntity.builder().id(1L).name("Dark").build()))
                .moodsLocked(false)
                .tags(Set.of(TagEntity.builder().id(1L).name("Thriller").build()))
                .tagsLocked(false)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .build();

        BookMetadata testNew = BookMetadata.builder()
                .bookId(1L)
                .title("Original Title")
                .subtitle("Original Subtitle")
                .publisher("Original Publisher")
                .authors(List.of()) // empty set, this is what we're testing
                .authorsLocked(false)
                .categories(Set.of("Fiction"))
                .categoriesLocked(false)
                .moods(Set.of("Dark"))
                .moodsLocked(false)
                .tags(Set.of("Thriller"))
                .tagsLocked(false)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .build();

        boolean result = MetadataChangeDetector.isDifferent(testNew, testExisting, clearFlags);
        assertFalse(result, "Should return false because toNameSet converts null to empty set, so null → empty collection is no change");
    }

    @Test
    void testEdgeCase_emptyStringToNull_returnsTrue() {
        existingMeta.setTitle("");
        newMeta.setTitle(null);
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true for empty string to null transition");
    }

    @Test
    void testEdgeCase_emptyCollectionToNull_returnsTrue() {
        BookMetadataEntity testExisting = BookMetadataEntity.builder()
                .bookId(1L)
                .title("Test Title")
                .authors(List.of()) // empty set, what we're testing
                .authorsLocked(false)
                .categories(Set.of(CategoryEntity.builder().id(1L).name("Fiction").build()))
                .categoriesLocked(false)
                .moods(Set.of(MoodEntity.builder().id(1L).name("Dark").build()))
                .moodsLocked(false)
                .tags(Set.of(TagEntity.builder().id(1L).name("Tag").build()))
                .tagsLocked(false)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .publishedDateLocked(false)
                .descriptionLocked(false)
                .seriesNameLocked(false)
                .seriesNumberLocked(false)
                .seriesTotalLocked(false)
                .isbn13Locked(false)
                .isbn10Locked(false)
                .asinLocked(false)
                .goodreadsIdLocked(false)
                .comicvineIdLocked(false)
                .hardcoverIdLocked(false)
                .hardcoverBookIdLocked(false)
                .googleIdLocked(false)
                .pageCountLocked(false)
                .languageLocked(false)
                .amazonRatingLocked(false)
                .amazonReviewCountLocked(false)
                .goodreadsRatingLocked(false)
                .goodreadsReviewCountLocked(false)
                .hardcoverRatingLocked(false)
                .hardcoverReviewCountLocked(false)
                .coverLocked(false)
                .reviewsLocked(false)
                .build();

        BookMetadata testNew = BookMetadata.builder()
                .bookId(1L)
                .title("Test Title")
                .authors(null) // null, what we're testing
                .authorsLocked(false)
                .categories(Set.of("Fiction"))  // Match existing
                .categoriesLocked(false)
                .moods(Set.of("Dark"))          // Match existing
                .moodsLocked(false)
                .tags(Set.of("Tag"))            // Match existing
                .tagsLocked(false)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .publishedDateLocked(false)
                .descriptionLocked(false)
                .seriesNameLocked(false)
                .seriesNumberLocked(false)
                .seriesTotalLocked(false)
                .isbn13Locked(false)
                .isbn10Locked(false)
                .asinLocked(false)
                .goodreadsIdLocked(false)
                .comicvineIdLocked(false)
                .hardcoverIdLocked(false)
                .hardcoverBookIdLocked(false)
                .googleIdLocked(false)
                .pageCountLocked(false)
                .languageLocked(false)
                .amazonRatingLocked(false)
                .amazonReviewCountLocked(false)
                .goodreadsRatingLocked(false)
                .goodreadsReviewCountLocked(false)
                .hardcoverRatingLocked(false)
                .hardcoverReviewCountLocked(false)
                .coverLocked(false)
                .reviewsLocked(false)
                .build();
        boolean result = MetadataChangeDetector.isDifferent(testNew, testExisting, clearFlags);
        assertTrue(result, "Should return true for empty collection to null transition");
    }

    @Test
    @DisplayName("Locked field blocks value changes but still detects lock state changes")
    void testIsDifferent_whenFieldLockedAndValueDifferent_stillDetectsLockChange() {
        existingMeta.setTitleLocked(true);
        newMeta.setTitle("New Title"); // value change (should be ignored due to lock)
        newMeta.setTitleLocked(false); // lock change (should be detected)

        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should detect lock change even if value change is blocked by lock");

        boolean valueResult = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(valueResult, "Should not detect value change when field is locked");
    }

    @Test
    void testIsDifferent_whenFieldLockedAndOnlyValueDifferent_returnsFalse() {
        existingMeta.setTitleLocked(true);
        newMeta.setTitle("New Title");
        newMeta.setTitleLocked(true); // lock unchanged

        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should not detect value change when field is locked and lock is unchanged");
    }

    @Test
    void testClearFlag_whenFieldUnlocked_triggersChange() {
        BookMetadataEntity testExisting = BookMetadataEntity.builder()
                .bookId(1L)
                .title("Existing Title")
                .titleLocked(false) // field is unlocked
                .build();
        BookMetadata testNew = BookMetadata.builder()
                .bookId(1L)
                .title("Existing Title") // same title
                .titleLocked(false)
                .build();
        MetadataClearFlags testClearFlags = new MetadataClearFlags();
        testClearFlags.setTitle(true); // clear flag forces change detection

        boolean result = MetadataChangeDetector.hasValueChanges(testNew, testExisting, testClearFlags);
        assertTrue(result, "Clear flag should trigger change when field is unlocked, even with identical values");
    }

    @Test
    @DisplayName("Lock takes precedence over clear flag")
    void testHasValueChanges_whenClearFlagTrue_lockStillTakesPrecedence() {
        existingMeta.setTitleLocked(true);
        newMeta.setTitle("New Title");
        clearFlags.setTitle(true);

        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Lock should take precedence over clear flag");
    }

    @Test
    void testClearFlag_whenExistingValueIsNull_returnsFalse() {
        existingMeta.setTitle(null);
        existingMeta.setTitleLocked(false);
        newMeta.setTitle("New Title");
        clearFlags.setTitle(true);

        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Clear flag with null existing value means nothing to clear");
    }

    @Test
    void testAuthorsComparison_isOrderSensitive() {
        newMeta.setAuthors(List.of("Author Two", "Author One"));

        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Authors comparison should be order sensitive");
    }

    @Test
    void testIsDifferent_whenClearFlagTrue_andValuesIdentical_returnsTrue() {
        // newMeta and existingMeta have identical title values
        clearFlags.setTitle(true); // but clear flag forces change detection

        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "isDifferent should return true when clear flag is set, even if values are identical");
    }

    @Test
    @DisplayName("hasValueChangesForFileWrite() includes booklore-namespace fields that are written to files")
    void testHasValueChangesForFileWrite_includesPageCount() {
        newMeta.setPageCount(999);
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "PageCount should trigger file write");
    }

    @Test
    void testHasValueChangesForFileWrite_includesMoods() {
        newMeta.setMoods(Set.of("New Mood"));
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Moods should trigger file write");
    }

    @Test
    void testHasValueChangesForFileWrite_includesTags() {
        newMeta.setTags(Set.of("New Tag"));
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Tags should trigger file write");
    }

    @Test
    void testHasValueChangesForFileWrite_includesRatingFields() {
        newMeta.setAmazonRating(4.9);
        newMeta.setGoodreadsRating(4.8);
        newMeta.setHardcoverRating(4.7);
        newMeta.setAmazonReviewCount(2000);
        newMeta.setGoodreadsReviewCount(35000);
        newMeta.setHardcoverReviewCount(800);
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Rating and review count fields should trigger file write");
    }

    @Test
    void testHasValueChangesForFileWrite_excludesAudiobookFields() {
        newMeta.setAudibleRating(4.6);
        newMeta.setAudibleReviewCount(3000);
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Audiobook-only fields should not trigger file write");
    }

    @Test
    void testHasValueChangesForFileWrite_includesAuthors() {
        newMeta.setAuthors(List.of("New Author"));
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Authors change should trigger file write");
    }

    @Test
    void testHasValueChangesForFileWrite_includesCategories() {
        newMeta.setCategories(Set.of("New Category"));
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Categories change should trigger file write");
    }

    @Test
    void testIsDifferent_whenBothSubtitleValuesNull_returnsFalse() {
        existingMeta.setSubtitle(null);
        newMeta.setSubtitle(null);

        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when both values are null");
    }

    @Test
    void testIsDifferent_whenMultipleFieldsChange_returnsTrue() {
        newMeta.setTitle("New Title");
        newMeta.setSubtitle("New Subtitle");
        newMeta.setPublisher("New Publisher");
        newMeta.setPageCount(999);

        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when multiple fields change simultaneously");
    }

    @Test
    void testEdgeCase_whitespaceNormalization() {
        existingMeta.setTitle("  Original Title  ");
        newMeta.setTitle("Original Title");
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when strings are equal after whitespace normalization");
    }

    @Test
    void testIsDifferent_whenAuthorsOrderChanges_returnsTrue() {
        newMeta.setAuthors(List.of("Author Two", "Author One"));
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when author order changes");
    }

    @Test
    void testIsDifferent_whenAuthorsSetContentChanges_returnsTrue() {
        newMeta.setAuthors(List.of("Author One", "Author Three")); // Different author
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when author set content changes");
    }

    @Test
    void testIsDifferent_whenCategoriesSetChanges_returnsTrue() {
        newMeta.setCategories(Set.of("Fiction", "New Category"));
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when categories change");
    }

    @Test
    void testIsDifferent_whenMoodsSetChanges_returnsTrue() {
        newMeta.setMoods(Set.of("Dark", "New Mood"));
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when moods change");
    }

    @Test
    void testIsDifferent_whenTagsSetChanges_returnsTrue() {
        newMeta.setTags(Set.of("Thriller", "New Tag"));
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when tags change");
    }

    @Test
    void testHasValueChanges_whenAuthorsOrderChanges_returnsTrue() {
        newMeta.setAuthors(List.of("Author Two", "Author One"));
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when author order changes");
    }

    @Test
    void testHasValueChanges_whenMultipleCollectionsChange_returnsTrue() {
        newMeta.setAuthors(List.of("New Author"));
        newMeta.setCategories(Set.of("New Category"));
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when multiple collections change");
    }

    @Test
    void testHasValueChanges_whenEmptyStringToNull_returnsTrue() {
        existingMeta.setTitle("");
        newMeta.setTitle(null);
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true for empty string to null transition");
    }

    @Test
    void testHasValueChanges_whenNullToEmptyString_returnsFalse() {
        existingMeta.setTitle(null);
        newMeta.setTitle("");
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false for null to empty string transition");
    }

    @Test
    void testHasValueChanges_whenEmptySetToNull_returnsTrue() {
        BookMetadataEntity testExisting = BookMetadataEntity.builder()
                .bookId(1L)
                .title("Test")
                .authors(List.of())
                .authorsLocked(false)
                .categories(Set.of())
                .categoriesLocked(false)
                .moods(Set.of())
                .moodsLocked(false)
                .tags(Set.of())
                .tagsLocked(false)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .publishedDateLocked(false)
                .descriptionLocked(false)
                .seriesNameLocked(false)
                .seriesNumberLocked(false)
                .seriesTotalLocked(false)
                .isbn13Locked(false)
                .isbn10Locked(false)
                .asinLocked(false)
                .goodreadsIdLocked(false)
                .comicvineIdLocked(false)
                .hardcoverIdLocked(false)
                .hardcoverBookIdLocked(false)
                .googleIdLocked(false)
                .pageCountLocked(false)
                .languageLocked(false)
                .amazonRatingLocked(false)
                .amazonReviewCountLocked(false)
                .goodreadsRatingLocked(false)
                .goodreadsReviewCountLocked(false)
                .hardcoverRatingLocked(false)
                .hardcoverReviewCountLocked(false)
                .coverLocked(false)
                .reviewsLocked(false)
                .build();
        BookMetadata testNew = BookMetadata.builder()
                .bookId(1L)
                .title("Test")
                .authors(null)
                .authorsLocked(false)
                .categories(Set.of())
                .categoriesLocked(false)
                .moods(Set.of())
                .moodsLocked(false)
                .tags(Set.of())
                .tagsLocked(false)
                .titleLocked(false)
                .subtitleLocked(false)
                .publisherLocked(false)
                .publishedDateLocked(false)
                .descriptionLocked(false)
                .seriesNameLocked(false)
                .seriesNumberLocked(false)
                .seriesTotalLocked(false)
                .isbn13Locked(false)
                .isbn10Locked(false)
                .asinLocked(false)
                .goodreadsIdLocked(false)
                .comicvineIdLocked(false)
                .hardcoverIdLocked(false)
                .hardcoverBookIdLocked(false)
                .googleIdLocked(false)
                .pageCountLocked(false)
                .languageLocked(false)
                .amazonRatingLocked(false)
                .amazonReviewCountLocked(false)
                .goodreadsRatingLocked(false)
                .goodreadsReviewCountLocked(false)
                .hardcoverRatingLocked(false)
                .hardcoverReviewCountLocked(false)
                .coverLocked(false)
                .reviewsLocked(false)
                .build();
        boolean result = MetadataChangeDetector.hasValueChanges(testNew, testExisting, clearFlags);
        assertTrue(result, "Should return true for empty set to null transition");
    }

    @Test
    void testHasValueChangesForFileWrite_whenTitleAndPageCountChange_onlyTitleTriggers() {
        newMeta.setTitle("New Title");
        newMeta.setPageCount(999);
        boolean result = MetadataChangeDetector.hasValueChangesForFileWrite(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when title changes, even if pageCount also changes");
    }

    @Test
    void testIsDifferent_whenOnlyLockChanges_noValueChange_returnsTrue() {
        newMeta.setTitleLocked(true);
        // Title value is same, only lock changes
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when only lock changes, even if value is same");
    }

    @Test
    void testIsDifferent_whenCoverLockUnchanged_returnsFalse() {
        existingMeta.setCoverLocked(true);
        newMeta.setCoverLocked(true);
        boolean result = MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when cover lock unchanged");
    }

    @Test
    void testHasValueChanges_whenStringWithWhitespaceChanges_returnsTrue() {
        existingMeta.setTitle("Original Title");
        newMeta.setTitle("  New Title  ");
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when normalized strings differ");
    }

    @Test
    void testHasValueChanges_whenStringWhitespaceOnly_returnsFalse() {
        existingMeta.setTitle("Original Title");
        newMeta.setTitle("  Original Title  "); // Same after normalization
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when strings are same after normalization");
    }

    @Test
    void testHasValueChanges_whenFloatSeriesNumberChanges_returnsTrue() {
        newMeta.setSeriesNumber(2.5f);
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertTrue(result, "Should return true when series number changes");
    }

    @Test
    void testHasValueChanges_whenFloatSeriesNumberUnchanged_returnsFalse() {
        boolean result = MetadataChangeDetector.hasValueChanges(newMeta, existingMeta, clearFlags);
        assertFalse(result, "Should return false when series number unchanged");
    }

    @Nested
    @DisplayName("hasLockChanges()")
    class HasLockChangesTests {

        @Test
        void noChanges_returnsFalse() {
            assertFalse(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return false when no lock differs");
        }

        @Test
        void simpleFieldLockChanges_returnsTrue() {
            newMeta.setTitleLocked(true);
            assertTrue(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return true when a simple field lock changes");
        }

        @Test
        void collectionFieldLockChanges_returnsTrue() {
            newMeta.setAuthorsLocked(true);
            assertTrue(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return true when a collection field lock changes");
        }

        @Test
        void coverLockChanges_returnsTrue() {
            newMeta.setCoverLocked(true);
            assertTrue(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return true when cover lock changes");
        }

        @Test
        void audiobookCoverLockChanges_returnsTrue() {
            newMeta.setAudiobookCoverLocked(true);
            assertTrue(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return true when audiobook cover lock changes");
        }

        @Test
        void reviewsLockChanges_returnsTrue() {
            newMeta.setReviewsLocked(true);
            assertTrue(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return true when reviews lock changes");
        }
    }

    @Nested
    @DisplayName("Comic metadata value comparison")
    class ComicMetadataValueTests {

        private ComicMetadata comicDto;
        private ComicMetadataEntity comicEntity;

        @BeforeEach
        void setupComic() {
            comicDto = baselineComicDto();
            comicEntity = baselineComicEntity();
            newMeta.setComicMetadata(comicDto);
            existingMeta.setComicMetadata(comicEntity);
        }

        @Test
        void matchingComicMetadata_returnsFalse() {
            assertFalse(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return false when comic metadata matches");
        }

        @Test
        void bothSidesHaveNoComicMetadata_returnsFalse() {
            newMeta.setComicMetadata(null);
            existingMeta.setComicMetadata(null);
            assertFalse(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return false when neither side has comic metadata");
        }

        @Test
        void entityHasNoComicMetadata_dtoHasNonEmptyValue_returnsTrue() {
            existingMeta.setComicMetadata(null);
            assertTrue(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return true when only the DTO carries comic data");
        }

        @Test
        void entityHasNoComicMetadata_dtoEmpty_returnsFalse() {
            existingMeta.setComicMetadata(null);
            newMeta.setComicMetadata(ComicMetadata.builder().build());
            assertFalse(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return false when the DTO's comic metadata is entirely empty");
        }

        @ParameterizedTest(name = "comic {0} differs")
        @MethodSource("org.booklore.util.MetadataChangeDetectorTest#comicFieldChangeProvider")
        void singleFieldDiffers_returnsTrue(String field, Consumer<ComicMetadata> modifier) {
            modifier.accept(comicDto);
            assertTrue(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should detect comic change in " + field);
        }

        @Test
        void creatorsPresentOnDtoOnly_returnsTrue() {
            comicDto.setPencillers(Set.of("Penciller One"));
            assertTrue(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return true when only the DTO lists creators");
        }

        @Test
        void creatorsMatchInCount_returnsFalse() {
            comicDto.setPencillers(Set.of("Penciller One"));
            comicEntity.setCreatorMappings(Set.of(creatorMapping(comicEntity, "Penciller One", ComicCreatorRole.PENCILLER)));
            assertFalse(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return false when creator counts match on both sides");
        }

        @Test
        void creatorsDifferInCount_returnsTrue() {
            comicDto.setPencillers(Set.of("P1", "P2"));
            comicEntity.setCreatorMappings(Set.of(creatorMapping(comicEntity, "P1", ComicCreatorRole.PENCILLER)));
            assertTrue(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return true when creator counts differ");
        }

        @Test
        void bothSidesHaveNoCreators_returnsFalse() {
            assertFalse(MetadataChangeDetector.isDifferent(newMeta, existingMeta, clearFlags),
                    "Should return false when neither side lists creators");
        }
    }

    static Stream<Arguments> comicFieldChangeProvider() {
        return Stream.of(
                Arguments.of("issueNumber", (Consumer<ComicMetadata>) m -> m.setIssueNumber("2")),
                Arguments.of("volumeName", (Consumer<ComicMetadata>) m -> m.setVolumeName("Different Volume")),
                Arguments.of("volumeNumber", (Consumer<ComicMetadata>) m -> m.setVolumeNumber(99)),
                Arguments.of("storyArc", (Consumer<ComicMetadata>) m -> m.setStoryArc("Different Arc")),
                Arguments.of("storyArcNumber", (Consumer<ComicMetadata>) m -> m.setStoryArcNumber(99)),
                Arguments.of("alternateSeries", (Consumer<ComicMetadata>) m -> m.setAlternateSeries("Different Series")),
                Arguments.of("alternateIssue", (Consumer<ComicMetadata>) m -> m.setAlternateIssue("Different Issue")),
                Arguments.of("imprint", (Consumer<ComicMetadata>) m -> m.setImprint("Different Imprint")),
                Arguments.of("format", (Consumer<ComicMetadata>) m -> m.setFormat("Different Format")),
                Arguments.of("blackAndWhite", (Consumer<ComicMetadata>) m -> m.setBlackAndWhite(true)),
                Arguments.of("manga", (Consumer<ComicMetadata>) m -> m.setManga(true)),
                Arguments.of("readingDirection", (Consumer<ComicMetadata>) m -> m.setReadingDirection("rtl")),
                Arguments.of("webLink", (Consumer<ComicMetadata>) m -> m.setWebLink("https://different.example")),
                Arguments.of("notes", (Consumer<ComicMetadata>) m -> m.setNotes("Different notes")),
                Arguments.of("characters", (Consumer<ComicMetadata>) m -> m.setCharacters(Set.of("Villain"))),
                Arguments.of("teams", (Consumer<ComicMetadata>) m -> m.setTeams(Set.of("Other Team"))),
                Arguments.of("locations", (Consumer<ComicMetadata>) m -> m.setLocations(Set.of("Other City")))
        );
    }

    @Nested
    @DisplayName("Comic metadata lock comparison")
    class ComicMetadataLockTests {

        private ComicMetadata comicDto;
        private ComicMetadataEntity comicEntity;

        @BeforeEach
        void setupComic() {
            comicDto = baselineComicDto();
            comicEntity = baselineComicEntity();
            newMeta.setComicMetadata(comicDto);
            existingMeta.setComicMetadata(comicEntity);
        }

        @Test
        void matchingLocks_returnsFalse() {
            assertFalse(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return false when comic locks match");
        }

        @Test
        void dtoHasNoComicMetadata_returnsFalse() {
            newMeta.setComicMetadata(null);
            assertFalse(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return false when the DTO has no comic metadata to compare locks against");
        }

        @Test
        void entityHasNoComicMetadata_returnsFalse() {
            existingMeta.setComicMetadata(null);
            assertFalse(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should return false when the entity has no comic metadata to compare locks against");
        }

        @ParameterizedTest(name = "comic {0} differs")
        @MethodSource("org.booklore.util.MetadataChangeDetectorTest#comicLockChangeProvider")
        void singleLockDiffers_returnsTrue(String field, Consumer<ComicMetadata> modifier) {
            modifier.accept(comicDto);
            assertTrue(MetadataChangeDetector.hasLockChanges(newMeta, existingMeta),
                    "Should detect comic lock change in " + field);
        }
    }

    static Stream<Arguments> comicLockChangeProvider() {
        return Stream.of(
                Arguments.of("issueNumberLocked", (Consumer<ComicMetadata>) m -> m.setIssueNumberLocked(true)),
                Arguments.of("volumeNameLocked", (Consumer<ComicMetadata>) m -> m.setVolumeNameLocked(true)),
                Arguments.of("volumeNumberLocked", (Consumer<ComicMetadata>) m -> m.setVolumeNumberLocked(true)),
                Arguments.of("storyArcLocked", (Consumer<ComicMetadata>) m -> m.setStoryArcLocked(true)),
                Arguments.of("storyArcNumberLocked", (Consumer<ComicMetadata>) m -> m.setStoryArcNumberLocked(true)),
                Arguments.of("alternateSeriesLocked", (Consumer<ComicMetadata>) m -> m.setAlternateSeriesLocked(true)),
                Arguments.of("alternateIssueLocked", (Consumer<ComicMetadata>) m -> m.setAlternateIssueLocked(true)),
                Arguments.of("imprintLocked", (Consumer<ComicMetadata>) m -> m.setImprintLocked(true)),
                Arguments.of("formatLocked", (Consumer<ComicMetadata>) m -> m.setFormatLocked(true)),
                Arguments.of("blackAndWhiteLocked", (Consumer<ComicMetadata>) m -> m.setBlackAndWhiteLocked(true)),
                Arguments.of("mangaLocked", (Consumer<ComicMetadata>) m -> m.setMangaLocked(true)),
                Arguments.of("readingDirectionLocked", (Consumer<ComicMetadata>) m -> m.setReadingDirectionLocked(true)),
                Arguments.of("webLinkLocked", (Consumer<ComicMetadata>) m -> m.setWebLinkLocked(true)),
                Arguments.of("notesLocked", (Consumer<ComicMetadata>) m -> m.setNotesLocked(true)),
                Arguments.of("creatorsLocked", (Consumer<ComicMetadata>) m -> m.setCreatorsLocked(true)),
                Arguments.of("pencillersLocked", (Consumer<ComicMetadata>) m -> m.setPencillersLocked(true)),
                Arguments.of("inkersLocked", (Consumer<ComicMetadata>) m -> m.setInkersLocked(true)),
                Arguments.of("coloristsLocked", (Consumer<ComicMetadata>) m -> m.setColoristsLocked(true)),
                Arguments.of("letterersLocked", (Consumer<ComicMetadata>) m -> m.setLetterersLocked(true)),
                Arguments.of("coverArtistsLocked", (Consumer<ComicMetadata>) m -> m.setCoverArtistsLocked(true)),
                Arguments.of("editorsLocked", (Consumer<ComicMetadata>) m -> m.setEditorsLocked(true)),
                Arguments.of("charactersLocked", (Consumer<ComicMetadata>) m -> m.setCharactersLocked(true)),
                Arguments.of("teamsLocked", (Consumer<ComicMetadata>) m -> m.setTeamsLocked(true)),
                Arguments.of("locationsLocked", (Consumer<ComicMetadata>) m -> m.setLocationsLocked(true))
        );
    }

    private static ComicMetadata baselineComicDto() {
        return ComicMetadata.builder()
                .issueNumber("1")
                .volumeName("Volume One")
                .volumeNumber(1)
                .storyArc("Arc One")
                .storyArcNumber(1)
                .alternateSeries("Alt Series")
                .alternateIssue("Alt Issue")
                .imprint("Imprint")
                .format("Format")
                .blackAndWhite(false)
                .manga(false)
                .readingDirection("ltr")
                .webLink("http://example.com")
                .notes("Some notes")
                .characters(Set.of("Hero"))
                .teams(Set.of("Team"))
                .locations(Set.of("City"))
                .build();
    }

    private static ComicMetadataEntity baselineComicEntity() {
        return ComicMetadataEntity.builder()
                .issueNumber("1")
                .volumeName("Volume One")
                .volumeNumber(1)
                .storyArc("Arc One")
                .storyArcNumber(1)
                .alternateSeries("Alt Series")
                .alternateIssue("Alt Issue")
                .imprint("Imprint")
                .format("Format")
                .blackAndWhite(false)
                .manga(false)
                .readingDirection("ltr")
                .webLink("http://example.com")
                .notes("Some notes")
                .characters(Set.of(ComicCharacterEntity.builder().id(1L).name("Hero").build()))
                .teams(Set.of(ComicTeamEntity.builder().id(1L).name("Team").build()))
                .locations(Set.of(ComicLocationEntity.builder().id(1L).name("City").build()))
                .build();
    }

    private static ComicCreatorMappingEntity creatorMapping(ComicMetadataEntity owner, String creatorName, ComicCreatorRole role) {
        return ComicCreatorMappingEntity.builder()
                .id(1L)
                .comicMetadata(owner)
                .creator(ComicCreatorEntity.builder().id(1L).name(creatorName).build())
                .role(role)
                .build();
    }
}
