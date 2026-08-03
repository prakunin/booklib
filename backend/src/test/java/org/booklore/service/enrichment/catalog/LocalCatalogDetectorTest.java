package org.booklore.service.enrichment.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCatalogDetectorTest {

    private final LocalCatalogDetector detector = new LocalCatalogDetector(new FlibustaCatalogLayout());

    @TempDir
    Path books;

    private Path catalog(String name) throws IOException {
        Path directory = Files.createDirectory(books.resolve(name));
        Files.createFile(directory.resolve("annotations.7z"));
        return directory;
    }

    @Test
    void findsTheSiblingNamedAfterTheArchiveDirectory() throws IOException {
        Path archives = Files.createDirectory(books.resolve("fb2.Flibusta.Net"));
        Path expected = catalog("fb2.Flibusta.Net.FLibrary.etc");

        assertThat(detector.detect(archives.toString())).contains(expected);
    }

    /**
     * A differently named catalog is still worth suggesting, but only after the exact sibling has
     * been ruled out — the exact one is the catalog that actually belongs to these archives.
     */
    @Test
    void prefersTheExactSiblingOverAnyOtherCatalog() throws IOException {
        Path archives = Files.createDirectory(books.resolve("fb2.Flibusta.Net"));
        catalog("aaa.FLibrary.etc");
        Path expected = catalog("fb2.Flibusta.Net.FLibrary.etc");

        assertThat(detector.detect(archives.toString())).contains(expected);
    }

    @Test
    void fallsBackToAnyCatalogAlongsideTheArchives() throws IOException {
        Path archives = Files.createDirectory(books.resolve("some-library"));
        Path other = catalog("unrelated.FLibrary.etc");

        assertThat(detector.detect(archives.toString())).contains(other);
    }

    @Test
    void ignoresDirectoriesThatOnlyLookLikeACatalog() throws IOException {
        Path archives = Files.createDirectory(books.resolve("fb2.Flibusta.Net"));
        Files.createDirectory(books.resolve("fb2.Flibusta.Net.FLibrary.etc"));

        assertThat(detector.detect(archives.toString())).isEmpty();
    }

    @Test
    void returnsEmptyForMissingOrUnusableInput() {
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect("  ")).isEmpty();
        assertThat(detector.detect(books.resolve("absent/deeper").toString())).isEmpty();
    }
}
