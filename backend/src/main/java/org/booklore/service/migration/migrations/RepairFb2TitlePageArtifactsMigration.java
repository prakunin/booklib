package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.enrichment.catalog.LocalCatalogBackfillService;
import org.booklore.service.enrichment.queue.EnrichmentPriority;
import org.booklore.service.enrichment.queue.EnrichmentQueueService;
import org.booklore.service.metadata.extractor.OriginalTitleHeuristic;
import org.booklore.service.migration.Migration;
import org.booklore.util.MojibakeText;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Repairs what the FB2 title-page fallback wrote before it was tightened.
 * <p>
 * Two distinct kinds of damage, both from the same place. Subtitles: the old rule accepted any body
 * paragraph carrying three Latin letters and a four-digit run, which matched ISBN lines, copyright
 * notices, e-mail addresses and even a web counter's JavaScript — every subtitle in the database
 * came from it. Titles: a book whose FB2 spells its own title in replacement characters had that
 * spelling written over the INPX record that was still intact.
 * <p>
 * The subtitle half is decided here, by re-asking {@link OriginalTitleHeuristic} — the same rule the
 * extractor now applies, so a stored value survives exactly if the extractor would produce it today.
 * The title half is not: recovering an identity is the local catalog's job, and this only queues the
 * affected books for it rather than duplicating the resolution, confidence and locking rules the
 * enrichment pipeline already owns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepairFb2TitlePageArtifactsMigration implements Migration {

    private static final int BATCH_SIZE = 500;
    private static final String REPLACEMENT_CHARACTER = "�";

    private final BookMetadataRepository bookMetadataRepository;
    private final EnrichmentQueueService enrichmentQueueService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String getKey() {
        return "repairFb2TitlePageArtifacts";
    }

    @Override
    public String getDescription() {
        return "Clear title-page subtitles the tightened heuristic rejects and re-derive undecodable titles";
    }

    /**
     * Tens of thousands of rows are rewritten, so this runs in batch-sized transactions rather than
     * holding one open for the whole pass.
     */
    @Override
    public boolean runsInSingleTransaction() {
        return false;
    }

    @Override
    public void execute() {
        long cleared = clearRejectedSubtitles();
        long queued = queueUndecodableTitlesForCatalogRepair();
        log.info("Migration '{}' completed. Cleared {} title-page subtitles, queued {} books with an "
                + "undecodable title for local catalog repair.", getKey(), cleared, queued);
    }

    /**
     * Each batch reads and writes inside one transaction. Not only for the write: {@code searchText}
     * is a lazy column in its own fetch group and {@code @PreUpdate} rebuilds it from the row, which
     * a detached entity cannot supply.
     */
    private long clearRejectedSubtitles() {
        long lastBookId = 0;
        long cleared = 0;
        while (true) {
            BatchResult batch = clearRejectedSubtitleBatch(lastBookId);
            if (!batch.hasRows()) {
                return cleared;
            }
            lastBookId = batch.lastBookId();
            cleared += batch.cleared();
        }
    }

    private BatchResult clearRejectedSubtitleBatch(long afterBookId) {
        BatchResult result = transactionTemplate.execute(status -> {
            List<BookMetadataEntity> page = bookMetadataRepository.findUnlockedSubtitlesAfterBookId(
                    afterBookId, PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) {
                return BatchResult.EMPTY;
            }
            long cleared = 0;
            for (BookMetadataEntity metadata : page) {
                if (!OriginalTitleHeuristic.looksLikeOriginalTitle(metadata.getSubtitle())) {
                    // Managed entities inside this transaction: the clear flushes on commit.
                    metadata.setSubtitle(null);
                    cleared++;
                }
            }
            return new BatchResult(true, page.getLast().getBookId(), cleared);
        });
        return Objects.requireNonNullElse(result, BatchResult.EMPTY);
    }

    private record BatchResult(boolean hasRows, long lastBookId, long cleared) {
        private static final BatchResult EMPTY = new BatchResult(false, 0, 0);
    }

    /**
     * Queued rather than written: only the library's local catalog knows what the destroyed title
     * said, and {@code LOCAL_CATALOG} is the step that reads it. The queue is left to decide the
     * order, and a book with no catalog behind it simply finds nothing and keeps what it has.
     */
    private long queueUndecodableTitlesForCatalogRepair() {
        long lastBookId = 0;
        List<Long> bookIds = new ArrayList<>();
        while (true) {
            List<BookMetadataEntity> page = bookMetadataRepository.findTitlesContainingAfterBookId(
                    lastBookId, REPLACEMENT_CHARACTER, PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) {
                break;
            }
            lastBookId = page.getLast().getBookId();
            page.stream()
                    .filter(metadata -> MojibakeText.isMojibake(metadata.getTitle()))
                    .map(BookMetadataEntity::getBookId)
                    .forEach(bookIds::add);
        }
        if (bookIds.isEmpty()) {
            return 0;
        }
        enrichmentQueueService.enqueue(EnrichmentRequest.builder()
                .scope(EnrichmentRequest.Scope.BOOKS)
                .bookIds(Set.copyOf(bookIds))
                .steps(LocalCatalogBackfillService.LOCAL_STEPS)
                .writePolicy(EnrichmentWritePolicy.AUTO)
                .agentAllowed(false)
                .build(), EnrichmentPriority.IMPORT_TOP_UP);
        return bookIds.size();
    }
}
