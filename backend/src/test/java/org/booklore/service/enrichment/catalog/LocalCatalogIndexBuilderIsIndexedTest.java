package org.booklore.service.enrichment.catalog;

import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code isIndexed} is asked once per book — {@code EnrichmentPipeline.enrich} calls
 * {@code ensureIndexed} at the top of every book's enrichment — so how it asks is a throughput
 * contract, not an implementation detail.
 * <p>
 * Measured on the 1,031,476-row {@code local_catalog_index} of library 19:
 * <pre>
 * SELECT count(*) … WHERE library_id=19 AND source_type='REVIEW'   41.6 / 40.0 / 45.8 ms
 * SELECT 1        … WHERE library_id=19 AND source_type='REVIEW' LIMIT 1   0.25 / 0.12 / 0.11 ms
 * </pre>
 * Both use the same {@code uk_local_catalog_index} index and both are covering; the difference is
 * that {@code COUNT(*)} must walk every matching index entry while {@code LIMIT 1} stops at the
 * first. A count is therefore the wrong question to ask when the answer wanted is "is there any
 * row at all", and asking it cost 45.4 ms × 14,003 books = 57.7% of a measured backfill's wall
 * clock.
 * <p>
 * Readiness is represented by one marker written only after every source pass completes. That both
 * upgrades legacy indexes and prevents committed partial rows from looking ready.
 */
class LocalCatalogIndexBuilderIsIndexedTest {

    private static final long LIBRARY_ID = 19L;

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);
    private final LocalCatalogIndexRepository indexRepository = mock(LocalCatalogIndexRepository.class);
    private final FlibustaCatalogLayout layout = mock(FlibustaCatalogLayout.class);
    private final FlibustaCompilationParser compilationParser = mock(FlibustaCompilationParser.class);
    private final FlibustaContentsParser contentsParser = mock(FlibustaContentsParser.class);
    private final ArchiveService archiveService = mock(ArchiveService.class);

    private final LocalCatalogIndexBuilder builder = new LocalCatalogIndexBuilder(
            libraryRepository, indexRepository, layout, compilationParser, contentsParser,
            archiveService, new ObjectMapper());

    private void hasRows(LocalCatalogSourceType sourceType) {
        if (sourceType == LocalCatalogSourceType.INDEX_VERSION) {
            when(indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(
                    LIBRARY_ID, sourceType, LocalCatalogIndexBuilder.INDEX_VERSION_KEY))
                    .thenReturn(Optional.of(LocalCatalogIndexEntity.builder().payload("2").build()));
        } else {
            when(indexRepository.existsByLibraryIdAndSourceType(LIBRARY_ID, sourceType)).thenReturn(true);
        }
    }

    @Nested
    class WhatDecidesTheAnswer {

        @Test
        void theCurrentVersionMarkerMakesTheLibraryIndexed() {
            hasRows(LocalCatalogSourceType.INDEX_VERSION);

            assertThat(builder.isIndexed(LIBRARY_ID)).isTrue();
        }

        @Test
        void legacyRowsWithoutTheMarkerRequireARebuild() {
            hasRows(LocalCatalogSourceType.REVIEW);
            hasRows(LocalCatalogSourceType.AUTHOR_BIO);
            hasRows(LocalCatalogSourceType.LANGUAGE);

            assertThat(builder.isIndexed(LIBRARY_ID)).isFalse();
        }

        @Test
        void anEmptyIndexIsNotIndexed() {
            assertThat(builder.isIndexed(LIBRARY_ID)).isFalse();
        }

        @Test
        void aMarkerWithTheWrongPayloadIsNotIndexed() {
            when(indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(
                    LIBRARY_ID, LocalCatalogSourceType.INDEX_VERSION,
                    LocalCatalogIndexBuilder.INDEX_VERSION_KEY))
                    .thenReturn(Optional.of(LocalCatalogIndexEntity.builder().payload("corrupt").build()));

            assertThat(builder.isIndexed(LIBRARY_ID)).isFalse();
        }

        @Test
        void partialRowsAreNotEvidenceOfACompletedCatalog() {
            hasRows(LocalCatalogSourceType.REVIEW);
            hasRows(LocalCatalogSourceType.COMPILATION);
            hasRows(LocalCatalogSourceType.COMPILATION_PART);

            assertThat(builder.isIndexed(LIBRARY_ID)).isFalse();
        }
    }

    @Nested
    class HowItAsks {

        @Test
        void neverCountsRowsToAnswerAYesNoQuestion() {
            hasRows(LocalCatalogSourceType.INDEX_VERSION);

            builder.isIndexed(LIBRARY_ID);

            verify(indexRepository, never()).countByLibraryIdAndSourceType(anyLong(), any());
        }

        @Test
        void asksOnlyForTheCompletionMarker() {
            hasRows(LocalCatalogSourceType.INDEX_VERSION);

            builder.isIndexed(LIBRARY_ID);

            verify(indexRepository).findByLibraryIdAndSourceTypeAndEntryKey(
                    LIBRARY_ID, LocalCatalogSourceType.INDEX_VERSION,
                    LocalCatalogIndexBuilder.INDEX_VERSION_KEY);
            verify(indexRepository, never()).existsByLibraryIdAndSourceType(anyLong(), any());
        }
    }
}
