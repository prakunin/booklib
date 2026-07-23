package org.booklore.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.service.event.LibraryScanCompletedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Recomputes a library's materialized statistics right after its scan or import completes, so the
 * statistics screen reflects freshly imported books without waiting for the hourly sweep. Runs
 * asynchronously after commit; the coordinator coalesces overlapping recomputes and the hourly sweep
 * remains the safety net if this recompute fails.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class LibraryStatsScanListener {

    private final LibraryStatsRecomputeCoordinator statsRecomputeCoordinator;

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void handle(LibraryScanCompletedEvent event) {
        try {
            statsRecomputeCoordinator.recomputeAfterChange(event.libraryId());
        } catch (Exception e) {
            log.error("Failed to recompute statistics after scan of library {}", event.libraryId(), e);
        }
    }
}
