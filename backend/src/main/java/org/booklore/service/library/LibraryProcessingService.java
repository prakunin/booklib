package org.booklore.service.library;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.inpx.InpxLibraryScanner;
import org.booklore.service.file.FileFingerprint;
import org.booklore.task.options.RescanLibraryContext;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class LibraryProcessingService {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final NotificationService notificationService;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final FileAsBookProcessor fileAsBookProcessor;
    private final BookRestorationService bookRestorationService;
    private final BookDeletionService bookDeletionService;
    private final LibraryFileHelper libraryFileHelper;
    private final BookGroupingService bookGroupingService;
    private final BookCoverGenerator bookCoverGenerator;
    private final BookFileAutoAttacher bookFileAutoAttacher;
    private final InpxLibraryScanner inpxLibraryScanner;

    public void processLibrary(long libraryId) {
        LibraryEntity libraryEntity = libraryRepository.findByIdWithPaths(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started processing library: " + libraryEntity.getName()));
        if (libraryEntity.getSourceType() == LibrarySourceType.INPX) {
            scanInpxLibrary(libraryEntity, "processing");
            return;
        }
        try {
            List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity);
            List<BookEntity> existingBooks = bookRepository.findAllByLibraryIdForRescan(libraryId);
            List<BookFileEntity> allAdditionalFiles = bookAdditionalFileRepository.findByLibraryId(libraryEntity.getId());

            List<LibraryFile> newFiles = libraryFileHelper.detectNewBookPaths(libraryFiles, existingBooks, allAdditionalFiles);

            // Use BookGroupingService for consistent grouping based on organization mode
            Map<String, List<LibraryFile>> groups = bookGroupingService.groupForInitialScan(newFiles, libraryEntity);
            fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

            notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished processing library: " + libraryEntity.getName()));
        } catch (IOException e) {
            log.error("Failed to process library {}: {}", libraryEntity.getName(), e.getMessage(), e);
            notificationService.sendMessage(Topic.LOG, LogNotification.error("Failed to process library: " + libraryEntity.getName() + " - " + e.getMessage()));
            throw new UncheckedIOException("Library processing failed", e);
        }
    }

    public void rescanLibrary(RescanLibraryContext context) throws IOException {
        long libraryId = context.getLibraryId();
        LibraryEntity libraryEntity = libraryRepository.findByIdWithPaths(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started refreshing library: " + libraryEntity.getName()));

        if (libraryEntity.getSourceType() == LibrarySourceType.INPX) {
            scanInpxLibrary(libraryEntity, "refreshing");
            return;
        }

        validateLibraryPathsAccessible(libraryEntity);

        List<BookEntity> books = bookRepository.findAllByLibraryIdForRescan(libraryId);

        List<LibraryFile> allLibraryFiles = libraryFileHelper.getAllLibraryFiles(libraryEntity);
        List<LibraryFile> filteredFiles = libraryFileHelper.filterByAllowedFormats(
                allLibraryFiles, libraryEntity.getAllowedFormats());

        int existingBookCount = books.size();
        if (existingBookCount > 0 && allLibraryFiles.isEmpty()) {
            String paths = libraryEntity.getLibraryPaths().stream()
                    .map(LibraryPathEntity::getPath)
                    .collect(Collectors.joining(", "));
            log.error("Library '{}' has {} existing books but scan found 0 files. Paths may be offline: {}",
                    libraryEntity.getName(), existingBookCount, paths);
            throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(paths);
        }

        List<BookFileEntity> allAdditionalFiles = bookAdditionalFileRepository.findByLibraryId(libraryEntity.getId());

        List<Long> additionalFileIds = libraryFileHelper.detectDeletedAdditionalFiles(allLibraryFiles, allAdditionalFiles);
        if (!additionalFileIds.isEmpty()) {
            log.info("Detected {} removed additional files in library: {}", additionalFileIds.size(), libraryEntity.getName());
            bookDeletionService.deleteRemovedAdditionalFiles(additionalFileIds);
        }
        List<Long> bookIds = libraryFileHelper.detectDeletedBookIds(allLibraryFiles, books);
        if (!bookIds.isEmpty()) {
            log.info("Detected {} removed books in library: {}", bookIds.size(), libraryEntity.getName());
            bookDeletionService.processDeletedLibraryFiles(bookIds, allLibraryFiles);
        }
        bookRestorationService.restoreDeletedBooks(allLibraryFiles);
        bookDeletionService.purgeDisallowedFormats(libraryEntity);

        // Re-fetch fresh state after bounded write services have committed their own transactions.
        libraryEntity = libraryRepository.findByIdWithPaths(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        books = bookRepository.findAllByLibraryIdForRescan(libraryId);

        List<LibraryFile> newFiles = libraryFileHelper.detectNewBookPaths(filteredFiles, books, allAdditionalFiles);

        // Use BookGroupingService to determine what to attach vs create new
        BookGroupingService.GroupingResult groupingResult = bookGroupingService.groupForRescan(newFiles, libraryEntity);

        // Auto-attach files to existing books
        for (Map.Entry<BookEntity, List<LibraryFile>> entry : groupingResult.filesToAttach().entrySet()) {
            for (LibraryFile file : entry.getValue()) {
                autoAttachFile(entry.getKey(), file);
            }
        }

        // Process new book groups
        fileAsBookProcessor.processLibraryFilesGrouped(groupingResult.newBookGroups(), libraryEntity);

        notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished refreshing library: " + libraryEntity.getName()));
    }

    public void processLibraryFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        fileAsBookProcessor.processLibraryFiles(libraryFiles, libraryEntity);
    }

    private void validateLibraryPathsAccessible(LibraryEntity libraryEntity) {
        for (var pathEntity : libraryEntity.getLibraryPaths()) {
            Path path = Path.of(pathEntity.getPath());
            if (!Files.exists(path) || !Files.isDirectory(path) || !Files.isReadable(path)) {
                log.error("Library path not accessible: {}", path);
                throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(path.toString());
            }
        }
    }

    private void autoAttachFile(BookEntity book, LibraryFile file) {
        try {
            String hash = file.isFolderBased()
                    ? FileFingerprint.generateFolderHash(file.getFullPath())
                    : FileFingerprint.generateHash(file.getFullPath());
            Long fileSizeKb = file.isFolderBased()
                    ? FileUtils.getFolderSizeInKb(file.getFullPath())
                    : FileUtils.getFileSizeInKb(file.getFullPath());
            bookFileAutoAttacher.attach(book.getId(), file, hash, fileSizeKb)
                    .ifPresent(attachedBook -> bookCoverGenerator.generateCoverFromAdditionalFile(attachedBook, file));
        } catch (Exception e) {
            log.error("Error auto-attaching file {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private void scanInpxLibrary(LibraryEntity libraryEntity, String action) {
        String inpxPath = libraryEntity.getInpxPath();
        if (inpxPath == null || inpxPath.isBlank()) {
            log.info("Skipping {} of INPX library {}: no index configured", action, libraryEntity.getId());
            notificationService.sendMessage(Topic.LOG, LogNotification.info(
                    "Skipped " + action + " INPX library without an index: " + libraryEntity.getName()));
            return;
        }
        // InpxLibraryScanner is deliberately non-transactional; InpxBatchWriter commits one batch
        // at a time. Keep the long archive scan outside service-level transactions.
        inpxLibraryScanner.scan(libraryEntity.getId());
        notificationService.sendMessage(Topic.LIBRARY_SCAN_COMPLETE, libraryEntity.getId());
        notificationService.sendMessage(Topic.LOG, LogNotification.info(
                "Finished " + action + " INPX library: " + libraryEntity.getName()));
    }
}
