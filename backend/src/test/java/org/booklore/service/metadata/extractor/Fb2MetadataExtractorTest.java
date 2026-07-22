package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fb2MetadataExtractorTest {

    private static final String NS = "http://www.gribuser.ru/xml/fictionbook/2.0";
    private static final String XLINK = "http://www.w3.org/1999/xlink";

    private Fb2MetadataExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        extractor = new Fb2MetadataExtractor();
    }

    private File writeFb2(String xmlBody) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="%s" xmlns:l="%s">
                %s
                </FictionBook>
                """.formatted(NS, XLINK, xmlBody);
        Path file = tempDir.resolve("test.fb2");
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file.toFile();
    }

    private File writeFb2Gz(String xmlBody) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="%s" xmlns:l="%s">
                %s
                </FictionBook>
                """.formatted(NS, XLINK, xmlBody);
        Path file = tempDir.resolve("test.fb2.gz");
        try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(file))) {
            gzos.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return file.toFile();
    }

    @Test
    void extractMetadata_titleAndAuthors() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <book-title>War and Peace</book-title>
                    <author>
                      <first-name>Leo</first-name>
                      <last-name>Tolstoy</last-name>
                    </author>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getTitle()).isEqualTo("War and Peace");
        assertThat(metadata.getAuthors()).containsExactly("Leo Tolstoy");
    }

    @Test
    void recoversBrokenTitleInfoFromBodyTitlePage() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <book-title>_201.DOCX</book-title>
                    <author><nickname>ebook.convertstandard.com</nickname></author>
                  </title-info>
                </description>
                <body><section>
                  <p>Лорел К. Гамильтон</p>
                  <p>«Страдание»</p>
                  <p>Оригинальное название:</p>
                  <p>Laurell K. Hamilton. «Affliction», 2013</p>
                </section></body>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getTitle()).isEqualTo("Страдание");
        assertThat(metadata.getAuthors()).containsExactly("Лорел К. Гамильтон");
        assertThat(metadata.getSubtitle()).isEqualTo("Laurell K. Hamilton. «Affliction», 2013");
    }

    @Test
    void extractMetadata_fromArchiveEntryStream() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <book-title>Streamed book</book-title>
                    <lang>ru</lang>
                  </title-info>
                </description>
                """);

        BookMetadata metadata;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            metadata = extractor.extractMetadata(input, "books.zip!42.fb2");
        }

        assertThat(metadata).isNotNull();
        assertThat(metadata.getTitle()).isEqualTo("Streamed book");
        assertThat(metadata.getLanguage()).isEqualTo("ru");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("singleListFieldCases")
    void extractsSingleListField(String scenario, String field, String titleInfoXml, List<String> expected) throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                %s
                  </title-info>
                </description>
                """.formatted(titleInfoXml));

        BookMetadata metadata = extractor.extractMetadata(file);

        Collection<String> actual = "authors".equals(field) ? metadata.getAuthors() : metadata.getCategories();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Stream<Arguments> singleListFieldCases() {
        return Stream.of(
                Arguments.of("authorWithMiddleName", "authors", """
                        <author>
                          <first-name>Edgar</first-name>
                          <middle-name>Allan</middle-name>
                          <last-name>Poe</last-name>
                        </author>
                        """, List.of("Edgar Allan Poe")),
                Arguments.of("authorNicknameFallback", "authors", """
                        <author>
                          <nickname>voltaire</nickname>
                        </author>
                        """, List.of("voltaire")),
                Arguments.of("nicknameIgnoredWhenNamePartsPresent", "authors", """
                        <author>
                          <first-name>John</first-name>
                          <last-name>Doe</last-name>
                          <nickname>jdoe</nickname>
                        </author>
                        """, List.of("John Doe")),
                Arguments.of("multipleAuthors", "authors", """
                        <author><first-name>Alpha</first-name><last-name>One</last-name></author>
                        <author><first-name>Beta</first-name><last-name>Two</last-name></author>
                        """, List.of("Alpha One", "Beta Two")),
                Arguments.of("genres", "categories", """
                        <genre>sf_fantasy</genre>
                        <genre>adventure</genre>
                        """, List.of("sf_fantasy", "adventure"))
        );
    }

    @Test
    void extractMetadata_keywordsCommaSeparated() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <keywords>magic, dragons; wizards</keywords>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getCategories()).containsExactlyInAnyOrder("magic", "dragons", "wizards");
    }

    @Test
    void extractMetadata_keywordsAndGenresMerged() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <genre>fantasy</genre>
                    <keywords>magic, elves</keywords>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getCategories()).containsExactlyInAnyOrder("fantasy", "magic", "elves");
    }

    @Test
    void extractMetadata_isoDateFromTitleInfo() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <date value="2005-03-15">March 2005</date>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2005, Month.MARCH, 15));
    }

    @Test
    void extractMetadata_dateValueAttributePreferredOverText() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <date value="2010-06-01">Some text 1999</date>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2010, Month.JUNE, 1));
    }

    @Test
    void extractMetadata_yearOnlyDateFromText() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <date>1999</date>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1999, Month.JANUARY, 1));
    }

    @Test
    void extractMetadata_blankDateReturnsNull() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <date value="">  </date>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getPublishedDate()).isNull();
    }

    @Test
    void extractMetadata_language() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <lang>ru</lang>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getLanguage()).isEqualTo("ru");
    }

    @Test
    void extractMetadata_seriesWithNumber() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <sequence name="Discworld" number="5"/>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getSeriesName()).isEqualTo("Discworld");
        assertThat(metadata.getSeriesNumber()).isEqualTo(5.0f);
    }

    @Test
    void extractMetadata_seriesWithoutNumber() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <sequence name="Discworld"/>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getSeriesName()).isEqualTo("Discworld");
        assertThat(metadata.getSeriesNumber()).isNull();
    }

    @Test
    void extractMetadata_seriesInvalidNumberIgnored() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <sequence name="Series" number="abc"/>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getSeriesName()).isEqualTo("Series");
        assertThat(metadata.getSeriesNumber()).isNull();
    }

    @Test
    void extractMetadata_annotation() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <annotation>
                      <p>A great book about things.</p>
                      <p>Second paragraph.</p>
                    </annotation>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getDescription()).contains("A great book about things.");
        assertThat(metadata.getDescription()).contains("Second paragraph.");
    }

    @Test
    void extractMetadata_publishInfo() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info/>
                  <publish-info>
                    <publisher>Penguin Books</publisher>
                    <year>2001</year>
                  </publish-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getPublisher()).isEqualTo("Penguin Books");
        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2001, Month.JANUARY, 1));
    }

    @Test
    void extractMetadata_isbn13() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info/>
                  <publish-info>
                    <isbn>978-0-06-112008-4</isbn>
                  </publish-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getIsbn13()).isEqualTo("9780061120084");
    }

    @Test
    void extractMetadata_isbn10() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info/>
                  <publish-info>
                    <isbn>0-06-112008-X</isbn>
                  </publish-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getIsbn10()).isEqualTo("006112008X");
    }

    @Test
    void extractMetadata_isbnPatternMatch() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info/>
                  <publish-info>
                    <isbn>ISBN: 0-451-52493-4, another</isbn>
                  </publish-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getIsbn10()).isEqualTo("0451524934");
    }

    @Test
    void extractMetadata_gzipCompressedFile() throws IOException {
        File file = writeFb2Gz("""
                <description>
                  <title-info>
                    <book-title>Compressed Book</book-title>
                    <author><first-name>Gzip</first-name><last-name>Author</last-name></author>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getTitle()).isEqualTo("Compressed Book");
        assertThat(metadata.getAuthors()).containsExactly("Gzip Author");
    }

    @Test
    void extractMetadata_emptyTitleInfo() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info/>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getTitle()).isNull();
        assertThat(metadata.getAuthors()).isEmpty();
    }

    @Test
    void extractMetadata_invalidXmlReturnsNull() throws IOException {
        Path file = tempDir.resolve("bad.fb2");
        Files.writeString(file, "this is not xml at all");

        BookMetadata metadata = extractor.extractMetadata(file.toFile());

        assertThat(metadata).isNull();
    }

    @Test
    void extractMetadata_stopsAfterDescriptionWithoutParsingEmbeddedBinaryPayload() throws IOException {
        Path file = tempDir.resolve("large.fb2");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="%s" xmlns:l="%s">
                <description>
                  <title-info>
                    <book-title>Metadata only</book-title>
                    <author><first-name>Streamed</first-name><last-name>Author</last-name></author>
                  </title-info>
                </description>
                <binary id="payload.jpg" content-type="image/jpeg">
                """.formatted(NS, XLINK), StandardCharsets.UTF_8);

        BookMetadata metadata = extractor.extractMetadata(file.toFile());

        assertThat(metadata).isNotNull();
        assertThat(metadata.getTitle()).isEqualTo("Metadata only");
        assertThat(metadata.getAuthors()).containsExactly("Streamed Author");
    }

    @Test
    void extractMetadata_fullDocument() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <genre>detective</genre>
                    <author><first-name>Arthur</first-name><middle-name>Conan</middle-name><last-name>Doyle</last-name></author>
                    <book-title>The Hound of the Baskervilles</book-title>
                    <annotation><p>A detective mystery.</p></annotation>
                    <keywords>mystery, detective</keywords>
                    <date value="1902-04-01"/>
                    <lang>en</lang>
                    <sequence name="Sherlock Holmes" number="5"/>
                  </title-info>
                  <publish-info>
                    <publisher>George Newnes</publisher>
                    <year>1902</year>
                    <isbn>978-0-14-043786-7</isbn>
                  </publish-info>
                  <document-info>
                    <id>abc-123</id>
                  </document-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getTitle()).isEqualTo("The Hound of the Baskervilles");
        assertThat(metadata.getAuthors()).containsExactly("Arthur Conan Doyle");
        assertThat(metadata.getCategories()).contains("detective", "mystery");
        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1902, Month.JANUARY, 1));
        assertThat(metadata.getLanguage()).isEqualTo("en");
        assertThat(metadata.getSeriesName()).isEqualTo("Sherlock Holmes");
        assertThat(metadata.getSeriesNumber()).isEqualTo(5.0f);
        assertThat(metadata.getPublisher()).isEqualTo("George Newnes");
        assertThat(metadata.getIsbn13()).isEqualTo("9780140437867");
    }

    @Test
    void extractCover_binaryWithCoverId() throws IOException {
        byte[] imageData = {(byte) 0x89, 0x50, 0x4E, 0x47};
        String base64 = Base64.getEncoder().encodeToString(imageData);
        File file = writeFb2("""
                <description><title-info/></description>
                <binary id="cover.jpg" content-type="image/jpeg">%s</binary>
                """.formatted(base64));

        byte[] cover = extractor.extractCover(file);

        assertThat(cover).isEqualTo(imageData);
    }

    @Test
    void extractCover_binaryWithLineWrappedBase64() throws IOException {
        File file = writeFb2("""
                <description><title-info/></description>
                <binary id="cover.jpg" content-type="image/jpeg">
                    SGVs
                    bG8=
                </binary>
                """);

        byte[] cover = extractor.extractCover(file);

        assertThat(cover).isEqualTo("Hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractCover_fallbackToCoverpageReference() throws IOException {
        byte[] imageData = {0x01, 0x02, 0x03, 0x04};
        String base64 = Base64.getEncoder().encodeToString(imageData);
        File file = writeFb2("""
                <description>
                  <title-info>
                    <coverpage>
                      <image l:href="#img1"/>
                    </coverpage>
                  </title-info>
                </description>
                <binary id="img1" content-type="image/png">%s</binary>
                """.formatted(base64));

        byte[] cover = extractor.extractCover(file);

        assertThat(cover).isEqualTo(imageData);
    }

    @Test
    void extractCover_decodesOnlyReferencedBinaryPayload() throws IOException {
        byte[] imageData = {0x01, 0x02, 0x03, 0x04};
        String base64 = Base64.getEncoder().encodeToString(imageData);
        File file = writeFb2("""
                <description>
                  <title-info>
                    <coverpage>
                      <image l:href="#img1"/>
                    </coverpage>
                  </title-info>
                </description>
                <binary id="payload.jpg" content-type="image/jpeg">%s</binary>
                <binary id="img1" content-type="image/png">%s</binary>
                """.formatted("!!!not@@base64###".repeat(10_000), base64));

        byte[] cover = extractor.extractCover(file);

        assertThat(cover).isEqualTo(imageData);
    }

    @Test
    void extractCover_noBinaryReturnsNull() throws IOException {
        File file = writeFb2("""
                <description><title-info/></description>
                """);

        byte[] cover = extractor.extractCover(file);

        assertThat(cover).isNull();
    }

    @Test
    void extractCover_nonImageBinarySkipped() throws IOException {
        String base64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        File file = writeFb2("""
                <description><title-info/></description>
                <binary id="cover.dat" content-type="application/octet-stream">%s</binary>
                """.formatted(base64));

        byte[] cover = extractor.extractCover(file);

        assertThat(cover).isNull();
    }

    @Test
    void extractCover_gzipCompressedFile() throws IOException {
        byte[] imageData = {0x10, 0x20, 0x30};
        String base64 = Base64.getEncoder().encodeToString(imageData);
        File file = writeFb2Gz("""
                <description><title-info/></description>
                <binary id="cover.png" content-type="image/png">%s</binary>
                """.formatted(base64));

        byte[] cover = extractor.extractCover(file);

        assertThat(cover).isEqualTo(imageData);
    }

    /**
     * The root of the defect four fix waves chased one floor too high, pinned at the layer that can
     * actually see it.
     * <p>
     * {@code extractCover} answered every one of these with the same bare {@code null} it uses for
     * "this FB2 has no cover". {@code Fb2Processor} mapped that {@code null} to
     * {@code NO_COVER_FOUND}, {@code BookCoverService.tryGenerateMissingInpxCover} took it for a
     * completed probe, and {@code cover_probed_at} was set <em>permanently</em> - so a file that was
     * merely unreadable for a moment had its cover written off until the next rescan. The processor
     * layer's {@code catch (Exception e) -> readFailed()} was already written and already correct,
     * and could never run, because nothing below it ever threw.
     * <p>
     * Every case here uses the real extractor against a real file on disk. That is the point: the
     * test that was supposed to cover this stubbed {@code extractCover} to throw, which the real
     * extractor could not do, so it passed while production did the opposite.
     */
    @Nested
    class ExtractCoverDistinguishesAFailedReadFromACleanMiss {

        @Test
        void malformedXmlThrowsRatherThanClaimingThereIsNoCover() throws IOException {
            Path file = tempDir.resolve("bad.fb2");
            Files.writeString(file, "not xml");
            File fb2File = file.toFile();

            assertThatThrownBy(() -> extractor.extractCover(fb2File))
                    .isInstanceOf(CoverExtractionException.class)
                    .hasMessageContaining("bad.fb2");
        }

        @Test
        void truncatedFb2ThrowsRatherThanClaimingThereIsNoCover() throws IOException {
            byte[] imageData = {0x10, 0x20, 0x30};
            String base64 = Base64.getEncoder().encodeToString(imageData);
            String full = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <FictionBook xmlns="%s" xmlns:l="%s">
                    <description><title-info/></description>
                    <binary id="cover.png" content-type="image/png">%s</binary>
                    </FictionBook>
                    """.formatted(NS, XLINK, base64);
            // Cut mid-document: well-formed right up to the point it stops, which is exactly the
            // shape a half-written or half-transferred file has.
            Path file = tempDir.resolve("truncated.fb2");
            Files.writeString(file, full.substring(0, full.length() / 2), StandardCharsets.UTF_8);
            File fb2File = file.toFile();

            assertThatThrownBy(() -> extractor.extractCover(fb2File))
                    .isInstanceOf(CoverExtractionException.class)
                    .hasMessageContaining("truncated.fb2");
        }

        @Test
        void corruptBase64InTheCoverBinaryThrowsRatherThanClaimingThereIsNoCover() throws IOException {
            // Parses fine; the failure is inside the cover binary, so this is the case that proves
            // the distinction is drawn on the cover read itself and not merely on the XML parse.
            File file = writeFb2("""
                    <description><title-info/></description>
                    <binary id="cover.png" content-type="image/png">!!!not@@base64###</binary>
                    """);

            assertThatThrownBy(() -> extractor.extractCover(file))
                    .isInstanceOf(CoverExtractionException.class);
        }

        @Test
        void missingFileThrowsRatherThanClaimingThereIsNoCover() {
            File missing = tempDir.resolve("gone.fb2").toFile();

            assertThatThrownBy(() -> extractor.extractCover(missing))
                    .isInstanceOf(CoverExtractionException.class)
                    .hasRootCauseInstanceOf(FileNotFoundException.class);
        }

        @Test
        void unreadableGzipThrowsRatherThanClaimingThereIsNoCover() throws IOException {
            // Named .gz, so getInputStream wraps it in a GZIPInputStream that will reject the bytes.
            Path file = tempDir.resolve("broken.fb2.gz");
            Files.write(file, new byte[]{0x00, 0x01, 0x02, 0x03});
            File fb2File = file.toFile();

            assertThatThrownBy(() -> extractor.extractCover(fb2File))
                    .isInstanceOf(CoverExtractionException.class);
        }

        /**
         * The other half of the distinction, and the reason the failures above cannot simply be
         * "throw on anything": a file that really has no cover must still say so, definitively,
         * because that answer is the one the probe marker is allowed to persist.
         */
        @Test
        void anFb2ThatGenuinelyHasNoCoverStillReturnsNull() throws IOException {
            File file = writeFb2("""
                    <description><title-info/></description>
                    """);

            assertThat(extractor.extractCover(file)).isNull();
        }
    }

    @Test
    void extractMetadata_blankGenreSkipped() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <genre>  </genre>
                    <genre>valid</genre>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getCategories()).containsExactly("valid");
    }

    @Test
    void extractMetadata_blankAuthorSkipped() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <author/>
                    <author><first-name>Valid</first-name></author>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getAuthors()).containsExactly("Valid");
    }

    @Test
    void extractMetadata_publishInfoYearOverridesTitleInfoDate() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <date value="2005-03-15"/>
                  </title-info>
                  <publish-info>
                    <year>2010</year>
                  </publish-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2010, Month.JANUARY, 1));
    }

    @Test
    void extractMetadata_authorFirstNameOnly() throws IOException {
        File file = writeFb2("""
                <description>
                  <title-info>
                    <author><first-name>Madonna</first-name></author>
                  </title-info>
                </description>
                """);

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata.getAuthors()).containsExactly("Madonna");
    }

    @Test
    void extractMetadata_noDescriptionReturnsEmptyMetadata() throws IOException {
        File file = writeFb2("<body/>");

        BookMetadata metadata = extractor.extractMetadata(file);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getTitle()).isNull();
        assertThat(metadata.getAuthors()).isEmpty();
        assertThat(metadata.getCategories()).isEmpty();
    }
}
