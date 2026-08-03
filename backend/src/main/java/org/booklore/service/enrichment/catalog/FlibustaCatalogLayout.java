package org.booklore.service.enrichment.catalog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Knows where things live inside a {@code *.FLibrary.etc} directory. Everything layout-specific is
 * confined here and to the parsers, so another catalog format only needs its own
 * {@link LocalCatalogSource} implementation rather than changes to the pipeline.
 *
 * <pre>
 * fb2.Flibusta.Net.FLibrary.etc/
 *   annotations.7z      218 XML documents, one per library archive
 *   contents.7z         per-language listings, ru.txt / be.txt / …
 *   compilations.7z     compilations.json
 *   authors/            0.7z … 78.7z, biographies keyed by MD5 of the author name
 *   reviews/            YYYYMM.7z, reviews keyed by "&lt;archive&gt;.zip#&lt;entry&gt;.fb2"
 * </pre>
 */
@Slf4j
@Component
public class FlibustaCatalogLayout {

    private static final String ANNOTATIONS = "annotations.7z";
    private static final String CONTENTS = "contents.7z";
    private static final String COMPILATIONS = "compilations.7z";
    private static final String COMPILATIONS_ENTRY = "compilations.json";
    private static final String AUTHORS_DIR = "authors";
    private static final String REVIEWS_DIR = "reviews";
    private static final String ARCHIVE_SUFFIX = ".7z";

    /**
     * Whether the directory looks like this layout. The annotations document is the marker: it is the
     * one file no such catalog ships without.
     */
    public boolean matches(Path catalogRoot) {
        return catalogRoot != null
                && Files.isDirectory(catalogRoot)
                && Files.isReadable(catalogRoot.resolve(ANNOTATIONS));
    }

    public Path annotations(Path catalogRoot) {
        return catalogRoot.resolve(ANNOTATIONS);
    }

    public Path contents(Path catalogRoot) {
        return catalogRoot.resolve(CONTENTS);
    }

    public Path compilations(Path catalogRoot) {
        return catalogRoot.resolve(COMPILATIONS);
    }

    public String compilationsEntry() {
        return COMPILATIONS_ENTRY;
    }

    public Path authorBucket(Path catalogRoot, String container) {
        return catalogRoot.resolve(AUTHORS_DIR).resolve(container);
    }

    public Path reviewContainer(Path catalogRoot, String container) {
        return catalogRoot.resolve(REVIEWS_DIR).resolve(container);
    }

    public List<Path> authorBuckets(Path catalogRoot) {
        return archivesIn(catalogRoot.resolve(AUTHORS_DIR));
    }

    public List<Path> reviewContainers(Path catalogRoot) {
        return archivesIn(catalogRoot.resolve(REVIEWS_DIR));
    }

    /**
     * The key a book file is filed under in the reviews and compilations data sets.
     */
    public String bookKey(String archiveName, String entryName) {
        if (archiveName == null || archiveName.isBlank() || entryName == null || entryName.isBlank()) {
            return null;
        }
        return archiveName.strip() + "#" + leafName(entryName);
    }

    /**
     * Archive-scanned books from nested containers carry a path rather than a bare name; the catalog
     * files everything by the leaf, so the leaf is what is looked up.
     */
    public String leafName(String entryName) {
        String normalized = entryName.strip().replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash < 0 ? normalized : normalized.substring(lastSlash + 1);
    }

    private List<Path> archivesIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ARCHIVE_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list local catalog directory {}: {}", directory, e.getMessage());
            return List.of();
        }
    }
}
