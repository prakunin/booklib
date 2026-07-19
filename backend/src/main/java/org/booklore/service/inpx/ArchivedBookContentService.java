package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.exception.ArchiveEntryMissingException;
import org.booklore.model.entity.BookFileEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
public class ArchivedBookContentService {

    private static final long MAX_EXTRACTED_SIZE = 1024L * 1024 * 1024;
    private final AppProperties appProperties;
    private final ConcurrentMap<Long, CompletableFuture<Path>> extractionFlights = new ConcurrentHashMap<>();

    /** Resolves for reading, serving the extraction cache whenever it looks current. */
    public Path resolve(BookFileEntity bookFile) {
        return resolve(bookFile, false);
    }

    /**
     * Resolves without trusting the extraction cache, re-reading the archive itself.
     * <p>
     * For the per-archive full scan, which is the documented repair for a replaced archive. The
     * cache is keyed on the archive being newer than the cached copy, and a restore that preserves
     * timestamps (rsync -a, cp -p, tar -x) is not - so the cached read path cannot notice either a
     * vanished entry or one whose content was swapped. The repair must look for itself.
     */
    public Path resolveRevalidated(BookFileEntity bookFile) {
        return resolve(bookFile, true);
    }

    private Path resolve(BookFileEntity bookFile, boolean revalidate) {
        if (!bookFile.isArchivedSource()) {
            return bookFile.getFullFilePath();
        }
        if (bookFile.getId() == null || bookFile.getBook() == null || bookFile.getBook().getLibrary() == null) {
            throw ApiError.FILE_NOT_FOUND.createException("Archived book source is incomplete");
        }
        if (revalidate) {
            // Deliberately outside the in-flight dedup: joining a concurrent reader's flight would
            // hand back the very cached result this call exists to distrust. Concurrent extraction
            // is safe - extract() stages to a temp file and atomically replaces the target.
            return resolveLocked(bookFile, true);
        }

        CompletableFuture<Path> flight = new CompletableFuture<>();
        CompletableFuture<Path> existing = extractionFlights.putIfAbsent(bookFile.getId(), flight);
        if (existing != null) {
            return await(existing);
        }

        try {
            Path resolved = resolveLocked(bookFile, false);
            flight.complete(resolved);
            return resolved;
        } catch (RuntimeException | Error e) {
            flight.completeExceptionally(e);
            throw e;
        } finally {
            extractionFlights.remove(bookFile.getId(), flight);
        }
    }

    private Path await(CompletableFuture<Path> flight) {
        try {
            return flight.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private Path resolveLocked(BookFileEntity bookFile, boolean revalidate) {
        var library = bookFile.getBook().getLibrary();
        Path archiveRoot = Path.of(library.getInpxArchivePath()).toAbsolutePath().normalize();
        String archiveName = safeLeaf(bookFile.getSourceArchive(), ".zip");
        String entryName = safeLeaf(bookFile.getSourceArchiveEntry(), ".fb2");
        Path archivePath = archiveRoot.resolve(archiveName).normalize();
        if (!archivePath.startsWith(archiveRoot) || !Files.isRegularFile(archivePath) || !Files.isReadable(archivePath)) {
            throw ApiError.FILE_NOT_FOUND.createException("INPX archive is unavailable: " + archiveName);
        }

        Path cacheDirectory = Path.of(appProperties.getPathConfig(), "cache", "inpx",
                String.valueOf(library.getId()), String.valueOf(bookFile.getId()));
        Path cached = cacheDirectory.resolve(entryName);
        try {
            if (!revalidate && Files.isRegularFile(cached)
                    && Files.getLastModifiedTime(cached).compareTo(Files.getLastModifiedTime(archivePath)) >= 0) {
                return cached;
            }
            Files.createDirectories(cacheDirectory);
            extract(archivePath, entryName, cached);
            return cached;
        } catch (MissingEntryException _) {
            throw new ArchiveEntryMissingException(entryName);
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException("Unable to read archived book: " + e.getMessage());
        }
    }

    private void extract(Path archivePath, String entryName, Path target) throws IOException {
        try (ZipFile archive = new ZipFile(archivePath.toFile())) {
            ZipEntry entry = archive.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new MissingEntryException(entryName);
            }
            Path temporary = Files.createTempFile(target.getParent(), ".inpx-", ".tmp");
            try {
                try (InputStream input = archive.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(temporary)) {
                    copyBounded(input, output);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException _) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_EXTRACTED_SIZE) {
                throw new IOException("Archived book exceeds the 1 GiB cache limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private String safeLeaf(String value, String suffix) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || !Path.of(value).getFileName().toString().equals(value)
                || !value.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            throw ApiError.FILE_NOT_FOUND.createException("Unsafe archived book path");
        }
        return value;
    }

    /** Internal marker so a vanished entry is not mistaken for a generic read failure. */
    private static final class MissingEntryException extends IOException {
        private MissingEntryException(String entryName) {
            super("Entry is missing: " + entryName);
        }
    }
}
