package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.migration.Migration;
import org.booklore.util.BookUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Brings stored {@code search_text} in line with the punctuation folding the search now applies to
 * the query.
 * <p>
 * The search is a substring match of the normalized query against the normalized text, so the two
 * sides have to be normalized the same way. Until now typographic punctuation survived both: a title
 * stored as {@code generation «p»} was found by a reader who typed the guillemets and missed by one
 * who did not — and readers do not type them. Folding only the query would move the miss rather than
 * remove it, so the stored side is rewritten once here.
 * <p>
 * The text is folded in place rather than rebuilt from the metadata fields: rebuilding runs through
 * {@code buildSearchText}, which needs every author of every affected book, and the folding is a
 * pure string operation over text that is already normalized.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoldSearchTextPunctuationMigration implements Migration {

    private static final int BATCH_SIZE = 500;

    private final BookMetadataRepository bookMetadataRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String getKey() {
        return "foldSearchTextPunctuation";
    }

    @Override
    public String getDescription() {
        return "Fold typographic quotes, apostrophes and dashes in stored search_text";
    }

    /**
     * Tens of thousands of rows are rewritten, so each batch commits on its own rather than holding
     * one transaction open for the whole pass.
     */
    @Override
    public boolean runsInSingleTransaction() {
        return false;
    }

    @Override
    public void execute() {
        long lastBookId = 0;
        long scanned = 0;
        long rewritten = 0;

        List<BookMetadataRepository.SearchTextView> batch;
        do {
            long batchStart = lastBookId;
            batch = transactionTemplate.execute(status ->
                    bookMetadataRepository.findSearchTextsWithTypographicPunctuation(batchStart, PageRequest.of(0, BATCH_SIZE)));

            if (batch == null || batch.isEmpty()) {
                break;
            }

            rewritten += foldBatch(batch);
            scanned += batch.size();
            lastBookId = batch.getLast().getBookId();
        } while (batch.size() >= BATCH_SIZE);

        log.info("Completed migration '{}'. Scanned {} rows, rewrote {}.", getKey(), scanned, rewritten);
    }

    private long foldBatch(List<BookMetadataRepository.SearchTextView> batch) {
        Long count = transactionTemplate.execute(status -> {
            long updated = 0;
            for (BookMetadataRepository.SearchTextView row : batch) {
                String folded = BookUtils.foldSearchPunctuation(row.getSearchText());
                if (folded != null && !folded.equals(row.getSearchText())) {
                    bookMetadataRepository.updateSearchText(row.getBookId(), folded);
                    updated++;
                }
            }
            return updated;
        });
        // The callback always returns a boxed long; the fallback only honours the template's @Nullable contract.
        return Objects.requireNonNullElse(count, 0L);
    }
}
