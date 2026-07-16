package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds FB2 books in ZIP archives that are not yet fully represented by an INPX library.
 * This supplements (rather than replaces) the INPX index, allowing newly downloaded ZIPs
 * to be added without waiting for a new .inpx file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InpxArchiveScanner {

    private static final int EXISTING_ENTRY_BATCH_SIZE = 500;

    private final BookFileRepository bookFileRepository;
    private final Fb2MetadataExtractor fb2MetadataExtractor;
    private final ConcurrentMap<Path, ArchiveFile> archiveFileCache = new ConcurrentHashMap<>();

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
        Path root = validateArchiveRoot(archiveRoot);
        try (Stream<Path> paths = Files.list(root)) {
            List<ArchiveFile> archives = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(this::isZip)
                    .sorted(Comparator.comparing(item -> item.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList()) {
                long size = Files.size(path);
                Instant modifiedAt = Files.getLastModifiedTime(path).toInstant();
                ArchiveFile cached = archiveFileCache.get(path);
                if (cached != null && cached.sizeBytes() == size && cached.modifiedAt().equals(modifiedAt)) {
                    archives.add(cached);
                } else {
                    ArchiveFile inspected = new ArchiveFile(path, path.getFileName().toString(), size,
                            modifiedAt, countFb2Entries(path));
                    archiveFileCache.put(path, inspected);
                    archives.add(inspected);
                }
            }
            return List.copyOf(archives);
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException("Unable to scan INPX archive folder: " + e.getMessage());
        }
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
            return new ArchiveCandidate(path, archiveName, countFb2Entries(path));
        } catch (InvalidPathException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid ZIP archive name");
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
        try (ZipFile archive = new ZipFile(candidate.path().toFile())) {
            List<? extends ZipEntry> entries = archive.stream().filter(this::isReadableFb2Entry).toList();
            for (int offset = 0; offset < entries.size(); offset += EXISTING_ENTRY_BATCH_SIZE) {
                List<? extends ZipEntry> entryBatch = entries.subList(
                        offset, Math.min(offset + EXISTING_ENTRY_BATCH_SIZE, entries.size()));
                Set<String> existingEntries = findExistingEntries(libraryId, candidate.archiveName(), entryBatch);
                for (ZipEntry entry : entryBatch) {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    if (existingEntries.contains(entry.getName())) {
                        continue;
                    }
                    try (InputStream input = archive.getInputStream(entry)) {
                        BookMetadata metadata = fb2MetadataExtractor.extractMetadata(
                                input, candidate.archiveName() + "!" + entry.getName());
                        consumer.accept(toBook(candidate.archiveName(), entry.getName(), metadata));
                    } catch (IOException e) {
                        log.warn("Unable to read FB2 entry {} from {}: {}",
                                entry.getName(), candidate.archiveName(), e.getMessage());
                        consumer.accept(toBook(candidate.archiveName(), entry.getName(), null));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Unable to scan ZIP archive {}: {}", candidate.path(), e.getMessage());
        }
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

    private InpxBookDto toBook(String archiveName, String entryName, BookMetadata metadata) {
        String fileName = entryName.substring(0, entryName.length() - ".fb2".length());
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
                .id(InpxParser.id(archiveName, fileName, "fb2"))
                .authors(authors)
                .genres(genres)
                .title(title)
                .series(metadata == null ? null : metadata.getSeriesName())
                .seriesNumber(metadata == null || metadata.getSeriesNumber() == null
                        ? null : metadata.getSeriesNumber().toString())
                .fileName(fileName)
                .extension("fb2")
                .libraryId("")
                .date(metadata == null || metadata.getPublishedDate() == null
                        ? null : metadata.getPublishedDate().toString())
                .language(metadata == null ? null : metadata.getLanguage())
                .rating(metadata == null ? null : metadata.getRating())
                .archiveName(archiveName)
                .build();
    }

    private Map<String, Long> persistedCounts(long libraryId) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : bookFileRepository.countArchiveEntriesByLibraryId(libraryId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private long countFb2Entries(Path path) {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            return archive.stream().filter(this::isReadableFb2Entry).count();
        } catch (IOException e) {
            log.warn("Unable to inspect ZIP archive {}: {}", path, e.getMessage());
            return 0;
        }
    }

    private boolean isReadableFb2Entry(ZipEntry entry) {
        if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".fb2")) {
            return false;
        }
        try {
            Path entryPath = Path.of(entry.getName());
            return entryPath.getNameCount() == 1 && entryPath.getFileName().toString().equals(entry.getName());
        } catch (InvalidPathException e) {
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
        } catch (InvalidPathException e) {
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

    public record ArchiveFile(Path path, String archiveName, long sizeBytes, Instant modifiedAt, long entryCount) {
    }
}
