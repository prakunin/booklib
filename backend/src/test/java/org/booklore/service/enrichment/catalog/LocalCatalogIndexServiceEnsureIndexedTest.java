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

    /**
     * The blocking form the backfill uses. The distinction from {@code ensureIndexed} is the whole
     * point of it existing: {@code ensureIndexed} hands the 2m20s rebuild to the executor and returns,
     * which is right for {@code EnrichmentPipeline} (no user action may block behind it) and wrong for
     * a 702,511-book walk with no checkpoint.
     */
    @Nested
    class EnsureIndexedNow {

        @Test
        void buildsTheIndexOnTheCallingThreadRatherThanTheExecutor() {
            when(indexBuilder.isIndexed(LIBRARY_ID)).thenReturn(false);
            when(indexBuilder.rebuild(LIBRARY_ID)).thenReturn(new LocalCatalogIndexBuilder.IndexResult(1, 1, 1, 1, 1));

            boolean ready = service.ensureIndexedNow(LIBRARY_ID);

            assertThat(ready).isTrue();
            assertThat(submitted).isEmpty();
            verify(indexBuilder).rebuild(LIBRARY_ID);
        }

        @Test
        void buildsNothingWhenTheCatalogIsAlreadyIndexed() {
            when(indexBuilder.isIndexed(LIBRARY_ID)).thenReturn(true);

            boolean ready = service.ensureIndexedNow(LIBRARY_ID);

            assertThat(ready).isTrue();
            verify(indexBuilder, never()).rebuild(LIBRARY_ID);
        }

        /**
         * A rebuild started elsewhere holds the per-library slot, so this call builds nothing and must
         * say so. Returning true here would let the backfill walk against somebody else's half-written
         * index, which is the failure the blocking form exists to prevent.
         */
        @Test
        void reportsNotReadyWhenARebuildIsAlreadyInFlight() {
            when(indexBuilder.isIndexed(LIBRARY_ID)).thenReturn(false);
            service.rebuildAsync(LIBRARY_ID);

            boolean ready = service.ensureIndexedNow(LIBRARY_ID);

            assertThat(ready).isFalse();
            verify(indexBuilder, never()).rebuild(LIBRARY_ID);
        }
    }
}
