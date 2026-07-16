package org.booklore.service.inpx;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.NotificationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class InpxArchiveFullScanService {

    private static final int BATCH_SIZE = 500;

    private final InpxArchiveCatalogService catalogService;
    private final InpxArchiveScanner archiveScanner;
    private final InpxBatchWriter batchWriter;
    private final BookFileRepository bookFileRepository;
    private final InpxArchiveBookRefreshService bookRefreshService;
    private final NotificationService notificationService;
    private final TaskExecutor taskExecutor;

    public InpxArchiveFullScanService(InpxArchiveCatalogService catalogService,
                                      InpxArchiveScanner archiveScanner,
                                      InpxBatchWriter batchWriter,
                                      BookFileRepository bookFileRepository,
                                      InpxArchiveBookRefreshService bookRefreshService,
                                      NotificationService notificationService,
                                      @Qualifier("inpxArchiveScanExecutor") TaskExecutor taskExecutor) {
        this.catalogService = catalogService;
        this.archiveScanner = archiveScanner;
        this.batchWriter = batchWriter;
        this.bookFileRepository = bookFileRepository;
        this.bookRefreshService = bookRefreshService;
        this.notificationService = notificationService;
        this.taskExecutor = taskExecutor;
    }

    public void start(long libraryId, String archiveName) {
        LibraryEntity library = catalogService.requireInpxLibrary(libraryId);
        InpxArchiveScanner.ArchiveCandidate candidate = archiveScanner.inspectArchive(
                library.getInpxArchivePath(), archiveName);
        if (!catalogService.queue(libraryId, archiveName, candidate.entryCount())) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Archive is already being scanned: " + archiveName);
        }
        try {
            taskExecutor.execute(() -> scan(libraryId, archiveName));
        } catch (RejectedExecutionException e) {
            String message = ApiError.INPX_ARCHIVE_SCAN_QUEUE_FULL.getMessage();
            catalogService.failed(libraryId, archiveName, message);
            throw ApiError.INPX_ARCHIVE_SCAN_QUEUE_FULL.createException();
        }
    }

    private void scan(long libraryId, String archiveName) {
        try {
            LibraryEntity library = catalogService.requireInpxLibrary(libraryId);
            LibraryPathEntity libraryPath = library.getLibraryPaths().stream().findFirst()
                    .orElseThrow(() -> ApiError.GENERIC_BAD_REQUEST.createException(
                            "INPX library requires an archive path"));
            InpxArchiveScanner.ArchiveCandidate candidate = archiveScanner.inspectArchive(
                    library.getInpxArchivePath(), archiveName);
            InpxArchiveScanner.Discovery discovery = archiveScanner.discoveryForArchive(libraryId, candidate);
            catalogService.importing(libraryId, archiveName, discovery.totalEntries());

            InpxScanCaches caches = new InpxScanCaches();
            List<InpxBookDto> batch = new ArrayList<>(BATCH_SIZE);
            long[] discoveredBooks = {0};
            long[] addedBooks = {0};
            archiveScanner.forEach(discovery, book -> {
                batch.add(book);
                discoveredBooks[0]++;
                if (batch.size() >= BATCH_SIZE) {
                    addedBooks[0] += persist(batch, libraryId, libraryPath.getId(), caches);
                    catalogService.progress(libraryId, archiveName, discoveredBooks[0], 0, 0);
                }
            }, () -> false);
            addedBooks[0] += persist(batch, libraryId, libraryPath.getId(), caches);

            List<Long> bookIds = bookFileRepository.findBookIdsByArchive(libraryId, archiveName);
            catalogService.refreshing(libraryId, archiveName, bookIds.size(), addedBooks[0]);
            long covered = 0;
            long failed = 0;
            long processed = 0;
            for (Long bookId : bookIds) {
                try {
                    if (bookRefreshService.refresh(bookId)) {
                        covered++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Skipping failed INPX archive book {} in {}: {}",
                            bookId, archiveName, e.getMessage());
                }
                processed++;
                catalogService.progress(libraryId, archiveName, processed, covered, failed);
            }
            catalogService.completed(libraryId, archiveName);
            notificationService.sendMessage(Topic.LIBRARY_SCAN_COMPLETE, libraryId);
            log.info("Fully rescanned INPX archive {} in library {}: books={}, covers={}, failed={}",
                    archiveName, libraryId, bookIds.size(), covered, failed);
        } catch (RuntimeException e) {
            catalogService.failed(libraryId, archiveName, e.getMessage());
            log.error("Failed to rescan INPX archive {} in library {}: {}",
                    archiveName, libraryId, e.getMessage(), e);
        }
    }

    private int persist(List<InpxBookDto> batch, long libraryId, long libraryPathId, InpxScanCaches caches) {
        if (batch.isEmpty()) {
            return 0;
        }
        InpxBatchWriter.BatchResult result = batchWriter.persist(
                List.copyOf(batch), libraryId, libraryPathId, caches);
        batch.clear();
        return result.added();
    }
}
