package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.exception.ArchiveEntryMissingException;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.service.ArchiveService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class ArchivedBookContentService {

    private static final long MAX_EXTRACTED_SIZE = 1024L * 1024 * 1024;
    private static final long MAX_TOTAL_EXPANDED_SIZE = 4L * MAX_EXTRACTED_SIZE;
    private final AppProperties appProperties;
    private final ArchiveService archiveService;
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

    @SuppressWarnings("java:S1181") // Error is rethrown unchanged after completing the CompletableFuture and freeing the dedup slot - not swallowed
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
        } catch (Exception | Error e) {
            // A JVM Error must still complete the flight and free the dedup slot below in `finally` -
            // otherwise concurrent callers awaiting `existing` would hang forever. Rethrown unchanged.
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
        List<String> entryChain = bookFile.getSourceArchiveEntry().equals(bookFile.getFileName())
                ? List.of(bookFile.getSourceArchiveEntry())
                : NestedArchiveLocator.decode(bookFile.getSourceArchiveEntry());
        String entryName = safeEntryLeaf(bookFile.getFileName());
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
            extract(archivePath, entryChain, cached);
            return cached;
        } catch (MissingEntryException _) {
            throw new ArchiveEntryMissingException(entryName);
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException("Unable to read archived book: " + e.getMessage());
        }
    }

    private void extract(Path archivePath, List<String> entryChain, Path target) throws IOException {
        List<Path> temporaryPaths = new ArrayList<>();
        long[] totalExpanded = {0};
        Path currentArchive = archivePath;
        try {
            for (int index = 0; index < entryChain.size(); index++) {
                String entryName = entryChain.get(index);
                boolean finalEntry = index == entryChain.size() - 1;
                Path output = finalEntry
                        ? Files.createTempFile(target.getParent(), ".inpx-", ".tmp")
                        : Files.createTempFile("booklib-inpx-nested-", suffix(entryName));
                temporaryPaths.add(output);
                extractEntry(currentArchive, entryName, output, index == 0, totalExpanded);
                if (!finalEntry) {
                    currentArchive = output;
                }
            }
            Path completed = temporaryPaths.getLast();
            try {
                Files.move(completed, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException _) {
                Files.move(completed, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            for (int index = temporaryPaths.size() - 1; index >= 0; index--) {
                Files.deleteIfExists(temporaryPaths.get(index));
            }
        }
    }

    private void extractEntry(Path archivePath, String entryName, Path output, boolean outer,
                              long[] totalExpanded) throws IOException {
        if (outer || archivePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipFile archive = ZipFile.builder().setFile(archivePath.toFile()).get()) {
                ZipArchiveEntry entry = ZipEntryNameResolver.findEntry(archive, entryName);
                if (entry == null || entry.isDirectory()) {
                    throw new MissingEntryException(entryName);
                }
                try (InputStream input = archive.getInputStream(entry);
                     OutputStream target = Files.newOutputStream(output)) {
                    copyBounded(input, target, totalExpanded);
                }
            }
        } else {
            try (OutputStream target = Files.newOutputStream(output)) {
                archiveService.transferEntryTo(archivePath, entryName,
                        new BoundedOutputStream(target, totalExpanded));
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("Entry not found")) {
                    throw new MissingEntryException(entryName);
                }
                throw e;
            }
        }
    }

    private void copyBounded(InputStream input, OutputStream output, long[] totalExpanded) throws IOException {
        input.transferTo(new BoundedOutputStream(output, totalExpanded));
    }

    private String suffix(String entryName) {
        int dot = entryName.lastIndexOf('.');
        return dot >= 0 && dot < entryName.length() - 1 ? entryName.substring(dot) : ".archive";
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long[] totalExpanded;
        private long entryBytes;

        private BoundedOutputStream(OutputStream delegate, long[] totalExpanded) {
            this.delegate = delegate;
            this.totalExpanded = totalExpanded;
        }

        @Override
        public void write(int value) throws IOException {
            account(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            account(length);
            delegate.write(bytes, offset, length);
        }

        private void account(int length) throws IOException {
            entryBytes += length;
            totalExpanded[0] += length;
            if (entryBytes > MAX_EXTRACTED_SIZE) {
                throw new IOException("Archived book exceeds the 1 GiB cache limit");
            }
            if (totalExpanded[0] > MAX_TOTAL_EXPANDED_SIZE) {
                throw new IOException("Nested archive expanded-byte limit exceeded");
            }
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

    /**
     * The archive entry, validated as a safe single-segment filename with an extension. Unlike
     * {@link #safeLeaf} it fixes no particular suffix: multi-format archives hold pdf, doc, djvu …,
     * not only fb2. The extension is still required so the cached copy keeps a real file name.
     */
    private String safeEntryLeaf(String value) {
        int dot = value == null ? -1 : value.lastIndexOf('.');
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || !Path.of(value).getFileName().toString().equals(value)
                || dot <= 0 || dot >= value.length() - 1) {
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
