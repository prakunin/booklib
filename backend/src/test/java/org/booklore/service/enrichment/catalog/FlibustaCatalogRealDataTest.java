package org.booklore.service.enrichment.catalog;

import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the real catalog rather than a fixture, to catch the failure the unit tests structurally
 * cannot: synthetic documents prove the parsers handle the shape we believe the data has, not the
 * shape it actually has.
 * <p>
 * Skipped whenever the catalog is absent or libarchive is not loadable — which includes an ordinary
 * Gradle run, since libarchive is not on the test JVM's {@code java.library.path}. To run it:
 *
 * <pre>{@code
 * LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu ./gradlew test \
 *     --tests "org.booklore.service.enrichment.catalog.FlibustaCatalogRealDataTest" --rerun-tasks
 * }</pre>
 */
class FlibustaCatalogRealDataTest {

    private static final Path CATALOG_ROOT = Path.of("/books/fb2.Flibusta.Net.FLibrary.etc");
    private static final String KNOWN_ARCHIVE = "d.fb2-009373-367300.zip";

    private final FlibustaCatalogLayout layout = new FlibustaCatalogLayout();
    private final FlibustaAnnotationParser annotationParser = new FlibustaAnnotationParser();
    private final ArchiveService archiveService = new ArchiveService();

    static boolean catalogReadable() {
        return Files.isDirectory(CATALOG_ROOT) && ArchiveService.isAvailable();
    }

    @Test
    @EnabledIf("catalogReadable")
    void layoutMatchesTheShippedCatalog() {
        assertThat(layout.matches(CATALOG_ROOT)).isTrue();
        assertThat(layout.authorBuckets(CATALOG_ROOT)).isNotEmpty();
        assertThat(layout.reviewContainers(CATALOG_ROOT)).isNotEmpty();
    }

    @Test
    @EnabledIf("catalogReadable")
    void parsesARealAnnotationsDocument() throws Exception {
        byte[] xml = archiveService.getEntryBytes(layout.annotations(CATALOG_ROOT), KNOWN_ARCHIVE);
        assertThat(xml).isNotEmpty();

        Map<String, String> annotations = annotationParser.parse(xml);

        assertThat(annotations).isNotEmpty();
        assertThat(annotations.keySet()).allSatisfy(entry -> assertThat(entry).endsWith(".fb2"));
        assertThat(annotations.values()).allSatisfy(text -> assertThat(text).isNotBlank());
    }

    /**
     * The author key is the one piece of this catalog we reverse-engineered rather than read from a
     * specification, so it is checked against a bucket listing rather than trusted.
     */
    @Test
    @EnabledIf("catalogReadable")
    void authorKeysMatchTheShippedBucketNames() throws Exception {
        List<String> keys = archiveService.getEntryNames(layout.authorBucket(CATALOG_ROOT, "10.7z"));

        assertThat(keys).isNotEmpty();
        assertThat(keys).contains(FlibustaAuthorKey.of("Хэндлер Дэниел"));
        assertThat(keys).allSatisfy(key -> assertThat(key).hasSize(32).matches("[0-9a-f]+"));
    }
}
