package org.booklore.service.inpx;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.UnicodeExtraFieldPolicy;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards what ignoring the local file header is allowed to change: nothing an archive reader can
 * observe. The speed it buys is not assertable here — it only shows on a multi-gigabyte archive on a
 * mounted volume — so these pin the behaviour instead, which is the half that could regress silently.
 */
class LibraryArchivesTest {

    @TempDir
    Path tempDir;

    private String contentOf(ZipFile archive, ZipArchiveEntry entry) throws IOException {
        try (InputStream input = archive.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    class Reading {

        @Test
        void readsAnEntryWhoseDataOffsetWasNeverResolvedUpFront() throws IOException {
            Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("plain.zip"),
                    "UTF-8", true, UnicodeExtraFieldPolicy.NEVER,
                    ZipCharsetTestFixtures.entry("first.fb2", "first content"),
                    ZipCharsetTestFixtures.entry("second.fb2", "second content"));

            try (ZipFile archive = LibraryArchives.open(archivePath)) {
                ZipArchiveEntry second = ZipEntryNameResolver.findEntry(archive, "second.fb2");

                assertThat(second).isNotNull();
                assertThat(contentOf(archive, second)).isEqualTo("second content");
            }
        }

        @Test
        void reportsTheSameEntrySizeTheCentralDirectoryRecords() throws IOException {
            Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("sizes.zip"),
                    "UTF-8", true, UnicodeExtraFieldPolicy.NEVER,
                    ZipCharsetTestFixtures.entry("sized.fb2", "0123456789"));

            try (ZipFile archive = LibraryArchives.open(archivePath)) {
                assertThat(Collections.list(archive.getEntries()).getFirst().getSize()).isEqualTo(10L);
            }
        }
    }

    @Nested
    class NameResolution {

        /**
         * The one case the skipped pass would otherwise have handled. Commons Compress applies a
         * Unicode path extra field while walking local file headers; with that walk gone, the
         * resolver has to read the field itself, or a name stored in a legacy code page would fall
         * through to the guessing heuristic.
         */
        @Test
        void stillPrefersTheUnicodePathExtraFieldOverTheLegacyHeaderName() throws IOException {
            Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("unicode-extra.zip"),
                    "US-ASCII", false, UnicodeExtraFieldPolicy.ALWAYS,
                    ZipCharsetTestFixtures.entry("Пушкин.fb2", "content"));

            try (ZipFile archive = LibraryArchives.open(archivePath)) {
                ZipArchiveEntry entry = Collections.list(archive.getEntries()).getFirst();

                // Commons Compress never got to set it, which is exactly why resolve() must.
                assertThat(entry.getNameSource()).isEqualTo(ZipArchiveEntry.NameSource.NAME);
                assertThat(ZipEntryNameResolver.resolve(entry)).isEqualTo("Пушкин.fb2");
                assertThat(ZipEntryNameResolver.findEntry(archive, "Пушкин.fb2")).isNotNull();
            }
        }

        @Test
        void resolvesLegacyEncodedNamesTheSameWayAsAFullyParsedArchive() throws IOException {
            Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("legacy.zip"),
                    "IBM866", false, UnicodeExtraFieldPolicy.NEVER,
                    ZipCharsetTestFixtures.entry("Пушкин.FB2", "content"));

            try (ZipFile ignoringHeaders = LibraryArchives.open(archivePath);
                 ZipFile parsingHeaders = ZipFile.builder().setPath(archivePath).get()) {
                assertThat(ZipEntryNameResolver.indexEntries(ignoringHeaders).keySet())
                        .isEqualTo(ZipEntryNameResolver.indexEntries(parsingHeaders).keySet())
                        .containsExactly("Пушкин.FB2");
            }
        }

        @Test
        void keepsEfsFlaggedUnicodeNames() throws IOException {
            Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("efs.zip"),
                    "UTF-8", true, UnicodeExtraFieldPolicy.NEVER,
                    ZipCharsetTestFixtures.entry("Пушкин.fb2", "content"));

            try (ZipFile archive = LibraryArchives.open(archivePath)) {
                assertThat(ZipEntryNameResolver.findEntry(archive, "Пушкин.fb2")).isNotNull();
            }
        }
    }
}
