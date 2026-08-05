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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
    static final String INDEX_VERSION_KEY = "local-catalog-index-v2";
    private static final String INDEX_VERSION = "2";

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
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.INDEX_VERSION);

        long reviews = indexContainers(libraryId, layout.reviewContainers(catalogRoot), LocalCatalogSourceType.REVIEW);
        long authors = indexContainers(libraryId, layout.authorBuckets(catalogRoot), LocalCatalogSourceType.AUTHOR_BIO);
        CompilationCounts compilations = indexCompilations(libraryId, catalogRoot);
        LanguageIndexResult languageIndex = indexLanguages(libraryId, catalogRoot);

        IndexResult result = new IndexResult(reviews, authors, compilations.forward(),
                compilations.reverse(), languageIndex.rows());
        if (languageIndex.completed()) {
            writeCompletionMarker(libraryId);
        }
        logSummary(libraryId, result);
        return result;
    }

    /**
     * A source type that indexes nothing is not a quiet outcome: every enrichment step reading it
     * becomes a silent no-op, and a backfill over such an index is indistinguishable from a successful
     * one. Two whole measurement attempts were spent before anyone thought to query the table, so an
     * empty source type is reported as loudly as the pass can report anything.
     * <p>
     * All five source types are covered, {@code COMPILATION_PART} included: it is written in its own
     * pass from its own accumulator and can come out empty while the forward {@code COMPILATION} rows
     * do not, so folding the two into one number would leave exactly the blind spot this reporting
     * exists to close.
     */
    private void logSummary(long libraryId, IndexResult result) {
        log.info("Indexed local catalog for library {}: {} reviews, {} author biographies, "
                        + "{} compilations, {} compilation parts, {} languages",
                libraryId, result.reviews(), result.authorBios(), result.compilations(),
                result.compilationParts(), result.languages());
        warnWhenEmpty(libraryId, LocalCatalogSourceType.REVIEW, result.reviews());
        warnWhenEmpty(libraryId, LocalCatalogSourceType.AUTHOR_BIO, result.authorBios());
        warnWhenEmpty(libraryId, LocalCatalogSourceType.COMPILATION, result.compilations());
        warnWhenEmpty(libraryId, LocalCatalogSourceType.COMPILATION_PART, result.compilationParts());
        warnWhenEmpty(libraryId, LocalCatalogSourceType.LANGUAGE, result.languages());
    }

    private void warnWhenEmpty(long libraryId, LocalCatalogSourceType sourceType, long rows) {
        if (rows == 0) {
            log.warn("Local catalog index for library {} has no {} rows — every enrichment step that "
                    + "reads {} will silently do nothing", libraryId, sourceType, sourceType);
        }
    }

    /**
     * Whether this library's catalog has ever been walked.
     * <p>
     * Asked once per enriched book — {@link LocalCatalogIndexService#ensureIndexed} is called at the
     * top of every book's enrichment, not only by the backfill — so it must be the cheapest question
     * the database can answer, and {@code COUNT(*)} is not that question. Both forms are served by
     * {@code uk_local_catalog_index} and both are covering, but a count has to walk every matching
     * index entry, and on the dev library the first source type it asks about has 176,334 of them:
     * <pre>
     * SELECT count(*) … WHERE library_id=19 AND source_type='REVIEW'            41.6 / 40.0 / 45.8 ms
     * SELECT 1        … WHERE library_id=19 AND source_type='REVIEW' LIMIT 1     0.25 / 0.12 / 0.11 ms
     * </pre>
     * The old form cost 45.4 ms per book, 57.7% of a measured 14,000-book backfill's wall clock, to
     * establish something a single row settles. Spring Data's {@code exists} projection emits
     * {@code LIMIT 1}.
     * <p>
     * The answer is deliberately <em>not</em> remembered between calls. A per-library memo would
     * remove the query entirely, but it would also mean an index that later goes away — a rebuild
     * that fails part-way, a manual truncate, a library repointed at another catalog — is never
     * noticed, and every subsequent book silently enriches to nothing. Re-asking costs 0.16 ms and
     * bounds that staleness at a single book.
     * <p>
     * The completion marker is written last, after every source pass. Older indexes do not have it,
     * so upgrading the application makes the first enrichment rebuild them before identity lookups
     * begin. A failed partial rebuild has no marker either and is never reported as ready.
     */
    public boolean isIndexed(long libraryId) {
        return indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(
                        libraryId, LocalCatalogSourceType.INDEX_VERSION, INDEX_VERSION_KEY)
                .map(LocalCatalogIndexEntity::getPayload)
                .filter(INDEX_VERSION::equals)
                .isPresent();
    }

    private Optional<Path> catalogRoot(long libraryId) {
        return libraryRepository.findById(libraryId)
                .map(library -> library.getMetadataSidecarPath())
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of)
                .filter(layout::matches);
    }

    /**
     * Records one row per key whose {@code payload} names <em>every</em> container the key was seen in,
     * in the order the containers were walked — which is ascending file name, so a review key's
     * containers come out oldest month first.
     * <p>
     * Keeping all of them rather than the last one is what the data requires. Monthly review archives
     * are <em>increments</em>, not snapshots: measured against the shipped catalog, consecutive archives
     * holding the same key are fully disjoint and every review's timestamp falls inside its own
     * archive's month, so a later archive never supersedes an earlier one. 78,646 of the 176,334 review
     * keys live in more than one archive, one of them in 99 — last-wins would have discarded the review
     * history of 45% of reviewed books. Author buckets are numbered rather than dated, so there is no
     * "later" bucket to prefer at all, and 286 of their 296 duplicated keys hold genuinely different
     * documents; {@link FlibustaCatalogSource#lookupAuthorBio} decides what to do with that.
     * <p>
     * A key cannot therefore be finalised the first time it is seen, so {@code containersByKey}
     * accumulates the whole pass and the rows are written once, after it — the same shape
     * {@link #indexCompilations} uses for its reverse rows, and for the same reason. The map is local to
     * the call: a field would carry one rebuild's keys into the next. It holds one entry per distinct
     * key and one short, shared container name per sighting — for reviews, 176,334 entries over 432,413
     * sightings, tens of megabytes, comparable to what the compilation pass already keeps resident.
     */
    private long indexContainers(long libraryId, List<Path> containers, LocalCatalogSourceType sourceType) {
        if (containers.isEmpty()) {
            return 0;
        }
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, sourceType);

        Map<String, List<String>> containersByKey = new LinkedHashMap<>();
        long sightings = 0;
        int containerNumber = 0;
        for (Path container : containers) {
            containerNumber++;
            String containerName = container.getFileName().toString();
            for (String entryName : entryNames(container)) {
                containersByKey.computeIfAbsent(entryName, unused -> new ArrayList<>(1)).add(containerName);
                sightings++;
            }
            if (containerNumber % PROGRESS_EVERY == 0) {
                log.info("Indexing {} for library {}: {}/{} containers, {} keys from {} entries so far",
                        sourceType, libraryId, containerNumber, containers.size(),
                        containersByKey.size(), sightings);
            }
        }

        List<LocalCatalogIndexEntity> batch = new ArrayList<>(BATCH_SIZE);
        for (Map.Entry<String, List<String>> entry : containersByKey.entrySet()) {
            batch.add(LocalCatalogIndexEntity.builder()
                    .libraryId(libraryId)
                    .sourceType(sourceType)
                    .entryKey(entry.getKey())
                    .payload(writeContainers(entry.getValue()))
                    .build());
            if (batch.size() >= BATCH_SIZE) {
                flush(batch);
            }
        }
        flush(batch);

        log.info("Indexed {} for library {}: {} keys from {} entries across {} containers",
                sourceType, libraryId, containersByKey.size(), sightings, containers.size());
        return containersByKey.size();
    }

    /**
     * {@link FlibustaCompilationParser#parse}, like {@link FlibustaContentsParser#parse}, wraps its
     * whole read loop in a broad {@code catch (Exception)}, so a real failure from {@link #flush} (a
     * database error, not a parsing problem) thrown out of the consumer would otherwise be swallowed
     * there and logged only as a parse warning — the pass would then look complete when it is not.
     * {@code saveFailure} recovers that failure once {@code parse} returns so a partial index is never
     * mistaken for a finished one. That guard only covers the forward {@code COMPILATION} rows, which
     * are still written from inside the parser's callback; the reverse {@code COMPILATION_PART} rows
     * are written afterwards, in plain code the parser's swallow-all catch never sees, so no such guard
     * is needed for them.
     * <p>
     * A part key legitimately belongs to more than one omnibus — 45% of the 78,907 keys in the shipped
     * catalog do, one of them 46 times — and the unique index allows only one row per key, so a key's
     * memberships cannot be finalised the first time it is seen. The parse is streaming (one callback
     * per compilation), so {@code membershipsByPartKey} accumulates every membership for every key
     * across the whole pass and the reverse rows are written once, after it completes. That map holds
     * at most 78,907 small (archive, entry, part) records — a few tens of megabytes — alongside the
     * ~30 MB {@code compilations.json} document already held in memory for the parse itself, so the
     * extra cost is small relative to what this pass already keeps resident.
     * <p>
     * The forward {@code COMPILATION} rows are deduplicated by {@code compilationKeys}, first sighting
     * wins — the same rule {@link #indexLanguages} applies through {@code putIfAbsent}, and for the same
     * reason: {@code uk_local_catalog_index} allows one row per (library, source type, key), so a second
     * sighting of a {@code (folder, file)} pair would abort the whole rebuild at {@link #flush}. That
     * abort is not recoverable on a later run: it happens after the {@code REVIEW} and
     * {@code AUTHOR_BIO} rows are written and before {@link #indexLanguages} runs, and
     * {@link #isIndexed} is already satisfied by those rows, so {@code ensureIndexed} never retries and
     * {@code LANGUAGE} and {@code COMPILATION} stay permanently empty for that library. A repeated key
     * denotes the same compilation, so its parts are skipped with it rather than being counted twice
     * into {@code membershipsByPartKey}, where a doubled membership would make the single-omnibus book
     * look as though it belonged to two and {@code LocalCompilationStep} would then decline to name a
     * series at all.
     */
    private CompilationCounts indexCompilations(long libraryId, Path catalogRoot) {
        Path container = layout.compilations(catalogRoot);
        if (!Files.isReadable(container)) {
            return CompilationCounts.none();
        }
        byte[] json = readEntry(container, layout.compilationsEntry());
        if (json.length == 0) {
            return CompilationCounts.none();
        }
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.COMPILATION);
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.COMPILATION_PART);

        List<LocalCatalogIndexEntity> batch = new ArrayList<>(BATCH_SIZE);
        Map<String, List<CompilationMembership>> membershipsByPartKey = new LinkedHashMap<>();
        Set<String> compilationKeys = new HashSet<>();
        AtomicLong duplicates = new AtomicLong();
        AtomicReference<RuntimeException> saveFailure = new AtomicReference<>();
        compilationParser.parse(new ByteArrayInputStream(json), (key, parts) -> {
            String entryKey = layout.bookKey(key.archiveName(), key.entryName());
            if (entryKey == null) {
                return;
            }
            if (!compilationKeys.add(entryKey)) {
                duplicates.incrementAndGet();
                return;
            }
            batch.add(LocalCatalogIndexEntity.builder()
                    .libraryId(libraryId)
                    .sourceType(LocalCatalogSourceType.COMPILATION)
                    .entryKey(entryKey)
                    .payload(writeParts(parts))
                    .build());
            for (CompilationPart part : parts) {
                String partKey = layout.bookKey(part.archiveName(), part.entryName());
                if (partKey == null) {
                    continue;
                }
                membershipsByPartKey.computeIfAbsent(partKey, unused -> new ArrayList<>())
                        .add(new CompilationMembership(key.archiveName(), key.entryName(), part.part()));
            }
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
                    "Could not save local catalog compilation rows: " + failure.getMessage(), failure);
        }
        flush(batch);

        for (Map.Entry<String, List<CompilationMembership>> entry : membershipsByPartKey.entrySet()) {
            batch.add(LocalCatalogIndexEntity.builder()
                    .libraryId(libraryId)
                    .sourceType(LocalCatalogSourceType.COMPILATION_PART)
                    .entryKey(entry.getKey())
                    .payload(writeMemberships(entry.getValue()))
                    .build());
            if (batch.size() >= BATCH_SIZE) {
                flush(batch);
            }
        }
        flush(batch);

        if (duplicates.get() > 0) {
            log.warn("compilations.json for library {} lists {} compilation(s) more than once; kept the "
                    + "first sighting of each", libraryId, duplicates.get());
        }
        return new CompilationCounts(compilationKeys.size(), membershipsByPartKey.size());
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
     * <p>
     * Unlike the review and author passes this one streams its rows out as it reads, because it has no
     * reason to hold them: measured across all 75 listings, not one of the 702,291 keys appears under
     * two different languages, and the only duplication that exists is 75 keys listed twice as
     * byte-identical rows <em>within</em> a single listing. First-wins is therefore lossless on the
     * shipped catalog, and {@code metadataHashByKey} only has to remember which keys have already
     * been written. A compact payload hash detects conflicting identity or language without retaining
     * 702k titles and author lists in memory. That map is the pass's memory cost and is held only for
     * the duration of the pass.
     */
    private LanguageIndexResult indexLanguages(long libraryId, Path catalogRoot) {
        Path container = layout.contents(catalogRoot);
        if (!Files.isReadable(container)) {
            log.warn("Local catalog contents archive is not readable: {}", container);
            return LanguageIndexResult.incomplete();
        }
        indexRepository.deleteByLibraryIdAndSourceType(libraryId, LocalCatalogSourceType.LANGUAGE);

        List<LocalCatalogIndexEntity> batch = new ArrayList<>(BATCH_SIZE);
        Map<String, Integer> metadataHashByKey = new HashMap<>();
        AtomicLong conflicts = new AtomicLong();
        long rows = 0;
        List<String> contentsEntries;
        try {
            contentsEntries = archiveService.getEntryNames(container);
        } catch (IOException e) {
            log.warn("Could not list required local catalog archive {}: {}", container, e.getMessage());
            return LanguageIndexResult.incomplete();
        }
        int listings = 0;
        for (String entryName : contentsEntries) {
            String language = languageCode(entryName);
            if (language.isEmpty()) {
                continue;
            }
            listings++;
            byte[] listing;
            try {
                listing = archiveService.getEntryBytes(container, entryName);
            } catch (IOException e) {
                log.warn("Could not read required local catalog entry '{}' from {}: {}",
                        entryName, container, e.getMessage());
                return LanguageIndexResult.incomplete();
            }
            if (listing.length == 0) {
                log.warn("Local catalog contents listing is empty: {}", entryName);
                return LanguageIndexResult.incomplete();
            }
            AtomicReference<RuntimeException> saveFailure = new AtomicReference<>();
            rows += contentsParser.parse(new ByteArrayInputStream(listing), row -> {
                try {
                    String entryKey = layout.bookKey(row.archiveName(), row.entryName());
                    if (entryKey == null) {
                        return;
                    }
                    CatalogBookMetadata catalogMetadata = new CatalogBookMetadata(
                            row.title(), row.authors(), language);
                    Integer alreadyIndexed = metadataHashByKey.putIfAbsent(entryKey, catalogMetadata.hashCode());
                    if (alreadyIndexed != null) {
                        if (alreadyIndexed != catalogMetadata.hashCode()) {
                            conflicts.incrementAndGet();
                        }
                        return;
                    }
                    batch.add(LocalCatalogIndexEntity.builder()
                            .libraryId(libraryId)
                            .sourceType(LocalCatalogSourceType.LANGUAGE)
                            .entryKey(entryKey)
                            .payload(writeCatalogMetadata(catalogMetadata))
                            .build());
                    if (batch.size() >= BATCH_SIZE) {
                        flush(batch);
                    }
                } catch (RuntimeException e) {
                    saveFailure.set(e);
                    throw e;
                }
            });
            RuntimeException failure = saveFailure.get();
            if (failure != null) {
                throw new IllegalStateException(
                        "Could not save local catalog language rows from '" + entryName
                                + "': " + failure.getMessage(), failure);
            }
        }
        if (listings == 0) {
            log.warn("Local catalog contents archive has no language listings: {}", container);
            return LanguageIndexResult.incomplete();
        }
        flush(batch);

        if (conflicts.get() > 0) {
            log.warn("{} book(s) in library {} have conflicting contents listings; kept the listing "
                    + "each was seen in first", conflicts.get(), libraryId);
        }
        log.info("Indexed {} for library {}: {} keys from {} rows",
                LocalCatalogSourceType.LANGUAGE, libraryId, metadataHashByKey.size(), rows);
        return new LanguageIndexResult(metadataHashByKey.size(), true);
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

    private void writeCompletionMarker(long libraryId) {
        indexRepository.saveAll(List.of(LocalCatalogIndexEntity.builder()
                .libraryId(libraryId)
                .sourceType(LocalCatalogSourceType.INDEX_VERSION)
                .entryKey(INDEX_VERSION_KEY)
                .payload(INDEX_VERSION)
                .build()));
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

    private String writeMemberships(List<CompilationMembership> memberships) {
        try {
            return objectMapper.writeValueAsString(memberships);
        } catch (JacksonException e) {
            log.warn("Could not serialise compilation memberships: {}", e.getMessage());
            return null;
        }
    }

    private String writeContainers(List<String> containers) {
        try {
            return objectMapper.writeValueAsString(containers);
        } catch (JacksonException e) {
            log.warn("Could not serialise catalog containers: {}", e.getMessage());
            return null;
        }
    }

    private String writeCatalogMetadata(CatalogBookMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise local catalog book metadata", e);
        }
    }

    /**
     * The two row counts one pass over {@code compilations.json} produces: forward
     * {@code COMPILATION} rows and reverse {@code COMPILATION_PART} rows. They are not the same
     * number and do not go empty together, so they are carried separately rather than summed.
     */
    private record CompilationCounts(long forward, long reverse) {

        static CompilationCounts none() {
            return new CompilationCounts(0, 0);
        }
    }

    private record LanguageIndexResult(long rows, boolean completed) {
        private static LanguageIndexResult incomplete() {
            return new LanguageIndexResult(0, false);
        }
    }

    public record IndexResult(long reviews, long authorBios, long compilations, long compilationParts,
                              long languages) {

        static IndexResult empty() {
            return new IndexResult(0, 0, 0, 0, 0);
        }

        public long total() {
            return reviews + authorBios + compilations + compilationParts + languages;
        }
    }
}
