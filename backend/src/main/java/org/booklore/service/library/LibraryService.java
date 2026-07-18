package org.booklore.service.library;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.LibraryPath;
import org.booklore.model.dto.request.CreateLibraryRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.inpx.InpxScanControl;
import org.booklore.service.monitoring.LibraryWatchService;
import org.booklore.task.options.RescanLibraryContext;
import org.booklore.util.FileService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.event.EventListener;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

@Slf4j
@Service
@AllArgsConstructor
@DependsOnDatabaseInitialization
@Transactional(readOnly = true)
public class LibraryService {

    private final LibraryRepository libraryRepository;
    private final LibraryPathRepository libraryPathRepository;
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final LibraryMapper libraryMapper;
    private final NotificationService notificationService;
    private final FileService fileService;
    private final LibraryWatchService libraryWatchService;
    private final AuthenticationService authenticationService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final LibraryProcessingService libraryProcessingService;
    private final Executor taskExecutor;
    private final InpxScanControl inpxScanControl;
    private final ConcurrentMap<Long, Boolean> scanningLibraries = new ConcurrentHashMap<>();

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void initializeMonitoring() {
        List<Library> libraries = libraryRepository.findAll().stream()
                .filter(library -> library.getSourceType() != LibrarySourceType.INPX)
                .map(libraryMapper::toLibrary)
                .toList();
        libraryWatchService.registerLibraries(libraries);
        log.info("Monitoring initialized with {} libraries", libraries.size());
    }

    @Transactional
    public Library updateLibrary(CreateLibraryRequest request, Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        LibrarySourceType previousSourceType = library.getSourceType();
        applyAndValidateSource(request, library);

        library.setName(request.getName());
        library.setIcon(request.getIcon());
        library.setIconType(request.getIconType());
        library.setWatch(library.getSourceType() == LibrarySourceType.FILESYSTEM && request.isWatch());
        library.setFormatPriority(request.getFormatPriority());
        library.setAllowedFormats(request.getAllowedFormats());
        if (request.getMetadataSource() != null) {
            library.setMetadataSource(request.getMetadataSource());
        }
        if (request.getOrganizationMode() != null) {
            library.setOrganizationMode(request.getOrganizationMode());
        }

        Set<String> currentPaths = library.getLibraryPaths().stream()
                .map(LibraryPathEntity::getPath)
                .collect(Collectors.toSet());
        Set<String> updatedPaths = request.getPaths().stream()
                .map(LibraryPath::getPath)
                .collect(Collectors.toSet());

        Set<String> deletedPaths = currentPaths.stream()
                .filter(path -> !updatedPaths.contains(path))
                .collect(Collectors.toSet());
        Set<String> newPaths = updatedPaths.stream()
                .filter(path -> !currentPaths.contains(path))
                .collect(Collectors.toSet());

        if (!deletedPaths.isEmpty()) {
            Set<LibraryPathEntity> pathsToRemove = library.getLibraryPaths().stream()
                    .filter(pathEntity -> deletedPaths.contains(pathEntity.getPath()))
                    .collect(Collectors.toSet());

            library.getLibraryPaths().removeAll(pathsToRemove);
            List<Long> books = bookRepository.findAllBookIdsByLibraryPathIdIn(
                    pathsToRemove.stream().map(LibraryPathEntity::getId).collect(Collectors.toSet()));

            if (!books.isEmpty()) {
                notificationService.sendMessage(Topic.BOOKS_REMOVE, books);
            }

            libraryPathRepository.deleteAll(pathsToRemove);
        }

        if (!newPaths.isEmpty()) {
            Set<LibraryPathEntity> newPathEntities = newPaths.stream()
                    .map(path -> LibraryPathEntity.builder().path(path).library(library).build())
                    .collect(Collectors.toSet());

            library.getLibraryPaths().addAll(newPathEntities);
            libraryPathRepository.saveAll(library.getLibraryPaths());
        }

        LibraryEntity savedLibrary = libraryRepository.save(library);

        if (library.getSourceType() == LibrarySourceType.FILESYSTEM && request.isWatch()) {
            libraryWatchService.registerLibraries(List.of(libraryMapper.toLibrary(savedLibrary)));
        } else {
            libraryWatchService.unregisterLibrary(libraryId);
        }

        if (!newPaths.isEmpty() || previousSourceType != library.getSourceType()
                || library.getSourceType() == LibrarySourceType.INPX) {
            scheduleBackgroundScanAfterCommit(libraryId);
        }

        auditService.log(AuditAction.LIBRARY_UPDATED, "Library", libraryId, "Updated library: " + library.getName());
        return libraryMapper.toLibrary(savedLibrary);
    }

    @Transactional
    public Library createLibrary(CreateLibraryRequest request) {
        BookLoreUser bookLoreUser = authenticationService.getAuthenticatedUser();
        BookLoreUserEntity userEntity = userRepository.findById(bookLoreUser.getId())
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(bookLoreUser.getId()));

        // Stream.toList() returns an unmodifiable list, while JPA/Hibernate requires a mutable collection for entity
        // relationship fields
        LibrarySourceType sourceType = sourceType(request);
        validateSource(request, sourceType);

        LibraryEntity libraryEntity = LibraryEntity.builder()
                .name(request.getName())
                .libraryPaths(
                        request.getPaths() == null || request.getPaths().isEmpty() ?
                                Collections.emptyList() :
                                new ArrayList<>(request.getPaths().stream()
                                        .map(path -> LibraryPathEntity.builder().path(path.getPath()).build())
                                        .toList())
                )
                .icon(request.getIcon())
                .iconType(request.getIconType())
                .watch(sourceType == LibrarySourceType.FILESYSTEM && request.isWatch())
                .sourceType(sourceType)
                .inpxPath(sourceType == LibrarySourceType.INPX ? normalizedOrNull(request.getInpxPath()) : null)
                .inpxArchivePath(sourceType == LibrarySourceType.INPX ? normalized(request.getInpxArchivePath()) : null)
                .formatPriority(request.getFormatPriority())
                .allowedFormats(request.getAllowedFormats())
                .metadataSource(request.getMetadataSource())
                .organizationMode(request.getOrganizationMode())
                .users(new HashSet<>(Set.of(userEntity)))
                .build();

        for (LibraryPathEntity p : libraryEntity.getLibraryPaths()) {
            p.setLibrary(libraryEntity);
        }

        libraryEntity = libraryRepository.save(libraryEntity);
        Long libraryId = libraryEntity.getId();

        if (sourceType == LibrarySourceType.FILESYSTEM && request.isWatch()) {
            for (LibraryPathEntity pathEntity : libraryEntity.getLibraryPaths()) {
                Path path = Paths.get(pathEntity.getPath());
                libraryWatchService.registerPath(path, libraryId);
            }
        }

        scheduleBackgroundScanAfterCommit(libraryId);

        auditService.log(AuditAction.LIBRARY_CREATED, "Library", libraryEntity.getId(), "Created library: " + libraryEntity.getName());
        return libraryMapper.toLibrary(libraryEntity);
    }

    @Transactional
    public void rescanLibrary(long libraryId) {
        LibraryEntity lib = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        auditService.log(AuditAction.LIBRARY_SCANNED, "Library", libraryId, "Scanned library: " + lib.getName());

        taskExecutor.execute(() -> {
            if (!beginScan(libraryId)) {
                log.warn("Library {} is already being scanned, skipping duplicate rescan request", libraryId);
                return;
            }
            try {
                RescanLibraryContext context = RescanLibraryContext.builder()
                        .libraryId(libraryId)
                        .build();
                libraryProcessingService.rescanLibrary(context);
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("InvalidDataAccessApiUsageException - Library id: {}", libraryId);
            } catch (IOException e) {
                log.error("Error while parsing library books", e);
            } finally {
                scanningLibraries.remove(libraryId);
            }
            log.info("Parsing task completed!");
        });
    }

    /**
     * Opens the scan guard for a library, clearing any leftover cancellation flag as part of the
     * same atomic step.
     * <p>
     * Doing both inside the mapping function is what makes cancellation safe. {@link #cancelScan}
     * only accepts a cancellation once the key is visible, and the map publishes the key only after
     * the mapping function returns, so a cancellation can never be accepted before the clear (it
     * would be silently wiped) nor wiped after being accepted. Clearing here also disarms a flag
     * stranded by a cancellation that raced a finishing scan, which would otherwise abort the next
     * scan at its first batch boundary. A duplicate request does not run the mapping function, so a
     * running scan's accepted cancellation survives.
     *
     * @return false when a scan is already running for this library.
     */
    private boolean beginScan(long libraryId) {
        boolean[] opened = {false};
        scanningLibraries.computeIfAbsent(libraryId, id -> {
            inpxScanControl.clear(id);
            opened[0] = true;
            return Boolean.TRUE;
        });
        return opened[0];
    }

    public void cancelScan(long libraryId) {
        // Only forward the cancellation while a scan is actually running, so a cancel that races a
        // scan's terminal event cannot strand a flag. beginScan clears any that slips through.
        if (!scanningLibraries.containsKey(libraryId)) {
            log.info("Ignoring cancellation for library {}: no scan is currently running", libraryId);
            return;
        }
        inpxScanControl.requestCancel(libraryId);
        log.info("Cancellation requested for the scan of library {}", libraryId);
    }

    public Library getLibrary(long libraryId) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        return libraryMapper.toLibrary(libraryEntity);
    }

    public List<Library> getAllLibraries() {
        List<LibraryEntity> libraries = libraryRepository.findAll();
        return libraries.stream().map(libraryMapper::toLibrary).toList();
    }

    public List<Library> getLibraries() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        BookLoreUserEntity userEntity = userRepository.findByIdWithLibraries(user.getId()).orElseThrow(() -> new UsernameNotFoundException("User not found"));
        List<LibraryEntity> libraries;
        if (userEntity.getPermissions().isPermissionAdmin()) {
            libraries = libraryRepository.findAll();
        } else {
            List<Long> libraryIds = userEntity.getLibraries().stream().map(LibraryEntity::getId).toList();
            libraries = libraryRepository.findByIdIn(libraryIds);
        }
        return libraries.stream().map(libraryMapper::toLibrary).toList();
    }

    @Transactional
    public void deleteLibrary(long id) {
        LibraryEntity library = libraryRepository.findById(id)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(id));
        libraryWatchService.unregisterLibrary(id);
        Set<Long> bookIds = bookRepository.findBookIdsByLibraryId(id);
        fileService.deleteBookCovers(bookIds);
        String libraryName = library.getName();
        libraryRepository.deleteById(id);
        auditService.log(AuditAction.LIBRARY_DELETED, "Library", id, "Deleted library: " + libraryName);
        log.info("Library deleted successfully: {}", id);
    }

    public Book getBook(long libraryId, long bookId) {
        libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId).filter(b -> b.getLibrary().getId() == libraryId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        return bookMapper.toBook(bookEntity);
    }

    public long getBookCount(long libraryId) {
        libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        return bookRepository.countByLibraryIdNonDeleted(libraryId);
    }

    public List<Book> getBooks(long libraryId) {
        libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        List<BookEntity> bookEntities = bookRepository.findAllWithMetadataByLibraryId(libraryId);
        return bookEntities.stream().map(bookMapper::toBook).toList();
    }

    @Transactional
    public Library setFileNamingPattern(long libraryId, String pattern) {
        LibraryEntity library = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        library.setFileNamingPattern(pattern);
        Library result = libraryMapper.toLibrary(libraryRepository.save(library));
        auditService.log(AuditAction.NAMING_PATTERN_CHANGED, "Library", libraryId, "Changed naming pattern for library: " + library.getName() + " to: " + pattern);
        return result;
    }

    public Map<String, Long> getBookCountsByFormat(long libraryId) {
        libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        Map<String, Long> counts = new HashMap<>();
        for (BookFileType type : BookFileType.values()) {
            long count = bookRepository.countByLibraryIdAndBookType(libraryId, type);
            if (count > 0) {
                counts.put(type.name(), count);
            }
        }
        return counts;
    }

    public int scanLibraryPaths(CreateLibraryRequest request) {
        if (sourceType(request) == LibrarySourceType.INPX) {
            validateSource(request, LibrarySourceType.INPX);
            return 0;
        }
        int count = 0;
        if (request.getPaths() == null || request.getPaths().isEmpty()) {
            return count;
        }
        Set<BookFileType> allowedFormats = request.getAllowedFormats() != null && !request.getAllowedFormats().isEmpty()
                ? Set.copyOf(request.getAllowedFormats())
                : null;
        for (LibraryPath libraryPath : request.getPaths()) {
            Path path = Paths.get(libraryPath.getPath());
            if (!Files.exists(path)) {
                log.warn("Path does not exist: {}", path);
                continue;
            }
            if (Files.isDirectory(path)) {
                count += scanDirectory(path, allowedFormats);
            } else if (Files.isRegularFile(path) && isProcessableFile(path, allowedFormats)) {
                count++;
            }
        }
        return count;
    }

    private int scanDirectory(Path directory, Set<BookFileType> allowedFormats) {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    count += scanDirectory(entry, allowedFormats);
                } else if (Files.isRegularFile(entry) && isProcessableFile(entry, allowedFormats)) {
                    count++;
                }
            }
        } catch (IOException e) {
            log.error("Error scanning directory: {}", directory, e);
        }
        return count;
    }

    private boolean isProcessableFile(Path file, Set<BookFileType> allowedFormats) {
        String fileName = file.getFileName().toString().toLowerCase();
        for (BookFileType fileType : BookFileType.values()) {
            if (allowedFormats != null && !allowedFormats.contains(fileType)) {
                continue;
            }
            for (String ext : fileType.getExtensions()) {
                if (fileName.endsWith("." + ext)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyAndValidateSource(CreateLibraryRequest request, LibraryEntity library) {
        LibrarySourceType sourceType = sourceType(request);
        validateSource(request, sourceType);
        library.setSourceType(sourceType);
        library.setInpxPath(sourceType == LibrarySourceType.INPX ? normalizedOrNull(request.getInpxPath()) : null);
        library.setInpxArchivePath(sourceType == LibrarySourceType.INPX ? normalized(request.getInpxArchivePath()) : null);
    }

    private void validateSource(CreateLibraryRequest request, LibrarySourceType sourceType) {
        if (sourceType != LibrarySourceType.INPX) {
            return;
        }
        Path archivePath = requiredPath(request.getInpxArchivePath(), "INPX archive directory is required");
        if (!Files.isDirectory(archivePath) || !Files.isReadable(archivePath)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("INPX archive path must be a readable directory");
        }
        if (request.getInpxPath() == null || request.getInpxPath().isBlank()) {
            return;
        }
        Path inpxPath = requiredPath(request.getInpxPath(), "INPX index path is required");
        if (!Files.isRegularFile(inpxPath) || !Files.isReadable(inpxPath)
                || !inpxPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".inpx")) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("INPX index must be a readable .inpx file");
        }
        validateInpxArchive(inpxPath);
    }

    private Path requiredPath(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(message);
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(message);
        }
    }

    private void validateInpxArchive(Path inpxPath) {
        try (ZipFile inpx = new ZipFile(inpxPath.toFile())) {
            boolean containsIndex = inpx.stream()
                    .anyMatch(entry -> !entry.isDirectory()
                            && entry.getName().toLowerCase(Locale.ROOT).endsWith(".inp"));
            if (!containsIndex) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("INPX index does not contain any .inp catalogs");
            }
        } catch (IOException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("INPX index is empty or invalid");
        }
    }

    private String normalized(String value) {
        return Path.of(value).toAbsolutePath().normalize().toString();
    }

    private String normalizedOrNull(String value) {
        return value == null || value.isBlank() ? null : normalized(value);
    }

    private LibrarySourceType sourceType(CreateLibraryRequest request) {
        return request.getSourceType() == null ? LibrarySourceType.FILESYSTEM : request.getSourceType();
    }

    private void scheduleBackgroundScanAfterCommit(long libraryId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    startBackgroundScan(libraryId);
                }
            });
        } else {
            startBackgroundScan(libraryId);
        }
    }

    private void startBackgroundScan(long libraryId) {
        taskExecutor.execute(() -> {
            if (!beginScan(libraryId)) {
                log.warn("Library {} is already being scanned, skipping duplicate process request", libraryId);
                return;
            }
            try {
                libraryProcessingService.processLibrary(libraryId);
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("InvalidDataAccessApiUsageException - Library id: {}", libraryId);
            } finally {
                scanningLibraries.remove(libraryId);
            }
            log.info("Parsing task completed!");
        });
    }
}
