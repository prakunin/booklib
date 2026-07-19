package org.booklore.service.file;

import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.FileMoveResult;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.request.FileMoveRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
@Transactional
public class FileMoveService {

    private static final long EVENT_DRAIN_TIMEOUT_MS = 300;

    private final AppProperties appProperties;
    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookFileRepository;
    private final LibraryRepository libraryRepository;
    private final FileMoveHelper fileMoveHelper;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final LibraryMapper libraryMapper;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final SidecarMetadataWriter sidecarMetadataWriter;


    public void bulkMoveFiles(FileMoveRequest request) {
        List<FileMoveRequest.Move> moves = request.getMoves();

        validateLocalStorage();

        Set<Long> allAffectedLibraryIds = collectAllAffectedLibraryIds(moves);
        Set<Path> libraryPaths = monitoringRegistrationService.getPathsForLibraries(allAffectedLibraryIds);

        log.info("Unregistering {} libraries before bulk file move", allAffectedLibraryIds.size());
        monitoringRegistrationService.unregisterLibraries(allAffectedLibraryIds);
        monitoringRegistrationService.waitForEventsDrainedByPaths(libraryPaths, EVENT_DRAIN_TIMEOUT_MS);

        try {
            for (FileMoveRequest.Move move : moves) {
                processSingleMove(move);
            }
            // Ensure any file system events from the moves are drained/ignored while we are still unregistered
            sleep(EVENT_DRAIN_TIMEOUT_MS);
        } finally {
            for (Long libraryId : allAffectedLibraryIds) {
                libraryRepository.findByIdWithPaths(libraryId)
                        .ifPresent(library -> monitoringRegistrationService.registerLibrary(libraryMapper.toLibrary(library)));
            }
        }
    }

    private Set<Long> collectAllAffectedLibraryIds(List<FileMoveRequest.Move> moves) {
        Set<Long> libraryIds = new HashSet<>();
        
        for (FileMoveRequest.Move move : moves) {
            libraryIds.add(move.getTargetLibraryId());
            bookRepository.findByIdWithBookFiles(move.getBookId())
                    .ifPresent(book -> libraryIds.add(book.getLibrary().getId()));
        }
        
        return libraryIds;
    }

    private void processSingleMove(FileMoveRequest.Move move) {
        Long bookId = move.getBookId();
        Long targetLibraryId = move.getTargetLibraryId();
        Long targetLibraryPathId = move.getTargetLibraryPathId();

        Map<Long, PlannedMove> plannedMovesByBookFileId = new HashMap<>();
        Set<Path> sourceParentsToCleanup = new HashSet<>();

        try {
            Optional<BookEntity> optionalBook = bookRepository.findByIdWithBookFiles(bookId);
            Optional<LibraryEntity> optionalLibrary = libraryRepository.findByIdWithPaths(targetLibraryId);
            if (optionalBook.isEmpty()) {
                log.warn("Book not found for move operation: bookId={}", bookId);
                return;
            }
            if (optionalLibrary.isEmpty()) {
                log.warn("Target library not found for move operation: libraryId={}", targetLibraryId);
                return;
            }
            BookEntity bookEntity = optionalBook.get();
            LibraryEntity targetLibrary = optionalLibrary.get();

            Optional<LibraryPathEntity> optionalLibraryPathEntity = targetLibrary.getLibraryPaths().stream()
                    .filter(libraryPath -> Objects.equals(libraryPath.getId(), targetLibraryPathId))
                    .findFirst();
            if (optionalLibraryPathEntity.isEmpty()) {
                log.warn("Target library path not found for move operation: libraryId={}, pathId={}", targetLibraryId, targetLibraryPathId);
                return;
            }
            LibraryPathEntity libraryPathEntity = optionalLibraryPathEntity.get();

            if (bookEntity.getBookFiles() == null || bookEntity.getBookFiles().isEmpty()) {
                moveBookWithoutFiles(bookEntity, targetLibrary, libraryPathEntity, bookId);
                return;
            }

            List<BookFileEntity> bookFiles = dedupBookFilesById(bookEntity.getBookFiles());

            Path currentPrimaryFilePath = bookEntity.getFullFilePath();
            String pattern = fileMoveHelper.getFileNamingPattern(targetLibrary);
            Path newFilePath = fileMoveHelper.generateNewFilePath(bookEntity, libraryPathEntity, pattern);

            if (currentPrimaryFilePath.equals(newFilePath)) {
                return;
            }

            String newFileSubPath = fileMoveHelper.extractSubPath(newFilePath, libraryPathEntity);
            Path targetParentDir = newFilePath.getParent();

            if (targetParentDir == null) {
                log.warn("Target parent directory could not be determined for move operation: bookId={}", bookId);
                return;
            }

            // Validate all source paths exist before attempting moves
            if (!allSourcesExist(bookFiles, bookId)) {
                return;
            }

            stagePlannedMoves(bookFiles, bookEntity, libraryPathEntity, pattern, targetParentDir,
                    plannedMovesByBookFileId, sourceParentsToCleanup);

            if (plannedMovesByBookFileId.isEmpty()) {
                return;
            }

            // Commit file moves FIRST before updating database
            List<PlannedMove> committedMoves = commitPlannedMoves(plannedMovesByBookFileId);

            // Only update database after all file commits succeed
            commitDatabaseUpdate(bookId, bookFiles, pattern, libraryPathEntity, bookEntity, targetLibrary, newFileSubPath, committedMoves);

            Set<Path> libraryRoots = collectLibraryRoots(targetLibrary, bookEntity);

            moveSidecarFilesQuietly(bookId, currentPrimaryFilePath, newFilePath);

            cleanupSourceParents(sourceParentsToCleanup, libraryRoots);

            entityManager.clear();

            BookEntity fresh = bookRepository.findByIdWithBookFiles(bookId).orElseThrow();

            notificationService.sendMessage(Topic.BOOK_UPDATE, bookMapper.toBookWithDescription(fresh, false));

        } catch (Exception e) {
            log.error("Error moving file for book ID {}: {}", bookId, e.getMessage(), e);
        } finally {
            for (PlannedMove planned : plannedMovesByBookFileId.values()) {
                fileMoveHelper.rollbackMove(planned.temp(), planned.source());
            }
        }
    }

    public FileMoveResult moveSingleFile(BookEntity bookEntity) {
        validateLocalStorage();

        Long libraryId = bookEntity.getLibraryPath().getLibrary().getId();
        Path libraryRoot = Paths.get(bookEntity.getLibraryPath().getPath()).toAbsolutePath().normalize();

        // Evict the L1-cached LibraryEntity so that findByIdWithPaths() returns a fresh
        // instance with libraryPaths eagerly loaded.  Without this, the caller's EntityGraph
        // (e.g. findAllWithMetadataByIds) may have loaded the LibraryEntity without
        // libraryPaths, poisoning the L1 cache and causing a LazyInitializationException
        // when libraryPaths is accessed later (OSIV is disabled).
        LibraryEntity cachedLibrary = bookEntity.getLibraryPath().getLibrary();
        if (entityManager.contains(cachedLibrary)) {
            entityManager.detach(cachedLibrary);
        }

        boolean isLibraryMonitoredWhenCalled = false;
        Map<Long, PlannedMove> plannedMovesByBookFileId = new HashMap<>();
        Set<Path> sourceParentsToCleanup = new HashSet<>();

        try {
            Set<Path> existingPaths = monitoringRegistrationService.getPathsForLibraries(Set.of(libraryId));
            isLibraryMonitoredWhenCalled = monitoringRegistrationService.isLibraryMonitored(libraryId) || !existingPaths.isEmpty();

            // Ensure bookFiles are loaded
            Optional<BookEntity> loaded = loadBookWithFiles(bookEntity);
            if (loaded.isEmpty()) {
                return FileMoveResult.builder().moved(false).build();
            }
            BookEntity bookWithFiles = loaded.get();

            List<BookFileEntity> bookFiles = dedupBookFilesById(bookWithFiles.getBookFiles());

            String pattern = fileMoveHelper.getFileNamingPattern(bookWithFiles.getLibraryPath().getLibrary());
            Path currentPrimaryFilePath = bookWithFiles.getFullFilePath();
            Path expectedPrimaryFilePath = fileMoveHelper.generateNewFilePath(bookWithFiles, bookWithFiles.getLibraryPath(), pattern);

            if (currentPrimaryFilePath.equals(expectedPrimaryFilePath)) {
                return FileMoveResult.builder().moved(false).build();
            }

            log.info("Files for book ID {} need to be moved to match library pattern", bookWithFiles.getId());

            String newFileSubPath = fileMoveHelper.extractSubPath(expectedPrimaryFilePath, bookWithFiles.getLibraryPath());
            Path targetParentDir = expectedPrimaryFilePath.getParent();

            if (targetParentDir == null) {
                log.warn("Target parent directory could not be determined for move operation: bookId={}", bookWithFiles.getId());
                return FileMoveResult.builder().moved(false).build();
            }

            // Validate all source paths exist before attempting moves
            if (!allSourcesExist(bookFiles, bookEntity.getId())) {
                return FileMoveResult.builder().moved(false).build();
            }

            if (isLibraryMonitoredWhenCalled) {
                log.debug("Unregistering library {} before moving files", libraryId);
                fileMoveHelper.unregisterLibrary(libraryId);
                monitoringRegistrationService.waitForEventsDrainedByPaths(existingPaths, EVENT_DRAIN_TIMEOUT_MS);
            }

            // Stage all files to temp locations
            stagePlannedMoves(bookFiles, bookWithFiles, bookWithFiles.getLibraryPath(), pattern, targetParentDir,
                    plannedMovesByBookFileId, sourceParentsToCleanup);

            if (plannedMovesByBookFileId.isEmpty()) {
                return FileMoveResult.builder().moved(false).build();
            }

            // Commit all file moves FIRST before updating database
            List<PlannedMove> committedMoves = commitPlannedMoves(plannedMovesByBookFileId);

            // Update database for ALL BookFileEntity records (only after all commits succeed)
            BookEntity finalBookWithFiles = bookWithFiles;
            commitFileRenameUpdate(bookEntity.getId(), bookFiles, pattern, finalBookWithFiles, newFileSubPath, committedMoves);

            // Clean up empty parent directories – load library paths via a separate query
            // to avoid LazyInitializationException (OSIV is disabled) and MultipleBagFetchException
            // (cannot eagerly fetch both bookFiles and libraryPaths in the same EntityGraph).
            Set<Path> libraryRoots = collectLibraryRootsById(libraryId, bookWithFiles);

            moveSidecarFilesQuietly(bookEntity.getId(), currentPrimaryFilePath, expectedPrimaryFilePath);

            cleanupSourceParents(sourceParentsToCleanup, libraryRoots);

            if (isLibraryMonitoredWhenCalled) {
                // Ensure any file system events from the move and cleanup are drained/ignored while we are still unregistered
                sleep(EVENT_DRAIN_TIMEOUT_MS);
            }

            String newFileName = expectedPrimaryFilePath.getFileName().toString();

            return FileMoveResult.builder()
                    .moved(true)
                    .newFileName(newFileName)
                    .newFileSubPath(newFileSubPath)
                    .build();
        } catch (Exception e) {
            log.error("Failed to move files for book ID {}: {}", bookEntity.getId(), e.getMessage(), e);
        } finally {
            // Rollback any uncommitted moves
            for (PlannedMove planned : plannedMovesByBookFileId.values()) {
                fileMoveHelper.rollbackMove(planned.temp(), planned.source());
            }

            if (isLibraryMonitoredWhenCalled) {
                reRegisterLibrary(libraryId, libraryRoot);
            }
        }

        return FileMoveResult.builder().moved(false).build();
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sleep interrupted", e);
        }
    }

    private void validateLocalStorage() {
        if (!appProperties.isLocalStorage()) {
            throw new IllegalStateException(
                    "File move operations are only supported on local storage. " +
                    "Current disk type is configured as: " + appProperties.getDiskType() + ". " +
                    "If you are using local storage, set DISK_TYPE=LOCAL in your environment."
            );
        }
    }

    private void commitDatabaseUpdate(Long bookId, List<BookFileEntity> bookFiles, String pattern,
                                       LibraryPathEntity libraryPathEntity, BookEntity bookEntity,
                                       LibraryEntity targetLibrary, String newFileSubPath,
                                       List<PlannedMove> committedMoves) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (var bookFile : bookFiles) {
                    String newFileName;
                    if (bookFile.isBook()) {
                        Path targetPath = fileMoveHelper.generateNewFilePath(bookEntity, bookFile, libraryPathEntity, pattern);
                        newFileName = targetPath.getFileName().toString();
                    } else {
                        newFileName = bookFile.getFileName();
                    }
                    bookFileRepository.updateFileNameAndSubPath(bookFile.getId(), newFileName, newFileSubPath);
                }

                bookRepository.updateLibrary(bookEntity.getId(), targetLibrary.getId(), libraryPathEntity);
            });
        } catch (Exception e) {
            log.error("Database update failed after files were moved. Attempting to rollback file moves for book ID {}", bookId, e);
            for (PlannedMove committed : committedMoves) {
                rollbackSingleFileMove(bookId, committed.target(), committed.source());
            }
            throw e;
        }
    }

    private void commitFileRenameUpdate(Long bookId, List<BookFileEntity> bookFiles, String pattern,
                                         BookEntity bookWithFiles, String newFileSubPath,
                                         List<PlannedMove> committedMoves) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (var bookFile : bookFiles) {
                    String newFileName;
                    if (bookFile.isBook()) {
                        Path targetPath = fileMoveHelper.generateNewFilePath(bookWithFiles, bookFile, bookWithFiles.getLibraryPath(), pattern);
                        newFileName = targetPath.getFileName().toString();
                    } else {
                        newFileName = bookFile.getFileName();
                    }
                    bookFileRepository.updateFileNameAndSubPath(bookFile.getId(), newFileName, newFileSubPath);
                }
            });
        } catch (Exception e) {
            log.error("Database update failed after files were moved. Attempting to rollback file moves for book ID {}", bookId, e);
            for (PlannedMove committed : committedMoves) {
                rollbackSingleFileMove(bookId, committed.target(), committed.source());
            }
            throw e;
        }
    }

    private void rollbackSingleFileMove(Long bookId, Path target, Path source) {
        try {
            fileMoveHelper.moveFile(target, source);
        } catch (Exception rollbackEx) {
            log.error("Failed to rollback file move (Target -> Source) for book ID {}: {} -> {}", bookId, target, source, rollbackEx);
        }
    }

    private void moveSidecarFilesQuietly(Long bookId, Path oldPath, Path newPath) {
        try {
            sidecarMetadataWriter.moveSidecarFiles(oldPath, newPath);
        } catch (Exception e) {
            log.warn("Failed to move sidecar files for book ID {}: {}", bookId, e.getMessage());
        }
    }

    private List<BookFileEntity> dedupBookFilesById(Collection<BookFileEntity> bookFiles) {
        return bookFiles.stream()
                .filter(f -> f.getId() != null)
                .collect(Collectors.toMap(BookFileEntity::getId, f -> f, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
    }

    private void moveBookWithoutFiles(BookEntity bookEntity, LibraryEntity targetLibrary, LibraryPathEntity libraryPathEntity, Long bookId) {
        if (Boolean.TRUE.equals(bookEntity.getIsPhysical())) {
            log.info("Moving physical book (no files): bookId={}", bookId);
            bookRepository.updateLibrary(bookEntity.getId(), targetLibrary.getId(), libraryPathEntity);
            entityManager.clear();
            BookEntity fresh = bookRepository.findByIdWithBookFiles(bookId).orElseThrow();
            notificationService.sendMessage(Topic.BOOK_UPDATE, bookMapper.toBookWithDescription(fresh, false));
        } else {
            log.warn("Book has no files to move: bookId={}", bookId);
        }
    }

    private Optional<BookEntity> loadBookWithFiles(BookEntity bookEntity) {
        BookEntity bookWithFiles = bookEntity;
        if (bookEntity.getBookFiles() == null || bookEntity.getBookFiles().isEmpty()) {
            Optional<BookEntity> optionalBook = bookRepository.findByIdWithBookFiles(bookEntity.getId());
            if (optionalBook.isEmpty()) {
                log.warn("Book not found for move operation: bookId={}", bookEntity.getId());
                return Optional.empty();
            }
            bookWithFiles = optionalBook.get();
        }
        if (bookWithFiles.getBookFiles() == null || bookWithFiles.getBookFiles().isEmpty()) {
            log.warn("Book has no files to move: bookId={}", bookEntity.getId());
            return Optional.empty();
        }
        return Optional.of(bookWithFiles);
    }

    private boolean allSourcesExist(List<BookFileEntity> bookFiles, Long bookId) {
        for (var bookFile : bookFiles) {
            Path sourcePath = bookFile.getFullFilePath();
            if (!fileMoveHelper.validateSourceExists(sourcePath, bookFile.isFolderBased())) {
                log.warn("Source {} not found: bookId={}, path={}",
                        bookFile.isFolderBased() ? "folder" : "file", bookId, sourcePath);
                return false;
            }
        }
        return true;
    }

    private void stagePlannedMoves(List<BookFileEntity> bookFiles, BookEntity book, LibraryPathEntity libraryPath,
                                   String pattern, Path targetParentDir,
                                   Map<Long, PlannedMove> plannedMovesByBookFileId, Set<Path> sourceParentsToCleanup) throws IOException {
        for (var bookFile : bookFiles) {
            Path sourcePath = bookFile.getFullFilePath();
            Path targetPath;
            if (bookFile.isBook()) {
                targetPath = fileMoveHelper.generateNewFilePath(book, bookFile, libraryPath, pattern);
            } else {
                targetPath = targetParentDir.resolve(bookFile.getFileName());
            }

            if (sourcePath.equals(targetPath)) {
                continue;
            }

            Path tempPath = fileMoveHelper.moveFileWithBackup(sourcePath);
            plannedMovesByBookFileId.put(bookFile.getId(), new PlannedMove(sourcePath, tempPath, targetPath));
            if (sourcePath.getParent() != null) {
                sourceParentsToCleanup.add(sourcePath.getParent());
            }
        }
    }

    private List<PlannedMove> commitPlannedMoves(Map<Long, PlannedMove> plannedMovesByBookFileId) throws IOException {
        List<PlannedMove> committedMoves = new ArrayList<>();
        for (PlannedMove planned : plannedMovesByBookFileId.values()) {
            fileMoveHelper.commitMove(planned.temp(), planned.target());
            committedMoves.add(planned);
        }
        plannedMovesByBookFileId.clear();
        return committedMoves;
    }

    private Set<Path> collectLibraryRoots(LibraryEntity targetLibrary, BookEntity bookEntity) {
        Set<Path> libraryRoots = targetLibrary.getLibraryPaths().stream()
                .map(LibraryPathEntity::getPath)
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .collect(Collectors.toCollection(HashSet::new));
        // Also protect the source library root so cleanup doesn't traverse above it
        libraryRoots.add(Paths.get(bookEntity.getLibraryPath().getPath()).toAbsolutePath().normalize());
        return libraryRoots;
    }

    private Set<Path> collectLibraryRootsById(Long libraryId, BookEntity bookWithFiles) {
        Set<Path> libraryRoots = libraryRepository.findByIdWithPaths(libraryId)
                .map(lib -> lib.getLibraryPaths().stream()
                        .map(LibraryPathEntity::getPath)
                        .map(Paths::get)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .collect(Collectors.toCollection(HashSet::new)))
                .orElseGet(HashSet::new);
        // Also protect the source library root so cleanup doesn't traverse above it
        libraryRoots.add(Paths.get(bookWithFiles.getLibraryPath().getPath()).toAbsolutePath().normalize());
        return libraryRoots;
    }

    private void cleanupSourceParents(Set<Path> sourceParentsToCleanup, Set<Path> libraryRoots) {
        for (Path sourceParent : sourceParentsToCleanup) {
            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(sourceParent, libraryRoots);
        }
    }

    private void reRegisterLibrary(Long libraryId, Path libraryRoot) {
        log.debug("Registering library paths for library {} with root {}", libraryId, libraryRoot);
        libraryRepository.findByIdWithPaths(libraryId).ifPresent(lib -> {
            Library library = libraryMapper.toLibrary(lib);
            library.setWatch(true);
            monitoringRegistrationService.registerLibrary(library);
        });
    }

    private record PlannedMove(Path source, Path temp, Path target) {}
}
