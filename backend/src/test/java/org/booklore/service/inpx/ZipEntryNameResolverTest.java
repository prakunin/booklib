package org.booklore.service.inpx;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.UnicodeExtraFieldPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class ZipEntryNameResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void preservesAnEfsFlaggedUnicodeName() throws IOException {
        assertResolvedName("UTF-8", true, UnicodeExtraFieldPolicy.NEVER,
                "Пушкин.fb2", "Пушкин.fb2", ZipArchiveEntry.NameSource.NAME_WITH_EFS_FLAG);
    }

    @Test
    void givesTheUnicodePathExtraFieldAuthorityOverTheLegacyHeaderName() throws IOException {
        assertResolvedName("US-ASCII", false, UnicodeExtraFieldPolicy.ALWAYS,
                "Пушкин.fb2", "Пушкин.fb2", ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD);
    }

    @Test
    void preservesValidUnflaggedUtf8IncludingNonCyrillicUnicode() throws IOException {
        assertResolvedName("UTF-8", false, UnicodeExtraFieldPolicy.NEVER,
                "日本語.fb2", "日本語.fb2", ZipArchiveEntry.NameSource.NAME);
    }

    @Test
    void resolvesAnIbm866RussianName() throws IOException {
        assertResolvedName("IBM866", false, UnicodeExtraFieldPolicy.NEVER,
                "Пушкин.fb2", "Пушкин.fb2", ZipArchiveEntry.NameSource.NAME);
    }

    @Test
    void resolvesAWindows1251RussianName() throws IOException {
        assertResolvedName("windows-1251", false, UnicodeExtraFieldPolicy.NEVER,
                "Толстой.fb2", "Толстой.fb2", ZipArchiveEntry.NameSource.NAME);
    }

    @Test
    void resolvesShortAndUkrainianLegacyNames() throws IOException {
        assertResolvedName("IBM866", false, UnicodeExtraFieldPolicy.NEVER,
                "Ян.fb2", "Ян.fb2", ZipArchiveEntry.NameSource.NAME);
        assertResolvedName("windows-1251", false, UnicodeExtraFieldPolicy.NEVER,
                "Її.fb2", "Її.fb2", ZipArchiveEntry.NameSource.NAME);
    }

    @Test
    void retainsTheLibraryDecodedNameWhenLegacyEvidenceIsAmbiguous() throws IOException {
        Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("ambiguous.zip"),
                "ISO-8859-1", false, UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("é.fb2", "content"));

        try (ZipFile archive = ZipFile.builder().setPath(archivePath).get()) {
            ZipArchiveEntry entry = Collections.list(archive.getEntries()).getFirst();

            assertThat(ZipEntryNameResolver.resolve(entry)).isEqualTo(entry.getName());
        }
    }

    @Test
    void findsTheConcreteEntryByItsResolvedCaseSensitiveName() throws IOException {
        Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("lookup.zip"),
                "IBM866", false, UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Пушкин.FB2", "content"));

        try (ZipFile archive = ZipFile.builder().setPath(archivePath).get()) {
            assertThat(ZipEntryNameResolver.findEntry(archive, "Пушкин.FB2")).isNotNull();
            assertThat(ZipEntryNameResolver.findEntry(archive, "пушкин.fb2")).isNull();
        }
    }

    @Test
    void indexesResolvedNamesInOneArchivePass() throws IOException {
        Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve("index.zip"),
                "IBM866", false, UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Пушкин.FB2", "first"),
                ZipCharsetTestFixtures.entry("Толстой.pdf", "second"));

        try (ZipFile archive = ZipFile.builder().setPath(archivePath).get()) {
            assertThat(ZipEntryNameResolver.indexEntries(archive))
                    .containsOnlyKeys("Пушкин.FB2", "Толстой.pdf");
        }
    }

    private void assertResolvedName(String charset, boolean languageEncodingFlag,
                                    UnicodeExtraFieldPolicy unicodePolicy, String storedName,
                                    String expectedName, ZipArchiveEntry.NameSource expectedSource) throws IOException {
        Path archivePath = ZipCharsetTestFixtures.write(tempDir.resolve(charset.replace('-', '_') + ".zip"),
                charset, languageEncodingFlag, unicodePolicy,
                ZipCharsetTestFixtures.entry(storedName, "content"));

        try (ZipFile archive = ZipFile.builder().setPath(archivePath).get()) {
            ZipArchiveEntry entry = Collections.list(archive.getEntries()).getFirst();
            assertThat(entry.getNameSource()).isEqualTo(expectedSource);
            assertThat(ZipEntryNameResolver.resolve(entry)).isEqualTo(expectedName);
        }
    }
}
