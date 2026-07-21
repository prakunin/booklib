package org.booklore.service.library;

import org.booklore.exception.ApiError;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.fileprocessor.AudiobookProcessor;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.task.options.RescanLibraryContext;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
public class LibraryRescanHelper {

    private static final int RESCAN_BATCH_SIZE = 250;

    private final LibraryRepository libraryRepository;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;
    private final BookRepository bookRepository;
    private final AudiobookProcessor audiobookProcessor;
    private final TransactionTemplate transactionTemplate;

    public LibraryRescanHelper(LibraryRepository libraryRepository, MetadataExtractorFactory metadataExtractorFactory, @Lazy BookMetadataUpdater bookMetadataUpdater, NotificationService notificationService, TaskCancellationManager cancellationManager, BookRepository bookRepository, AudiobookProcessor audiobookProcessor, TransactionTemplate transactionTemplate) {
        this.libraryRepository = libraryRepository;
        this.metadataExtractorFactory = metadataExtractorFactory;
        this.bookMetadataUpdater = bookMetadataUpdater;
        this.notificationService = notificationService;
        this.cancellationManager = cancellationManager;
        this.bookRepository = bookRepository;
        this.audiobookProcessor = audiobookProcessor;
        this.transactionTemplate = transactionTemplate;
    }

    public void handleRescanOptions(RescanLibraryContext context, String taskId) {

        LibraryEntity library = libraryRepository.findById(context.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(context.getLibraryId()));
        long libraryId = library.getId();
        String libraryName = library.getName();

        long totalBooksLong = bookRepository.countByLibraryIdNonDeleted(libraryId);
        int totalBooks = Math.toIntExact(Math.min(totalBooksLong, Integer.MAX_VALUE));
        int processedBooks = 0;

        log.info("Found {} book(s) to process in library id={}", totalBooksLong, libraryId);

        sendTaskProgressNotification(taskId, 0, String.format("Starting rescan for library: %s", libraryName), TaskStatus.IN_PROGRESS);

        long afterId = 0L;
        while (true) {
            List<Long> bookIds = bookRepository.findBookIdsByLibraryIdAfterId(libraryId, afterId, PageRequest.of(0, RESCAN_BATCH_SIZE));
            if (bookIds.isEmpty()) {
                break;
            }

            for (Long bookId : bookIds) {
                if (bookId == null) {
                    continue;
                }
                afterId = Math.max(afterId, bookId);

                RescanStepOutcome outcome = processBookForRescan(
                        context, libraryId, libraryName, taskId, bookId, processedBooks, totalBooks);
                processedBooks = outcome.processedBooks();
                if (outcome.cancelled()) {
                    return;
                }
            }
        }

        sendTaskProgressNotification(taskId, 100,
                String.format("Rescan completed for library: %s (%d books processed)", libraryName, processedBooks),
                TaskStatus.COMPLETED);
    }

    private record RescanStepOutcome(int processedBooks, boolean cancelled) {
    }

    /**
     * Processes a single book during a library rescan. Returns the updated {@code processedBooks}
     * count and whether the task was cancelled, so the caller's loop can update its counter and
     * stop the whole rescan without needing more than one {@code continue} in its loop.
     */
    private RescanStepOutcome processBookForRescan(RescanLibraryContext context, long libraryId, String libraryName,
                                                    String taskId, Long bookId, int processedBooks, int totalBooks) {
        if (taskId != null && cancellationManager.isTaskCancelled(taskId)) {
            log.info("Library rescan for library {} was cancelled", libraryId);
            sendTaskProgressNotification(taskId, progressPercentage(processedBooks, totalBooks),
                    String.format("Rescan cancelled for library: %s (%d/%d books processed)", libraryName, processedBooks, totalBooks),
                    TaskStatus.CANCELLED);
            return new RescanStepOutcome(processedBooks, true);
        }

        BookEntity bookEntity = findBookForRescan(bookId, libraryId);
        if (bookEntity == null) {
            return new RescanStepOutcome(processedBooks, false);
        }

        // Skip fileless books (e.g., physical books) - they have no file to extract metadata from
        if (!bookEntity.hasFiles()) {
            return new RescanStepOutcome(processedBooks + 1, false);
        }

        BookFileEntity primaryFile = bookEntity.getPrimaryBookFile();
        Path fullFilePath = bookEntity.getFullFilePath();
        if (primaryFile == null || fullFilePath == null) {
            return new RescanStepOutcome(processedBooks + 1, false);
        }

        log.info("Processing book: library={}, bookId={}, fileName={}", libraryName, bookId, primaryFile.getFileName());

        sendTaskProgressNotification(taskId, progressPercentage(processedBooks, totalBooks),
                String.format("Processing: %s (Library: %s)", primaryFile.getFileName(), libraryName),
                TaskStatus.IN_PROGRESS);

        try {
            BookFileType bookType = primaryFile.getBookType();
            BookMetadata bookMetadata = metadataExtractorFactory.extractMetadata(bookType, fullFilePath.toFile());
            if (bookMetadata == null) {
                log.warn("No metadata extracted for book id={} path={}", bookId, fullFilePath);
                return new RescanStepOutcome(processedBooks + 1, false);
            }
            updateBookMetadataInTransaction(context, libraryId, bookId, bookMetadata);
        } catch (Exception e) {
            log.error("Failed to update metadata for book id={} path={}: {}", bookId, fullFilePath, e.getMessage(), e);
        }
        return new RescanStepOutcome(processedBooks + 1, false);
    }

    private BookEntity findBookForRescan(Long bookId, Long libraryId) {
        return transactionTemplate.execute(status -> bookRepository.findByIdForLibraryRescan(bookId, libraryId).orElse(null));
    }

    private void updateBookMetadataInTransaction(RescanLibraryContext context, Long libraryId, Long bookId, BookMetadata bookMetadata) {
        transactionTemplate.executeWithoutResult(status -> {
            BookEntity bookEntity = bookRepository.findByIdForLibraryRescan(bookId, libraryId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
            MetadataUpdateContext metadataUpdateContext = MetadataUpdateContext.builder()
                    .bookEntity(bookEntity)
                    .metadataUpdateWrapper(
                            MetadataUpdateWrapper.builder()
                                    .metadata(bookMetadata)
                                    .build()
                    )
                    .replaceMode(context.getOptions().getMetadataReplaceMode())
                    .updateThumbnail(false)
                    .mergeCategories(false)
                    .mergeMoods(true)
                    .mergeTags(true)
                    .build();
            bookMetadataUpdater.setBookMetadata(metadataUpdateContext);

            if (bookEntity.getPrimaryBookFile().getBookType() == BookFileType.AUDIOBOOK && bookMetadata.getAudiobookMetadata() != null) {
                audiobookProcessor.setAudiobookTechnicalMetadata(bookEntity, bookMetadata);
            }
        });
    }

    private int progressPercentage(int processedBooks, int totalBooks) {
        return totalBooks > 0 ? (processedBooks * 100) / totalBooks : 0;
    }

    private void sendTaskProgressNotification(String taskId, int progress, String message, TaskStatus taskStatus) {
        try {
            TaskProgressPayload payload = TaskProgressPayload.builder()
                    .taskId(taskId)
                    .taskType(TaskType.REFRESH_LIBRARY_METADATA)
                    .message(message)
                    .progress(progress)
                    .taskStatus(taskStatus)
                    .build();

            notificationService.sendMessage(Topic.TASK_PROGRESS, payload);
        } catch (Exception e) {
            log.error("Failed to send task progress notification for taskId={}: {}", taskId, e.getMessage(), e);
        }
    }
}
