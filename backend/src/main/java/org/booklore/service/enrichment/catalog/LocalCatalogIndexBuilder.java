package org.booklore.service.enrichment.catalog;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Walks a local catalog once and records where each key lives.
 * <p>
 * This is the price of the catalog's shape: reviews are filed by month and author biographies by
 * arbitrary bucket, so without this pass every lookup would have to open 300-odd containers. The
 * pass itself is slow — hundreds of archives, hundreds of thousands of keys — which is why it is
 * driven from a background job and never from a request.
 * <p>
 * Rebuilds are idempotent: rows for the library and data set are dropped before the new ones land, so
 * re-running after the catalog is updated converges rather than accumulating.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCatalogIndexBuilder {

    private static final int BATCH_SIZE = 1000;
    private static final int PROGRESS_EVERY = 25;

    private final LibraryRepository libraryRepository;
    private final LocalCatalogIndexRepository indexRepository;
    private final FlibustaCatalogLayout layout;
    private final FlibustaCompilationParser compilationParser;
    private final FlibustaContentsParser contentsParser;
    private final ArchiveService archiveService;
    private final ObjectMapper objectMapper;

    /**
     * @return what was indexed, or an empty result when the library has no usable local catalog
     */
    public IndexResult rebuild(long libraryId) {
        Optional<Path> root = catalogRoot(libraryId);
        if (root.isEmpty()) {
            log.info("Library {} has no local metadata catalog to index", libraryId);
            return IndexResult.empty();
        }
        Path catalogRoot = root.get();
        log.info("Indexing local catalog {} for library {}", catalogRoot, libraryId);

        long reviews = indexContainers(libraryId, layout.reviewContainers(catalogRoot), LocalCatalogSourceType.REVIEW);
        long authors = indexContainers(libraryId, layout.authorBuckets(catalogRoot), LocalCatalogSourceType.AUTHOR_BIO);
        long compilations = indexCompilations(libraryId, catalogRoot);
        long languages = indexLanguages(libraryId, catalogRoot);

        log.info("Indexed local catalog for library {}: {} reviews, {} author biographies, "
                        + "{} compilations, {} languages",
                libraryId, reviews, authors, compilations, languages);
        return new IndexResult(reviews, authors, compilations, languages);
    }

    public boolean isIndexed(long libraryId) {
        return indexRepository.countByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.REVIEW) > 0
                || indexRepository.countByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.AUTHOR_BIO) > 0
                || indexRepository.countByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.LANGUAGE) > 0;
    }

    private Optional<Path> catalogRoot(long libraryId) {
        return libraryRepository.findById(libraryId)
                .map(library -> library.getMetadataSidecarPath())
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of)
                .filter(layout::matches);
    }

    /**
     * Records every entry of every container as {@code key → container}. Duplicate keys across
     * containers are resolved last-wins, which for monthly review archives means the most recent
     * batch of reviews for a book.
     */
    private long indexContainers(long libraryId, List<Path> containers, LocalCatalogSourceType sourceType) {
        if (containers.isEmpty()) {
            return 0;
        }
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, sourceType);

        List<LocalCatalogIndexEntity> batch = new ArrayList<>(BATCH_SIZE);
        long total = 0;
        int containerNumber = 0;
        for (Path container : containers) {
            containerNumber++;
            for (String entryName : entryNames(container)) {
                batch.add(LocalCatalogIndexEntity.builder()
                        .libraryId(libraryId)
                        .sourceType(sourceType)
                        .entryKey(entryName)
                        .container(container.getFileName().toString())
                        .build());
                total++;
                if (batch.size() >= BATCH_SIZE) {
                    flush(batch);
                }
            }
            if (containerNumber % PROGRESS_EVERY == 0) {
                log.info("Indexing {} for library {}: {}/{} containers, {} keys so far",
                        sourceType, libraryId, containerNumber, containers.size(), total);
            }
        }
        flush(batch);
        return total;
    }

    private long indexCompilations(long libraryId, Path catalogRoot) {
        Path container = layout.compilations(catalogRoot);
        if (!Files.isReadable(container)) {
            return 0;
        }
        byte[] json = readEntry(container, layout.compilationsEntry());
        if (json.length == 0) {
            return 0;
        }
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.COMPILATION);

        List<LocalCatalogIndexEntity> batch = new ArrayList<>(BATCH_SIZE);
        int indexed = compilationParser.parse(new ByteArrayInputStream(json), (key, parts) -> {
            String entryKey = layout.bookKey(key.archiveName(), key.entryName());
            if (entryKey == null) {
                return;
            }
            batch.add(LocalCatalogIndexEntity.builder()
                    .libraryId(libraryId)
                    .sourceType(LocalCatalogSourceType.COMPILATION)
                    .entryKey(entryKey)
                    .payload(writeParts(parts))
                    .build());
            if (batch.size() >= BATCH_SIZE) {
                flush(batch);
            }
        });
        flush(batch);
        return indexed;
    }

    /**
     * The language is the listing's file name — {@code ru.txt} means every row in it is Russian — so
     * one pass over the 75 listings produces a language for every book the catalog knows.
     * <p>
     * {@link FlibustaContentsParser#parse} wraps its own read loop in a broad {@code catch (Exception)},
     * so a real failure from {@link #flush} (a database error, not a parsing problem) thrown out of the
     * consumer would otherwise be swallowed there and logged only as a row-read warning — the pass would
     * then look complete when it is not. {@code saveFailure} recovers that failure once {@code parse}
     * returns so an incomplete index for a listing is never mistaken for a finished one.
     */
    private long indexLanguages(long libraryId, Path catalogRoot) {
        Path container = layout.contents(catalogRoot);
        if (!Files.isReadable(container)) {
            return 0;
        }
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.LANGUAGE);

        List<LocalCatalogIndexEntity> batch = new ArrayList<>(BATCH_SIZE);
        long total = 0;
        for (String entryName : entryNames(container)) {
            String language = languageCode(entryName);
            if (language.isEmpty()) {
                continue;
            }
            byte[] listing = readEntry(container, entryName);
            if (listing.length == 0) {
                continue;
            }
            AtomicReference<RuntimeException> saveFailure = new AtomicReference<>();
            total += contentsParser.parse(new ByteArrayInputStream(listing), (archive, entry) -> {
                String entryKey = layout.bookKey(archive, entry);
                if (entryKey == null) {
                    return;
                }
                batch.add(LocalCatalogIndexEntity.builder()
                        .libraryId(libraryId)
                        .sourceType(LocalCatalogSourceType.LANGUAGE)
                        .entryKey(entryKey)
                        .payload(language)
                        .build());
                if (batch.size() >= BATCH_SIZE) {
                    try {
                        flush(batch);
                    } catch (RuntimeException e) {
                        saveFailure.set(e);
                        throw e;
                    }
                }
            });
            RuntimeException failure = saveFailure.get();
            if (failure != null) {
                throw new IllegalStateException(
                        "Could not save local catalog language rows from '" + entryName
                                + "': " + failure.getMessage(), failure);
            }
        }
        flush(batch);
        return total;
    }

    private String languageCode(String entryName) {
        String leaf = layout.leafName(entryName);
        int dot = leaf.lastIndexOf('.');
        return dot <= 0 ? "" : leaf.substring(0, dot).toLowerCase(Locale.ROOT);
    }

    private void flush(List<LocalCatalogIndexEntity> batch) {
        if (batch.isEmpty()) {
            return;
        }
        indexRepository.saveAll(batch);
        batch.clear();
    }

    private List<String> entryNames(Path container) {
        try {
            return archiveService.getEntryNames(container);
        } catch (IOException e) {
            log.warn("Could not list local catalog container {}: {}", container, e.getMessage());
            return List.of();
        }
    }

    private byte[] readEntry(Path container, String entryName) {
        try {
            return archiveService.getEntryBytes(container, entryName);
        } catch (IOException e) {
            log.warn("Could not read '{}' from {}: {}", entryName, container, e.getMessage());
            return new byte[0];
        }
    }

    private String writeParts(List<CompilationPart> parts) {
        try {
            return objectMapper.writeValueAsString(parts);
        } catch (JacksonException e) {
            log.warn("Could not serialise compilation parts: {}", e.getMessage());
            return null;
        }
    }

    public record IndexResult(long reviews, long authorBios, long compilations, long languages) {

        static IndexResult empty() {
            return new IndexResult(0, 0, 0, 0);
        }

        public long total() {
            return reviews + authorBios + compilations + languages;
        }
    }
}
