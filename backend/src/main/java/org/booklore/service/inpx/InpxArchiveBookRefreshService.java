package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.BookCoverService;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class InpxArchiveBookRefreshService {

    private final BookRepository bookRepository;
    private final ArchivedBookContentService archivedBookContentService;
    private final Fb2MetadataExtractor fb2MetadataExtractor;
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
        BookEntity book = bookRepository.findByIdForInpxArchiveRefresh(bookId).orElse(null);
        if (book == null) {
            return false;
        }
        BookFileEntity bookFile = book.getPrimaryBookFile();
        if (bookFile == null || !bookFile.isArchivedSource()) {
            return false;
        }

        // Revalidated, not cached: this is the repair path for a replaced archive, so it must read
        // the archive itself rather than a cached copy that may predate the replacement.
        File fb2File = archivedBookContentService.resolveRevalidated(bookFile).toFile();
        try {
            BookMetadata metadata = fb2MetadataExtractor.extractMetadata(fb2File);
            transactionTemplate.executeWithoutResult(status -> {
                BookEntity managedBook = bookRepository.findByIdForInpxArchiveRefresh(bookId)
                        .orElseThrow(() -> new IllegalStateException("Book disappeared during archive refresh: " + bookId));
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
            });
        } catch (RuntimeException e) {
            log.warn("Failed to refresh metadata for archived book {}: {}", bookId, e.getMessage());
            throw e;
        }

        try {
            bookCoverService.regenerateCover(bookId);
        } catch (RuntimeException e) {
            log.debug("No cover regenerated for archived book {}: {}", bookId, e.getMessage());
        }
        return bookRepository.findById(bookId)
                .map(refreshed -> refreshed.getBookCoverHash() != null)
                .orElse(false);
    }
}
