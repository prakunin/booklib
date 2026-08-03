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
     */
    public void ensureIndexed(long libraryId) {
        if (indexBuilder.isIndexed(libraryId) || isRunning(libraryId)) {
            return;
        }
        rebuildAsync(libraryId);
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
