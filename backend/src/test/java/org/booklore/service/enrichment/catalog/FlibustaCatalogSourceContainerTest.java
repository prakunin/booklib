package org.booklore.service.enrichment.catalog;

import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The read half of the container-keyed source types. An index row now names every container a key was
 * seen in, and what to do with that list differs per source type: monthly review archives are
 * increments and must all be read, while author buckets that disagree describe different people and
 * must not be guessed between.
 */
class FlibustaCatalogSourceContainerTest {

    private static final long LIBRARY = 7L;
    private static final String ARCHIVE = "shared.zip";
    private static final String ENTRY = "work.fb2";
    private static final String REVIEW_KEY = ARCHIVE + "#" + ENTRY;

    @TempDir
    Path catalogRoot;

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);
    private final LocalCatalogIndexRepository indexRepository = mock(LocalCatalogIndexRepository.class);
    private final ArchiveService archiveService = mock(ArchiveService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path reviews;
    private Path authors;
    private FlibustaCatalogSource source;

    @BeforeEach
    void setUp() throws Exception {
        Files.write(catalogRoot.resolve("annotations.7z"), new byte[]{1});
        reviews = Files.createDirectory(catalogRoot.resolve("reviews"));
        authors = Files.createDirectory(catalogRoot.resolve("authors"));
        for (String month : List.of("200801.7z", "201003.7z")) {
            Files.write(reviews.resolve(month), new byte[]{1});
        }
        for (String bucket : List.of("1.7z", "2.7z")) {
            Files.write(authors.resolve(bucket), new byte[]{1});
        }

        LibraryEntity library = new LibraryEntity();
        library.setMetadataSidecarPath(catalogRoot.toString());
        when(libraryRepository.findById(LIBRARY)).thenReturn(Optional.of(library));

        source = new FlibustaCatalogSource(libraryRepository, indexRepository, new FlibustaCatalogLayout(),
                new FlibustaAnnotationParser(), new FlibustaReviewParser(objectMapper), archiveService,
                objectMapper);
    }

    private void indexed(LocalCatalogSourceType type, String key, String... containers) {
        when(indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(LIBRARY, type, key))
                .thenReturn(Optional.of(LocalCatalogIndexEntity.builder()
                        .libraryId(LIBRARY)
                        .sourceType(type)
                        .entryKey(key)
                        .payload(objectMapper.writeValueAsString(List.of(containers)))
                        .build()));
    }

    @Nested
    class Reviews {

        /**
         * Distinct reviewer names and distinct months, so an implementation that reads only the first or
         * only the last container loses a review and fails here rather than passing by coincidence.
         */
        @Test
        void readsEveryMonthlyArchiveTheKeyWasSeenIn() throws Exception {
            indexed(LocalCatalogSourceType.REVIEW, REVIEW_KEY, "200801.7z", "201003.7z");
            when(archiveService.getEntryBytes(reviews.resolve("200801.7z"), REVIEW_KEY))
                    .thenReturn("""
                            [{"name": "earliest", "text": "read it in 2008", "time": "2008-01-05 10:00:00"}]
                            """.getBytes(StandardCharsets.UTF_8));
            when(archiveService.getEntryBytes(reviews.resolve("201003.7z"), REVIEW_KEY))
                    .thenReturn("""
                            [{"name": "latest", "text": "read it in 2010", "time": "2010-03-05 10:00:00"}]
                            """.getBytes(StandardCharsets.UTF_8));

            List<CatalogReview> found = source.lookupReviews(LIBRARY, ARCHIVE, ENTRY);

            assertThat(found).extracting(CatalogReview::reviewerName)
                    .containsExactly("earliest", "latest");
        }

        @Test
        void skipsAnArchiveThatNoLongerHoldsTheKey() throws Exception {
            indexed(LocalCatalogSourceType.REVIEW, REVIEW_KEY, "200801.7z", "201003.7z");
            when(archiveService.getEntryBytes(reviews.resolve("200801.7z"), REVIEW_KEY))
                    .thenThrow(new java.io.IOException("no such entry"));
            when(archiveService.getEntryBytes(reviews.resolve("201003.7z"), REVIEW_KEY))
                    .thenReturn("""
                            [{"name": "latest", "text": "read it in 2010", "time": "2010-03-05 10:00:00"}]
                            """.getBytes(StandardCharsets.UTF_8));

            assertThat(source.lookupReviews(LIBRARY, ARCHIVE, ENTRY))
                    .extracting(CatalogReview::reviewerName)
                    .containsExactly("latest");
        }

        /**
         * The disjointness of monthly archives is measured, not guaranteed: 40 of the 78,646 duplicated
         * keys were sampled. If any pair does overlap, the concatenation would hand the same review to
         * the caller twice — and the very next thing that runs is a gate measuring whether a second
         * backfill duplicates reviews, which would then blame the wrong code. Reviews are identified by
         * reviewer, timestamp and text, so a repeat collapses.
         */
        @Test
        void doesNotReturnTheSameReviewTwiceWhenTwoArchivesOverlap() throws Exception {
            indexed(LocalCatalogSourceType.REVIEW, REVIEW_KEY, "200801.7z", "201003.7z");
            String shared = """
                    {"name": "shared", "text": "carried over", "time": "2008-01-05 10:00:00"}""";
            when(archiveService.getEntryBytes(reviews.resolve("200801.7z"), REVIEW_KEY))
                    .thenReturn(("[" + shared + "]").getBytes(StandardCharsets.UTF_8));
            when(archiveService.getEntryBytes(reviews.resolve("201003.7z"), REVIEW_KEY))
                    .thenReturn(("[" + shared + """
                            , {"name": "later", "text": "read it in 2010", "time": "2010-03-05 10:00:00"}]""")
                            .getBytes(StandardCharsets.UTF_8));

            List<CatalogReview> found = source.lookupReviews(LIBRARY, ARCHIVE, ENTRY);

            assertThat(found).extracting(CatalogReview::reviewerName).containsExactly("shared", "later");
        }

        /**
         * Two different people posting the same words at different times, or the same person twice in a
         * month, are distinct reviews and must both survive — the dedup keys on identity, not on text.
         */
        @Test
        void keepsReviewsThatShareTextButDifferInReviewerOrTime() throws Exception {
            indexed(LocalCatalogSourceType.REVIEW, REVIEW_KEY, "200801.7z");
            when(archiveService.getEntryBytes(reviews.resolve("200801.7z"), REVIEW_KEY))
                    .thenReturn("""
                            [{"name": "anna", "text": "excellent", "time": "2008-01-05 10:00:00"},
                             {"name": "boris", "text": "excellent", "time": "2008-01-05 10:00:00"},
                             {"name": "anna", "text": "excellent", "time": "2008-01-19 22:00:00"}]
                            """.getBytes(StandardCharsets.UTF_8));

            assertThat(source.lookupReviews(LIBRARY, ARCHIVE, ENTRY)).hasSize(3);
        }

        @Test
        void returnsNothingForAKeyThatIsNotIndexed() {
            when(indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(
                    LIBRARY, LocalCatalogSourceType.REVIEW, REVIEW_KEY)).thenReturn(Optional.empty());

            assertThat(source.lookupReviews(LIBRARY, ARCHIVE, ENTRY)).isEmpty();
        }
    }

    @Nested
    class AuthorBiographies {

        private static final String AUTHOR = "Джин Стоун";

        private String authorKey() {
            return FlibustaAuthorKey.of(AUTHOR);
        }

        @Test
        void returnsTheBiographyWhenOnlyOneBucketHoldsTheKey() throws Exception {
            indexed(LocalCatalogSourceType.AUTHOR_BIO, authorKey(), "1.7z");
            when(archiveService.getEntryBytes(authors.resolve("1.7z"), authorKey()))
                    .thenReturn("American romance novelist".getBytes(StandardCharsets.UTF_8));

            assertThat(source.lookupAuthorBio(LIBRARY, AUTHOR)).contains("American romance novelist");
        }

        /**
         * The shipped catalog's {@code Джин Стоун} key names two different people — Jean Stone the
         * romance novelist and Gene Stone the editor — and the buckets are numbered, not dated, so there
         * is no defensible way to pick. Attaching either one is worse than attaching none.
         */
        @Test
        void returnsNothingWhenTwoBucketsHoldDifferentBiographies() throws Exception {
            indexed(LocalCatalogSourceType.AUTHOR_BIO, authorKey(), "1.7z", "2.7z");
            when(archiveService.getEntryBytes(authors.resolve("1.7z"), authorKey()))
                    .thenReturn("Jean Stone, American romance novelist".getBytes(StandardCharsets.UTF_8));
            when(archiveService.getEntryBytes(authors.resolve("2.7z"), authorKey()))
                    .thenReturn("Gene Stone, American book editor".getBytes(StandardCharsets.UTF_8));

            assertThat(source.lookupAuthorBio(LIBRARY, AUTHOR)).isEmpty();
        }

        @Test
        void returnsTheBiographyWhenBothBucketsAgree() throws Exception {
            indexed(LocalCatalogSourceType.AUTHOR_BIO, authorKey(), "1.7z", "2.7z");
            when(archiveService.getEntryBytes(authors.resolve("1.7z"), authorKey()))
                    .thenReturn("Jean Stone, American romance novelist".getBytes(StandardCharsets.UTF_8));
            when(archiveService.getEntryBytes(authors.resolve("2.7z"), authorKey()))
                    .thenReturn("Jean Stone, American romance novelist".getBytes(StandardCharsets.UTF_8));

            assertThat(source.lookupAuthorBio(LIBRARY, AUTHOR))
                    .contains("Jean Stone, American romance novelist");
        }

        /**
         * A bucket that has lost the entry must not count as a second, disagreeing biography.
         */
        @Test
        void ignoresABucketWithNoEntryForTheKey() throws Exception {
            indexed(LocalCatalogSourceType.AUTHOR_BIO, authorKey(), "1.7z", "2.7z");
            when(archiveService.getEntryBytes(authors.resolve("1.7z"), authorKey()))
                    .thenReturn("Jean Stone, American romance novelist".getBytes(StandardCharsets.UTF_8));
            when(archiveService.getEntryBytes(authors.resolve("2.7z"), authorKey()))
                    .thenReturn(new byte[0]);

            assertThat(source.lookupAuthorBio(LIBRARY, AUTHOR))
                    .contains("Jean Stone, American romance novelist");
        }

        @Test
        void returnsNothingForAnAuthorThatIsNotIndexed() {
            when(indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(
                    LIBRARY, LocalCatalogSourceType.AUTHOR_BIO, authorKey())).thenReturn(Optional.empty());

            assertThat(source.lookupAuthorBio(LIBRARY, AUTHOR)).isEmpty();
        }
    }
}
