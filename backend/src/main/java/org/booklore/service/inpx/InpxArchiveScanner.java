package org.booklore.service.inpx;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.booklore.util.FileUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Finds books of any format in ZIP archives that are not yet fully represented by an INPX library.
 * This supplements (rather than replaces) the INPX index, allowing newly downloaded ZIPs
 * to be added without waiting for a new .inpx file. Readable formats (FB2, PDF, …) keep their type;
 * everything else (djvu, doc, …) is ingested as a download-only {@code OTHER} catalog entry.
 * <p>
 * Incremental discovery is <em>additive and count-gated</em>: an archive is only considered when it
 * holds more entries than are persisted for it, and nothing here ever removes a row. Replacing an
 * archive's contents without changing its entry count is therefore invisible to a normal rescan,
 * and books whose entry vanished keep their rows. A per-archive full scan
 * ({@link InpxArchiveFullScanService}) is the repair: it re-adds missing entries and retires the
 * orphans.
 */
@Slf4j
@Component
public class InpxArchiveScanner {

    private static final int EXISTING_ENTRY_BATCH_SIZE = 500;

    private final BookFileRepository bookFileRepository;
    private final Fb2MetadataExtractor fb2MetadataExtractor;
    private final ArchiveEntryMetadataRecognizer entryMetadataRecognizer;
    private final TaskExecutor archiveInspectionExecutor;
    private final ConcurrentMap<Path, ArchiveFile> archiveFileCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<ArchiveInspectionKey, CompletableFuture<ArchiveFile>> inspections =
            new ConcurrentHashMap<>();

    public InpxArchiveScanner(BookFileRepository bookFileRepository,
                              Fb2MetadataExtractor fb2MetadataExtractor,
                              ArchiveEntryMetadataRecognizer entryMetadataRecognizer,
                              @Qualifier("inpxArchiveInspectionExecutor") TaskExecutor archiveInspectionExecutor) {
        this.bookFileRepository = bookFileRepository;
        this.fb2MetadataExtractor = fb2MetadataExtractor;
        this.entryMetadataRecognizer = entryMetadataRecognizer;
        this.archiveInspectionExecutor = archiveInspectionExecutor;
    }

    public Discovery discover(long libraryId, String archiveRoot) {
        Map<String, Long> persistedCounts = persistedCounts(libraryId);
        List<ArchiveCandidate> candidates = new ArrayList<>();
        long totalEntries = 0;

        for (ArchiveFile archive : listArchives(archiveRoot)) {
            if (archive.entryCount() > persistedCounts.getOrDefault(archive.archiveName(), 0L)) {
                candidates.add(new ArchiveCandidate(archive.path(), archive.archiveName(), archive.entryCount()));
                totalEntries += archive.entryCount() - persistedCounts.getOrDefault(archive.archiveName(), 0L);
            }
        }

        return new Discovery(List.copyOf(candidates), totalEntries, libraryId);
    }

    public Discovery discoveryForArchive(long libraryId, ArchiveCandidate candidate) {
        long persisted = persistedCounts(libraryId).getOrDefault(candidate.archiveName(), 0L);
        return new Discovery(List.of(candidate), Math.max(0, candidate.entryCount() - persisted), libraryId);
    }

    public List<ArchiveFile> listArchives(String archiveRoot) {
        return listArchives(archiveRoot, true);
    }

    public List<ArchiveFile> listArchiveMetadata(String archiveRoot) {
        return listArchives(archiveRoot, false);
    }

    private List<ArchiveFile> listArchives(String archiveRoot, boolean awaitInspection) {
        Path root = validateArchiveRoot(archiveRoot);
        try (Stream<Path> paths = Files.list(root)) {
            List<ArchiveFile> archives = new ArrayList<>();
            Set<Path> seenArchives = new HashSet<>();
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(this::isZip)
                    .sorted(Comparator.comparing(item -> item.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList()) {
                seenArchives.add(path);
                long size = Files.size(path);
                Instant modifiedAt = Files.getLastModifiedTime(path).toInstant();
                ArchiveFile cached = archiveFileCache.get(path);
                if (cached != null && cached.sizeBytes() == size && cached.modifiedAt().equals(modifiedAt)) {
                    archives.add(cached);
                } else {
                    CompletableFuture<ArchiveFile> inspection = inspectInBackground(path, size, modifiedAt);
                    archives.add(awaitInspection ? awaitInspection(inspection)
                            : new ArchiveFile(path, path.getFileName().toString(), size, modifiedAt, null));
                }
            }
            archiveFileCache.keySet().retainAll(seenArchives);
            return List.copyOf(archives);
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException("Unable to scan INPX archive folder: " + e.getMessage());
        }
    }

    int archiveFileCacheSize() {
        return archiveFileCache.size();
    }

    int activeInspectionCount() {
        return inspections.size();
    }

    public ArchiveCandidate inspectArchive(String archiveRoot, String archiveName) {
        Path root = validateArchiveRoot(archiveRoot);
        if (archiveName == null || archiveName.isBlank() || archiveName.indexOf('\0') >= 0) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("ZIP archive name is required");
        }
        try {
            Path leaf = Path.of(archiveName);
            if (leaf.getNameCount() != 1 || !leaf.getFileName().toString().equals(archiveName)
                    || !archiveName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid ZIP archive name");
            }
            Path path = root.resolve(leaf).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw ApiError.FILE_NOT_FOUND.createException("ZIP archive is unavailable: " + archiveName);
            }
            long size = Files.size(path);
            Instant modifiedAt = Files.getLastModifiedTime(path).toInstant();
            ArchiveFile cached = archiveFileCache.get(path);
            ArchiveFile inspected = cached != null && cached.sizeBytes() == size
                    && cached.modifiedAt().equals(modifiedAt)
                    ? cached
                    : awaitInspection(inspectInBackground(path, size, modifiedAt));
            return new ArchiveCandidate(path, archiveName, inspected.entryCount());
        } catch (InvalidPathException _) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid ZIP archive name");
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException("Unable to inspect ZIP archive: " + e.getMessage());
        }
    }

    public void forEach(Discovery discovery, Consumer<InpxBookDto> consumer, BooleanSupplier cancelled) {
        for (ArchiveCandidate candidate : discovery.candidates()) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            scanArchive(candidate, discovery.libraryId(), consumer, cancelled);
        }
    }

    private void scanArchive(ArchiveCandidate candidate, long libraryId, Consumer<InpxBookDto> consumer,
                             BooleanSupplier cancelled) {
        // Apache Commons Compress rather than java.util.zip: the JDK reader rejects whole archives
        // whose CEN headers trip its strict validation ("invalid CEN header"), which is exactly what
        // the legacy Flibusta usr ZIPs do. Commons Compress reads them.
        try (ZipFile archive = ZipFile.builder().setFile(candidate.path().toFile()).get()) {
            List<ZipArchiveEntry> entries = Collections.list(archive.getEntries()).stream()
                    .filter(this::isIngestableEntry).toList();
            for (int offset = 0; offset < entries.size(); offset += EXISTING_ENTRY_BATCH_SIZE) {
                List<ZipArchiveEntry> entryBatch = entries.subList(
                        offset, Math.min(offset + EXISTING_ENTRY_BATCH_SIZE, entries.size()));
                Set<String> existingEntries = findExistingEntries(libraryId, candidate.archiveName(), entryBatch);
                for (ZipArchiveEntry entry : entryBatch) {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    if (existingEntries.contains(entry.getName())) {
                        continue;
                    }
                    processEntry(archive, candidate, entry, consumer);
                }
            }
        } catch (IOException e) {
            log.warn("Unable to scan ZIP archive {}: {}", candidate.path(), e.getMessage());
        }
    }

    /**
     * Discovery is streaming and cheap: FB2 is read straight from the ZIP stream (its opening pages
     * recover a blank title-info), while every other format is recognised from its filename only. The
     * per-format extractors that need a real file on disk (PDF, Word, …) run later, in the full-scan
     * refresh pass, which materialises each entry.
     */
    private void processEntry(ZipFile archive, ArchiveCandidate candidate, ZipArchiveEntry entry, Consumer<InpxBookDto> consumer) {
        String entryName = entry.getName();
        BookFileType bookType = entryMetadataRecognizer.resolveBookType(entryName);
        BookMetadata metadata;
        if (bookType == BookFileType.FB2) {
            try (InputStream input = archive.getInputStream(entry)) {
                metadata = fb2MetadataExtractor.extractMetadata(input, candidate.archiveName() + "!" + entryName);
            } catch (IOException e) {
                log.warn("Unable to read FB2 entry {} from {}: {}", entryName, candidate.archiveName(), e.getMessage());
                metadata = null;
            }
        } else {
            metadata = entryMetadataRecognizer.recognize(entryName, null);
        }
        consumer.accept(toBook(candidate.archiveName(), entryName, entry.getSize(), metadata, bookType));
    }

    private Set<String> findExistingEntries(long libraryId, String archiveName,
                                            List<? extends ZipEntry> entries) {
        if (libraryId <= 0 || entries.isEmpty()) {
            return Set.of();
        }
        Set<String> entryNames = entries.stream().map(ZipEntry::getName).collect(Collectors.toSet());
        return bookFileRepository.findExistingArchiveEntries(
                        libraryId, Set.of(archiveName), entryNames).stream()
                .map(row -> (String) row[1])
                .collect(Collectors.toSet());
    }

    private InpxBookDto toBook(String archiveName, String entryName, long sizeBytes,
                              BookMetadata metadata, BookFileType bookType) {
        String extension = extension(entryName);
        String fileName = extension.isEmpty()
                ? entryName
                : entryName.substring(0, entryName.length() - extension.length() - 1);
        String title = metadata == null || metadata.getTitle() == null || metadata.getTitle().isBlank()
                ? fileName
                : metadata.getTitle();
        List<String> authors = metadata == null || metadata.getAuthors() == null
                ? List.of()
                : metadata.getAuthors();
        List<String> genres = metadata == null || metadata.getCategories() == null
                ? List.of()
                : List.copyOf(metadata.getCategories());

        return InpxBookDto.builder()
                .id(InpxParser.id(archiveName, fileName, extension))
                .authors(authors)
                .genres(genres)
                .title(title)
                .series(metadata == null ? null : metadata.getSeriesName())
                .seriesNumber(metadata == null || metadata.getSeriesNumber() == null
                        ? null : metadata.getSeriesNumber().toString())
                .fileName(fileName)
                .extension(extension)
                .bookType(bookType)
                .libraryId("")
                .date(metadata == null || metadata.getPublishedDate() == null
                        ? null : metadata.getPublishedDate().toString())
                .language(metadata == null ? null : metadata.getLanguage())
                .rating(metadata == null ? null : metadata.getRating())
                .archiveName(archiveName)
                .fileSizeKb(toKilobytes(sizeBytes))
                .build();
    }

    private String extension(String entryName) {
        int lastDot = entryName.lastIndexOf('.');
        // Original case is preserved on purpose: the stored extension rebuilds the archive entry name
        // for later reads, and ZIP entry lookup is case-sensitive (".PDF" != ".pdf"). Type detection
        // lowercases separately in ArchiveEntryMetadataRecognizer.
        return lastDot > 0 && lastDot < entryName.length() - 1
                ? entryName.substring(lastDot + 1)
                : "";
    }

    void populateFileSizes(List<InpxBookDto> books, String archiveRoot) {
        if (books.isEmpty() || archiveRoot == null || archiveRoot.isBlank()) {
            return;
        }
        books.stream()
                .filter(book -> book.getFileSizeKb() == null)
                .collect(Collectors.groupingBy(InpxBookDto::getArchiveName))
                .forEach((archiveName, archiveBooks) -> populateFileSizes(archiveBooks, archiveRoot, archiveName));
    }

    private void populateFileSizes(List<InpxBookDto> books, String archiveRoot, String archiveName) {
        try {
            Path root = Path.of(archiveRoot).toAbsolutePath().normalize();
            Path archivePath = root.resolve(archiveName).normalize();
            if (!archivePath.startsWith(root) || !Files.isRegularFile(archivePath)) {
                return;
            }
            try (ZipFile archive = ZipFile.builder().setFile(archivePath.toFile()).get()) {
                for (InpxBookDto book : books) {
                    ZipArchiveEntry entry = archive.getEntry(book.getFileName() + "." + book.getExtension());
                    if (entry != null) {
                        book.setFileSizeKb(toKilobytes(entry.getSize()));
                    }
                }
            }
        } catch (IOException | InvalidPathException e) {
            log.warn("Unable to read file sizes from INPX archive {}: {}", archiveName, e.getMessage());
        }
    }

    private Long toKilobytes(long sizeBytes) {
        return sizeBytes >= 0 ? sizeBytes / 1024 : null;
    }

    private Map<String, Long> persistedCounts(long libraryId) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : bookFileRepository.countArchiveEntriesByLibraryId(libraryId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private long countIngestableEntries(Path path) {
        try (ZipFile archive = ZipFile.builder()
                .setFile(path.toFile())
                .setIgnoreLocalFileHeader(true)
                .get()) {
            return Collections.list(archive.getEntries()).stream().filter(this::isIngestableEntry).count();
        } catch (IOException e) {
            log.warn("Unable to inspect ZIP archive {}: {}", path, e.getMessage());
            return 0;
        }
    }

    private CompletableFuture<ArchiveFile> inspectInBackground(Path path, long size, Instant modifiedAt) {
        ArchiveInspectionKey key = new ArchiveInspectionKey(path, size, modifiedAt);
        CompletableFuture<ArchiveFile> created = new CompletableFuture<>();
        CompletableFuture<ArchiveFile> existing = inspections.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        try {
            archiveInspectionExecutor.execute(() -> {
                try {
                    ArchiveFile inspected = new ArchiveFile(path, path.getFileName().toString(), size,
                            modifiedAt, countIngestableEntries(path));
                    archiveFileCache.put(path, inspected);
                    created.complete(inspected);
                } catch (Exception | Error e) {
                    created.completeExceptionally(e);
                } finally {
                    inspections.remove(key, created);
                }
            });
        } catch (RejectedExecutionException e) {
            inspections.remove(key, created);
            created.completeExceptionally(e);
        }
        return created;
    }

    private ArchiveFile awaitInspection(CompletableFuture<ArchiveFile> inspection) {
        try {
            return inspection.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    /**
     * Any flat, non-junk file with an extension is a catalog candidate — not just FB2. The format
     * is resolved per entry: readable types (FB2, PDF, …) open in a reader, everything else becomes a
     * download-only OTHER book. Directory entries, nested paths and ignored files are skipped.
     */
    private boolean isIngestableEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }
        String name = entry.getName();
        try {
            Path entryPath = Path.of(name);
            return entryPath.getNameCount() == 1
                    && entryPath.getFileName().toString().equals(name)
                    && !FileUtils.shouldIgnore(entryPath)
                    && !extension(name).isEmpty();
        } catch (InvalidPathException _) {
            return false;
        }
    }

    private boolean isZip(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private Path validateArchiveRoot(String archiveRoot) {
        if (archiveRoot == null || archiveRoot.isBlank() || archiveRoot.indexOf('\0') >= 0) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("INPX archive path is required");
        }
        try {
            Path root = Path.of(archiveRoot).toAbsolutePath().normalize();
            if (!Files.isDirectory(root) || !Files.isReadable(root)) {
                throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(root.toString());
            }
            return root;
        } catch (InvalidPathException _) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid INPX archive path");
        }
    }

    public record Discovery(List<ArchiveCandidate> candidates, long totalEntries, long libraryId) {
        public Discovery(List<ArchiveCandidate> candidates, long totalEntries) {
            this(candidates, totalEntries, 0);
        }
    }

    public record ArchiveCandidate(Path path, String archiveName, long entryCount) {
    }

    public record ArchiveFile(Path path, String archiveName, long sizeBytes, Instant modifiedAt, Long entryCount) {
    }

    private record ArchiveInspectionKey(Path path, long sizeBytes, Instant modifiedAt) {
    }
}
