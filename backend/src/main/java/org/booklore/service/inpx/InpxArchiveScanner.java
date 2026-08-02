package org.booklore.service.inpx;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.ArchiveService;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.booklore.util.FileUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

/**
 * Finds books of any format in ZIP archives that are not yet fully represented by an INPX library.
 * This supplements (rather than replaces) the INPX index, allowing newly downloaded ZIPs
 * to be added without waiting for a new .inpx file. Readable formats (FB2, PDF, …) keep their type;
 * everything else (djvu, doc, …) is ingested as a download-only {@code OTHER} catalog entry.
 * <p>
 * Flat-archive incremental discovery remains additive and count-gated. Archives containing generic
 * nested containers are key-reconciled even when their leaf count has not changed. A per-archive full scan
 * ({@link InpxArchiveFullScanService}) is the repair: it re-adds missing entries and retires the
 * orphans.
 */
@Slf4j
@Component
public class InpxArchiveScanner {

    private static final int EXISTING_ENTRY_BATCH_SIZE = 500;
    private static final int MAX_NESTED_DEPTH = 5;
    private static final int MAX_VISITED_ENTRIES = 100_000;
    private static final long MAX_CONTAINER_SIZE = 1024L * 1024 * 1024;
    private static final long MAX_EXPANDED_SIZE = 4L * 1024 * 1024 * 1024;

    private final BookFileRepository bookFileRepository;
    private final Fb2MetadataExtractor fb2MetadataExtractor;
    private final ArchiveEntryMetadataRecognizer entryMetadataRecognizer;
    private final ArchiveService archiveService;
    private final TaskExecutor archiveInspectionExecutor;
    private final ConcurrentMap<Path, ArchiveFile> archiveFileCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<ArchiveInspectionKey, CompletableFuture<ArchiveFile>> inspections =
            new ConcurrentHashMap<>();

    public InpxArchiveScanner(BookFileRepository bookFileRepository,
                              Fb2MetadataExtractor fb2MetadataExtractor,
                              ArchiveEntryMetadataRecognizer entryMetadataRecognizer,
                              ArchiveService archiveService,
                              @Qualifier("inpxArchiveInspectionExecutor") TaskExecutor archiveInspectionExecutor) {
        this.bookFileRepository = bookFileRepository;
        this.fb2MetadataExtractor = fb2MetadataExtractor;
        this.entryMetadataRecognizer = entryMetadataRecognizer;
        this.archiveService = archiveService;
        this.archiveInspectionExecutor = archiveInspectionExecutor;
    }

    public Discovery discover(long libraryId, String archiveRoot) {
        Map<String, Long> persistedCounts = persistedCounts(libraryId);
        Set<String> archivesWithLegacyContainers = new HashSet<>(
                bookFileRepository.findArchivesWithActiveGenericContainerEntries(libraryId));
        List<ArchiveCandidate> candidates = new ArrayList<>();
        long totalEntries = 0;

        for (ArchiveFile archive : listArchives(archiveRoot)) {
            if ((archive.hasNestedContainers() && archivesWithLegacyContainers.contains(archive.archiveName()))
                    || archive.entryCount() > persistedCounts.getOrDefault(archive.archiveName(), 0L)) {
                candidates.add(new ArchiveCandidate(archive.path(), archive.archiveName(), archive.entryCount(),
                        archive.hasNestedContainers()));
                totalEntries += Math.max(0,
                        archive.entryCount() - persistedCounts.getOrDefault(archive.archiveName(), 0L));
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
            return new ArchiveCandidate(path, archiveName, inspected.entryCount(), inspected.hasNestedContainers());
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
        TraversalState state = new TraversalState(cancelled);
        try {
            List<InpxBookDto> discovered = new ArrayList<>();
            traverseZip(candidate.path(), candidate.archiveName(), List.of(), 0, state, discovered, true);
            for (int offset = 0; offset < discovered.size(); offset += EXISTING_ENTRY_BATCH_SIZE) {
                List<InpxBookDto> batch = discovered.subList(offset,
                        Math.min(offset + EXISTING_ENTRY_BATCH_SIZE, discovered.size()));
                Set<String> existingEntries = findExistingEntries(libraryId, candidate.archiveName(),
                        batch.stream().map(this::sourceEntry).toList());
                for (InpxBookDto book : batch) {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    if (!existingEntries.contains(sourceEntry(book))) {
                        consumer.accept(book);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Unable to scan ZIP archive {}: {}", candidate.path(), e.getMessage());
        } finally {
            state.close();
        }
    }

    /**
     * Discovery is streaming and cheap: FB2 is read straight from the ZIP stream (its opening pages
     * recover a blank title-info), while every other format is recognised from its filename only. The
     * per-format extractors that need a real file on disk (PDF, Word, …) run later, in the full-scan
     * refresh pass, which materialises each entry.
     */
    private InpxBookDto processEntry(String archiveName, List<String> chain, String entryName, long size,
                                     InputStream input, boolean extractMetadata) {
        String leafName = leafName(entryName);
        BookFileType bookType = entryMetadataRecognizer.resolveBookType(leafName);
        BookMetadata metadata;
        if (extractMetadata && bookType == BookFileType.FB2 && input != null) {
            metadata = fb2MetadataExtractor.extractMetadata(input, archiveName + "!" + entryName);
        } else {
            metadata = entryMetadataRecognizer.recognize(leafName, null);
        }
        List<String> locatorChain = new ArrayList<>(chain);
        locatorChain.add(entryName);
        String locator = NestedArchiveLocator.encode(locatorChain);
        return toBook(archiveName, leafName, locator, size, metadata, bookType);
    }

    private Set<String> findExistingEntries(long libraryId, String archiveName, List<String> entries) {
        if (libraryId <= 0 || entries.isEmpty()) {
            return Set.of();
        }
        Set<String> entryNames = new HashSet<>(entries);
        return bookFileRepository.findExistingArchiveEntries(
                        libraryId, Set.of(archiveName), entryNames).stream()
                .map(row -> (String) row[1])
                .collect(Collectors.toSet());
    }

    private InpxBookDto toBook(String archiveName, String entryName, String sourceEntry, long sizeBytes,
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
                .sourceArchiveEntry(sourceEntry.equals(entryName) ? null : sourceEntry)
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

    private void traverseZip(Path archivePath, String archiveName, List<String> chain, int depth,
                             TraversalState state, List<InpxBookDto> books, boolean extractMetadata) throws IOException {
        // Commons Compress remains the reader for the legacy outer ZIPs whose central directory is
        // rejected by java.util.zip. Nested ZIPs use it too, preserving exact case-sensitive names.
        try (ZipFile archive = ZipFile.builder().setFile(archivePath.toFile()).get()) {
            for (ZipArchiveEntry entry : Collections.list(archive.getEntries())) {
                if (!state.visit() || state.cancelled()) {
                    return;
                }
                String entryName = ZipEntryNameResolver.resolve(entry);
                if (!isCandidateEntry(entryName, entry.isDirectory())) {
                    continue;
                }
                if (isGenericArchive(entryName)) {
                    state.hasNestedContainers = true;
                    descend(archiveName, chain, depth, state, books, entryName, entry.getSize(),
                            output -> {
                                try (InputStream input = archive.getInputStream(entry)) {
                                    input.transferTo(output);
                                }
                            }, extractMetadata);
                } else {
                    try (InputStream input = extractMetadata && isFb2(entryName)
                            ? new CountingInputStream(archive.getInputStream(entry), state) : null) {
                        addLeaf(archiveName, chain, entryName, entry.getSize(), input, books, extractMetadata);
                    } catch (RuntimeException | IOException e) {
                        log.warn("Skipping archive entry {} in {}: {}", entryName, archiveName, e.getMessage());
                    }
                }
            }
        }
    }

    private void traverseNative(Path archivePath, String archiveName, List<String> chain, int depth,
                                TraversalState state, List<InpxBookDto> books, boolean extractMetadata) throws IOException {
        for (ArchiveService.Entry entry : archiveService.getEntries(archivePath)) {
            if (!state.visit() || state.cancelled()) {
                return;
            }
            String entryName = entry.name();
            if (!isCandidateEntry(entryName, entryName.endsWith("/"))) {
                continue;
            }
            if (isGenericArchive(entryName)) {
                state.hasNestedContainers = true;
                descend(archiveName, chain, depth, state, books, entryName, entry.size(),
                        output -> archiveService.transferEntryTo(archivePath, entryName, output), extractMetadata);
            } else if (extractMetadata && isFb2(entryName)) {
                if (entry.size() < 0 || entry.size() > MAX_CONTAINER_SIZE) {
                    log.warn("Skipping archive entry {} in {} because its size is unsafe", entryName, archiveName);
                    continue;
                }
                Path leaf = state.createTemporary(suffix(entryName));
                try {
                    try (OutputStream output = Files.newOutputStream(leaf)) {
                        copyNativeBounded(archivePath, entryName, output, state);
                    }
                    try (InputStream input = Files.newInputStream(leaf)) {
                        addLeaf(archiveName, chain, entryName, entry.size(), input, books, true);
                    }
                } catch (RuntimeException | IOException e) {
                    log.warn("Skipping archive entry {} in {}: {}", entryName, archiveName, e.getMessage());
                } finally {
                    state.releaseTemporary(leaf);
                }
            } else {
                addLeaf(archiveName, chain, entryName, entry.size(), null, books, extractMetadata);
            }
        }
    }

    private void descend(String archiveName, List<String> chain, int depth, TraversalState state,
                         List<InpxBookDto> books, String entryName, long size, EntryTransfer transfer,
                         boolean extractMetadata) {
        if (depth >= MAX_NESTED_DEPTH || size < 0 || size > MAX_CONTAINER_SIZE || state.cancelled()) {
            log.warn("Skipping nested archive {} in {} because a traversal limit was reached", entryName, archiveName);
            return;
        }
        List<String> nestedChain = new ArrayList<>(chain);
        nestedChain.add(entryName);
        try {
            NestedArchiveLocator.encode(nestedChain.size() == 1
                    ? List.of(nestedChain.getFirst(), "probe") : nestedChain);
            Path nested = state.createTemporary(suffix(entryName));
            try (OutputStream output = Files.newOutputStream(nested)) {
                CountingOutputStream bounded = new CountingOutputStream(output, state);
                transfer.transfer(bounded);
            }
            if (isZip(entryName)) {
                traverseZip(nested, archiveName, nestedChain, depth + 1, state, books, extractMetadata);
            } else {
                traverseNative(nested, archiveName, nestedChain, depth + 1, state, books, extractMetadata);
            }
        } catch (Exception e) {
            log.warn("Skipping unreadable nested archive {} in {}: {}", entryName, archiveName, e.getMessage());
        }
    }

    private void addLeaf(String archiveName, List<String> chain, String entryName, long size,
                         InputStream input, List<InpxBookDto> books, boolean extractMetadata) {
        try {
            books.add(processEntry(archiveName, chain, entryName, size, input, extractMetadata));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping archive entry {} in {}: {}", entryName, archiveName, e.getMessage());
        }
    }

    private void copyNativeBounded(Path archivePath, String entryName, OutputStream output,
                                   TraversalState state) throws IOException {
        archiveService.transferEntryTo(archivePath, entryName, new CountingOutputStream(output, state));
    }

    private String sourceEntry(InpxBookDto book) {
        return book.getSourceArchiveEntry() == null
                ? book.getFileName() + "." + book.getExtension()
                : book.getSourceArchiveEntry();
    }

    private boolean isCandidateEntry(String name, boolean directory) {
        if (directory || name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            return false;
        }
        String leaf = leafName(name);
        try {
            return !leaf.isBlank() && !FileUtils.shouldIgnore(Path.of(leaf)) && !extension(leaf).isEmpty();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private String leafName(String entryName) {
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    private boolean isFb2(String entryName) {
        return "fb2".equals(extension(entryName).toLowerCase(Locale.ROOT));
    }

    static boolean isGenericArchive(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z");
    }

    private String suffix(String entryName) {
        String extension = extension(entryName);
        return extension.isEmpty() ? ".archive" : "." + extension;
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
                Map<String, ZipArchiveEntry> entriesByName = ZipEntryNameResolver.indexEntries(archive);
                for (InpxBookDto book : books) {
                    ZipArchiveEntry entry = entriesByName.get(book.getFileName() + "." + book.getExtension());
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
                    InspectionResult result = inspectEntries(path);
                    ArchiveFile inspected = new ArchiveFile(path, path.getFileName().toString(), size,
                            modifiedAt, result.entryCount(), result.hasNestedContainers());
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

    private boolean isZip(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private boolean isZip(String entryName) {
        return entryName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private InspectionResult inspectEntries(Path path) {
        TraversalState state = new TraversalState(() -> false);
        try {
            List<InpxBookDto> books = new ArrayList<>();
            traverseZip(path, path.getFileName().toString(), List.of(), 0, state, books, false);
            return new InspectionResult(books.size(), state.hasNestedContainers);
        } catch (IOException e) {
            log.warn("Unable to inspect ZIP archive {}: {}", path, e.getMessage());
            return new InspectionResult(0, state.hasNestedContainers);
        } finally {
            state.close();
        }
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

    public record ArchiveCandidate(Path path, String archiveName, long entryCount, boolean hasNestedContainers) {
        public ArchiveCandidate(Path path, String archiveName, long entryCount) {
            this(path, archiveName, entryCount, false);
        }
    }

    public record ArchiveFile(Path path, String archiveName, long sizeBytes, Instant modifiedAt, Long entryCount,
                              boolean hasNestedContainers) {
        public ArchiveFile(Path path, String archiveName, long sizeBytes, Instant modifiedAt, Long entryCount) {
            this(path, archiveName, sizeBytes, modifiedAt, entryCount, false);
        }
    }

    private record ArchiveInspectionKey(Path path, long sizeBytes, Instant modifiedAt) {
    }

    private record InspectionResult(long entryCount, boolean hasNestedContainers) {
    }

    @FunctionalInterface
    private interface EntryTransfer {
        void transfer(OutputStream output) throws IOException;
    }

    private static final class TraversalState {
        private final BooleanSupplier cancellation;
        private final List<Path> temporaryPaths = new ArrayList<>();
        private int visitedEntries;
        private long expandedBytes;
        private boolean hasNestedContainers;

        private TraversalState(BooleanSupplier cancellation) {
            this.cancellation = cancellation;
        }

        private boolean visit() {
            return ++visitedEntries <= MAX_VISITED_ENTRIES;
        }

        private boolean cancelled() {
            return cancellation.getAsBoolean() || visitedEntries > MAX_VISITED_ENTRIES;
        }

        private Path createTemporary(String suffix) throws IOException {
            Path path = Files.createTempFile("booklib-inpx-", suffix);
            temporaryPaths.add(path);
            return path;
        }

        private void releaseTemporary(Path path) {
            temporaryPaths.remove(path);
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Unable to delete nested archive temporary file {}: {}", path, e.getMessage());
            }
        }

        private void account(long count) throws IOException {
            expandedBytes += count;
            if (expandedBytes > MAX_EXPANDED_SIZE) {
                throw new IOException("Nested archive expanded-byte limit exceeded");
            }
        }

        private void close() {
            for (int index = temporaryPaths.size() - 1; index >= 0; index--) {
                try {
                    Files.deleteIfExists(temporaryPaths.get(index));
                } catch (IOException e) {
                    log.warn("Unable to delete nested archive temporary file {}: {}",
                            temporaryPaths.get(index), e.getMessage());
                }
            }
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final TraversalState state;
        private long containerBytes;

        private CountingOutputStream(OutputStream delegate, TraversalState state) {
            this.delegate = delegate;
            this.state = state;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(int length) throws IOException {
            containerBytes += length;
            if (containerBytes > MAX_CONTAINER_SIZE) {
                throw new IOException("Nested archive exceeds the 1 GiB extraction limit");
            }
            state.account(length);
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final TraversalState state;
        private long entryBytes;

        private CountingInputStream(InputStream delegate, TraversalState state) {
            this.delegate = delegate;
            this.state = state;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        private void account(int length) throws IOException {
            entryBytes += length;
            if (entryBytes > MAX_CONTAINER_SIZE) {
                throw new IOException("Archive entry exceeds the 1 GiB scan limit");
            }
            state.account(length);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
