package org.booklore.service.enrichment.catalog;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code *.FLibrary.etc} catalog that ships next to the fb2.Flibusta.Net INPX libraries.
 * <p>
 * Reads through {@link ArchiveService} rather than commons-compress: every container in this catalog
 * is PPMD-compressed 7z, which commons-compress rejects outright ("Unsupported compression method
 * [3, 4, 1]"), while the libarchive binding the INPX scanner already depends on reads it fine.
 * <p>
 * Every lookup is best-effort. A missing directory, an unreadable archive or a malformed document
 * yields an empty result and a log line — enrichment continues with the next source, because a book
 * losing its description is not a reason to fail the operation the user asked for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlibustaCatalogSource implements LocalCatalogSource {

    /**
     * One parsed annotations document covers a whole library archive — several thousand books and a
     * few megabytes of text — so a handful of them is enough to make a run that walks archive by
     * archive read each container exactly once, without holding the whole catalog in memory.
     */
    private static final int ANNOTATION_CACHE_SIZE = 6;

    private final LibraryRepository libraryRepository;
    private final LocalCatalogIndexRepository indexRepository;
    private final FlibustaCatalogLayout layout;
    private final FlibustaAnnotationParser annotationParser;
    private final FlibustaReviewParser reviewParser;
    private final ArchiveService archiveService;
    private final ObjectMapper objectMapper;

    private final Cache<Long, Optional<Path>> catalogRoots = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private final Cache<AnnotationKey, Map<String, String>> annotations = Caffeine.newBuilder()
            .maximumSize(ANNOTATION_CACHE_SIZE)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    @Override
    public boolean isAvailable(long libraryId) {
        return catalogRoot(libraryId).isPresent();
    }

    @Override
    public Optional<String> lookupDescription(long libraryId, String archiveName, String entryName) {
        if (archiveName == null || archiveName.isBlank() || entryName == null || entryName.isBlank()) {
            return Optional.empty();
        }
        return catalogRoot(libraryId)
                .map(root -> annotationsFor(root, libraryId, archiveName))
                .map(byEntry -> byEntry.get(layout.leafName(entryName)))
                .filter(description -> !description.isBlank());
    }

    @Override
    public List<CatalogReview> lookupReviews(long libraryId, String archiveName, String entryName) {
        String key = layout.bookKey(archiveName, entryName);
        if (key == null) {
            return List.of();
        }
        return findIndexed(libraryId, LocalCatalogSourceType.REVIEW, key)
                .flatMap(row -> catalogRoot(libraryId)
                        .map(root -> layout.reviewContainer(root, row.getContainer())))
                .map(container -> reviewParser.parse(readEntry(container, key)))
                .orElseGet(List::of);
    }

    @Override
    public Optional<String> lookupAuthorBio(long libraryId, String authorName) {
        String key = FlibustaAuthorKey.of(authorName);
        if (key == null) {
            return Optional.empty();
        }
        return findIndexed(libraryId, LocalCatalogSourceType.AUTHOR_BIO, key)
                .flatMap(row -> catalogRoot(libraryId)
                        .map(root -> layout.authorBucket(root, row.getContainer())))
                .map(bucket -> readEntry(bucket, key))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .filter(bio -> !bio.isBlank());
    }

    @Override
    public List<CompilationPart> lookupCompilation(long libraryId, String archiveName, String entryName) {
        String key = layout.bookKey(archiveName, entryName);
        if (key == null) {
            return List.of();
        }
        return findIndexed(libraryId, LocalCatalogSourceType.COMPILATION, key)
                .map(LocalCatalogIndexEntity::getPayload)
                .map(this::readParts)
                .orElseGet(List::of);
    }

    /**
     * The catalog directory configured on the library, if it exists and matches this layout.
     * Cached briefly rather than permanently: the path is a library setting and can be re-pointed
     * from the UI, and a one-minute window is short enough that nobody notices the staleness.
     */
    private Optional<Path> catalogRoot(long libraryId) {
        return catalogRoots.get(libraryId, id -> libraryRepository.findById(id)
                .map(library -> library.getMetadataSidecarPath())
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of)
                .filter(layout::matches));
    }

    private Map<String, String> annotationsFor(Path catalogRoot, long libraryId, String archiveName) {
        return annotations.get(new AnnotationKey(libraryId, archiveName),
                key -> annotationParser.parse(readEntry(layout.annotations(catalogRoot), key.archiveName())));
    }

    private Optional<LocalCatalogIndexEntity> findIndexed(long libraryId, LocalCatalogSourceType type, String key) {
        return indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(libraryId, type, key);
    }

    private byte[] readEntry(Path container, String entryName) {
        if (!Files.isReadable(container)) {
            log.debug("Local catalog container {} is not readable", container);
            return new byte[0];
        }
        try {
            return archiveService.getEntryBytes(container, entryName);
        } catch (IOException e) {
            // Missing entries are the common case — most archives hold only a slice of the catalog.
            log.debug("No entry '{}' in local catalog container {}: {}", entryName, container, e.getMessage());
            return new byte[0];
        }
    }

    private List<CompilationPart> readParts(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<List<CompilationPart>>() {
            });
        } catch (JacksonException e) {
            log.warn("Could not read indexed compilation payload: {}", e.getMessage());
            return List.of();
        }
    }

    private record AnnotationKey(long libraryId, String archiveName) {
    }
}
