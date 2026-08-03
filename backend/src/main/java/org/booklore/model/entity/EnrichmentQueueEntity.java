package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.EnrichmentQueueStatus;
import org.booklore.model.enums.EnrichmentWritePolicy;

import java.time.Instant;

/**
 * One book waiting to be enriched.
 *
 * @see org.booklore.service.enrichment.queue.EnrichmentQueueService
 */
@Entity
@Table(name = "enrichment_queue")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentQueueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Groups the rows one request produced, so progress and cancellation address them together. */
    @Column(name = "job_id", nullable = false, length = 100)
    private String jobId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** Comma-separated step names; null means every step the request allows. */
    @Column(name = "steps", length = 255)
    private String steps;

    @Column(name = "agent_allowed", nullable = false)
    private boolean agentAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "write_policy", nullable = false, length = 20)
    private EnrichmentWritePolicy writePolicy;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EnrichmentQueueStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /**
     * Who asked. Progress goes to this user specifically: the worker runs in the background, where
     * {@code NotificationService.sendMessage} finds no authenticated user and silently drops.
     */
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
