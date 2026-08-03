package org.booklore.service.enrichment.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.EnrichmentQueueEntity;
import org.booklore.model.enums.EnrichmentQueueStatus;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.EnrichmentQueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns an enrichment request into queued work.
 * <p>
 * Everything asynchronous goes through here, so the rules about what may be queued — one outstanding
 * row per book, priority by how much a user is waiting on it — hold no matter which surface asked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentQueueService {

    private final EnrichmentQueueRepository queueRepository;
    private final BookRepository bookRepository;
    private final AuthenticationService authenticationService;

    /**
     * @return the job id the queued rows share, used to follow or cancel them together
     */
    @Transactional
    public String enqueue(EnrichmentRequest request, int priority) {
        Set<Long> bookIds = resolveBookIds(request);
        if (bookIds.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Nothing to enrich");
        }
        String jobId = UUID.randomUUID().toString();
        Long userId = currentUserId();
        int queued = 0;
        for (Long bookId : bookIds) {
            if (enqueueBook(jobId, bookId, request, priority, userId)) {
                queued++;
            }
        }
        log.info("Enrichment job {} queued {} of {} books at priority {}", jobId, queued, bookIds.size(), priority);
        return jobId;
    }

    /**
     * Re-queueing a book that is already waiting raises its priority instead of adding a second
     * identical unit of work — which is what happens when a user presses the button on a book a
     * library sweep queued an hour ago and has not reached yet.
     */
    private boolean enqueueBook(String jobId, Long bookId, EnrichmentRequest request, int priority, Long userId) {
        Optional<EnrichmentQueueEntity> outstanding = queueRepository.findOutstandingForBook(
                bookId, EnumSet.of(EnrichmentQueueStatus.QUEUED, EnrichmentQueueStatus.RUNNING));
        if (outstanding.isPresent()) {
            EnrichmentQueueEntity existing = outstanding.get();
            if (existing.getStatus() == EnrichmentQueueStatus.QUEUED && existing.getPriority() < priority) {
                existing.setPriority(priority);
                existing.setAgentAllowed(existing.isAgentAllowed() || request.isAgentAllowed());
                existing.setRequestedByUserId(userId);
                queueRepository.save(existing);
            }
            return false;
        }
        queueRepository.save(EnrichmentQueueEntity.builder()
                .jobId(jobId)
                .bookId(bookId)
                .steps(encodeSteps(request.getSteps()))
                .agentAllowed(request.isAgentAllowed())
                .writePolicy(request.getWritePolicy())
                .priority(priority)
                .status(EnrichmentQueueStatus.QUEUED)
                .attempts(0)
                .requestedByUserId(userId)
                .requestedAt(Instant.now())
                .build());
        return true;
    }

    @Transactional
    public int cancel(String jobId) {
        return queueRepository.cancelQueued(jobId, Instant.now());
    }

    public JobProgress progress(String jobId) {
        List<EnrichmentQueueEntity> rows = queueRepository.findByJobId(jobId);
        if (rows.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Unknown enrichment job " + jobId);
        }
        long done = rows.stream().filter(row -> row.getStatus() == EnrichmentQueueStatus.DONE).count();
        long skipped = rows.stream().filter(row -> row.getStatus() == EnrichmentQueueStatus.SKIPPED).count();
        long failed = rows.stream().filter(row -> row.getStatus() == EnrichmentQueueStatus.FAILED).count();
        long cancelled = rows.stream().filter(row -> row.getStatus() == EnrichmentQueueStatus.CANCELLED).count();
        long outstanding = rows.stream().filter(row -> row.getStatus().isOutstanding()).count();
        return new JobProgress(jobId, rows.size(), done, skipped, failed, cancelled, outstanding);
    }

    private Set<Long> resolveBookIds(EnrichmentRequest request) {
        return switch (request.getScope()) {
            case BOOK, BOOKS -> request.getBookIds() == null ? Set.of() : request.getBookIds();
            case LIBRARY -> {
                if (request.getLibraryId() == null) {
                    throw ApiError.GENERIC_BAD_REQUEST.createException("Library id is required for a library scope");
                }
                yield bookRepository.findBookIdsByLibraryId(request.getLibraryId());
            }
        };
    }

    private Long currentUserId() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        return user == null ? null : user.getId();
    }

    static String encodeSteps(Set<EnrichmentStepType> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        return steps.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static Set<EnrichmentStepType> decodeSteps(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        Set<EnrichmentStepType> steps = EnumSet.noneOf(EnrichmentStepType.class);
        for (String name : encoded.split(",")) {
            try {
                steps.add(EnrichmentStepType.valueOf(name.strip()));
            } catch (IllegalArgumentException e) {
                // A step removed in a later version: the queued row outlives the enum value, and
                // dropping the unknown name is better than failing work that is still mostly valid.
                log.warn("Ignoring unknown enrichment step '{}' on a queued row", name);
            }
        }
        return steps.isEmpty() ? null : steps;
    }

    public record JobProgress(String jobId, long total, long done, long skipped, long failed,
                              long cancelled, long outstanding) {

        public boolean isFinished() {
            return outstanding == 0;
        }
    }
}
