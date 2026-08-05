package org.booklore.service.enrichment.catalog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The container-keyed source types — {@code REVIEW} and {@code AUTHOR_BIO} — file the same key under
 * several containers, which used to abort the whole rebuild on the unique index. The shipped catalog
 * has 78,646 review keys in more than one monthly archive and 296 author keys in more than one bucket,
 * so this is the normal case rather than an edge case.
 */
class LocalCatalogIndexBuilderContainerTest {

    private static final String SHARED_REVIEW_KEY = "shared.zip#work.fb2";
    private static final String SOLO_REVIEW_KEY = "solo.zip#only.fb2";
    private static final String SHARED_AUTHOR_KEY = "0bdb39734094b5713ba682148a0cda76";
    private static final String SOLO_AUTHOR_KEY = "a28a7c6a6fd35a46c7c7e1957da5c21e";

    @TempDir
    Path catalogRoot;

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);
    private final LocalCatalogIndexRepository indexRepository = mock(LocalCatalogIndexRepository.class);
    private final ArchiveService archiveService = mock(ArchiveService.class);

    /**
     * What {@code saveAll} was actually invoked with, snapshotted at call time rather than captured by
     * reference: {@link LocalCatalogIndexBuilder} reuses and clears its batch buffer immediately after
     * handing it to the repository, so a plain {@code ArgumentCaptor} would only ever observe the
     * buffer's post-clear (empty) state.
     */
    private final List<LocalCatalogIndexEntity> savedRows = new ArrayList<>();

    private LocalCatalogIndexBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        Files.write(catalogRoot.resolve("annotations.7z"), new byte[]{1});
        Path reviews = Files.createDirectory(catalogRoot.resolve("reviews"));
        Path authors = Files.createDirectory(catalogRoot.resolve("authors"));
        for (String month : List.of("200801.7z", "200902.7z", "201003.7z")) {
            Files.write(reviews.resolve(month), new byte[]{1});
        }
        for (String bucket : List.of("1.7z", "2.7z")) {
            Files.write(authors.resolve(bucket), new byte[]{1});
        }

        LibraryEntity library = new LibraryEntity();
        library.setMetadataSidecarPath(catalogRoot.toString());
        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));

        // The shared key is reviewed in all three months; the solo key only in the first. Distinct
        // container names make an implementation that keeps the wrong one, or loses one, fail here.
        when(archiveService.getEntryNames(reviews.resolve("200801.7z")))
                .thenReturn(List.of(SHARED_REVIEW_KEY, SOLO_REVIEW_KEY));
        when(archiveService.getEntryNames(reviews.resolve("200902.7z")))
                .thenReturn(List.of(SHARED_REVIEW_KEY));
        when(archiveService.getEntryNames(reviews.resolve("201003.7z")))
                .thenReturn(List.of(SHARED_REVIEW_KEY));
        when(archiveService.getEntryNames(authors.resolve("1.7z")))
                .thenReturn(List.of(SHARED_AUTHOR_KEY, SOLO_AUTHOR_KEY));
        when(archiveService.getEntryNames(authors.resolve("2.7z")))
                .thenReturn(List.of(SHARED_AUTHOR_KEY));

        when(indexRepository.<LocalCatalogIndexEntity>saveAll(any()))
                .thenAnswer(invocation -> {
                    List<LocalCatalogIndexEntity> batch = invocation.getArgument(0);
                    savedRows.addAll(batch);
                    return List.copyOf(batch);
                });

        builder = new LocalCatalogIndexBuilder(libraryRepository, indexRepository,
                new FlibustaCatalogLayout(), new FlibustaCompilationParser(new ObjectMapper()),
                new FlibustaContentsParser(), archiveService, new ObjectMapper());
    }

    private List<LocalCatalogIndexEntity> rowsOfType(LocalCatalogSourceType type) {
        return savedRows.stream()
                .filter(row -> row.getSourceType() == type)
                .toList();
    }

    private List<String> containersOf(LocalCatalogSourceType type, String entryKey) {
        LocalCatalogIndexEntity row = rowsOfType(type).stream()
                .filter(candidate -> candidate.getEntryKey().equals(entryKey))
                .findFirst()
                .orElseThrow();
        return new ObjectMapper().readValue(row.getPayload(), new TypeReference<List<String>>() {
        });
    }

    @Nested
    class Reviews {

        /**
         * The previously fatal case. Monthly review archives are increments, not snapshots — measured
         * against the shipped catalog, consecutive archives holding the same key are fully disjoint and
         * every review's timestamp falls inside its own archive's month — so keeping only the last one
         * would discard the earlier months' reviews rather than superseding them.
         */
        @Test
        void aBookReviewedInThreeMonthsProducesOneRowListingEveryContainer() {
            builder.rebuild(7L);

            assertThat(rowsOfType(LocalCatalogSourceType.REVIEW))
                    .extracting(LocalCatalogIndexEntity::getEntryKey)
                    .containsExactlyInAnyOrder(SHARED_REVIEW_KEY, SOLO_REVIEW_KEY);
            assertThat(containersOf(LocalCatalogSourceType.REVIEW, SHARED_REVIEW_KEY))
                    .containsExactly("200801.7z", "200902.7z", "201003.7z");
        }

        @Test
        void aBookReviewedInOneMonthKeepsThatOneContainer() {
            builder.rebuild(7L);

            assertThat(containersOf(LocalCatalogSourceType.REVIEW, SOLO_REVIEW_KEY))
                    .containsExactly("200801.7z");
        }

        @Test
        void rebuildOverRepeatingReviewKeysCompletesInsteadOfAborting() {
            LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

            assertThat(result.reviews()).isEqualTo(2);
            assertThat(rowsOfType(LocalCatalogSourceType.REVIEW))
                    .extracting(LocalCatalogIndexEntity::getEntryKey)
                    .containsOnlyOnce(SHARED_REVIEW_KEY);
        }

        @Test
        void clearsPreviousReviewRowsBeforeIndexing() {
            builder.rebuild(7L);

            verify(indexRepository).deleteByLibraryIdAndSourceType(7L, LocalCatalogSourceType.REVIEW);
        }
    }

    @Nested
    class AuthorBiographies {

        /**
         * Author buckets are numbered, not dated, so there is no "later" bucket to prefer — and 286 of
         * the shipped catalog's 296 duplicated keys hold genuinely different documents. Both buckets are
         * therefore recorded and the read side decides.
         */
        @Test
        void anAuthorKeyInTwoBucketsProducesOneRowListingBothBuckets() {
            builder.rebuild(7L);

            assertThat(rowsOfType(LocalCatalogSourceType.AUTHOR_BIO))
                    .extracting(LocalCatalogIndexEntity::getEntryKey)
                    .containsExactlyInAnyOrder(SHARED_AUTHOR_KEY, SOLO_AUTHOR_KEY);
            assertThat(containersOf(LocalCatalogSourceType.AUTHOR_BIO, SHARED_AUTHOR_KEY))
                    .containsExactly("1.7z", "2.7z");
        }

        @Test
        void anAuthorKeyInOneBucketKeepsThatOneBucket() {
            builder.rebuild(7L);

            assertThat(containersOf(LocalCatalogSourceType.AUTHOR_BIO, SOLO_AUTHOR_KEY))
                    .containsExactly("1.7z");
        }

        @Test
        void rebuildOverRepeatingAuthorKeysCompletesInsteadOfAborting() {
            LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

            assertThat(result.authorBios()).isEqualTo(2);
            assertThat(rowsOfType(LocalCatalogSourceType.AUTHOR_BIO))
                    .extracting(LocalCatalogIndexEntity::getEntryKey)
                    .containsOnlyOnce(SHARED_AUTHOR_KEY);
        }
    }

    /**
     * The accumulator that collapses duplicate keys must be local to one pass. A field would carry the
     * first run's keys into the second and grow the index on every rebuild.
     */
    @Test
    void rebuildingTwiceLeavesTheSameRowsNotDuplicates() {
        builder.rebuild(7L);
        List<String> firstRunKeys = rowsOfType(LocalCatalogSourceType.REVIEW).stream()
                .map(LocalCatalogIndexEntity::getEntryKey)
                .toList();
        savedRows.clear();

        builder.rebuild(7L);

        assertThat(rowsOfType(LocalCatalogSourceType.REVIEW))
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactlyInAnyOrderElementsOf(firstRunKeys);
        assertThat(containersOf(LocalCatalogSourceType.REVIEW, SHARED_REVIEW_KEY))
                .containsExactly("200801.7z", "200902.7z", "201003.7z");
        verify(indexRepository, times(2))
                .deleteByLibraryIdAndSourceType(7L, LocalCatalogSourceType.REVIEW);
    }

    /**
     * An index that ends up empty for a source type makes every enrichment step reading it a silent
     * no-op, which is how an entire measurement gate was spent before anyone noticed. This catalog has
     * no {@code compilations.7z} and no {@code contents.7z}, so those two pass zero rows while reviews
     * and biographies pass rows — the warning has to fire for the former and stay quiet for the latter.
     */
    @Nested
    class EmptySourceTypeWarning {

        private Logger logger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            logger = (Logger) LoggerFactory.getLogger(LocalCatalogIndexBuilder.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
            appender.stop();
        }

        @Test
        void warnsForEverySourceTypeThatIndexedNothing() {
            builder.rebuild(7L);

            assertThat(warningsMentioning(LocalCatalogSourceType.COMPILATION)).isNotEmpty();
            assertThat(warningsMentioning(LocalCatalogSourceType.COMPILATION_PART)).isNotEmpty();
            assertThat(warningsMentioning(LocalCatalogSourceType.LANGUAGE)).isNotEmpty();
        }

        @Test
        void staysQuietForSourceTypesThatIndexedRows() {
            builder.rebuild(7L);

            assertThat(warningsMentioning(LocalCatalogSourceType.REVIEW)).isEmpty();
            assertThat(warningsMentioning(LocalCatalogSourceType.AUTHOR_BIO)).isEmpty();
        }

        /**
         * Warnings naming exactly this source type. {@code COMPILATION_PART} contains
         * {@code COMPILATION}, so a plain {@code contains} would let one warning satisfy an assertion
         * about the other.
         */
        private List<String> warningsMentioning(LocalCatalogSourceType type) {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.matches(".*\\b" + type.name() + "\\b.*"))
                    .toList();
        }
    }
}
