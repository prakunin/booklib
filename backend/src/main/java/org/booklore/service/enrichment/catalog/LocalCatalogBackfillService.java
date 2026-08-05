package org.booklore.service.enrichment.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.enrichment.EnrichmentPipeline;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Drives every archived book of a library through the enrichment pipeline using only the local
 * catalog steps.
 * <p>
 * This is a driver, not a second pipeline: resolution, confidence, write policy and lock handling
 * all stay in {@link EnrichmentPipeline}. It deliberately does not use {@code enrichment_queue} —
 * that queue is drained five books per fifteen seconds because it is sized for rate-limited provider
 * calls, which would put a 615k-book library three weeks away.
 * <p>
 * There is no checkpoint. The local catalog is an exact archive-entry match and produces idempotent
 * values, so a run interrupted by a restart is simply started again.
 * <p>
 * That same absence of a checkpoint is why the index is built <em>synchronously</em> before the walk
 * begins, through {@link LocalCatalogIndexService#ensureIndexedNow} rather than the fire-and-forget
 * {@code ensureIndexed}. A book walked while the index is still being written finds nothing, which is
 * indistinguishable from a book the catalog has nothing for, and nothing ever comes back for it. If a
 * rebuild started elsewhere is already in flight the run refuses to start at all rather than walk
 * against a partial index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCatalogBackfillService {

    private static final int PAGE_SIZE = 500;

    /**
     * The local-catalog step set. Public so {@code InpxBatchWriter} can queue the same steps for
     * newly scanned books through the ordinary {@code enrichment_queue} instead of duplicating the
     * list. {@code EnumSet.of(...)} is mutable, so it is wrapped unmodifiable before being exposed.
     */
    public static final Set<EnrichmentStepType> LOCAL_STEPS = Collections.unmodifiableSet(EnumSet.of(
            EnrichmentStepType.LOCAL_CATALOG,
            EnrichmentStepType.LOCAL_LANGUAGE,
            EnrichmentStepType.LOCAL_COMPILATION,
            EnrichmentStepType.REVIEWS,
            EnrichmentStepType.AUTHOR_BIO));

    private final BookFileRepository bookFileRepository;
    private final EnrichmentPipeline pipeline;
    private final LocalCatalogIndexService indexService;

    public record BackfillResult(long processed, long failed, boolean cancelled) {
    }

    public BackfillResult run(long libraryId, String taskId, BooleanSupplier cancelled, LongConsumer progress) {
        if (!indexService.ensureIndexedNow(libraryId)) {
            throw ApiError.CONFLICT.createException(
                    "The local catalog index for library " + libraryId + " is being rebuilt; start the "
                            + "backfill again once indexing has finished");
        }

        EnrichmentRequest request = EnrichmentRequest.builder()
                .steps(LOCAL_STEPS)
                .writePolicy(EnrichmentWritePolicy.AUTO)
                .agentAllowed(false)
                .build();

        String afterArchive = "";
        String afterEntry = "";
        long afterId = 0;
        long processed = 0;
        long failed = 0;

        while (true) {
            if (cancelled.getAsBoolean()) {
                log.info("Local catalog backfill {} cancelled after {} books", taskId, processed);
                return new BackfillResult(processed, failed, true);
            }
            List<Object[]> page = bookFileRepository.findArchivedBooksForBackfill(
                    libraryId, afterArchive, afterEntry, afterId, PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            for (Object[] row : page) {
                long bookId = ((Number) row[0]).longValue();
                try {
                    pipeline.enrich(bookId, request);
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Local catalog backfill failed for book {}: {}", bookId, e.getMessage());
                }
                processed++;
            }
            Object[] last = page.getLast();
            afterArchive = (String) last[1];
            afterEntry = (String) last[2];
            afterId = ((Number) last[0]).longValue();
            progress.accept(processed);
        }

        log.info("Local catalog backfill {} for library {} finished: {} processed, {} failed",
                taskId, libraryId, processed, failed);
        return new BackfillResult(processed, failed, false);
    }
}
