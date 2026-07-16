package org.booklore.service.library;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.util.BookFileGroupingUtils;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator for processing library files as books.
 * Delegates transactional processing to BookGroupProcessor.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FileAsBookProcessor {

    private final BookGroupProcessor bookGroupProcessor;

    // A saturated Hikari pool (see application.yaml maximum-pool-size) surfaces as a transient
    // failure while a group is being persisted. Rather than silently dropping the group, we retry
    // a few times with a backoff so a brief contention spike does not lose books; only groups that
    // still fail after the retries (or fail for a non-transient reason) are reported as skipped.
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500;

    public ProcessingOutcome processLibraryFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Map<String, List<LibraryFile>> groups = BookFileGroupingUtils.groupByBaseName(libraryFiles);
        return processLibraryFilesGrouped(groups, libraryEntity);
    }

    public ProcessingOutcome processLibraryFilesGrouped(Map<String, List<LibraryFile>> groups, LibraryEntity libraryEntity) {
        long libraryId = libraryEntity.getId();
        List<String> skippedGroups = new ArrayList<>();
        for (Map.Entry<String, List<LibraryFile>> entry : groups.entrySet()) {
            if (!processGroupWithRetry(entry.getValue(), libraryId)) {
                skippedGroups.add(fileNames(entry.getValue()));
            }
        }
        if (!skippedGroups.isEmpty()) {
            log.warn("Skipped {} file group(s) in library '{}'; rescan to retry: {}",
                    skippedGroups.size(), libraryEntity.getName(), skippedGroups);
        }
        log.info("Finished processing library '{}'", libraryEntity.getName());
        return new ProcessingOutcome(skippedGroups);
    }

    private boolean processGroupWithRetry(List<LibraryFile> group, long libraryId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                bookGroupProcessor.process(group, libraryId);
                return true;
            } catch (Exception e) {
                if (!isTransientDbFailure(e)) {
                    log.error("Failed to process file group {}: {}", fileNames(group), e.getMessage());
                    return false;
                }
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Skipping file group {} after {} transient DB failures: {}",
                            fileNames(group), MAX_ATTEMPTS, e.getMessage());
                    return false;
                }
                log.warn("Transient DB failure on file group {} (attempt {}/{}), retrying in {}ms: {}",
                        fileNames(group), attempt, MAX_ATTEMPTS, RETRY_DELAY_MS, e.getMessage());
                if (!sleepBeforeRetry()) {
                    log.warn("Interrupted while retrying file group {}, skipping; rescan to retry", fileNames(group));
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean isTransientDbFailure(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof SQLTransientException) {
                return true;
            }
        }
        return false;
    }

    private static String fileNames(List<LibraryFile> group) {
        return group.stream().map(LibraryFile::getFileName).toList().toString();
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record ProcessingOutcome(List<String> skippedGroups) {
    }
}
