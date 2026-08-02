package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.BookCoverService;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class InpxArchiveBookRefreshService {

    // Author rows can be merged or retired by author-authority work between the import and this
    // refresh, surfacing here as a transient stale-state failure. A fresh transaction re-reads the
    // current state and normally succeeds, so a couple of retries turn most of them into successes.
    private static final int MAX_REFRESH_ATTEMPTS = 3;

    private final BookRepository bookRepository;
    private final ArchivedBookContentService archivedBookContentService;
    private final ArchiveEntryMetadataRecognizer entryMetadataRecognizer;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final BookCoverService bookCoverService;
    private final TransactionTemplate transactionTemplate;

    /**
     * Soft-deletes a book whose archive entry no longer exists, mirroring how the filesystem
     * watcher retires books whose file vanished. Soft rather than hard so the row can be restored
     * if the archive is put back.
     */
    public void retireOrphan(long bookId) {
        transactionTemplate.executeWithoutResult(status ->
                bookRepository.findById(bookId).ifPresent(book -> {
                    book.setDeleted(true);
                    book.setDeletedAt(Instant.now());
                    bookRepository.save(book);
                }));
    }

    public boolean refresh(long bookId) {
        RefreshResult result = refreshMetadataWithRetry(bookId);
        if (result != RefreshResult.REFRESHED) {
            return false;
        }

        try {
            bookCoverService.regenerateCover(bookId);
            return true;
        } catch (RuntimeException e) {
            log.debug("No cover regenerated for archived book {}: {}", bookId, e.getMessage());
            return false;
        }
    }

    private RefreshResult refreshMetadataWithRetry(long bookId) {
        int attempt = 0;
        while (true) {
            try {
                return refreshMetadata(bookId);
            } catch (OptimisticLockingFailureException | CannotAcquireLockException e) {
                if (++attempt >= MAX_REFRESH_ATTEMPTS) {
                    throw e;
                }
                log.debug("Book {}: retrying metadata refresh after a transient lock (attempt {}): {}",
                        bookId, attempt, e.getMessage());
            }
        }
    }

    private RefreshResult refreshMetadata(long bookId) {
        TransactionCallback<RefreshResult> work = status -> {
            BookEntity managedBook = bookRepository.findByIdForInpxArchiveRefresh(bookId).orElse(null);
            if (managedBook == null) {
                return RefreshResult.NOT_REFRESHED;
            }
            BookFileEntity bookFile = managedBook.getPrimaryBookFile();
            if (bookFile == null || !bookFile.isArchivedSource()) {
                return RefreshResult.NOT_REFRESHED;
            }

            String entryName = bookFile.getFileName();
            if (!requiresRevalidation(bookFile)) {
                // A download-only format with no extractor (djvu, rtf, …): the filename metadata set
                // at discovery is all there is. Skip materialising the entry — nothing to extract, no
                // cover to read — and leave the row as-is.
                managedBook.setScannedOn(Instant.now());
                bookRepository.save(managedBook);
                return RefreshResult.NOT_REFRESHED;
            }

            // Revalidated, not cached: this is the repair path for a replaced archive, so it must read
            // the archive itself rather than a cached copy that may predate the replacement. The
            // per-format extractor runs here, on the materialised file, layered over the filename.
            File file = archivedBookContentService.resolveRevalidated(bookFile).toFile();
            BookMetadata metadata = entryMetadataRecognizer.recognize(entryName, file);
            if (metadata != null) {
                bookMetadataUpdater.setBookMetadata(MetadataUpdateContext.builder()
                        .bookEntity(managedBook)
                        .metadataUpdateWrapper(MetadataUpdateWrapper.builder().metadata(metadata).build())
                        .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                        .build());
            }
            managedBook.setScannedOn(Instant.now());
            // A rescan may be repairing a replaced archive that now has a cover it didn't have
            // before, so a prior "no cover" probe result must not survive it.
            managedBook.setCoverProbedAt(null);
            bookRepository.save(managedBook);
            return RefreshResult.REFRESHED;
        };
        return transactionTemplate.execute(work);
    }

    private boolean requiresRevalidation(BookFileEntity bookFile) {
        return entryMetadataRecognizer.hasExtractor(bookFile.getFileName())
                || bookFile.getBookType() == BookFileType.HTML
                || bookFile.getBookType() == BookFileType.CBX
                && entryMetadataRecognizer.isGenericArchive(bookFile.getFileName());
    }

    private enum RefreshResult {
        REFRESHED,
        NOT_REFRESHED
    }
}
