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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Every monthly archive the book was reviewed in, oldest first — the index row lists them in that
     * order. They are increments rather than snapshots: a book reviewed in nine different months has
     * nine archives holding nine disjoint sets of reviews, so reading only one of them would return a
     * single month's worth and quietly drop the rest.
     * <p>
     * The archives were measured to be disjoint on a sample — 40 of the 78,646 keys that span several
     * of them — not proven to be so for all of them, and the result is collected into a set rather
     * than a list so that an overlap cannot turn into a duplicated review. {@link CatalogReview} is a
     * record, so identity is reviewer, body and timestamp together: two people posting the same words,
     * or one person posting twice, remain two reviews. The insertion-ordered set keeps the oldest
     * archive's reviews first.
     * <p>
     * That guard also covers the index side, where a key listed twice inside one archive would record
     * that archive's name twice: the second read returns the same reviews and collapses here.
     */
    @Override
    public List<CatalogReview> lookupReviews(long libraryId, String archiveName, String entryName) {
        String key = layout.bookKey(archiveName, entryName);
        if (key == null) {
            return List.of();
        }
        Optional<Path> root = catalogRoot(libraryId);
        if (root.isEmpty()) {
            return List.of();
        }
        Set<CatalogReview> reviews = new LinkedHashSet<>();
        for (String container : indexedContainers(libraryId, LocalCatalogSourceType.REVIEW, key)) {
            reviews.addAll(reviewParser.parse(readEntry(layout.reviewContainer(root.get(), container), key)));
        }
        return List.copyOf(reviews);
    }

    /**
     * Author biographies are keyed by a hash of the author's name, and 296 keys in the shipped catalog
     * land in more than one bucket — 286 of them holding genuinely different text. Some of those are
     * one writer described twice; others are two different people who happen to share a name, such as
     * Jean Stone the novelist and Gene Stone the editor. The buckets are numbered rather than dated, so
     * there is no later document to prefer, and attaching one person's life to another is worse than
     * attaching none: when the buckets disagree, nothing is returned — and the remaining candidates are
     * not tried, because an ambiguous key is an answer rather than a miss.
     * <p>
     * That rule is about one key's buckets and stays exactly as it is. It is <em>not</em> the rule for
     * the candidate keys, which are a precedence rather than a tie.
     * {@link FlibustaAuthorKey#candidates(String)} offers the stored name first and its surname-first
     * rotation second, because 21,689 of this library's authors are stored given-name first. The
     * candidates are walked in that order and the walk stops at the first one that resolves: for the
     * 126 authors that reach a different key under each candidate, the stored-order biography is the
     * one written. The stored name assumes nothing about which token is the surname while the rotation
     * asserts one, so stored-order is the more specific evidence and preferring it is a documented
     * precedence, not a guess between two people. The rotation is never read in that case.
     * <p>
     * The residual risk that leaves, stated plainly: the rotation is only ever read when the
     * stored-order key misses, and a rotated key that resolves is accepted without further evidence —
     * so a rotation colliding with a <em>different real person's</em> catalog key writes that person's
     * biography onto this author. {@link FlibustaAuthorKey} argues the opposite direction (a rotation
     * of an already-correct name is a key the catalog does not hold), which is true but is about a
     * different case: this one is a rotation that resolves precisely because it names somebody else.
     * Measured at 2-of-2 benign in the pairs that were read in full, out of the 126 authors that reach
     * a different key under each candidate, and accepted on that basis.
     */
    @Override
    public Optional<String> lookupAuthorBio(long libraryId, String authorName) {
        Optional<Path> root = catalogRoot(libraryId);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        for (String key : FlibustaAuthorKey.candidates(authorName)) {
            Set<String> distinct = new LinkedHashSet<>();
            for (String bucket : indexedContainers(libraryId, LocalCatalogSourceType.AUTHOR_BIO, key)) {
                String bio = new String(readEntry(layout.authorBucket(root.get(), bucket), key),
                        StandardCharsets.UTF_8);
                if (!bio.isBlank()) {
                    distinct.add(bio);
                }
            }
            if (distinct.size() > 1) {
                log.debug("Author key {} has {} different biographies in the local catalog; too "
                        + "ambiguous to attach one", key, distinct.size());
                return Optional.empty();
            }
            if (!distinct.isEmpty()) {
                return distinct.stream().findFirst();
            }
        }
        return Optional.empty();
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

    @Override
    public Optional<String> lookupLanguage(long libraryId, String archiveName, String entryName) {
        String key = layout.bookKey(archiveName, entryName);
        if (key == null) {
            return Optional.empty();
        }
        return findIndexed(libraryId, LocalCatalogSourceType.LANGUAGE, key)
                .map(LocalCatalogIndexEntity::getPayload)
                .filter(payload -> payload != null && !payload.isBlank());
    }

    @Override
    public List<CompilationMembership> lookupContainingCompilations(
            long libraryId, String archiveName, String entryName) {
        String key = layout.bookKey(archiveName, entryName);
        if (key == null) {
            return List.of();
        }
        return findIndexed(libraryId, LocalCatalogSourceType.COMPILATION_PART, key)
                .map(LocalCatalogIndexEntity::getPayload)
                .map(this::readMemberships)
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

    /**
     * The containers a key was indexed in, in the order the index recorded them. The payload is a JSON
     * array of container names; that shape is itself the format guard, exactly as it is for
     * {@link #readMemberships} — a row written before this change kept the container in its own column
     * and left the payload null, so it decodes to nothing here rather than being misread.
     */
    private List<String> indexedContainers(long libraryId, LocalCatalogSourceType type, String key) {
        return findIndexed(libraryId, type, key)
                .map(LocalCatalogIndexEntity::getPayload)
                .map(this::readContainers)
                .orElseGet(List::of);
    }

    private List<String> readContainers(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            log.warn("Could not read indexed catalog container payload: {}", e.getMessage());
            return List.of();
        }
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

    /**
     * The payload is a JSON array of {@link CompilationMembership} — one entry per omnibus the work
     * belongs to. That shape is itself the format guard: a row written before this change held a
     * single membership *object*, not an array, so decoding it as {@code List<CompilationMembership>}
     * fails cleanly here and falls back to an empty list rather than misreading it, the same way
     * {@link #readParts} already does for the forward direction.
     */
    private List<CompilationMembership> readMemberships(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<List<CompilationMembership>>() {
            });
        } catch (JacksonException e) {
            log.warn("Could not read indexed compilation membership payload: {}", e.getMessage());
            return List.of();
        }
    }

    private record AnnotationKey(long libraryId, String archiveName) {
    }
}
