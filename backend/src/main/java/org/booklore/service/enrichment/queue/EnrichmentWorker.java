package org.booklore.service.enrichment.queue;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.EnrichmentQueueEntity;
import org.booklore.model.enums.EnrichmentQueueStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.EnrichmentQueueRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.enrichment.EnrichmentOutcome;
import org.booklore.service.enrichment.EnrichmentPipeline;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drains the enrichment queue, one batch at a time.
 * <p>
 * Single-threaded by design. The expensive steps are already rate-limited at the source — the agent
 * by quota, the providers by their own scraping etiquette — so widening this loop would not make a
 * run finish sooner, it would only make it likelier to get an instance blocked. What makes a
 * library-wide pass tolerable is that it is resumable, not that it is fast.
 * <p>
 * A {@code @Scheduled} poll rather than a {@code Task}: the DB-driven cron machinery in this project
 * schedules user-visible jobs, and this is a background loop with no schedule to configure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentWorker {

    private static final int BATCH_SIZE = 5;
    private static final int MAX_ATTEMPTS = 3;

    private final EnrichmentQueueRepository queueRepository;
    private final EnrichmentQueueTransitions transitions;
    private final EnrichmentQueueService queueService;
    private final EnrichmentPipeline pipeline;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    private final AtomicBoolean draining = new AtomicBoolean(false);

    /**
     * Rows left RUNNING by a process that died would otherwise block their books forever: the unique
     * index on outstanding work means one stuck row keeps its book out of the queue for good.
     */
    @PostConstruct
    public void reclaimStaleWork() {
        int reclaimed = queueRepository.requeueStaleRunning();
        if (reclaimed > 0) {
            log.info("Re-queued {} enrichment rows left running by a previous process", reclaimed);
        }
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void drain() {
        // A batch of provider calls can outlast the poll interval; overlapping runs would claim the
        // same rows twice.
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            List<EnrichmentQueueEntity> batch = queueRepository.findNextBatch(PageRequest.of(0, BATCH_SIZE));
            batch.forEach(this::process);
        } catch (Exception e) {
            log.error("Enrichment queue drain failed", e);
        } finally {
            draining.set(false);
        }
    }

    private void process(EnrichmentQueueEntity queued) {
        EnrichmentQueueEntity row = transitions.claim(queued.getId()).orElse(null);
        if (row == null) {
            return;
        }
        try {
            EnrichmentOutcome outcome = pipeline.enrich(row.getBookId(), toRequest(row));
            transitions.complete(row.getId(),
                    outcome.changedAnything() ? EnrichmentQueueStatus.DONE : EnrichmentQueueStatus.SKIPPED);
            report(row, outcome);
        } catch (Exception e) {
            log.warn("Enrichment failed for book {}: {}", row.getBookId(), e.getMessage());
            transitions.fail(row.getId(), e.getMessage(), MAX_ATTEMPTS);
        }
    }

    /**
     * Progress goes to the user who asked, by name.
     * {@code NotificationService.sendMessage} resolves the <em>current</em> authenticated user and
     * silently drops the message when there is none — which, on this thread, is always.
     */
    private void report(EnrichmentQueueEntity row, EnrichmentOutcome outcome) {
        if (row.getRequestedByUserId() == null) {
            return;
        }
        userRepository.findById(row.getRequestedByUserId()).ifPresent(user -> {
            EnrichmentQueueService.JobProgress progress = queueService.progress(row.getJobId());
            notificationService.sendMessageToUser(user.getUsername(), Topic.ENRICHMENT_PROGRESS,
                    EnrichmentProgressEvent.builder()
                            .jobId(row.getJobId())
                            .bookId(row.getBookId())
                            .total(progress.total())
                            .completed(progress.done() + progress.skipped())
                            .outstanding(progress.outstanding())
                            .finished(progress.isFinished())
                            .bookChanged(outcome.changedAnything())
                            .notes(outcome.getNotes())
                            .build());
        });
    }

    private EnrichmentRequest toRequest(EnrichmentQueueEntity row) {
        Set<org.booklore.model.enums.EnrichmentStepType> steps = EnrichmentQueueService.decodeSteps(row.getSteps());
        return EnrichmentRequest.builder()
                .scope(EnrichmentRequest.Scope.BOOK)
                .bookIds(Set.of(row.getBookId()))
                .steps(steps)
                .writePolicy(row.getWritePolicy())
                .agentAllowed(row.isAgentAllowed())
                .build();
    }
}
