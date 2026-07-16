package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.dto.inpx.InpxBookReference;
import org.booklore.model.dto.inpx.InpxImportRequest;
import org.booklore.model.dto.inpx.InpxImportResult;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.LibraryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class InpxImportService {

    private static final long MAX_EXTRACTED_BOOK_SIZE = 1024L * 1024L * 1024L;
    private static final int MAX_REPORTED_ERRORS = 50;

    private final InpxParser inpxParser;
    private final LibraryRepository libraryRepository;

    @Transactional(readOnly = true)
    public InpxImportResult importBooks(InpxImportRequest request) {
        LibraryEntity library = libraryRepository.findById(request.getLibraryId())
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(request.getLibraryId()));
        LibraryPathEntity libraryPath = library.getLibraryPaths().stream()
                .filter(path -> path.getId().equals(request.getLibraryPathId()))
                .findFirst()
                .orElseThrow(() -> ApiError.GENERIC_BAD_REQUEST.createException(
                        "Library path does not belong to the selected library"));

        Path destinationRoot = normalizeDirectory(libraryPath.getPath(), true);
        Path archiveRoot = resolveArchiveRoot(request);
        Map<String, InpxBookDto> resolved = inpxParser.resolve(request.getInpxPath(), request.getBooks());

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        Map<String, ZipFile> openArchives = new HashMap<>();

        try {
            for (InpxBookReference reference : request.getBooks()) {
                String id = InpxParser.id(reference.getArchiveName(), reference.getFileName(), reference.getExtension());
                InpxBookDto book = resolved.get(id);
                if (book == null) {
                    failed++;
                    addError(errors, "Book is not present in the INPX index: " + id);
                    continue;
                }

                try {
                    ImportOutcome outcome = extractBook(book, archiveRoot, destinationRoot, openArchives);
                    if (outcome == ImportOutcome.IMPORTED) {
                        imported++;
                    } else {
                        skipped++;
                    }
                } catch (IOException | IllegalArgumentException e) {
                    failed++;
                    addError(errors, book.getTitle() + ": " + e.getMessage());
                    log.warn("Failed to import INPX book {} from {}: {}", book.getId(), book.getArchiveName(), e.getMessage());
                }
            }
        } finally {
            openArchives.values().forEach(this::closeQuietly);
        }

        return InpxImportResult.builder()
                .imported(imported)
                .skipped(skipped)
                .failed(failed)
                .errors(errors)
                .build();
    }

    private ImportOutcome extractBook(InpxBookDto book, Path archiveRoot, Path destinationRoot,
                                      Map<String, ZipFile> openArchives) throws IOException {
        String archiveName = safeFileName(book.getArchiveName(), ".zip");
        String entryName = safeFileName(book.getFileName(), "") + "." + safeExtension(book.getExtension());
        Path archivePath = archiveRoot.resolve(archiveName).normalize();
        if (!archivePath.startsWith(archiveRoot) || !Files.isRegularFile(archivePath) || !Files.isReadable(archivePath)) {
            throw new IOException("Archive is missing or unreadable: " + archiveName);
        }

        ZipFile archive = openArchives.get(archiveName);
        if (archive == null) {
            archive = new ZipFile(archivePath.toFile());
            openArchives.put(archiveName, archive);
        }
        ZipEntry entry = archive.getEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Archive entry is missing: " + entryName);
        }
        if (entry.getSize() > MAX_EXTRACTED_BOOK_SIZE) {
            throw new IOException("Book exceeds the 1 GiB extraction limit");
        }

        String archiveFolder = archiveName.substring(0, archiveName.length() - ".zip".length());
        Path targetDirectory = destinationRoot.resolve("INPX").resolve(archiveFolder).normalize();
        Path target = targetDirectory.resolve(entryName).normalize();
        if (!target.startsWith(destinationRoot)) {
            throw new IOException("Resolved destination escapes the library path");
        }
        if (Files.exists(target)) {
            return ImportOutcome.SKIPPED;
        }

        Files.createDirectories(targetDirectory);
        Path temporary = Files.createTempFile(targetDirectory, ".inpx-", ".tmp");
        try {
            try (InputStream input = archive.getInputStream(entry);
                 OutputStream output = Files.newOutputStream(temporary)) {
                copyBounded(input, output);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return ImportOutcome.IMPORTED;
    }

    private void copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_EXTRACTED_BOOK_SIZE) {
                throw new IOException("Book exceeds the 1 GiB extraction limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private Path resolveArchiveRoot(InpxImportRequest request) {
        if (request.getArchivePath() != null && !request.getArchivePath().isBlank()) {
            return normalizeDirectory(request.getArchivePath(), false);
        }
        try {
            Path index = Path.of(request.getInpxPath()).toAbsolutePath().normalize();
            return normalizeDirectory(index.getParent().toString(), false);
        } catch (InvalidPathException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid INPX path");
        }
    }

    private Path normalizeDirectory(String value, boolean writable) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(path) || !Files.isReadable(path) || (writable && !Files.isWritable(path))) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        writable ? "Library path must be a readable and writable directory"
                                : "Archive path must be a readable directory");
            }
            return path;
        } catch (InvalidPathException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid directory path");
        }
    }

    private String safeFileName(String value, String requiredSuffix) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || !Path.of(value).getFileName().toString().equals(value)
                || (!requiredSuffix.isEmpty() && !value.toLowerCase(Locale.ROOT).endsWith(requiredSuffix))) {
            throw new IllegalArgumentException("Unsafe archive or entry name");
        }
        return value;
    }

    private String safeExtension(String extension) {
        if (!"fb2".equalsIgnoreCase(extension)) {
            throw new IllegalArgumentException("Only FB2 entries are supported");
        }
        return "fb2";
    }

    private void addError(List<String> errors, String error) {
        if (errors.size() < MAX_REPORTED_ERRORS) {
            errors.add(error);
        }
    }

    private void closeQuietly(ZipFile archive) {
        try {
            archive.close();
        } catch (IOException e) {
            log.debug("Failed to close INPX source archive", e);
        }
    }

    private enum ImportOutcome {
        IMPORTED,
        SKIPPED
    }
}
