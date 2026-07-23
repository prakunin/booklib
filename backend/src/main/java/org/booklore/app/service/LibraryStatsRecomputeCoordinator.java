package org.booklore.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes materialized-statistics recomputes so the scheduled sweep and event-triggered recomputes
 * cannot delete-and-reinsert the same rows concurrently. A recompute already running for a scope
 * coalesces later requests for that same scope (the in-flight run produces equally fresh data), so a
 * burst of catalog changes collapses into one recompute rather than piling up.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryStatsRecomputeCoordinator {

    private final AppBookService appBookService;
    private final ConcurrentHashMap<Long, ReentrantLock> libraryLocks = new ConcurrentHashMap<>();
    private final ReentrantLock catalogLock = new ReentrantLock();

    /**
     * Recomputes one library's statistics under its per-library lock. Returns {@code false} without
     * recomputing when another recompute for the same library is already in progress.
     */
    public boolean recomputeLibrary(Long libraryId) {
        ReentrantLock lock = libraryLocks.computeIfAbsent(libraryId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("Skipping stats recompute for library {}: already in progress", libraryId);
            return false;
        }
        try {
            appBookService.recomputeLibraryStats(libraryId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Recomputes the whole-catalog statistics under the catalog lock. Returns {@code false} without
     * recomputing when a catalog recompute is already in progress.
     */
    public boolean recomputeCatalog() {
        if (!catalogLock.tryLock()) {
            log.debug("Skipping catalog stats recompute: already in progress");
            return false;
        }
        try {
            appBookService.recomputeCatalogStats();
            return true;
        } finally {
            catalogLock.unlock();
        }
    }

    /**
     * Recomputes the given library and then the whole-catalog scope, invalidating the statistics
     * caches afterwards. Used by the event-triggered path after a scan/import affecting one library.
     */
    public void recomputeAfterChange(Long libraryId) {
        boolean changed = recomputeLibrary(libraryId);
        recomputeCatalog();
        if (changed) {
            appBookService.invalidateStatsCaches();
        }
    }
}
