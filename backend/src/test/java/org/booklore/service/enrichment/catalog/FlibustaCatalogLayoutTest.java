package org.booklore.service.enrichment.catalog;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FlibustaCatalogLayoutTest {

    private final FlibustaCatalogLayout layout = new FlibustaCatalogLayout();

    @TempDir
    Path catalogRoot;

    @Nested
    class Detection {

        @Test
        void recognisesADirectoryHoldingAnnotations() throws IOException {
            Files.createFile(catalogRoot.resolve("annotations.7z"));

            assertThat(layout.matches(catalogRoot)).isTrue();
        }

        @Test
        void rejectsDirectoryWithoutAnnotations() {
            assertThat(layout.matches(catalogRoot)).isFalse();
        }

        @Test
        void rejectsMissingAndNullPaths() {
            assertThat(layout.matches(catalogRoot.resolve("absent"))).isFalse();
            assertThat(layout.matches(null)).isFalse();
        }
    }

    @Nested
    class Keys {

        @Test
        void joinsArchiveAndEntryTheWayTheCatalogFilesThem() {
            assertThat(layout.bookKey("f.fb2-173909-177717.zip", "110119.fb2"))
                    .isEqualTo("f.fb2-173909-177717.zip#110119.fb2");
        }

        /**
         * Books found by scanning nested containers carry a path, but the catalog only ever files
         * the leaf name, so the path must be stripped or every such book misses.
         */
        @Test
        void usesTheLeafOfANestedEntryPath() {
            assertThat(layout.bookKey("outer.zip", "inner/sub/110119.fb2"))
                    .isEqualTo("outer.zip#110119.fb2");
            assertThat(layout.leafName("inner\\sub\\110119.fb2")).isEqualTo("110119.fb2");
        }

        @Test
        void returnsNullWhenEitherHalfIsMissing() {
            assertThat(layout.bookKey(null, "1.fb2")).isNull();
            assertThat(layout.bookKey("a.zip", " ")).isNull();
        }
    }

    @Nested
    class ContainerListing {

        @Test
        void listsOnlyArchivesAndSortsThemStably() throws IOException {
            Path reviews = Files.createDirectory(catalogRoot.resolve("reviews"));
            Files.createFile(reviews.resolve("200710.7z"));
            Files.createFile(reviews.resolve("200709.7z"));
            Files.createFile(reviews.resolve("readme.txt"));

            assertThat(layout.reviewContainers(catalogRoot))
                    .extracting(path -> path.getFileName().toString())
                    .containsExactly("200709.7z", "200710.7z");
        }

        @Test
        void returnsEmptyWhenTheDirectoryIsAbsent() {
            assertThat(layout.authorBuckets(catalogRoot)).isEmpty();
            assertThat(layout.reviewContainers(catalogRoot)).isEmpty();
        }
    }
}
