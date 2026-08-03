package org.booklore.repository;

import org.booklore.model.entity.EnrichmentQueueEntity;
import org.booklore.model.enums.EnrichmentQueueStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EnrichmentQueueRepository extends JpaRepository<EnrichmentQueueEntity, Long> {

    @Query("""
            SELECT q FROM EnrichmentQueueEntity q
            WHERE q.status = org.booklore.model.enums.EnrichmentQueueStatus.QUEUED
            ORDER BY q.priority DESC, q.requestedAt ASC
            """)
    List<EnrichmentQueueEntity> findNextBatch(Pageable pageable);

    @Query("""
            SELECT q FROM EnrichmentQueueEntity q
            WHERE q.bookId = :bookId AND q.status IN :statuses
            """)
    Optional<EnrichmentQueueEntity> findOutstandingForBook(@Param("bookId") Long bookId,
                                                           @Param("statuses") Collection<EnrichmentQueueStatus> statuses);

    List<EnrichmentQueueEntity> findByJobId(String jobId);

    long countByJobIdAndStatus(String jobId, EnrichmentQueueStatus status);

    long countByJobId(String jobId);

    long countByStatus(EnrichmentQueueStatus status);

    /**
     * The timestamp is a parameter rather than {@code CURRENT_TIMESTAMP}: Hibernate types the latter
     * as {@code java.sql.Timestamp}, which it will not assign to an {@code Instant} column.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE EnrichmentQueueEntity q
            SET q.status = org.booklore.model.enums.EnrichmentQueueStatus.CANCELLED, q.finishedAt = :finishedAt
            WHERE q.jobId = :jobId AND q.status = org.booklore.model.enums.EnrichmentQueueStatus.QUEUED
            """)
    int cancelQueued(@Param("jobId") String jobId, @Param("finishedAt") Instant finishedAt);

    /**
     * Rows left RUNNING by a process that died. Reclaimed on startup rather than left to block their
     * books forever — the unique index on outstanding work means a stuck row keeps its book out of
     * the queue for good.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE EnrichmentQueueEntity q
            SET q.status = org.booklore.model.enums.EnrichmentQueueStatus.QUEUED, q.startedAt = NULL
            WHERE q.status = org.booklore.model.enums.EnrichmentQueueStatus.RUNNING
            """)
    int requeueStaleRunning();
}
