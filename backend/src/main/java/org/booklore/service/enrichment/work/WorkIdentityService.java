package org.booklore.service.enrichment.work;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.smart.ResolvedWorkIdentity;
import org.booklore.model.entity.BookWorkLinkEntity;
import org.booklore.model.entity.WorkIdentityEntity;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.WorkIdentitySource;
import org.booklore.repository.BookWorkLinkRepository;
import org.booklore.repository.WorkIdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Resolves a work once and lends the answer to every edition of it.
 * <p>
 * The point is the agent: it is the only step measured in minutes, and in an INPX library the same
 * work recurs dozens of times. Resolving per file would multiply that cost by the duplicate count
 * for no new information.
 * <p>
 * Concurrent asks for the same key are serialised rather than allowed to race. Without that, a bulk
 * run reaching ten copies of one book at once starts ten agent processes to compute the same answer
 * — the expensive mistake this class exists to prevent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkIdentityService {

    private final WorkIdentityRepository workIdentityRepository;
    private final BookWorkLinkRepository bookWorkLinkRepository;

    private final Map<String, ReentrantLock> inFlight = new ConcurrentHashMap<>();

    public Optional<WorkIdentityEntity> find(String workKey) {
        if (workKey == null) {
            return Optional.empty();
        }
        return workIdentityRepository.findByWorkKey(workKey);
    }

    /**
     * Returns the stored identity for the key, computing it with {@code resolver} only if there is
     * none. One thread computes; the rest wait and then read what it stored.
     *
     * @param resolver the expensive resolution, invoked at most once per key across all callers
     */
    public Optional<WorkIdentityEntity> findOrResolve(String workKey, Supplier<Optional<ResolvedWorkIdentity>> resolver) {
        if (workKey == null) {
            return Optional.empty();
        }
        Optional<WorkIdentityEntity> cached = find(workKey);
        if (cached.isPresent()) {
            return cached;
        }
        ReentrantLock lock = inFlight.computeIfAbsent(workKey, key -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have resolved it while this one waited for the lock.
            Optional<WorkIdentityEntity> resolvedMeanwhile = find(workKey);
            if (resolvedMeanwhile.isPresent()) {
                return resolvedMeanwhile;
            }
            return resolver.get().map(identity -> store(workKey, identity));
        } finally {
            lock.unlock();
            inFlight.remove(workKey, lock);
        }
    }

    @Transactional
    public WorkIdentityEntity store(String workKey, ResolvedWorkIdentity identity) {
        WorkIdentityEntity entity = workIdentityRepository.findByWorkKey(workKey)
                .orElseGet(() -> WorkIdentityEntity.builder().workKey(workKey).build());
        entity.setOriginalTitle(identity.originalTitle());
        entity.setOriginalAuthor(identity.originalAuthor());
        entity.setOriginalLanguage(identity.originalLanguage());
        entity.setGoodreadsId(goodreadsIdOf(identity));
        entity.setIsbn13(identity.isbn13());
        entity.setIsbn10(identity.isbn10());
        entity.setFirstPublishedYear(identity.firstPublishedYear());
        entity.setDescription(identity.description());
        entity.setDescriptionLanguage(identity.descriptionLanguage());
        // Whatever the agent reports is unverified by construction; the identity is worth reusing,
        // its contents are not worth writing without review.
        entity.setConfidence(EnrichmentConfidence.LOW);
        entity.setResolvedBy(WorkIdentitySource.AGENT);
        entity.setResolvedAt(Instant.now());
        return workIdentityRepository.save(entity);
    }

    @Transactional
    public void link(long bookId, WorkIdentityEntity work, EnrichmentConfidence matchConfidence) {
        bookWorkLinkRepository.save(BookWorkLinkEntity.builder()
                .bookId(bookId)
                .workIdentityId(work.getId())
                .matchConfidence(matchConfidence)
                .linkedAt(Instant.now())
                .build());
    }

    private String goodreadsIdOf(ResolvedWorkIdentity identity) {
        return org.booklore.service.metadata.smart.GoodreadsUrlParser
                .extractBookId(identity.goodreadsUrl())
                .orElse(null);
    }
}
