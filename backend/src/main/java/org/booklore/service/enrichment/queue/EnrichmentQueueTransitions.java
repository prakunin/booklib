package org.booklore.service.enrichment.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.EnrichmentQueueEntity;
import org.booklore.model.enums.EnrichmentQueueStatus;
import org.booklore.repository.EnrichmentQueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Status changes for queue rows, in their own bean.
 * <p>
 * Not an arbitrary split: {@code @Transactional} is applied by a proxy, so a worker calling its own
 * annotated method would run it with no transaction at all and claim rows outside one — the exact
 * shape of bug that only appears under concurrency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentQueueTransitions {

    private final EnrichmentQueueRepository queueRepository;

    /**
     * Moves a row to RUNNING, but only from QUEUED, so two drains racing over the same row leave
     * exactly one winner.
     *
     * @return the claimed row, or empty when someone else took it
     */
    @Transactional
    public Optional<EnrichmentQueueEntity> claim(long rowId) {
        EnrichmentQueueEntity row = queueRepository.findById(rowId).orElse(null);
        if (row == null || row.getStatus() != EnrichmentQueueStatus.QUEUED) {
            return Optional.empty();
        }
        row.setStatus(EnrichmentQueueStatus.RUNNING);
        row.setStartedAt(Instant.now());
        row.setAttempts(row.getAttempts() + 1);
        return Optional.of(queueRepository.save(row));
    }

    @Transactional
    public void complete(long rowId, EnrichmentQueueStatus status) {
        queueRepository.findById(rowId).ifPresent(row -> {
            row.setStatus(status);
            row.setLastError(null);
            row.setFinishedAt(Instant.now());
            queueRepository.save(row);
        });
    }

    /**
     * A transient failure — a scraper timing out, the database briefly unavailable — is worth
     * retrying; a book that fails {@code maxAttempts} times is failing for a reason retrying will
     * not fix.
     */
    @Transactional
    public void fail(long rowId, String error, int maxAttempts) {
        queueRepository.findById(rowId).ifPresent(row -> {
            boolean exhausted = row.getAttempts() >= maxAttempts;
            row.setStatus(exhausted ? EnrichmentQueueStatus.FAILED : EnrichmentQueueStatus.QUEUED);
            row.setLastError(truncate(error));
            row.setFinishedAt(exhausted ? Instant.now() : null);
            row.setStartedAt(null);
            queueRepository.save(row);
        });
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
