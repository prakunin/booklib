package org.booklore.service.metadata.writer;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.MoodEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.net.URLDecoder;

class EpubMetadataWriterTest {

    private static final String BELONGS_TO_COLLECTION = "belongs-to-collection";

    private EpubMetadataWriter writer;
    private BookMetadataEntity metadata;
    private BookEntity bookEntity;
    private AppSettingService appSettingService;
    private FileService fileService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        appSettingService = mock(AppSettingService.class);
        MetadataPersistenceSettings.FormatSettings epubFormatSettings = MetadataPersistenceSettings.FormatSettings.builder()
                .enabled(true)
                .maxFileSizeInMb(100)
                .build();
        MetadataPersistenceSettings.SaveToOriginalFile saveToOriginalFile = MetadataPersistenceSettings.SaveToOriginalFile.builder()
                .epub(epubFormatSettings)
                .build();
        MetadataPersistenceSettings metadataPersistenceSettings = new MetadataPersistenceSettings();
        metadataPersistenceSettings.setSaveToOriginalFile(saveToOriginalFile);

        AppSettings appSettings = mock(AppSettings.class);
        when(appSettings.getMetadataPersistenceSettings()).thenReturn(metadataPersistenceSettings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
        fileService = mock(FileService.class);

        writer = new EpubMetadataWriter(appSettingService, fileService);
        metadata = new BookMetadataEntity();
        metadata.setTitle("Test Book");
        AuthorEntity author = new AuthorEntity();
        author.setName("Test Author");
        metadata.setAuthors(List.of(author));

        bookEntity = new BookEntity();
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(tempDir.toString());
        bookEntity.setLibraryPath(libraryPath);
        BookFileEntity primaryFile = new BookFileEntity();
        primaryFile.setBook(bookEntity);
        bookEntity.setBookFiles(Collections.singletonList(primaryFile));
        bookEntity.getPrimaryBookFile().setFileSubPath("");
        bookEntity.getPrimaryBookFile().setFileName("test.epub");
    }

    @Nested
    @DisplayName("Metadata writing Tests")
    class MetadataWritingTests {
        @Test
        @DisplayName("Should only overwrite authors of EPUB metadata")
        void writeMetadata_withAuthor_onlyAuthor() throws IOException {
            StringBuilder existingMetadata = new StringBuilder();
            existingMetadata.append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">");
            existingMetadata.append("<dc:creator id=\"creator02\">Alice</dc:creator>");
            existingMetadata.append("<meta property=\"role\" refines=\"#creator02\">ill</meta>");
            existingMetadata.append("</metadata>");
            String opfContent = String.format("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        %s
                    </package>
                    """, existingMetadata);
            File epubFile = createEpubWithOpf(opfContent, "test-metadata-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags()));

            assertTrue(epubFile.exists());
            assertTrue(epubFile.length() > 0);
            try (ZipFile zf = new ZipFile(epubFile)) {
                ZipEntry ze = zf.getEntry("OEBPS/content.opf");
                try (InputStream is = zf.getInputStream(ze)) {
                    byte[] fileBytes = is.readAllBytes();
                    String fileString = new String(fileBytes);
                    assertTrue(fileString.contains("id=\"creator02\""));
                }
            }
        }
    }

    @Nested
    @DisplayName("URL Decoding Tests")
    class UrlDecodingTests {

        @Test
        @DisplayName("Should properly handle URL-encoded href values in manifest")
        void writeMetadataToFile_withUnicodeHref_handlesDecoding() throws IOException {
            byte[] epubContent = createEpubWithUnicodeCoverHref();
            File epubFile = tempDir.resolve("test_unicode.epub").toFile();
            Files.write(epubFile.toPath(), epubContent);

            assertDoesNotThrow(() -> writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags()));

            assertTrue(epubFile.exists());
            assertTrue(epubFile.length() > 0);
        }

        @Test
        @DisplayName("Should handle URL-encoded cover href during cover replacement")
        void replaceCoverImageFromUpload_withUnicodeHref_handlesDecoding() throws IOException {
            byte[] epubContent = createEpubWithUnicodeCoverHref();
            File epubFile = tempDir.resolve("test_cover_unicode.epub").toFile();
            Files.write(epubFile.toPath(), epubContent);

            byte[] imageBytes = createMinimalPngImage();
            MultipartFile coverFile = new MockMultipartFile(
                    "cover.png",
                    "cover.png",
                    "image/png",
                    imageBytes
            );

            assertDoesNotThrow(() -> writer.replaceCoverImageFromUpload(bookEntity, coverFile));
        }
    }

    @Nested
    @DisplayName("Whitespace Tests")
    class WhitespaceTests {
        @Test
        @DisplayName("Should not add extra whitespace lines on repeated saves")
        void saveMetadataToFile_repeatedSaves_shouldNotInflateWhitespace() throws IOException {
            String initialOpfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Original Title</dc:title>
                            <dc:creator>Original Author</dc:creator>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(initialOpfContent, "test-whitespace-" + System.nanoTime() + ".epub");

            BookMetadataEntity newMeta = new BookMetadataEntity();
            newMeta.setTitle("Updated Title");
            AuthorEntity author = new AuthorEntity();
            author.setName("Updated Author");
            newMeta.setAuthors(List.of(author));

            writer.saveMetadataToFile(epubFile, newMeta, null, new MetadataClearFlags());
            String contentAfterFirstSave = readOpfContent(epubFile);

            newMeta.setTitle("Updated Title 2"); // Change title to force write
            writer.saveMetadataToFile(epubFile, newMeta, null, new MetadataClearFlags());
            String contentAfterSecondSave = readOpfContent(epubFile);

            long lines1 = contentAfterFirstSave.lines().count();
            long lines2 = contentAfterSecondSave.lines().count();

            assertTrue(Math.abs(lines2 - lines1) <= 2, "Line count should be stable");
            assertTrue(!contentAfterSecondSave.contains("\n\n"), "Should not contain double newlines");
        }
    }

    @Nested
    @DisplayName("EPUB3 Creator Metadata Tests")
    class Epub3CreatorTests {

        @Test
        @DisplayName("Should use meta refines for file-as in EPUB3")
        void epub3_shouldUseMetaRefines_forFileAs() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Test Book</dc:title>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-epub3-creator-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            Document doc = parseOpf(epubFile);
            NodeList creators = doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "creator");
            assertThat(creators.getLength()).isGreaterThan(0);

            Element creator = (Element) creators.item(0);
            assertThat(creator.getTextContent()).isEqualTo("Test Author");
            assertThat(creator.getAttribute("id")).isNotEmpty();

            // Should NOT have opf:file-as or opf:role attributes
            String fileAsAttr = creator.getAttributeNS("http://www.idpf.org/2007/opf", "file-as");
            String roleAttr = creator.getAttributeNS("http://www.idpf.org/2007/opf", "role");
            assertThat(fileAsAttr).isEmpty();
            assertThat(roleAttr).isEmpty();

            // Should have meta refines elements instead
            String creatorId = creator.getAttribute("id");
            String opfContent2 = readOpfContent(epubFile);
            assertThat(opfContent2)
                    .contains("refines=\"#" + creatorId + "\"")
                    .contains("property=\"file-as\"")
                    .contains("property=\"role\"");
        }

        @Test
        @DisplayName("Should include role with marc:relators scheme in EPUB3")
        void epub3_shouldIncludeRoleScheme() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Test Book</dc:title>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-epub3-role-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("scheme=\"marc:relators\"")
                    .contains(">aut<");
        }
    }

    @Nested
    @DisplayName("EPUB2 Creator Metadata Tests")
    class Epub2CreatorTests {

        @Test
        @DisplayName("Should use opf:file-as attribute on dc:creator in EPUB2")
        void epub2_shouldUseOpfAttributes() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Test Book</dc:title>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-epub2-creator-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("opf:file-as=")
                    .contains("opf:role=")
                    // Should NOT have meta refines for creators
                    .doesNotContain("property=\"file-as\"")
                    .doesNotContain("property=\"role\"");
        }

        @Test
        @DisplayName("Should preserve EPUB2 structure without EPUB3 constructs")
        void epub2_shouldNotContainEpub3Constructs() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>EPUB2 Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setSubtitle("A Subtitle");
            metadata.setSeriesName("Test Series");
            metadata.setSeriesNumber(3.0f);
            metadata.setPageCount(200);

            File epubFile = createEpubWithOpf(opfContent, "test-epub2-no-epub3-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    // EPUB2 should not have prefix attribute on package
                    .doesNotContain("prefix=")
                    // EPUB2 should not have property= metas for series
                    .doesNotContain("property=\"belongs-to-collection\"")
                    .doesNotContain("property=\"collection-type\"")
                    .doesNotContain("property=\"group-position\"")
                    // EPUB2 should not have property= metas for subtitles
                    .doesNotContain("property=\"title-type\"");
        }
    }

    @Nested
    @DisplayName("Mimetype ZIP Entry Tests")
    class MimetypeZipTests {

        @Test
        @DisplayName("Should store mimetype as first uncompressed entry in ZIP")
        void saveMetadata_shouldStoreMimetypeFirst() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Original Title</dc:title>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-mimetype-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            try (ZipFile zf = new ZipFile(epubFile)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                assertThat(entries.hasMoreElements()).isTrue();

                ZipEntry firstEntry = entries.nextElement();
                assertThat(firstEntry.getName()).isEqualTo("mimetype");
                assertThat(firstEntry.getMethod()).isEqualTo(ZipEntry.STORED);

                // Verify mimetype content
                try (InputStream is = zf.getInputStream(firstEntry)) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(content).isEqualTo("application/epub+zip");
                }
            }
        }

        @Test
        @DisplayName("Should not duplicate mimetype entry in ZIP")
        void saveMetadata_shouldNotDuplicateMimetype() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Original Title</dc:title>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-no-dup-mimetype-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            try (ZipFile zf = new ZipFile(epubFile)) {
                int mimetypeCount = 0;
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    if ("mimetype".equals(entries.nextElement().getName())) {
                        mimetypeCount++;
                    }
                }
                assertThat(mimetypeCount).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("EPUB3 Series Metadata Tests")
    class Epub3SeriesTests {

        @Test
        @DisplayName("Should use belongs-to-collection for series in EPUB3")
        void epub3_shouldUseBelongsToCollection() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Series Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setSeriesName("The Dark Tower");
            metadata.setSeriesNumber(3.0f);

            File epubFile = createEpubWithOpf(opfContent, "test-epub3-series-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("property=\"belongs-to-collection\"")
                    .contains("The Dark Tower")
                    .contains("property=\"collection-type\"")
                    .contains("series")
                    .contains("property=\"group-position\"")
                    .contains(">3<");
        }
    }

    @Nested
    @DisplayName("EPUB2 Series Metadata Tests")
    class Epub2SeriesTests {

        @Test
        @DisplayName("Should use calibre:series convention for series in EPUB2")
        void epub2_shouldUseCalibreSeries() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Series Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setSeriesName("Wheel of Time");
            metadata.setSeriesNumber(5.0f);

            File epubFile = createEpubWithOpf(opfContent, "test-epub2-series-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("name=\"calibre:series\"")
                    .contains("content=\"Wheel of Time\"")
                    .contains("name=\"calibre:series_index\"")
                    .contains("content=\"5\"")
                    // Should NOT contain EPUB3 series constructs
                    .doesNotContain(BELONGS_TO_COLLECTION)
                    .doesNotContain("collection-type")
                    .doesNotContain("group-position");
        }
    }

    @Nested
    @DisplayName("EPUB3 Subtitle Tests")
    class Epub3SubtitleTests {

        @Test
        @DisplayName("Should add subtitle as separate dc:title with title-type refinement in EPUB3")
        void epub3_shouldAddSubtitleWithRefinement() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Main Title</dc:title>
                        </metadata>
                    </package>""";

            metadata.setTitle("Main Title");
            metadata.setSubtitle("A Great Subtitle");

            File epubFile = createEpubWithOpf(opfContent, "test-epub3-subtitle-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("A Great Subtitle")
                    .contains("property=\"title-type\"")
                    .contains(">subtitle<");
        }
    }

    @Nested
    @DisplayName("EPUB2 Subtitle Tests")
    class Epub2SubtitleTests {

        @Test
        @DisplayName("Should store subtitle via booklore:subtitle metadata in EPUB2 without modifying dc:title")
        void epub2_shouldStoreSubtitleInBookloreMeta() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Main Title</dc:title>
                        </metadata>
                    </package>""";

            metadata.setTitle("Main Title");
            metadata.setSubtitle("A Great Subtitle");

            File epubFile = createEpubWithOpf(opfContent, "test-epub2-subtitle-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    // Title should remain unchanged (not appended with subtitle)
                    .contains(">Main Title<")
                    .doesNotContain("Main Title: A Great Subtitle")
                    // Subtitle should be stored via booklore:subtitle metadata
                    .contains("name=\"booklore:subtitle\"")
                    .contains("content=\"A Great Subtitle\"")
                    // Should NOT have EPUB3 title-type refinement
                    .doesNotContain("property=\"title-type\"");
        }
    }

    @Nested
    @DisplayName("EPUB3 Booklore Metadata Tests")
    class Epub3BookloreMetadataTests {

        @Test
        @DisplayName("Should use property attribute for booklore metadata in EPUB3")
        void epub3_shouldUsePropertyAttribute() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Test Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setPageCount(350);

            File epubFile = createEpubWithOpf(opfContent, "test-epub3-booklore-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("property=\"booklore:page_count\"")
                    .contains(">350<")
                    .contains("prefix=")
                    .contains("booklore:");
        }
    }

    @Nested
    @DisplayName("EPUB2 Booklore Metadata Tests")
    class Epub2BookloreMetadataTests {

        @Test
        @DisplayName("Should use name/content attributes for booklore metadata in EPUB2")
        void epub2_shouldUseNameContentAttributes() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Test Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setPageCount(350);

            File epubFile = createEpubWithOpf(opfContent, "test-epub2-booklore-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("name=\"booklore:page_count\"")
                    .contains("content=\"350\"")
                    // Should NOT have EPUB3 prefix attribute
                    .doesNotContain("prefix=")
                    // Should NOT use property= form
                    .doesNotContain("property=\"booklore:");
        }
    }

    @Nested
    @DisplayName("Descriptive Metadata Tests")
    class DescriptiveMetadataTests {

        @Test
        @DisplayName("Should write description, publisher, language, and published date")
        void writesDescriptivePublisherLanguageDate() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setDescription("A great story.");
            metadata.setPublisher("Acme Publishing");
            metadata.setLanguage("fr");
            metadata.setPublishedDate(LocalDate.of(2021, Month.MAY, 1));

            File epubFile = createEpubWithOpf(opfContent, "test-descriptive-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("<dc:description>A great story.</dc:description>")
                    .contains("<dc:publisher>Acme Publishing</dc:publisher>")
                    .contains("<dc:language>fr</dc:language>")
                    .contains("<dc:date>2021-05-01</dc:date>");
        }
    }

    @Nested
    @DisplayName("Categories Tests")
    class CategoriesTests {

        @Test
        @DisplayName("Should replace existing dc:subject elements with new categories")
        void replacesCategories() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                            <dc:subject>OldCategory</dc:subject>
                        </metadata>
                    </package>""";

            CategoryEntity fiction = new CategoryEntity();
            fiction.setId(1L);
            fiction.setName("Fiction");
            CategoryEntity fantasy = new CategoryEntity();
            fantasy.setId(2L);
            fantasy.setName("Fantasy");
            metadata.setCategories(Set.of(fiction, fantasy));

            File epubFile = createEpubWithOpf(opfContent, "test-categories-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .doesNotContain("OldCategory")
                    .contains("<dc:subject>Fiction</dc:subject>")
                    .contains("<dc:subject>Fantasy</dc:subject>");
        }
    }

    @Nested
    @DisplayName("Identifier Metadata Tests")
    class IdentifierMetadataTests {

        @Test
        @DisplayName("Should write all provider identifiers as urn-scheme dc:identifier elements")
        void writesAllProviderIdentifiers() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setIsbn13("9780134685991");
            metadata.setIsbn10("0135957059");
            metadata.setAsin("B09XXXAMZN");
            metadata.setGoodreadsId("99999");
            metadata.setGoogleId("goo123");
            metadata.setComicvineId("cv456");
            metadata.setHardcoverId("hc789");
            metadata.setHardcoverBookId("hcb1");
            metadata.setLubimyczytacId("lu1");
            metadata.setRanobedbId("rn1");

            File epubFile = createEpubWithOpf(opfContent, "test-identifiers-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("urn:isbn:9780134685991")
                    .contains("urn:isbn:0135957059")
                    .contains("urn:amazon:B09XXXAMZN")
                    .contains("urn:goodreads:99999")
                    .contains("urn:google:goo123")
                    .contains("urn:comicvine:cv456")
                    .contains("urn:hardcover:hc789")
                    .contains("urn:hardcoverbook:hcb1")
                    .contains("urn:lubimyczytac:lu1")
                    .contains("urn:ranobedb:rn1");
        }

        @Test
        @DisplayName("Clearing isbn13 should remove the identifier without re-adding it")
        void clearingIsbn13RemovesIdentifier() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                            <dc:identifier>urn:isbn:9780134685991</dc:identifier>
                        </metadata>
                    </package>""";

            MetadataClearFlags clear = new MetadataClearFlags();
            clear.setIsbn13(true);

            File epubFile = createEpubWithOpf(opfContent, "test-clear-isbn-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, clear);

            String content = readOpfContent(epubFile);
            assertThat(content).doesNotContain("urn:isbn:9780134685991");
        }
    }

    @Nested
    @DisplayName("Clear Flags Tests")
    class ClearFlagsTests {

        @Test
        @DisplayName("Should remove title, description, publisher, language, date, categories and authors when cleared")
        void clearingFieldsRemovesThem() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Old Title</dc:title>
                            <dc:description>Old Description</dc:description>
                            <dc:publisher>Old Publisher</dc:publisher>
                            <dc:language>en</dc:language>
                            <dc:date>2019-01-01</dc:date>
                            <dc:subject>OldCategory</dc:subject>
                            <dc:creator id="creator01">Old Author</dc:creator>
                        </metadata>
                    </package>""";

            MetadataClearFlags clear = new MetadataClearFlags();
            clear.setTitle(true);
            clear.setDescription(true);
            clear.setPublisher(true);
            clear.setLanguage(true);
            clear.setPublishedDate(true);
            clear.setCategories(true);
            clear.setAuthors(true);

            BookMetadataEntity emptyMeta = new BookMetadataEntity();

            File epubFile = createEpubWithOpf(opfContent, "test-clearall-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, emptyMeta, null, clear);

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .doesNotContain("Old Title")
                    .doesNotContain("Old Description")
                    .doesNotContain("Old Publisher")
                    .doesNotContain("<dc:language>en</dc:language>")
                    .doesNotContain("2019-01-01")
                    .doesNotContain("OldCategory")
                    .doesNotContain("Old Author");
        }
    }

    @Nested
    @DisplayName("Booklore Ratings And Collections Tests")
    class BookloreRatingsAndCollectionsTests {

        @Test
        @DisplayName("Should write ratings, review counts, moods, tags, age rating, content rating and series total")
        void writesFullBookloreMetadataSet() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setAmazonRating(4.5);
            metadata.setAmazonReviewCount(1200);
            metadata.setGoodreadsRating(4.2);
            metadata.setGoodreadsReviewCount(5000);
            metadata.setHardcoverRating(4.0);
            metadata.setHardcoverReviewCount(300);
            metadata.setLubimyczytacRating(3.8);
            metadata.setRanobedbRating(4.1);
            metadata.setAgeRating(16);
            metadata.setContentRating("teen");
            metadata.setSeriesTotal(9);

            MoodEntity mood = new MoodEntity();
            mood.setId(1L);
            mood.setName("dark");
            metadata.setMoods(Set.of(mood));

            TagEntity tag = new TagEntity();
            tag.setId(1L);
            tag.setName("classic");
            metadata.setTags(Set.of(tag));

            File epubFile = createEpubWithOpf(opfContent, "test-booklore-full-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("property=\"booklore:amazon_rating\"").contains(">4.5<")
                    .contains("property=\"booklore:amazon_review_count\"").contains(">1200<")
                    .contains("property=\"booklore:goodreads_rating\"").contains(">4.2<")
                    .contains("property=\"booklore:goodreads_review_count\"").contains(">5000<")
                    .contains("property=\"booklore:hardcover_rating\"").contains(">4.0<")
                    .contains("property=\"booklore:hardcover_review_count\"").contains(">300<")
                    .contains("property=\"booklore:lubimyczytac_rating\"").contains(">3.8<")
                    .contains("property=\"booklore:ranobedb_rating\"").contains(">4.1<")
                    .contains("property=\"booklore:age_rating\"").contains(">16<")
                    .contains("property=\"booklore:content_rating\"").contains("teen")
                    .contains("property=\"booklore:series_total\"").contains(">9<")
                    .contains("property=\"booklore:moods\"").contains("dark")
                    .contains("property=\"booklore:tags\"").contains("classic");
        }
    }

    @Nested
    @DisplayName("No Changes Skips Write Tests")
    class NoChangesSkipsWriteTests {

        @Test
        @DisplayName("Should leave the EPUB untouched when nothing in metadata differs")
        void noOpMetadataSkipsRewrite() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Untouched</dc:title>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-noop-" + System.nanoTime() + ".epub");
            byte[] before = Files.readAllBytes(epubFile.toPath());

            // authors/categories default to empty (not null) collections, and the writer's
            // author/category callbacks unconditionally mark a change whenever they run at all
            // (see EpubMetadataWriter#applyAuthors / #applyCategories) - null them out explicitly
            // so this exercises the genuine "nothing to write" path rather than always rewriting.
            BookMetadataEntity emptyMeta = new BookMetadataEntity();
            emptyMeta.setAuthors(null);
            emptyMeta.setCategories(null);
            writer.saveMetadataToFile(epubFile, emptyMeta, null, new MetadataClearFlags());

            byte[] after = Files.readAllBytes(epubFile.toPath());
            assertThat(after).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Calibre Artifact Cleanup Tests")
    class CalibreCleanupTests {

        @Test
        @DisplayName("Should strip calibre prefix, xmlns, identifiers, contributors and metas on write")
        void cleansUpCalibreArtifacts() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" prefix="calibre: https://calibre-ebook.com/ rendition: http://www.idpf.org/vocab/rendition/#">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:calibre="http://calibre.kovidgoyal.net/2009/metadata">
                            <dc:title>Old Title</dc:title>
                            <dc:identifier>calibre:abc-123</dc:identifier>
                            <dc:identifier>urn:calibre:xyz</dc:identifier>
                            <dc:contributor id="calcontrib">calibre (5.10.1) [http://calibre-ebook.com]</dc:contributor>
                            <meta property="calibre:timestamp">2020-01-01T00:00:00Z</meta>
                        </metadata>
                    </package>""";

            metadata.setTitle("New Title");

            File epubFile = createEpubWithOpf(opfContent, "test-calibre-cleanup-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .doesNotContain("xmlns:calibre")
                    .doesNotContain("calibre:abc-123")
                    .doesNotContain("urn:calibre:xyz")
                    .doesNotContain("calibre (5.10.1)")
                    .doesNotContain("calibre:timestamp")
                    .doesNotContain("calibre:")
                    .contains("rendition:");
        }
    }

    @Nested
    @DisplayName("Cover Replacement findOpfPath Failure Tests")
    class CoverReplacementFindOpfPathFailureTests {

        private static final String OPF_WITH_COVER_ITEM = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Book</dc:title>
                    </metadata>
                    <manifest>
                        <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    </manifest>
                </package>""";

        @Test
        @DisplayName("Should not throw when container.xml is entirely missing during cover replacement")
        void missingContainerXmlDoesNotThrow() throws Exception {
            File epubFile = createEpubWithoutContainerXml(OPF_WITH_COVER_ITEM, "no-container-cover-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        @Test
        @DisplayName("Should not throw when container.xml is malformed XML during cover replacement")
        void malformedContainerXmlDoesNotThrow() throws Exception {
            String badContainerXml = "<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"";
            File epubFile = createEpubWithCustomContainer(badContainerXml, OPF_WITH_COVER_ITEM, "bad-container-cover-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        @Test
        @DisplayName("Should not throw when container.xml has no rootfile during cover replacement")
        void noRootfileDoesNotThrow() throws Exception {
            String containerXmlNoRootfile = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles/>
                    </container>""";
            File epubFile = createEpubWithCustomContainer(containerXmlNoRootfile, OPF_WITH_COVER_ITEM, "no-rootfile-cover-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        @Test
        @DisplayName("Should not throw when container.xml's rootfile has a blank full-path during cover replacement")
        void blankFullPathDoesNotThrow() throws Exception {
            String containerXmlBlankFullPath = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>""";
            File epubFile = createEpubWithCustomContainer(containerXmlBlankFullPath, OPF_WITH_COVER_ITEM, "blank-fullpath-cover-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        private File createEpubWithoutContainerXml(String opfContent, String filename) throws IOException {
            File epubFile = tempDir.resolve(filename).toFile();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
                zos.putNextEntry(new ZipEntry("mimetype"));
                zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
                zos.write(opfContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("OEBPS/cover.jpg"));
                zos.write(new byte[]{1, 2, 3});
                zos.closeEntry();
            }
            return epubFile;
        }

        private File createEpubWithCustomContainer(String containerXml, String opfContent, String filename) throws IOException {
            File epubFile = tempDir.resolve(filename).toFile();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
                zos.putNextEntry(new ZipEntry("mimetype"));
                zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
                zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
                zos.write(opfContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("OEBPS/cover.jpg"));
                zos.write(new byte[]{1, 2, 3});
                zos.closeEntry();
            }
            return epubFile;
        }

        private BookEntity bookEntity(File epubFile) {
            BookEntity entity = new BookEntity();
            LibraryPathEntity libraryPath = new LibraryPathEntity();
            libraryPath.setPath(epubFile.getParentFile().toString());
            entity.setLibraryPath(libraryPath);
            BookFileEntity primaryFile = new BookFileEntity();
            primaryFile.setBook(entity);
            entity.setBookFiles(Collections.singletonList(primaryFile));
            entity.getPrimaryBookFile().setFileSubPath("");
            entity.getPrimaryBookFile().setFileName(epubFile.getName());
            return entity;
        }
    }

    @Nested
    @DisplayName("OPF At ZIP Root Tests")
    class OpfAtZipRootTests {

        @Test
        @DisplayName("Should locate and write the OPF when it sits directly at the extracted ZIP root")
        void findsOpfDirectlyAtRootWithoutRecursion() throws Exception {
            File epubFile = tempDir.resolve("opf-at-root-" + System.nanoTime() + ".epub").toFile();
            String containerXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>
                    """;
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Root Level Book</dc:title>
                        </metadata>
                    </package>""";

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
                zos.putNextEntry(new ZipEntry("mimetype"));
                zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
                zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("content.opf"));
                zos.write(opfContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            try (ZipFile zf = new ZipFile(epubFile)) {
                ZipEntry ze = zf.getEntry("content.opf");
                try (InputStream is = zf.getInputStream(ze)) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(content).contains(metadata.getTitle());
                }
            }
        }
    }

    @Nested
    @DisplayName("Cleanup Calibre Prefix Removal Tests")
    class CleanupCalibrePrefixRemovalTests {

        @Test
        @DisplayName("Should remove the prefix attribute entirely when it contains only calibre entries (EPUB2)")
        void pureCalibrePrefixIsRemovedEntirely() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" prefix="calibre: https://calibre-ebook.com/">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Old Title</dc:title>
                        </metadata>
                    </package>""";

            metadata.setTitle("New Title");

            File epubFile = createEpubWithOpf(opfContent, "test-pure-calibre-prefix-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content).doesNotContain("prefix=");
        }
    }

    @Nested
    @DisplayName("Creator Removal Edge Case Tests")
    class CreatorRemovalEdgeCaseTests {

        @Test
        @DisplayName("Should remove a pre-existing creator that has no id attribute at all")
        void removesCreatorWithoutIdAttribute() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Book</dc:title>
                            <dc:creator>Anonymous Old Author</dc:creator>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-creator-no-id-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .doesNotContain("Anonymous Old Author")
                    .contains("Test Author");
        }

        @Test
        @DisplayName("Should remove a pre-existing creator whose role is set directly via an opf:role attribute")
        void removesCreatorWithDirectOpfRoleAttribute() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Book</dc:title>
                            <dc:creator id="c1" opf:role="aut">Direct Role Author</dc:creator>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-creator-direct-role-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .doesNotContain("Direct Role Author")
                    .contains("Test Author");
        }

        @Test
        @DisplayName("Should resolve a creator's role from a refining meta that uses the content attribute form")
        void removesCreatorWithRoleFromContentAttributeMeta() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                            <dc:creator id="c1">Content Attr Author</dc:creator>
                            <meta property="role" refines="#c1" content="aut"/>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-creator-content-attr-role-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .doesNotContain("Content Attr Author")
                    .contains("Test Author");
        }
    }

    @Nested
    @DisplayName("shouldSaveMetadataToFile Tests")
    class ShouldSaveMetadataToFileTests {

        private File smallFile;

        @BeforeEach
        void createSmallFile() throws IOException {
            smallFile = tempDir.resolve("small-" + System.nanoTime() + ".epub").toFile();
            Files.write(smallFile.toPath(), new byte[]{1, 2, 3});
        }

        @Test
        @DisplayName("Should return false when EPUB writing is disabled")
        void disabledSettingReturnsFalse() {
            configureEpubSettings(false, 100);
            assertThat(writer.shouldSaveMetadataToFile(smallFile)).isFalse();
        }

        @Test
        @DisplayName("Should return false when the file exceeds the configured max size")
        void oversizedFileReturnsFalse() {
            configureEpubSettings(true, -1);
            assertThat(writer.shouldSaveMetadataToFile(smallFile)).isFalse();
        }

        @Test
        @DisplayName("Should return true when enabled and within size limits")
        void enabledWithinLimitsReturnsTrue() {
            configureEpubSettings(true, 100);
            assertThat(writer.shouldSaveMetadataToFile(smallFile)).isTrue();
        }

        private void configureEpubSettings(boolean enabled, int maxFileSizeInMb) {
            MetadataPersistenceSettings.FormatSettings epubFormatSettings = MetadataPersistenceSettings.FormatSettings.builder()
                    .enabled(enabled)
                    .maxFileSizeInMb(maxFileSizeInMb)
                    .build();
            MetadataPersistenceSettings.SaveToOriginalFile saveToOriginalFile = MetadataPersistenceSettings.SaveToOriginalFile.builder()
                    .epub(epubFormatSettings)
                    .build();
            MetadataPersistenceSettings metadataPersistenceSettings = new MetadataPersistenceSettings();
            metadataPersistenceSettings.setSaveToOriginalFile(saveToOriginalFile);

            AppSettings appSettings = mock(AppSettings.class);
            when(appSettings.getMetadataPersistenceSettings()).thenReturn(metadataPersistenceSettings);
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
        }
    }

    @Nested
    @DisplayName("Missing OPF File Tests")
    class MissingOpfFileTests {

        @Test
        @DisplayName("Should leave the EPUB untouched when no OPF file can be located")
        void missingOpfLeavesFileUnchanged() throws Exception {
            File epubFile = tempDir.resolve("no-opf-" + System.nanoTime() + ".epub").toFile();
            String containerXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>
                    """;
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
                zos.putNextEntry(new ZipEntry("mimetype"));
                zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
                zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            byte[] before = Files.readAllBytes(epubFile.toPath());
            assertDoesNotThrow(() -> writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags()));
            byte[] after = Files.readAllBytes(epubFile.toPath());

            assertThat(after).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Cover Replacement Guard Clause Tests")
    class CoverGuardClauseTests {

        @Test
        @DisplayName("replaceCoverImageFromBytes with null bytes should not throw")
        void nullBytesDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity, null));
        }

        @Test
        @DisplayName("replaceCoverImageFromBytes with empty bytes should not throw")
        void emptyBytesDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity, new byte[0]));
        }

        @Test
        @DisplayName("replaceCoverImageFromUpload with null file should not throw")
        void nullUploadDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUpload(bookEntity, null));
        }

        @Test
        @DisplayName("replaceCoverImageFromUpload with empty file should not throw")
        void emptyUploadDoesNotThrow() {
            MultipartFile empty = new MockMultipartFile("cover.png", "cover.png", "image/png", new byte[0]);
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUpload(bookEntity, empty));
        }

        @Test
        @DisplayName("replaceCoverImageFromUrl with null url should not throw")
        void nullUrlDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUrl(bookEntity, null));
        }

        @Test
        @DisplayName("replaceCoverImageFromUrl with blank url should not throw")
        void blankUrlDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUrl(bookEntity, "   "));
        }

        @Test
        @DisplayName("replaceCoverImageFromUrl should not throw when the image fails to load")
        void loadFailureDoesNotThrow() throws IOException {
            when(fileService.downloadImageFromUrl(anyString())).thenThrow(new IOException("boom"));
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUrl(bookEntity, "https://example.com/cover.jpg"));
        }
    }

    @Nested
    @DisplayName("Cover Replacement Success Tests")
    class CoverReplacementSuccessTests {

        @Test
        @DisplayName("Should replace the cover image referenced via properties=cover-image")
        void replacesCoverViaBytesWhenManifestHasCoverImageProperty() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithManifestAndCover(opf, "cover.jpg", new byte[]{1, 2, 3}, "cover-props-" + System.nanoTime() + ".epub");

            writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage());

            byte[] updated = readZipEntry(epubFile, "OEBPS/cover.jpg");
            assertThat(updated).isNotEqualTo(new byte[]{1, 2, 3}).hasSizeGreaterThan(0);
        }

        @Test
        @DisplayName("Should locate the cover via the metadata name=cover reference (EPUB2 style)")
        void replacesCoverViaMetadataReference() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                            <meta name="cover" content="cover-img"/>
                        </metadata>
                        <manifest>
                            <item id="cover-img" href="cover.jpg" media-type="image/jpeg"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithManifestAndCover(opf, "cover.jpg", new byte[]{4, 5, 6}, "cover-metaref-" + System.nanoTime() + ".epub");

            writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage());

            byte[] updated = readZipEntry(epubFile, "OEBPS/cover.jpg");
            assertThat(updated).isNotEqualTo(new byte[]{4, 5, 6});
        }

        @Test
        @DisplayName("Should locate the cover via common id fallback (EPUB2 legacy)")
        void replacesCoverViaCommonIdFallback() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="cover" href="cover.jpg" media-type="image/jpeg"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithManifestAndCover(opf, "cover.jpg", new byte[]{7, 8, 9}, "cover-commonid-" + System.nanoTime() + ".epub");

            writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage());

            byte[] updated = readZipEntry(epubFile, "OEBPS/cover.jpg");
            assertThat(updated).isNotEqualTo(new byte[]{7, 8, 9});
        }

        @Test
        @DisplayName("Should not throw when manifest has no element at all")
        void noManifestElementDoesNotThrow() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";
            File epubFile = createEpubWithOpf(opf, "cover-no-manifest-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        @Test
        @DisplayName("Should not throw when manifest has no recognizable cover item")
        void noCoverItemFoundDoesNotThrow() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="chapter1" href="chapter1.html" media-type="application/xhtml+xml"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithManifestAndCover(opf, "unused.jpg", new byte[]{1}, "cover-none-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        @Test
        @DisplayName("Should not throw when the cover item has a blank href attribute")
        void blankCoverHrefDoesNotThrow() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="cover-image" href="" media-type="image/jpeg" properties="cover-image"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithOpf(opf, "cover-blank-href-" + System.nanoTime() + ".epub");

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        @Test
        @DisplayName("Should not throw when no OPF file can be located for cover replacement")
        void missingOpfDoesNotThrowDuringCoverReplacement() throws Exception {
            File epubFile = tempDir.resolve("cover-no-opf-" + System.nanoTime() + ".epub").toFile();
            String containerXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>
                    """;
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
                zos.putNextEntry(new ZipEntry("mimetype"));
                zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
                zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(bookEntity(epubFile), createMinimalPngImage()));
        }

        @Test
        @DisplayName("replaceCoverImageFromUrl should replace the cover when the image loads successfully")
        void urlReplacesCoverWhenLoadSucceeds() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithManifestAndCover(opf, "cover.jpg", new byte[]{1, 1, 1}, "cover-url-success-" + System.nanoTime() + ".epub");

            when(fileService.downloadImageFromUrl(anyString())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));

            writer.replaceCoverImageFromUrl(bookEntity(epubFile), "https://example.com/cover.jpg");

            byte[] updated = readZipEntry(epubFile, "OEBPS/cover.jpg");
            assertThat(updated).isNotEqualTo(new byte[]{1, 1, 1});
        }

        private BookEntity bookEntity(File epubFile) {
            BookEntity entity = new BookEntity();
            LibraryPathEntity libraryPath = new LibraryPathEntity();
            libraryPath.setPath(epubFile.getParentFile().toString());
            entity.setLibraryPath(libraryPath);
            BookFileEntity primaryFile = new BookFileEntity();
            primaryFile.setBook(entity);
            entity.setBookFiles(Collections.singletonList(primaryFile));
            entity.getPrimaryBookFile().setFileSubPath("");
            entity.getPrimaryBookFile().setFileName(epubFile.getName());
            return entity;
        }
    }

    @Nested
    @DisplayName("Thumbnail Cover On Save Tests")
    class ThumbnailCoverOnSaveTests {

        @Test
        @DisplayName("Should apply the thumbnail cover during saveMetadataToFile when it loads successfully")
        void appliesThumbnailCoverWhenLoadable() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithManifestAndCover(opf, "cover.jpg", new byte[]{9, 9, 9}, "thumb-ok-" + System.nanoTime() + ".epub");

            when(fileService.downloadImageFromUrl(anyString())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));

            writer.saveMetadataToFile(epubFile, metadata, "https://example.com/thumb.jpg", new MetadataClearFlags());

            byte[] updated = readZipEntry(epubFile, "OEBPS/cover.jpg");
            assertThat(updated).isNotEqualTo(new byte[]{9, 9, 9});
        }
    }

    @Nested
    @DisplayName("Metadata Element Ordering Tests")
    class MetadataElementOrderingTests {

        @Test
        @DisplayName("Should reorder metadata children into identifiers/titles/creators/.../booklore-metas bucket order")
        void reordersMetadataChildrenByBucket() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:subject>Fiction</dc:subject>
                            <meta property="rendition:layout">reflowable</meta>
                            <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                            <meta property="belongs-to-collection">Some Series</meta>
                            <dc:description>Desc</dc:description>
                            <dc:publisher>Pub</dc:publisher>
                            <dc:date>2020-01-01</dc:date>
                            <dc:language>en</dc:language>
                            <dc:contributor id="contrib1">An Editor</dc:contributor>
                            <dc:creator id="creator1">An Author</dc:creator>
                            <dc:title>A Title</dc:title>
                            <dc:identifier>urn:isbn:1112223334445</dc:identifier>
                            <dc:rights>All rights reserved</dc:rights>
                        </metadata>
                    </package>""";

            metadata.setPageCount(42);
            // Categories are always rewritten wholesale (old dc:subject elements are removed
            // regardless of whether the new set differs), so give it the same category text
            // the fixture already has to keep this test focused on ordering, not content.
            CategoryEntity fiction = new CategoryEntity();
            fiction.setId(1L);
            fiction.setName("Fiction");
            metadata.setCategories(Set.of(fiction));

            File epubFile = createEpubWithOpf(opfContent, "test-ordering-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);

            // dc:rights has no bucket in classifyDcElement and is dropped on reorder.
            assertThat(content).doesNotContain("All rights reserved");

            // metadata.title/authors (set in the outer setUp()) overwrite the placeholder
            // "A Title"/"An Author" text, so locate the values the writer actually produced.
            int idxIdentifier = content.indexOf("urn:isbn:1112223334445");
            int idxTitle = content.indexOf(metadata.getTitle());
            int idxCreator = content.indexOf("Test Author");
            int idxContributor = content.indexOf("An Editor");
            int idxLanguage = content.indexOf("<dc:language>en</dc:language>");
            int idxDate = content.indexOf("<dc:date>2020-01-01</dc:date>");
            int idxPublisher = content.indexOf("<dc:publisher>Pub</dc:publisher>");
            int idxDescription = content.indexOf("<dc:description>Desc</dc:description>");
            int idxSubject = content.indexOf("<dc:subject>Fiction</dc:subject>");
            int idxSeriesMeta = content.indexOf(BELONGS_TO_COLLECTION);
            int idxModified = content.indexOf("dcterms:modified");
            int idxOtherMeta = content.indexOf("rendition:layout");
            int idxBooklore = content.indexOf("booklore:page_count");

            assertThat(idxIdentifier).isPositive();
            assertThat(idxTitle).isGreaterThan(idxIdentifier);
            assertThat(idxCreator).isGreaterThan(idxTitle);
            assertThat(idxContributor).isGreaterThan(idxCreator);
            assertThat(idxLanguage).isGreaterThan(idxContributor);
            assertThat(idxDate).isGreaterThan(idxLanguage);
            assertThat(idxPublisher).isGreaterThan(idxDate);
            assertThat(idxDescription).isGreaterThan(idxPublisher);
            assertThat(idxSubject).isGreaterThan(idxDescription);
            assertThat(idxSeriesMeta).isGreaterThan(idxSubject);
            assertThat(idxModified).isGreaterThan(idxSeriesMeta);
            assertThat(idxOtherMeta).isGreaterThan(idxModified);
            assertThat(idxBooklore).isGreaterThan(idxOtherMeta);
        }
    }

    @Nested
    @DisplayName("Null Clear Flags Tests")
    class NullClearFlagsTests {

        @Test
        @DisplayName("Should treat a null MetadataClearFlags as no fields cleared")
        void nullClearFlagsTreatedAsNoClear() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";

            metadata.setDescription("A description.");

            File epubFile = createEpubWithOpf(opfContent, "test-null-clear-" + System.nanoTime() + ".epub");
            assertDoesNotThrow(() -> writer.saveMetadataToFile(epubFile, metadata, null, null));

            String content = readOpfContent(epubFile);
            assertThat(content).contains("<dc:description>A description.</dc:description>");
        }
    }

    @Nested
    @DisplayName("Clear Optional Identifiers And Series Tests")
    class ClearOptionalIdentifiersAndSeriesTests {

        @Test
        @DisplayName("Should remove every optional identifier and series metadata when their clear flags are set")
        void clearingAllOptionalIdentifiersAndSeriesRemovesThem() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                            <dc:identifier>urn:isbn:0135957059</dc:identifier>
                            <dc:identifier>urn:amazon:B09XXXAMZN</dc:identifier>
                            <dc:identifier>urn:goodreads:99999</dc:identifier>
                            <dc:identifier>urn:google:goo123</dc:identifier>
                            <dc:identifier>urn:comicvine:cv456</dc:identifier>
                            <dc:identifier>urn:hardcover:hc789</dc:identifier>
                            <dc:identifier>urn:hardcoverbook:hcb1</dc:identifier>
                            <dc:identifier>urn:lubimyczytac:lu1</dc:identifier>
                            <dc:identifier>urn:ranobedb:rn1</dc:identifier>
                            <meta property="belongs-to-collection">Old Series</meta>
                        </metadata>
                    </package>""";

            MetadataClearFlags clear = new MetadataClearFlags();
            clear.setIsbn13(true);
            clear.setIsbn10(true);
            clear.setAsin(true);
            clear.setGoodreadsId(true);
            clear.setGoogleId(true);
            clear.setComicvineId(true);
            clear.setHardcoverId(true);
            clear.setHardcoverBookId(true);
            clear.setLubimyczytacId(true);
            clear.setRanobedbId(true);
            clear.setSeriesName(true);
            clear.setSeriesNumber(true);

            BookMetadataEntity emptyMeta = new BookMetadataEntity();
            emptyMeta.setAuthors(null);
            emptyMeta.setCategories(null);

            File epubFile = createEpubWithOpf(opfContent, "test-clear-all-identifiers-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, emptyMeta, null, clear);

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .doesNotContain("urn:isbn:0135957059")
                    .doesNotContain("urn:amazon:B09XXXAMZN")
                    .doesNotContain("urn:goodreads:99999")
                    .doesNotContain("urn:google:goo123")
                    .doesNotContain("urn:comicvine:cv456")
                    .doesNotContain("urn:hardcover:hc789")
                    .doesNotContain("urn:hardcoverbook:hcb1")
                    .doesNotContain("urn:lubimyczytac:lu1")
                    .doesNotContain("urn:ranobedb:rn1")
                    .doesNotContain(BELONGS_TO_COLLECTION);
        }
    }

    @Nested
    @DisplayName("Single Word Author Name Tests")
    class SingleWordAuthorNameTests {

        @Test
        @DisplayName("Should handle a single-word author name without a space")
        void singleWordAuthorNameOmitsFirstName() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";

            AuthorEntity author = new AuthorEntity();
            author.setName("Cher");
            metadata.setAuthors(List.of(author));

            File epubFile = createEpubWithOpf(opfContent, "test-single-word-author-" + System.nanoTime() + ".epub");
            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains(">Cher<")
                    .contains("opf:file-as=\"Cher, \"");
        }
    }

    @Nested
    @DisplayName("Backup And Recovery Tests")
    class BackupAndRecoveryTests {

        @Test
        @DisplayName("Should abort without modifying the file when backup creation fails")
        void backupCreationFailureAbortsSave() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";
            File epubFile = createEpubWithOpf(opfContent, "test-backupfail-" + System.nanoTime() + ".epub");

            File backupBlocker = new File(epubFile.getParentFile(), epubFile.getName() + ".bak");
            assertTrue(backupBlocker.mkdir());
            Files.write(backupBlocker.toPath().resolve("occupied.txt"), new byte[]{1});
            byte[] before = Files.readAllBytes(epubFile.toPath());

            assertDoesNotThrow(() -> writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags()));

            byte[] after = Files.readAllBytes(epubFile.toPath());
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("Should restore from backup and clean it up when the OPF is malformed XML")
        void malformedOpfRestoresFromBackupAndCleansUp() throws Exception {
            String opfContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package><metadata><dc:title>Broken</metadata>";
            File epubFile = createEpubWithOpf(opfContent, "test-malformed-" + System.nanoTime() + ".epub");
            byte[] before = Files.readAllBytes(epubFile.toPath());

            assertDoesNotThrow(() -> writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags()));

            byte[] after = Files.readAllBytes(epubFile.toPath());
            assertThat(after).isEqualTo(before);
            assertThat(new File(epubFile.getParentFile(), epubFile.getName() + ".bak")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("Booklore Metadata Change Detection Tests")
    class BookloreMetadataChangeDetectionTests {

        @Test
        @DisplayName("Should not rewrite when pre-existing booklore metadata already matches expected values")
        void matchingExistingBookloreMetadataSkipsRewrite() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Untouched</dc:title>
                            <meta property="booklore:page_count">350</meta>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-booklore-match-" + System.nanoTime() + ".epub");
            byte[] before = Files.readAllBytes(epubFile.toPath());

            BookMetadataEntity sameMeta = new BookMetadataEntity();
            sameMeta.setTitle("Untouched");
            sameMeta.setPageCount(350);
            sameMeta.setAuthors(null);
            sameMeta.setCategories(null);

            writer.saveMetadataToFile(epubFile, sameMeta, null, new MetadataClearFlags());

            byte[] after = Files.readAllBytes(epubFile.toPath());
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("Should rewrite when pre-existing non-numeric booklore metadata differs from expected values")
        void differingExistingBookloreMetadataTriggersRewrite() throws Exception {
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Untouched</dc:title>
                            <meta property="booklore:content_rating">unrated</meta>
                        </metadata>
                    </package>""";

            File epubFile = createEpubWithOpf(opfContent, "test-booklore-diff-" + System.nanoTime() + ".epub");

            BookMetadataEntity sameTitleMeta = new BookMetadataEntity();
            sameTitleMeta.setTitle("Untouched");
            sameTitleMeta.setContentRating("teen");
            sameTitleMeta.setAuthors(null);
            sameTitleMeta.setCategories(null);

            writer.saveMetadataToFile(epubFile, sameTitleMeta, null, new MetadataClearFlags());

            String content = readOpfContent(epubFile);
            assertThat(content)
                    .contains("property=\"booklore:content_rating\"")
                    .contains(">teen<")
                    .doesNotContain("unrated");
        }
    }

    @Nested
    @DisplayName("Thumbnail Cover Load Failure Tests")
    class ThumbnailCoverLoadFailureTests {

        @Test
        @DisplayName("Should not apply a cover during saveMetadataToFile when the thumbnail fails to load")
        void thumbnailLoadFailureDoesNotApplyCover() throws Exception {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                        <manifest>
                            <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        </manifest>
                    </package>""";
            File epubFile = createEpubWithManifestAndCover(opf, "cover.jpg", new byte[]{9, 9, 9}, "thumb-fail-" + System.nanoTime() + ".epub");

            when(fileService.downloadImageFromUrl(anyString())).thenThrow(new IOException("boom"));

            assertDoesNotThrow(() -> writer.saveMetadataToFile(epubFile, metadata, "https://example.com/thumb.jpg", new MetadataClearFlags()));

            byte[] cover = readZipEntry(epubFile, "OEBPS/cover.jpg");
            assertThat(cover).isEqualTo(new byte[]{9, 9, 9});
        }
    }

    @Nested
    @DisplayName("Disabled Setting Skip Tests")
    class DisabledSettingSkipTests {

        @Test
        @DisplayName("saveMetadataToFile should skip writing when EPUB metadata writing is disabled")
        void saveMetadataToFileSkipsWhenDisabled() throws Exception {
            configureEpubWritingDisabled();
            String opfContent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Book</dc:title>
                        </metadata>
                    </package>""";
            metadata.setDescription("Should not be written.");
            File epubFile = createEpubWithOpf(opfContent, "test-save-disabled-" + System.nanoTime() + ".epub");
            byte[] before = Files.readAllBytes(epubFile.toPath());

            writer.saveMetadataToFile(epubFile, metadata, null, new MetadataClearFlags());

            assertThat(Files.readAllBytes(epubFile.toPath())).isEqualTo(before);
        }

        @Test
        @DisplayName("replaceCoverImageFromBytes should skip when EPUB metadata writing is disabled")
        void bytesSkipsWhenDisabled() throws IOException {
            configureEpubWritingDisabled();
            Path filePath = bookEntity.getFullFilePath();
            Files.write(filePath, new byte[]{1, 2, 3});
            byte[] before = Files.readAllBytes(filePath);

            writer.replaceCoverImageFromBytes(bookEntity, createMinimalPngImage());

            assertThat(Files.readAllBytes(filePath)).isEqualTo(before);
        }

        @Test
        @DisplayName("replaceCoverImageFromUpload should skip when EPUB metadata writing is disabled")
        void uploadSkipsWhenDisabled() throws IOException {
            configureEpubWritingDisabled();
            Path filePath = bookEntity.getFullFilePath();
            Files.write(filePath, new byte[]{1, 2, 3});
            byte[] before = Files.readAllBytes(filePath);
            MultipartFile coverFile = new MockMultipartFile("cover.png", "cover.png", "image/png", createMinimalPngImage());

            writer.replaceCoverImageFromUpload(bookEntity, coverFile);

            assertThat(Files.readAllBytes(filePath)).isEqualTo(before);
        }

        @Test
        @DisplayName("replaceCoverImageFromUrl should skip when EPUB metadata writing is disabled")
        void urlSkipsWhenDisabled() throws IOException {
            configureEpubWritingDisabled();
            Path filePath = bookEntity.getFullFilePath();
            Files.write(filePath, new byte[]{1, 2, 3});
            byte[] before = Files.readAllBytes(filePath);

            writer.replaceCoverImageFromUrl(bookEntity, "https://example.com/cover.jpg");

            assertThat(Files.readAllBytes(filePath)).isEqualTo(before);
        }
    }

    private void configureEpubWritingDisabled() {
        MetadataPersistenceSettings.FormatSettings epubFormatSettings = MetadataPersistenceSettings.FormatSettings.builder()
                .enabled(false)
                .maxFileSizeInMb(100)
                .build();
        MetadataPersistenceSettings.SaveToOriginalFile saveToOriginalFile = MetadataPersistenceSettings.SaveToOriginalFile.builder()
                .epub(epubFormatSettings)
                .build();
        MetadataPersistenceSettings metadataPersistenceSettings = new MetadataPersistenceSettings();
        metadataPersistenceSettings.setSaveToOriginalFile(saveToOriginalFile);

        AppSettings appSettings = mock(AppSettings.class);
        when(appSettings.getMetadataPersistenceSettings()).thenReturn(metadataPersistenceSettings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
    }

    private File createEpubWithManifestAndCover(String opfContent, String coverRelativeHref, byte[] coverBytes, String filename) throws IOException {
        File epubFile = tempDir.resolve(filename).toFile();

        String containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
                """;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opfContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/" + coverRelativeHref));
            zos.write(coverBytes);
            zos.closeEntry();
        }

        return epubFile;
    }

    private byte[] readZipEntry(File zipFile, String entryName) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile)) {
            ZipEntry entry = zf.getEntry(entryName);
            assertNotNull(entry, "Expected entry " + entryName + " to exist");
            try (InputStream is = zf.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    private Document parseOpf(File epubFile) throws Exception {
        try (ZipFile zf = new ZipFile(epubFile)) {
            ZipEntry ze = zf.getEntry("OEBPS/content.opf");
            try (InputStream is = zf.getInputStream(ze)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                return builder.parse(is);
            }
        }
    }

    private String readOpfContent(File epubFile) throws IOException {
        try (ZipFile zf = new ZipFile(epubFile)) {
            ZipEntry ze = zf.getEntry("OEBPS/content.opf");
            try (InputStream is = zf.getInputStream(ze)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private File createEpubWithOpf(String opfContent, String filename) throws IOException {
        File epubFile = tempDir.resolve(filename).toFile();

        String containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
                """;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opfContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        return epubFile;
    }

    private byte[] createEpubWithUnicodeCoverHref() throws IOException {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Test Book with Unicode Cover</dc:title>
                        <dc:creator>Test Author</dc:creator>
                        <meta name="cover" content="cover-image"/>
                    </metadata>
                    <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="cover-image" href="cover%C3%A1.png" media-type="image/png" properties="cover-image"/>
                        <item id="text" href="index.html" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="text"/>
                    </spine>
                </package>
                """;

        String containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
                """;

        String htmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <title>Test</title>
                </head>
                <body>
                    <h1>Test Content</h1>
                </body>
                </html>
                """;

        String ncxContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
                    "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                <ncx version="2005-1" xml:lang="en">
                    <head>
                        <meta name="dtb:uid" content="test-book"/>
                    </head>
                    <docTitle>
                        <text>Test Book</text>
                    </docTitle>
                    <navMap>
                        <navPoint id="navpoint-1" playOrder="1">
                            <navLabel>
                                <text>Test</text>
                            </navLabel>
                            <content src="index.html"/>
                        </navPoint>
                    </navMap>
                </ncx>
                """;

        byte[] coverImage = createMinimalPngImage();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opfContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/index.html"));
            zos.write(htmlContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(ncxContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            String decodedCoverPath = URLDecoder.decode("cover%C3%A1.png", StandardCharsets.UTF_8);
            zos.putNextEntry(new ZipEntry("OEBPS/" + decodedCoverPath));
            zos.write(coverImage);
            zos.closeEntry();
        }

        return baos.toByteArray();
    }

    private byte[] createMinimalPngImage() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D,
                0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x08, 0x06,
                0x00, 0x00, 0x00,
                (byte) 0x90, (byte) 0x77, (byte) 0x53, (byte) 0xDE,
                0x00, 0x00, 0x00, 0x0A,
                0x49, 0x44, 0x41, 0x54,
                0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
                0x00, 0x01,
                0x0D, (byte) 0x0A, 0x2D, (byte) 0xB4,
                0x00, 0x00, 0x00, 0x00,
                0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
    }
}
