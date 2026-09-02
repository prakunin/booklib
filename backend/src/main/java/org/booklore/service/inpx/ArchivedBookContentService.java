package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class ArchivedBookContentService {

    private static final long MAX_EXTRACTED_SIZE = 1024L * 1024 * 1024;
    private static final long MAX_TOTAL_EXPANDED_SIZE = 4L * MAX_EXTRACTED_SIZE;
    private static final int MAX_PUBLICATION_ENTRIES = 100_000;
    private static final String UNABLE_TO_READ = "Unable to read archived book: ";
    private static final String SCRATCH_DIRECTORY = "scratch";
    private static final String TOO_MANY_ENTRIES = "Publication archive has too many entries";
    private final AppProperties appProperties;
    private final ArchiveService archiveService;
    private final ConcurrentMap<Long, CompletableFuture<Path>> extractionFlights = new ConcurrentHashMap<>();

    public record ArchivedEntry(String name, long size) {
    }

    public String publicationEntryName(BookFileEntity bookFile) {
        return source(bookFile).entryChain().getLast();
    }

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

    /**
     * Reads an archived publication without adding it to the extraction cache.
     * <p>
     * For the passes that walk the whole library and read each book exactly once - cover probing
     * and metadata extraction during a scan. Going through {@link #resolve} there caches every
     * book the scan touches, so a full pass over an INPX library materialises the entire library
     * uncompressed beside it; on this deployment that reached 475 GB against 894 GB of archives.
     * <p>
     * An extraction that is already cached is handed over as-is rather than repeated - a scan
     * following a reader should not pay to extract a book that is already sitting there. Only a
     * copy this method had to make itself is deleted, so the returned path from {@code action} is
     * valid only for the duration of the call.
     */
    public <T> T withPublicationCopy(BookFileEntity bookFile, Function<Path, T> action) {
        if (!bookFile.isArchivedSource()) {
            return action.apply(bookFile.getFullFilePath());
        }
        Source source = source(bookFile);
        Path cached = cachePath(bookFile);
        if (isFresh(cached, source.archivePath())) {
            stampRead(cached);
            return action.apply(cached);
        }

        Path scratch;
        try {
            scratch = createScratchFile("booklib-inpx-readonce-", suffix(bookFile.getFileName()));
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException(UNABLE_TO_READ + e.getMessage());
        }
        try {
            extract(source.archivePath(), source.entryChain(), scratch);
            return action.apply(scratch);
        } catch (MissingEntryException _) {
            throw new ArchiveEntryMissingException(safeEntryLeaf(bookFile.getFileName()));
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException(UNABLE_TO_READ + e.getMessage());
        } finally {
            deleteQuietly(scratch);
        }
    }

    /** What one eviction sweep removed, for the cleanup task to report. */
    public record EvictionResult(int deletedFiles, long freedBytes) {
    }

    /**
     * Deletes least-recently-read extractions until the cache is back under {@code limitBytes}.
     * <p>
     * Driven by the nightly cleanup task rather than by each extraction, unlike the PDF rendition
     * cache: that one holds a handful of files in a flat directory, while this one reached 414 000
     * files across nested per-book directories, and walking that on every book opened would make
     * reading a book cost a full-cache stat.
     * <p>
     * Ordering is by modification time, which {@link #stampRead} maintains on every cache hit.
     * Access time is not usable - the data volume is mounted {@code noatime}, so it would order
     * entries by when they were first extracted and evict the most-read books first.
     */
    public EvictionResult evictBeyondCacheLimit(long limitBytes) {
        if (limitBytes <= 0) {
            return new EvictionResult(0, 0L);
        }
        Path root = cacheRoot();
        if (!Files.isDirectory(root)) {
            return new EvictionResult(0, 0L);
        }

        List<CachedExtraction> extractions = listExtractions(root);
        long total = extractions.stream().mapToLong(CachedExtraction::size).sum();
        if (total <= limitBytes) {
            return new EvictionResult(0, 0L);
        }

        extractions.sort(Comparator.comparingLong(CachedExtraction::lastRead));
        int deleted = 0;
        long freed = 0L;
        for (CachedExtraction extraction : extractions) {
            if (total <= limitBytes) {
                break;
            }
            if (deleteQuietly(extraction.path())) {
                total -= extraction.size();
                freed += extraction.size();
                deleted++;
                deleteIfEmpty(extraction.path().getParent(), root);
            }
        }
        return new EvictionResult(deleted, freed);
    }

    /** Lists entries beside an archived publication without exposing them through an API. */
    public List<ArchivedEntry> listPublicationEntries(BookFileEntity bookFile) {
        try {
            return withContainingArchive(bookFile, archivePath -> {
                try {
                    return archivePath.outer() || isZip(archivePath.path())
                            ? zipEntries(archivePath.path())
                            : nativeEntries(archivePath.path());
                } catch (IOException e) {
                    throw new ArchiveAccessException(e);
                }
            });
        } catch (ArchiveAccessException e) {
            throw ApiError.FILE_READ_ERROR.createException("Unable to list publication resources: "
                    + e.getCause().getMessage());
        }
    }

    private List<ArchivedEntry> zipEntries(Path archivePath) throws IOException {
        try (ZipFile archive = LibraryArchives.open(archivePath)) {
            List<ArchivedEntry> entries = new ArrayList<>();
            var archiveEntries = archive.getEntries();
            while (archiveEntries.hasMoreElements()) {
                ZipArchiveEntry entry = archiveEntries.nextElement();
                if (!entry.isDirectory()) {
                    if (entries.size() >= MAX_PUBLICATION_ENTRIES) {
                        throw new IOException(TOO_MANY_ENTRIES);
                    }
                    entries.add(new ArchivedEntry(ZipEntryNameResolver.resolve(entry), entry.getSize()));
                }
            }
            return List.copyOf(entries);
        }
    }

    private List<ArchivedEntry> nativeEntries(Path archivePath) throws IOException {
        List<ArchiveService.Entry> archiveEntries = archiveService.getEntries(archivePath);
        if (archiveEntries.size() > MAX_PUBLICATION_ENTRIES) {
            throw new IOException(TOO_MANY_ENTRIES);
        }
        return archiveEntries.stream()
                .filter(entry -> !entry.name().endsWith("/"))
                .map(entry -> new ArchivedEntry(entry.name(), entry.size()))
                .toList();
    }

    /** Streams one exact sibling selected from {@link #listPublicationEntries(BookFileEntity)}. */
    public void streamPublicationEntry(BookFileEntity bookFile, String entryName, OutputStream output) throws IOException {
        try {
            withContainingArchive(bookFile, archivePath -> {
                try {
                    streamExactEntry(archivePath, entryName, output);
                    return null;
                } catch (IOException e) {
                    throw new ArchiveAccessException(e);
                }
            });
        } catch (ArchiveAccessException e) {
            throw (IOException) e.getCause();
        }
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

        Path cached = cachePath(bookFile);
        Path cacheDirectory = cached.getParent();
        try {
            if (!revalidate && isFresh(cached, archivePath)) {
                stampRead(cached);
                return cached;
            }
            Files.createDirectories(cacheDirectory);
            extract(archivePath, entryChain, cached);
            return cached;
        } catch (MissingEntryException _) {
            throw new ArchiveEntryMissingException(entryName);
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException(UNABLE_TO_READ + e.getMessage());
        }
    }

    private <T> T withContainingArchive(BookFileEntity bookFile, Function<ContainingArchive, T> action) {
        Source source = source(bookFile);
        List<Path> temporaryPaths = new ArrayList<>();
        long[] totalExpanded = {0};
        Path currentArchive = source.archivePath();
        boolean outer = true;
        try {
            List<String> chain = source.entryChain();
            for (int index = 0; index < chain.size() - 1; index++) {
                String entryName = chain.get(index);
                Path nested = createScratchFile("booklib-inpx-publication-", suffix(entryName));
                temporaryPaths.add(nested);
                extractEntry(currentArchive, entryName, nested, outer, totalExpanded);
                currentArchive = nested;
                outer = false;
            }
            return action.apply(new ContainingArchive(currentArchive, outer));
        } catch (IOException e) {
            throw new ArchiveAccessException(e);
        } finally {
            for (int index = temporaryPaths.size() - 1; index >= 0; index--) {
                try {
                    Files.deleteIfExists(temporaryPaths.get(index));
                } catch (IOException _) {
                    // The ordinary extraction path has the same best-effort cleanup semantics.
                }
            }
        }
    }

    private Source source(BookFileEntity bookFile) {
        if (!bookFile.isArchivedSource() || bookFile.getBook() == null || bookFile.getBook().getLibrary() == null) {
            throw ApiError.FILE_NOT_FOUND.createException("Archived publication source is incomplete");
        }
        var library = bookFile.getBook().getLibrary();
        Path archiveRoot = Path.of(library.getInpxArchivePath()).toAbsolutePath().normalize();
        String archiveName = safeLeaf(bookFile.getSourceArchive(), ".zip");
        Path archivePath = archiveRoot.resolve(archiveName).normalize();
        if (!archivePath.startsWith(archiveRoot) || !Files.isRegularFile(archivePath) || !Files.isReadable(archivePath)) {
            throw ApiError.FILE_NOT_FOUND.createException("INPX archive is unavailable: " + archiveName);
        }
        List<String> entryChain = bookFile.getSourceArchiveEntry().equals(bookFile.getFileName())
                ? List.of(bookFile.getSourceArchiveEntry())
                : NestedArchiveLocator.decode(bookFile.getSourceArchiveEntry());
        return new Source(archivePath, entryChain);
    }

    private void streamExactEntry(ContainingArchive archivePath, String entryName, OutputStream output) throws IOException {
        long[] expanded = {0};
        if (archivePath.outer() || isZip(archivePath.path())) {
            try (ZipFile archive = LibraryArchives.open(archivePath.path())) {
                ZipArchiveEntry entry = ZipEntryNameResolver.findEntry(archive, entryName);
                if (entry == null || entry.isDirectory()) {
                    throw new MissingEntryException(entryName);
                }
                try (InputStream input = archive.getInputStream(entry)) {
                    copyBounded(input, output, expanded);
                }
            }
        } else {
            archiveService.transferEntryTo(archivePath.path(), entryName, new BoundedOutputStream(output, expanded));
        }
    }

    private boolean isZip(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private Path cacheRoot() {
        return Path.of(appProperties.getPathConfig(), "cache", "inpx");
    }

    /**
     * Scratch files live under the cache root rather than {@code java.io.tmpdir}: the cache is the
     * application's own directory, while the system temp directory is shared with every other
     * process on the host and may be world-writable.
     */
    private Path createScratchFile(String prefix, String suffix) throws IOException {
        Path scratch = cacheRoot().resolve(SCRATCH_DIRECTORY);
        Files.createDirectories(scratch);
        return Files.createTempFile(scratch, prefix, suffix);
    }

    private Path cachePath(BookFileEntity bookFile) {
        return cacheRoot()
                .resolve(String.valueOf(bookFile.getBook().getLibrary().getId()))
                .resolve(String.valueOf(bookFile.getId()))
                .resolve(safeEntryLeaf(bookFile.getFileName()));
    }

    private boolean isFresh(Path cached, Path archivePath) {
        try {
            return Files.isRegularFile(cached)
                    && Files.getLastModifiedTime(cached).compareTo(Files.getLastModifiedTime(archivePath)) >= 0;
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * Records that a cached extraction was just read, so eviction can order by real use. Moving
     * mtime forward cannot invalidate the entry: freshness asks that the copy be no older than its
     * archive, and now is never older.
     */
    private void stampRead(Path cached) {
        try {
            Files.setLastModifiedTime(cached, FileTime.from(Instant.now()));
        } catch (IOException e) {
            // A read-only or exotic filesystem costs eviction accuracy, never the read itself.
            log.debug("Could not stamp the read time of {}: {}", cached, e.getMessage());
        }
    }

    private List<CachedExtraction> listExtractions(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                            return new CachedExtraction(path, attributes.size(),
                                    attributes.lastModifiedTime().toMillis());
                        } catch (IOException _) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            log.warn("Could not walk the INPX extraction cache at {}: {}", root, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Prunes the per-book directory an eviction just emptied, never the cache root itself. */
    private void deleteIfEmpty(Path directory, Path root) {
        if (directory == null || directory.equals(root) || !directory.startsWith(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        } catch (IOException _) {
            return;
        }
        if (deleteQuietly(directory)) {
            deleteIfEmpty(directory.getParent(), root);
        }
    }

    private boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", path, e.getMessage());
            return false;
        }
    }

    private record CachedExtraction(Path path, long size, long lastRead) {
    }

    private record Source(Path archivePath, List<String> entryChain) {
    }

    private record ContainingArchive(Path path, boolean outer) {
    }

    private static final class ArchiveAccessException extends RuntimeException {
        private ArchiveAccessException(IOException cause) {
            super(cause);
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
                        : createScratchFile("booklib-inpx-nested-", suffix(entryName));
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
            try (ZipFile archive = LibraryArchives.open(archivePath)) {
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
