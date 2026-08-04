package org.booklore.service.enrichment.catalog;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ensureIndexed} exists so that a hot-path caller which does not know the state can ask —
 * {@code EnrichmentPipeline.enrich} calls it for every book, backfill or queue-driven alike. What
 * makes that safe is that it re-asks the database every time, so an index that disappears (a
 * rebuild that failed part-way, a manual truncate, a library repointed at a different catalog) is
 * noticed on the very next book rather than never.
 * <p>
 * These tests are here to keep it that way. Making {@code ensureIndexed} cheap by remembering the
 * answer would turn "the index vanished" into "enrichment silently writes nothing for every book,
 * forever" — the exact failure shape that hid on this branch for eleven tasks. The cheap version
 * shipped instead keeps the per-book question and only changes the question from
 * {@code COUNT(*)} to {@code EXISTS}, and {@link #asksAgainOnceTheIndexIsEmptied()} is the test
 * that fails the moment somebody memoises it.
 */
class LocalCatalogIndexServiceEnsureIndexedTest {

    private static final long LIBRARY_ID = 19L;

    private final LocalCatalogIndexBuilder indexBuilder = mock(LocalCatalogIndexBuilder.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private final Executor taskExecutor = submitted::add;

    private final LocalCatalogIndexService service = new LocalCatalogIndexService(indexBuilder, taskExecutor);

    @Nested
    class WhenTheCatalogHasNeverBeenIndexed {

        @Test
        void startsARebuild() {
            when(indexBuilder.isIndexed(LIBRARY_ID)).thenReturn(false);

            service.ensureIndexed(LIBRARY_ID);

            assertThat(submitted).hasSize(1);
            assertThat(service.isRunning(LIBRARY_ID)).isTrue();
        }

        @Test
        void doesNotStartASecondRebuildWhileTheFirstIsStillRunning() {
            when(indexBuilder.isIndexed(LIBRARY_ID)).thenReturn(false);

            service.ensureIndexed(LIBRARY_ID);
            service.ensureIndexed(LIBRARY_ID);

            assertThat(submitted).hasSize(1);
        }
    }

    @Nested
    class WhenTheCatalogIsAlreadyIndexed {

        @Test
        void startsNothing() {
            when(indexBuilder.isIndexed(LIBRARY_ID)).thenReturn(true);

            service.ensureIndexed(LIBRARY_ID);

            assertThat(submitted).isEmpty();
            verify(indexBuilder, never()).rebuild(LIBRARY_ID);
        }

        /**
         * The staleness bound, stated as a test: the answer is worth exactly one book. Nothing is
         * remembered between calls, so the longest an emptied index can go unnoticed is the single
         * book whose {@code ensureIndexed} ran before it was emptied.
         */
        @Test
        void asksAgainOnceTheIndexIsEmptied() {
            when(indexBuilder.isIndexed(LIBRARY_ID)).thenReturn(true, true, false);

            service.ensureIndexed(LIBRARY_ID);
            service.ensureIndexed(LIBRARY_ID);
            assertThat(submitted).isEmpty();

            service.ensureIndexed(LIBRARY_ID);

            assertThat(submitted).hasSize(1);
            verify(indexBuilder, times(3)).isIndexed(LIBRARY_ID);
        }
    }
}
