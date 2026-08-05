package org.booklore.service.enrichment.catalog;

import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code isIndexed} is asked once per book — {@code EnrichmentPipeline.enrich} calls
 * {@code ensureIndexed} at the top of every book's enrichment — so how it asks is a throughput
 * contract, not an implementation detail.
 * <p>
 * Measured on the 1,031,476-row {@code local_catalog_index} of library 19, where the first source
 * type it asks about (REVIEW) has 176,334 rows:
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
 * These tests pin the outcome (which source types decide the answer, and the short-circuit) plus
 * the one thing that is genuinely about which query is issued, because "does not count a
 * million rows to answer a yes/no question" is the contract this change exists to establish.
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
        when(indexRepository.existsByLibraryIdAndSourceType(LIBRARY_ID, sourceType)).thenReturn(true);
    }

    @Nested
    class WhatDecidesTheAnswer {

        @Test
        void reviewRowsAloneMakeTheLibraryIndexed() {
            hasRows(LocalCatalogSourceType.REVIEW);

            assertThat(builder.isIndexed(LIBRARY_ID)).isTrue();
        }

        @Test
        void authorBiographyRowsAloneMakeTheLibraryIndexed() {
            hasRows(LocalCatalogSourceType.AUTHOR_BIO);

            assertThat(builder.isIndexed(LIBRARY_ID)).isTrue();
        }

        @Test
        void languageRowsAloneMakeTheLibraryIndexed() {
            hasRows(LocalCatalogSourceType.LANGUAGE);

            assertThat(builder.isIndexed(LIBRARY_ID)).isTrue();
        }

        @Test
        void anEmptyIndexIsNotIndexed() {
            assertThat(builder.isIndexed(LIBRARY_ID)).isFalse();
        }

        /**
         * Compilations are derived from a single JSON document that indexes even when the archive
         * walk found nothing, so they are deliberately not evidence that the catalog was walked.
         */
        @Test
        void compilationRowsAloneAreNotEvidenceOfAWalkedCatalog() {
            hasRows(LocalCatalogSourceType.COMPILATION);
            hasRows(LocalCatalogSourceType.COMPILATION_PART);

            assertThat(builder.isIndexed(LIBRARY_ID)).isFalse();
        }
    }

    @Nested
    class HowItAsks {

        @Test
        void neverCountsRowsToAnswerAYesNoQuestion() {
            hasRows(LocalCatalogSourceType.LANGUAGE);

            builder.isIndexed(LIBRARY_ID);

            verify(indexRepository, never()).countByLibraryIdAndSourceType(anyLong(), any());
        }

        @Test
        void stopsAtTheFirstSourceTypeThatHasRows() {
            hasRows(LocalCatalogSourceType.REVIEW);

            builder.isIndexed(LIBRARY_ID);

            verify(indexRepository).existsByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.REVIEW);
            verify(indexRepository, never())
                    .existsByLibraryIdAndSourceType(eq(LIBRARY_ID), eq(LocalCatalogSourceType.AUTHOR_BIO));
            verify(indexRepository, never())
                    .existsByLibraryIdAndSourceType(eq(LIBRARY_ID), eq(LocalCatalogSourceType.LANGUAGE));
        }

        @Test
        void asksAtMostOncePerSourceTypeWhenNothingIsIndexed() {
            builder.isIndexed(LIBRARY_ID);

            verify(indexRepository).existsByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.REVIEW);
            verify(indexRepository).existsByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.AUTHOR_BIO);
            verify(indexRepository).existsByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.LANGUAGE);
        }
    }
}
