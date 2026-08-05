package org.booklore.service.enrichment.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Runs local-catalog indexing off the request path and keeps one run per library at a time.
 * <p>
 * The pass walks 300-odd archives and writes hundreds of thousands of rows, so it is never done
 * inside a request, and a second concurrent run for the same library would only fight the first one
 * over the same rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCatalogIndexService {

    private final LocalCatalogIndexBuilder indexBuilder;
    private final Executor taskExecutor;

    private final Map<Long, Boolean> running = new ConcurrentHashMap<>();

    /**
     * @return false when indexing for this library is already under way
     */
    public boolean rebuildAsync(long libraryId) {
        if (running.putIfAbsent(libraryId, Boolean.TRUE) != null) {
            log.info("Local catalog indexing for library {} is already running", libraryId);
            return false;
        }
        taskExecutor.execute(() -> {
            try {
                indexBuilder.rebuild(libraryId);
            } catch (Exception e) {
                log.error("Local catalog indexing failed for library {}", libraryId, e);
            } finally {
                running.remove(libraryId);
            }
        });
        return true;
    }

    public boolean isRunning(long libraryId) {
        return running.containsKey(libraryId);
    }

    /**
     * Starts indexing only when the library has a catalog that has never been indexed, so callers on
     * a hot path (library save, first enrichment run) can ask without having to know the state.
     * <p>
     * This returns as soon as the rebuild has been <em>handed to</em> the executor — it deliberately
     * does not wait for it. That is what its callers need: {@code EnrichmentPipeline} asks at the top
     * of every book's enrichment, including the queue-driven per-book path, and no user action may
     * block behind a 2m20s walk of 300-odd archives. A caller that cannot proceed against a
     * half-built index wants {@link #ensureIndexedNow} instead.
     */
    public void ensureIndexed(long libraryId) {
        if (indexBuilder.isIndexed(libraryId) || isRunning(libraryId)) {
            return;
        }
        rebuildAsync(libraryId);
    }

    /**
     * The blocking counterpart of {@link #ensureIndexed}, for the one caller that must not run
     * against an index that is still being written: the backfill.
     * <p>
     * The backfill walks every archived book of the library once and has no checkpoint, so a book it
     * walks while the index is half-built finds nothing, is indistinguishable from a book the catalog
     * genuinely has nothing for, and is never revisited — a completed run is never automatically run
     * again, and {@code AUTO_IF_EMPTY} leaves no trace that anything was missed. Waiting 2m20s once,
     * at the start of a multi-hour run, costs nothing by comparison.
     * <p>
     * The running check comes <strong>before</strong> the {@code isIndexed} check, and the order is
     * load-bearing. {@link LocalCatalogIndexBuilder#isIndexed} is satisfied by {@code REVIEW} rows
     * alone, and {@link LocalCatalogIndexBuilder#rebuild} writes {@code REVIEW} first — so from the
     * moment a rebuild started elsewhere flushes its first {@code REVIEW} batch, "some rows exist"
     * answers yes while {@code AUTHOR_BIO}, {@code COMPILATION} and {@code LANGUAGE} are still absent
     * or mid-{@code deleteByLibraryIdAndSourceType}. Asking {@code isIndexed} first would hand the
     * walk exactly the partial index this method exists to refuse. Concurrent rebuilds are not
     * hypothetical here: {@code EnrichmentPipeline} calls {@link #ensureIndexed} at the top of every
     * book on the queue-driven path, which the INPX import budget now reliably feeds, and
     * {@code EnrichmentController} exposes a manual rebuild.
     * <p>
     * {@link #rebuildNow}'s own empty result stays as the second line of defence, for a rebuild that
     * starts between this check and that call.
     *
     * @return true when the index is ready to be read; false when a rebuild started elsewhere is
     * already in flight, in which case this call did not build anything and the caller must not
     * proceed — walking against the other run's partial index is the very failure this exists to
     * prevent, and there is no safe point at which to join a build already under way.
     */
    public boolean ensureIndexedNow(long libraryId) {
        if (isRunning(libraryId)) {
            return false;
        }
        if (indexBuilder.isIndexed(libraryId)) {
            return true;
        }
        return rebuildNow(libraryId).isPresent();
    }

    public Optional<LocalCatalogIndexBuilder.IndexResult> rebuildNow(long libraryId) {
        if (running.putIfAbsent(libraryId, Boolean.TRUE) != null) {
            return Optional.empty();
        }
        try {
            return Optional.of(indexBuilder.rebuild(libraryId));
        } finally {
            running.remove(libraryId);
        }
    }
}
