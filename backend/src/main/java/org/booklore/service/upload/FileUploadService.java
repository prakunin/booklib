package org.booklore.service.upload;

import lombok.RequiredArgsConstructor;
import org.booklore.config.AppProperties;
import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.mapper.AdditionalFileMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.file.FileMovingHelper;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.util.FileUtils;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.PathPatternResolver;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.booklore.model.enums.AuditAction;
import org.booklore.service.audit.AuditService;
import java.nio.file.Paths;

@RequiredArgsConstructor
@Service
@Slf4j
public class FileUploadService {

    private static final String UPLOAD_TEMP_PREFIX = "upload-";
    private static final String BOOKDROP_TEMP_PREFIX = "bookdrop-";
    private static final long BYTES_TO_KB_DIVISOR = 1024L;
    private static final long MB_TO_BYTES_MULTIPLIER = 1024L * 1024L;
    private static final String INVALID_UPLOAD_TARGET_PATH_MESSAGE = "Invalid upload target path";

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository additionalFileRepository;
    private final AppSettingService appSettingService;
    private final AppProperties appProperties;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final AdditionalFileMapper additionalFileMapper;
    private final FileMovingHelper fileMovingHelper;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final AuditService auditService;

    @Transactional
    public void uploadFile(MultipartFile file, long libraryId, long pathId) {
        validateFile(file);

        final LibraryEntity libraryEntity = findLibraryById(libraryId);
        final LibraryPathEntity libraryPathEntity = findLibraryPathById(libraryEntity, pathId);
        final Path libraryRoot = FileUtils.normalizeAbsolutePath(Path.of(libraryPathEntity.getPath()));
        final String originalFileName = getValidatedFileName(file);
        final BookFileExtension fileExtension = getFileExtension(originalFileName);
        validateAllowedFormat(libraryEntity, fileExtension.getType());

        Path tempPath = null;
        try {
            tempPath = createTempFile(UPLOAD_TEMP_PREFIX, originalFileName);
            file.transferTo(tempPath);
            final BookMetadata metadata = extractMetadata(fileExtension, tempPath.toFile(), originalFileName);
            final String uploadPattern = fileMovingHelper.getFileNamingPattern(libraryEntity);

            final String relativePath = PathPatternResolver.resolvePattern(metadata, uploadPattern, originalFileName);
            final Path finalPath = resolvePathWithinRoot(libraryRoot, relativePath);

            validateFinalPath(finalPath, libraryRoot);
            moveFileToFinalLocation(tempPath, finalPath, libraryRoot);

            log.info("File uploaded to final location: {}", finalPath);
            auditService.log(AuditAction.BOOK_UPLOADED, "Library", libraryId, "Uploaded file: " + originalFileName);

        } catch (IOException e) {
            log.error("Failed to upload file: {}", originalFileName, e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        } finally {
            cleanupTempFile(tempPath);
        }
    }

    @Transactional
    public BookFile uploadAdditionalFile(Long bookId, MultipartFile file, boolean isBook, BookFileType bookType, String description) {
        final BookEntity book = findBookById(bookId);
        final String originalFileName = getValidatedFileName(file);
        final Long libraryId = book.getLibrary() != null ? book.getLibrary().getId() : null;
        final String sanitizedFileName = PathPatternResolver.truncateFilenameWithExtension(originalFileName);
        final boolean wasPhysicalBook = Boolean.TRUE.equals(book.getIsPhysical());

        Path tempPath = null;
        boolean monitoringUnregistered = false;
        try {
            tempPath = createTempFile(UPLOAD_TEMP_PREFIX, sanitizedFileName);
            file.transferTo(tempPath);

            final String fileHash = FileFingerprint.generateHash(tempPath);
            if (isBook) {
                validateAlternativeFormatDuplicate(fileHash);
            }

            final ResolvedUploadTarget target = resolveUploadTarget(book, sanitizedFileName, wasPhysicalBook, isBook, bookType);
            validateFinalPath(target.finalPath(), target.finalRootPath());

            if (libraryId != null) {
                log.debug("Unregistering library {} for monitoring", libraryId);
                monitoringRegistrationService.unregisterLibrary(libraryId);
                monitoringUnregistered = true;
            }
            moveFileToFinalLocation(tempPath, target.finalPath(), target.finalRootPath());

            log.info("Additional file uploaded to final location: {}", target.finalPath());

            final BookFileEntity entity = createAdditionalFileEntityWithSubPath(book, target.finalFileName(), target.fileSubPath(), isBook, target.effectiveBookType(), file.getSize(), fileHash, description);
            final BookFileEntity savedEntity = additionalFileRepository.save(entity);

            return additionalFileMapper.toAdditionalFile(savedEntity);

        } catch (IOException e) {
            log.error("Failed to upload additional file for book {}: {}", bookId, sanitizedFileName, e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        } finally {
            if (monitoringUnregistered) {
                reRegisterLibraryMonitoring(book, libraryId);
            }
            cleanupTempFile(tempPath);
        }
    }

    private record ResolvedUploadTarget(Path finalPath, Path finalRootPath, String finalFileName,
                                        String fileSubPath, BookFileType effectiveBookType) {}

    private ResolvedUploadTarget resolveUploadTarget(BookEntity book, String sanitizedFileName,
                                                     boolean wasPhysicalBook, boolean isBook, BookFileType bookType) {
        // Handle physical books or books that lost all their files
        if (wasPhysicalBook || book.getPrimaryBookFile() == null) {
            return resolveTargetForPhysicalOrOrphanBook(book, sanitizedFileName);
        } else if (isBook) {
            return resolveTargetForAlternativeFormat(book, sanitizedFileName);
        } else {
            return resolveTargetForAdditionalFile(book, sanitizedFileName, bookType);
        }
    }

    private ResolvedUploadTarget resolveTargetForPhysicalOrOrphanBook(BookEntity book, String sanitizedFileName) {
        LibraryPathEntity libraryPath = book.getLibraryPath();
        if (libraryPath == null) {
            libraryPath = determineLibraryPathForPhysicalBook(book);
            book.setLibraryPath(libraryPath);
        }

        String pattern = fileMovingHelper.getFileNamingPattern(book.getLibrary());
        String resolvedRelativePath = PathPatternResolver.resolvePattern(book.getMetadata(), pattern, sanitizedFileName);
        Path safeRelativePath = toSafeRelativePath(resolvedRelativePath);
        String finalFileName = safeRelativePath.getFileName().toString();
        String fileSubPath = safeRelativePath.getParent() != null
            ? safeRelativePath.getParent().toString()
                : "";
        Path finalRootPath = FileUtils.normalizeAbsolutePath(Path.of(libraryPath.getPath()));
        Path finalPath = resolvePathWithinRoot(finalRootPath, safeRelativePath.toString());
        String extension = sanitizedFileName.substring(sanitizedFileName.lastIndexOf('.') + 1);
        BookFileType effectiveBookType = BookFileType.fromExtension(extension)
                .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported book file extension: " + extension));
        return new ResolvedUploadTarget(finalPath, finalRootPath, finalFileName, fileSubPath, effectiveBookType);
    }

    private ResolvedUploadTarget resolveTargetForAlternativeFormat(BookEntity book, String sanitizedFileName) {
        String pattern = fileMovingHelper.getFileNamingPattern(book.getLibrary());
        String resolvedRelativePath = PathPatternResolver.resolvePattern(book.getMetadata(), pattern, sanitizedFileName);
        Path safeRelativePath = toSafeRelativePath(resolvedRelativePath);
        String finalFileName = safeRelativePath.getFileName().toString();
        String fileSubPath = book.getPrimaryBookFile().getFileSubPath();
        Path finalRootPath = FileUtils.normalizeAbsolutePath(Path.of(book.getLibraryPath().getPath()));
        Path finalPath = buildAdditionalFilePath(book, finalFileName);
        String extension = sanitizedFileName.substring(sanitizedFileName.lastIndexOf('.') + 1);
        BookFileType effectiveBookType = BookFileType.fromExtension(extension)
                .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported book file extension: " + extension));
        return new ResolvedUploadTarget(finalPath, finalRootPath, finalFileName, fileSubPath, effectiveBookType);
    }

    private ResolvedUploadTarget resolveTargetForAdditionalFile(BookEntity book, String sanitizedFileName, BookFileType bookType) {
        String finalFileName = sanitizedFileName;
        String fileSubPath = book.getPrimaryBookFile().getFileSubPath();
        Path finalRootPath = FileUtils.normalizeAbsolutePath(Path.of(book.getLibraryPath().getPath()));
        Path finalPath = buildAdditionalFilePath(book, sanitizedFileName);
        return new ResolvedUploadTarget(finalPath, finalRootPath, finalFileName, fileSubPath, bookType);
    }

    private void reRegisterLibraryMonitoring(BookEntity book, Long libraryId) {
        try {
            if (book.getLibrary() != null && book.getLibrary().getLibraryPaths() != null) {
                for (LibraryPathEntity libPath : book.getLibrary().getLibraryPaths()) {
                    Path libraryRoot = Path.of(libPath.getPath());
                    log.debug("Re-registering library {} for monitoring", libraryId);
                    monitoringRegistrationService.registerLibraryPaths(libraryId, libraryRoot);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to re-register library {} for monitoring after additional file upload: {}", libraryId, e.getMessage());
        }
    }

    private LibraryPathEntity determineLibraryPathForPhysicalBook(BookEntity book) {
        if (book.getLibrary() == null || book.getLibrary().getLibraryPaths() == null || book.getLibrary().getLibraryPaths().isEmpty()) {
            throw new IllegalStateException("Cannot upload file to physical book: library has no paths configured");
        }
        // Use the first library path for physical books
        return book.getLibrary().getLibraryPaths().getFirst();
    }

    // Each parameter is an independently-derived local at the single call site (no existing
    // cohesive DTO groups them); a param object here would be artificial. sonar java:S107.
    @SuppressWarnings("java:S107")
    private BookFileEntity createAdditionalFileEntityWithSubPath(BookEntity book, String fileName, String fileSubPath, boolean isBook, BookFileType bookType, long fileSize, String fileHash, String description) {
        return BookFileEntity.builder()
                .book(book)
                .fileName(fileName)
                .fileSubPath(fileSubPath)
                .isBookFormat(isBook)
                .bookType(bookType)
                .fileSizeKb(fileSize / BYTES_TO_KB_DIVISOR)
                .initialHash(fileHash)
                .currentHash(fileHash)
                .description(description)
                .addedOn(Instant.now())
                .build();
    }

    public Book uploadFileBookDrop(MultipartFile file) throws IOException {
        validateFile(file);

        final Path dropFolder = FileUtils.normalizeAbsolutePath(Path.of(appProperties.getBookdropFolder()));
        Files.createDirectories(dropFolder);

        final String originalFilename = getValidatedFileName(file);
        final String sanitizedFilename = PathPatternResolver.truncateFilenameWithExtension(originalFilename);
        Path tempPath = null;

        try {
            tempPath = createTempFile(BOOKDROP_TEMP_PREFIX, sanitizedFilename);
            file.transferTo(tempPath);

            final Path finalPath = resolvePathWithinRoot(dropFolder, sanitizedFilename);
            validateFinalPath(finalPath, dropFolder);
            moveFileToFinalLocation(tempPath, finalPath, dropFolder);

            log.info("File moved to book-drop folder: {}", finalPath);
            return null;

        } finally {
            cleanupTempFile(tempPath);
        }
    }

    private LibraryEntity findLibraryById(long libraryId) {
        return libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
    }

    private LibraryPathEntity findLibraryPathById(LibraryEntity libraryEntity, long pathId) {
        return libraryEntity.getLibraryPaths()
                .stream()
                .filter(p -> p.getId() == pathId)
                .findFirst()
                .orElseThrow(() -> ApiError.INVALID_LIBRARY_PATH.createException(libraryEntity.getId()));
    }

    private BookEntity findBookById(Long bookId) {
        return bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found with id: " + bookId));
    }

    private String getValidatedFileName(MultipartFile file) {
        final String originalFileName = file.getOriginalFilename();
        if (originalFileName == null) {
            throw new IllegalArgumentException("File must have a name");
        }
        // Prevent Path Traversal by extracting only the base file name
        String cleanPath = StringUtils.cleanPath(originalFileName);
        String baseFileName = StringUtils.getFilename(cleanPath);
        if (baseFileName == null || baseFileName.isEmpty() || baseFileName.equals("..")) {
            throw new IllegalArgumentException("Invalid filename");
        }
        return baseFileName;
    }

    private BookFileExtension getFileExtension(String fileName) {
        return BookFileExtension.fromFileName(fileName)
                .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));
    }

    private Path createTempFile(String prefix, String fileName) throws IOException {
        String suffix = "";
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex >= 0) {
            suffix = fileName.substring(lastDotIndex);
        }
        return Files.createTempFile(prefix, suffix);
    }

    private void validateFinalPath(Path finalPath, Path expectedRoot) {
        Path safeFinalPath = requirePathWithinRoot(finalPath, expectedRoot, INVALID_UPLOAD_TARGET_PATH_MESSAGE);
        if (Files.exists(safeFinalPath)) {
            throw ApiError.FILE_ALREADY_EXISTS.createException();
        }
    }

    private void moveFileToFinalLocation(Path sourcePath, Path targetPath, Path expectedRoot) throws IOException {
        Path safeSourcePath = requirePathWithinSystemTemp(sourcePath);
        Path safeTargetPath = requirePathWithinRoot(targetPath, expectedRoot, INVALID_UPLOAD_TARGET_PATH_MESSAGE);

        Files.createDirectories(safeTargetPath.getParent());
        Files.move(safeSourcePath, safeTargetPath);
    }

    private void validateAlternativeFormatDuplicate(String fileHash) {
        final Optional<BookFileEntity> existingAltFormat = additionalFileRepository.findByAltFormatCurrentHash(fileHash);
        if (existingAltFormat.isPresent()) {
            throw new IllegalArgumentException("Alternative format file already exists with same content");
        }
    }

    private Path buildAdditionalFilePath(BookEntity book, String fileName) {
        final BookFileEntity primaryFile = book.getPrimaryBookFile();
        final Path libraryRoot = FileUtils.normalizeAbsolutePath(Path.of(book.getLibraryPath().getPath()));
        final String safeRelativePath = buildSafeRelativePath(primaryFile.getFileSubPath(), fileName);
        return resolvePathWithinRoot(libraryRoot, safeRelativePath);
    }

    private void cleanupTempFile(Path tempPath) {
        if (tempPath != null) {
            try {
                Path safeTempPath = requirePathWithinSystemTemp(tempPath);
                Files.deleteIfExists(safeTempPath);
            } catch (RuntimeException _) {
                log.warn("Skipping cleanup for suspicious temp file path: {}", tempPath);
            } catch (IOException e) {
                log.warn("Failed to cleanup temp file: {}", tempPath, e);
            }
        }
    }

    private Path resolvePathWithinRoot(Path rootPath, String relativePath) {
        try {
            return FileUtils.resolvePathWithinBase(rootPath, relativePath);
        } catch (IllegalArgumentException _) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(INVALID_UPLOAD_TARGET_PATH_MESSAGE);
        }
    }

    private Path toSafeRelativePath(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(INVALID_UPLOAD_TARGET_PATH_MESSAGE);
        }

        try {
            Path parsed = Path.of(relativePath);
            if (parsed.isAbsolute()) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(INVALID_UPLOAD_TARGET_PATH_MESSAGE);
            }
            Path normalized = parsed.normalize();
            if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(INVALID_UPLOAD_TARGET_PATH_MESSAGE);
            }
            return normalized;
        } catch (APIException e) {
            throw e;
        } catch (RuntimeException _) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(INVALID_UPLOAD_TARGET_PATH_MESSAGE);
        }
    }

    private String buildSafeRelativePath(String subPath, String fileName) {
        Path safeFileNamePath = toSafeRelativePath(fileName);
        if (safeFileNamePath.getNameCount() != 1) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(INVALID_UPLOAD_TARGET_PATH_MESSAGE);
        }

        if (!StringUtils.hasText(subPath)) {
            return safeFileNamePath.toString();
        }

        Path safeSubPath = toSafeRelativePath(subPath);
        Path combined = safeSubPath.resolve(safeFileNamePath).normalize();
        if (combined.isAbsolute() || combined.startsWith("..")) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(INVALID_UPLOAD_TARGET_PATH_MESSAGE);
        }

        return combined.toString();
    }

    private Path requirePathWithinRoot(Path candidatePath, Path rootPath, String errorMessage) {
        try {
            return FileUtils.requirePathWithinBase(candidatePath, rootPath);
        } catch (IllegalArgumentException _) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(errorMessage);
        }
    }

    private Path requirePathWithinSystemTemp(Path candidatePath) {
        Path tempRoot = FileUtils.normalizeAbsolutePath(Path.of(System.getProperty("java.io.tmpdir")));
        return requirePathWithinRoot(candidatePath, tempRoot, "Invalid temporary file path");
    }

    private BookMetadata extractMetadata(BookFileExtension fileExt, File file, String originalFileName) {
        BookMetadata metadata = metadataExtractorFactory.extractMetadata(fileExt, file);

        // If the metadata title is the same as the temporary file's base name (which happens
        // when CBX files have no embedded metadata), use the original filename as the title instead
        String tempFileBaseName = Paths.get(file.getName()).getFileName().toString();
        int lastDotIndex = tempFileBaseName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            tempFileBaseName = tempFileBaseName.substring(0, lastDotIndex);
        }

        String originalFileBaseName = originalFileName;
        lastDotIndex = originalFileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            originalFileBaseName = originalFileName.substring(0, lastDotIndex);
        }

        if (metadata.getTitle() != null && (metadata.getTitle().equals(tempFileBaseName) || metadata.getTitle().startsWith(UPLOAD_TEMP_PREFIX))) {
            metadata.setTitle(originalFileBaseName);
        }

        return metadata;
    }

    private void validateFile(MultipartFile file) {
        final String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || BookFileExtension.fromFileName(originalFilename).isEmpty()) {
            throw ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension");
        }

        final int maxSizeMb = appSettingService.getAppSettings().getMaxFileUploadSizeInMb();
        if (file.getSize() > maxSizeMb * MB_TO_BYTES_MULTIPLIER) {
            throw ApiError.FILE_TOO_LARGE.createException(maxSizeMb);
        }
    }

    private void validateAllowedFormat(LibraryEntity library, BookFileType fileType) {
        var allowedFormats = library.getAllowedFormats();
        if (allowedFormats != null && !allowedFormats.isEmpty() && !allowedFormats.contains(fileType)) {
            throw ApiError.FORMAT_NOT_ALLOWED.createException(fileType.name(), library.getName());
        }
    }
}
