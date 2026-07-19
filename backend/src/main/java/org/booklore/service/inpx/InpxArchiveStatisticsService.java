package org.booklore.service.inpx;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.booklore.repository.BookFileRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class InpxArchiveStatisticsService {

    private final BookFileRepository bookFileRepository;
    private final TaskExecutor taskExecutor;
    private final Cache<Long, Map<String, ArchiveStatistics>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1000)
            .build();
    private final ConcurrentMap<Long, CompletableFuture<Map<String, ArchiveStatistics>>> inFlight =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AtomicLong> generations = new ConcurrentHashMap<>();

    public InpxArchiveStatisticsService(
            BookFileRepository bookFileRepository,
            @Qualifier("inpxArchiveStatisticsExecutor") TaskExecutor taskExecutor) {
        this.bookFileRepository = bookFileRepository;
        this.taskExecutor = taskExecutor;
    }

    public CompletableFuture<Map<String, ArchiveStatistics>> load(long libraryId) {
        Map<String, ArchiveStatistics> cached = cache.getIfPresent(libraryId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        while (true) {
            CompletableFuture<Map<String, ArchiveStatistics>> existing = inFlight.get(libraryId);
            if (existing != null) {
                return existing;
            }

            CompletableFuture<Map<String, ArchiveStatistics>> created = new CompletableFuture<>();
            if (inFlight.putIfAbsent(libraryId, created) != null) {
                continue;
            }

            long generation = generation(libraryId).get();
            try {
                taskExecutor.execute(() -> compute(libraryId, generation, created));
            } catch (RejectedExecutionException e) {
                inFlight.remove(libraryId, created);
                created.completeExceptionally(e);
            }
            return created;
        }
    }

    public Map<String, ArchiveStatistics> loadBlocking(long libraryId) {
        try {
            return load(libraryId).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    public void invalidate(long libraryId) {
        generation(libraryId).incrementAndGet();
        cache.invalidate(libraryId);
        // Requests made after the underlying archive data changed must not join a calculation
        // that started for the previous generation. The old callers still receive their result;
        // the next generation is queued behind it on the single-thread executor.
        inFlight.remove(libraryId);
    }

    @SuppressWarnings("java:S1181") // Error must still complete the future and free the dedup slot below, same as Exception - otherwise concurrent awaiters of this in-flight computation would hang forever
    private void compute(long libraryId, long generation,
                         CompletableFuture<Map<String, ArchiveStatistics>> future) {
        try {
            Map<String, ArchiveStatistics> statistics = queryStatistics(libraryId);
            if (generation(libraryId).get() == generation) {
                cache.put(libraryId, statistics);
            }
            inFlight.remove(libraryId, future);
            future.complete(statistics);
        } catch (Exception e) {
            inFlight.remove(libraryId, future);
            future.completeExceptionally(e);
        } catch (Error e) {
            inFlight.remove(libraryId, future);
            future.completeExceptionally(e);
        }
    }

    private Map<String, ArchiveStatistics> queryStatistics(long libraryId) {
        Map<String, ArchiveStatistics> statistics = new HashMap<>();
        for (Object[] row : bookFileRepository.findArchiveStatistics(libraryId)) {
            statistics.put((String) row[0], new ArchiveStatistics(
                    ((Number) row[1]).longValue(),
                    (Instant) row[2],
                    (Instant) row[3],
                    row[4] == null ? 0 : ((Number) row[4]).longValue()));
        }
        return Map.copyOf(statistics);
    }

    private AtomicLong generation(long libraryId) {
        return generations.computeIfAbsent(libraryId, ignored -> new AtomicLong());
    }

    public record ArchiveStatistics(long bookCount, Instant addedAt, Instant lastScannedAt,
                                    long coverCount) {
        public static final ArchiveStatistics EMPTY = new ArchiveStatistics(0, null, null, 0);
    }
}
