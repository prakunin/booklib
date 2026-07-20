package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link MobiBaseMetadataExtractor} through its concrete {@link MobiMetadataExtractor}
 * subclass, since the base class is abstract. Every fixture is a minimal, hand-assembled PalmDB /
 * MOBI / EXTH binary built in-process (there is no recorded sample file for this format in the
 * repository), matching the exact byte offsets {@code readPalmDB}/{@code readMobiHeader}/
 * {@code readExthRecords} read from.
 */
class MobiMetadataExtractorTest {

    private static final int MOBI_HEADER_LENGTH = 116;
    private static final int RECORD_LIST_START = 78;
    private static final String TITLE = "Title";

    private MobiMetadataExtractor extractor;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        extractor = new MobiMetadataExtractor();
    }

    private File writeFile(String name, byte[] content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, content);
        return path.toFile();
    }

    private record ExthEntry(int type, byte[] data) {
    }

    private static ExthEntry exthString(int type, String value) {
        return new ExthEntry(type, value.getBytes(StandardCharsets.UTF_8));
    }

    private static ExthEntry exthInt(int type, int value) {
        return new ExthEntry(type, new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        });
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeThreeBytes(ByteArrayOutputStream out, int value) {
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Builds record 0's payload: the 16-byte PalmDOC header, the MOBI header up to and including
     * {@code exthFlags} at relative offset 128 (ending exactly at {@link #MOBI_HEADER_LENGTH} so
     * the EXTH section starts right after), an optional EXTH section, and the title text.
     */
    private byte[] buildRecord0(String title, boolean includeMobiIdentifier, boolean includeExth,
                                 List<ExthEntry> exthEntries, int firstImageIndex) {
        Charset charset = StandardCharsets.UTF_8;
        byte[] titleBytes = title == null ? new byte[0] : title.getBytes(charset);

        ByteArrayOutputStream exthSection = new ByteArrayOutputStream();
        if (includeExth) {
            ByteArrayOutputStream recordsBuf = new ByteArrayOutputStream();
            for (ExthEntry entry : exthEntries) {
                writeInt(recordsBuf, entry.type());
                writeInt(recordsBuf, 8 + entry.data().length);
                recordsBuf.write(entry.data(), 0, entry.data().length);
            }
            byte[] recordsBytes = recordsBuf.toByteArray();
            int exthHeaderLength = 12 + recordsBytes.length;
            exthSection.write("EXTH".getBytes(StandardCharsets.US_ASCII), 0, 4);
            writeInt(exthSection, exthHeaderLength);
            writeInt(exthSection, exthEntries.size());
            exthSection.write(recordsBytes, 0, recordsBytes.length);
        }
        byte[] exthBytes = exthSection.toByteArray();

        int fullNameOffset = 16 + MOBI_HEADER_LENGTH + exthBytes.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // PalmDOC header (16 bytes)
        writeShort(out, 1);
        writeShort(out, 0);
        writeInt(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);

        out.write(includeMobiIdentifier ? "MOBI".getBytes(StandardCharsets.US_ASCII) : "XXXX".getBytes(StandardCharsets.US_ASCII), 0, 4);

        writeInt(out, MOBI_HEADER_LENGTH);
        writeInt(out, 2);
        writeInt(out, 65001);

        out.write(new byte[52], 0, 52);

        writeInt(out, fullNameOffset);
        writeInt(out, titleBytes.length);

        out.write(new byte[16], 0, 16);

        writeInt(out, firstImageIndex);

        out.write(new byte[16], 0, 16);

        writeInt(out, includeExth ? 0x40 : 0);

        out.write(exthBytes, 0, exthBytes.length);
        out.write(titleBytes, 0, titleBytes.length);

        return out.toByteArray();
    }

    private byte[] buildMobiFile(String title, boolean includeMobiIdentifier, boolean includeExth,
                                  List<ExthEntry> exthEntries, int firstImageIndex, List<byte[]> imageRecords) {
        byte[] record0 = buildRecord0(title, includeMobiIdentifier, includeExth, exthEntries, firstImageIndex);
        int numRecords = 1 + imageRecords.size();
        int record0Offset = RECORD_LIST_START + numRecords * 8;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[32], 0, 32); // database name
        out.write(new byte[44], 0, 44); // unused PalmDB header fields
        writeShort(out, numRecords);

        int offset = record0Offset;
        writeInt(out, offset);
        out.write(0);
        writeThreeBytes(out, 0);
        offset += record0.length;
        for (byte[] image : imageRecords) {
            writeInt(out, offset);
            out.write(0);
            writeThreeBytes(out, 0);
            offset += image.length;
        }

        out.write(record0, 0, record0.length);
        for (byte[] image : imageRecords) {
            out.write(image, 0, image.length);
        }

        return out.toByteArray();
    }

    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, 6};
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3, 4};
    private static final byte[] NOT_AN_IMAGE = "not an image".getBytes(StandardCharsets.US_ASCII);

    @Nested
    @DisplayName("extractMetadata")
    class ExtractMetadataTests {

        @Test
        @DisplayName("reads title and every recognised EXTH field")
        void readsAllRecognisedFields() throws IOException {
            List<ExthEntry> exth = List.of(
                    exthString(MobiBaseMetadataExtractor.EXTH_AUTHOR, "Jane Doe"),
                    exthString(MobiBaseMetadataExtractor.EXTH_PUBLISHER, "Test Press"),
                    exthString(MobiBaseMetadataExtractor.EXTH_DESCRIPTION, "A great book"),
                    exthString(MobiBaseMetadataExtractor.EXTH_ISBN, "978-0-31-644269-8"),
                    exthString(MobiBaseMetadataExtractor.EXTH_SUBJECT, "Fantasy; Adventure"),
                    exthString(MobiBaseMetadataExtractor.EXTH_PUBLISHED_DATE, "2020-05-15"),
                    exthString(MobiBaseMetadataExtractor.EXTH_LANGUAGE, "en"),
                    exthString(MobiBaseMetadataExtractor.EXTH_ASIN, "B01N123456"),
                    exthString(999, "some unhandled record")
            );
            File file = writeFile("full.mobi", buildMobiFile("My Light Novel", true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("My Light Novel");
            assertThat(metadata.getAuthors()).containsExactly("Jane Doe");
            assertThat(metadata.getPublisher()).isEqualTo("Test Press");
            assertThat(metadata.getDescription()).isEqualTo("A great book");
            assertThat(metadata.getIsbn13()).isEqualTo("9780316442698");
            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("Fantasy", "Adventure");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2020, Month.MAY, 15));
            assertThat(metadata.getLanguage()).isEqualTo("en");
            assertThat(metadata.getAsin()).isEqualTo("B01N123456");
        }

        @Test
        @DisplayName("a 10-digit ISBN is stored as isbn10 rather than isbn13")
        void tenDigitIsbn_storesAsIsbn10() throws IOException {
            List<ExthEntry> exth = List.of(exthString(MobiBaseMetadataExtractor.EXTH_ISBN, "0316442698"));
            File file = writeFile("isbn10.mobi", buildMobiFile(TITLE, true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getIsbn10()).isEqualTo("0316442698");
            assertThat(metadata.getIsbn13()).isNull();
        }

        @Test
        @DisplayName("an ISBN of an unrecognised length is dropped rather than mis-stored")
        void wrongLengthIsbn_isDropped() throws IOException {
            List<ExthEntry> exth = List.of(exthString(MobiBaseMetadataExtractor.EXTH_ISBN, "1234567"));
            File file = writeFile("isbn-bad.mobi", buildMobiFile(TITLE, true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getIsbn10()).isNull();
            assertThat(metadata.getIsbn13()).isNull();
        }

        @Test
        @DisplayName("categories split on both ';' and ',' are trimmed, deduplicated and blanks are skipped")
        void categoriesAreSplitTrimmedAndDeduplicated() throws IOException {
            List<ExthEntry> exth = List.of(exthString(MobiBaseMetadataExtractor.EXTH_SUBJECT, " Fantasy; Adventure,,Sci-Fi ; Fantasy "));
            File file = writeFile("categories.mobi", buildMobiFile(TITLE, true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("Fantasy", "Adventure", "Sci-Fi");
        }

        @Test
        @DisplayName("a year-only published date resolves to January 1st of that year")
        void yearOnlyPublishedDate() throws IOException {
            List<ExthEntry> exth = List.of(exthString(MobiBaseMetadataExtractor.EXTH_PUBLISHED_DATE, "1999"));
            File file = writeFile("year-only.mobi", buildMobiFile(TITLE, true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1999, Month.JANUARY, 1));
        }

        @Test
        @DisplayName("a slash-separated published date is parsed as YYYY/MM/DD")
        void slashFormatPublishedDate() throws IOException {
            List<ExthEntry> exth = List.of(exthString(MobiBaseMetadataExtractor.EXTH_PUBLISHED_DATE, "2020/05/15"));
            File file = writeFile("slash-date.mobi", buildMobiFile(TITLE, true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2020, Month.MAY, 15));
        }

        @Test
        @DisplayName("a dash-formatted date with an out-of-range month/day is caught and yields no published date")
        void invalidCalendarDate_isCaughtAndIgnored() throws IOException {
            List<ExthEntry> exth = List.of(exthString(MobiBaseMetadataExtractor.EXTH_PUBLISHED_DATE, "9999-99-99"));
            File file = writeFile("invalid-date.mobi", buildMobiFile(TITLE, true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getPublishedDate()).isNull();
        }

        @Test
        @DisplayName("a date matching none of the known formats yields no published date")
        void unrecognisedDateFormat_isIgnored() throws IOException {
            List<ExthEntry> exth = List.of(exthString(MobiBaseMetadataExtractor.EXTH_PUBLISHED_DATE, "not-a-date"));
            File file = writeFile("unparseable-date.mobi", buildMobiFile(TITLE, true, true, exth, 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getPublishedDate()).isNull();
        }

        @Test
        @DisplayName("a file too small to hold a PalmDB header returns null")
        void tooSmallFile_returnsNull() throws IOException {
            File file = writeFile("tiny.mobi", new byte[10]);

            assertThat(extractor.extractMetadata(file)).isNull();
        }

        @Test
        @DisplayName("a file missing the MOBI identifier returns null")
        void missingMobiIdentifier_returnsNull() throws IOException {
            File file = writeFile("no-mobi-id.mobi", buildMobiFile(TITLE, false, false, List.of(), 0, List.of()));

            assertThat(extractor.extractMetadata(file)).isNull();
        }

        @Test
        @DisplayName("a missing title leaves the title field unset rather than blank")
        void missingTitle_leavesTitleUnset() throws IOException {
            File file = writeFile("no-title.mobi", buildMobiFile("", true, false, List.of(), 0, List.of()));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isNull();
        }

        @Test
        @DisplayName("a file that cannot be opened at all returns null rather than throwing")
        void unreadableFile_returnsNull() {
            File missing = tempDir.resolve("does-not-exist.mobi").toFile();

            assertThat(extractor.extractMetadata(missing)).isNull();
        }
    }

    @Nested
    @DisplayName("extractCover")
    class ExtractCoverTests {

        @Test
        @DisplayName("uses the EXTH cover-offset record to locate the cover image")
        void coverOffset_locatesImage() throws IOException {
            List<ExthEntry> exth = List.of(exthInt(MobiBaseMetadataExtractor.EXTH_COVER_OFFSET, 0));
            File file = writeFile("cover-offset.mobi", buildMobiFile(TITLE, true, true, exth, 1, List.of(JPEG_BYTES)));

            byte[] cover = extractor.extractCover(file);

            assertThat(cover).isEqualTo(JPEG_BYTES);
        }

        @Test
        @DisplayName("falls back to the EXTH thumbnail-offset record when there is no cover-offset record")
        void thumbOffset_isUsedAsFallback() throws IOException {
            List<ExthEntry> exth = List.of(exthInt(MobiBaseMetadataExtractor.EXTH_THUMB_OFFSET, 0));
            File file = writeFile("thumb-offset.mobi", buildMobiFile(TITLE, true, true, exth, 1, List.of(PNG_BYTES)));

            byte[] cover = extractor.extractCover(file);

            assertThat(cover).isEqualTo(PNG_BYTES);
        }

        @Test
        @DisplayName("falls back to the first image record when there is no EXTH offset at all")
        void noExthOffset_fallsBackToFirstImage() throws IOException {
            File file = writeFile("first-image.mobi", buildMobiFile(TITLE, true, false, List.of(), 1, List.of(JPEG_BYTES)));

            byte[] cover = extractor.extractCover(file);

            assertThat(cover).isEqualTo(JPEG_BYTES);
        }

        @Test
        @DisplayName("an out-of-range cover-offset record falls back to the first image record")
        void outOfRangeCoverOffset_fallsBackToFirstImage() throws IOException {
            List<ExthEntry> exth = List.of(exthInt(MobiBaseMetadataExtractor.EXTH_COVER_OFFSET, 50));
            File file = writeFile("oob-offset.mobi", buildMobiFile(TITLE, true, true, exth, 1, List.of(JPEG_BYTES)));

            byte[] cover = extractor.extractCover(file);

            assertThat(cover).isEqualTo(JPEG_BYTES);
        }

        @Test
        @DisplayName("no cover record and no image record read through to a clean null (not a failure)")
        void noCoverAndNoImage_returnsNull() throws IOException {
            File file = writeFile("no-cover.mobi", buildMobiFile(TITLE, true, false, List.of(), 0, List.of()));

            assertThat(extractor.extractCover(file)).isNull();
        }

        @Test
        @DisplayName("an image record whose bytes don't match any known image magic number yields null")
        void unrecognisedImageMagic_returnsNull() throws IOException {
            File file = writeFile("bad-magic.mobi", buildMobiFile(TITLE, true, false, List.of(), 1, List.of(NOT_AN_IMAGE)));

            assertThat(extractor.extractCover(file)).isNull();
        }

        @Test
        @DisplayName("a zero-length trailing image record yields null rather than a failure")
        void zeroLengthTrailingRecord_returnsNull() throws IOException {
            File file = writeFile("empty-record.mobi", buildMobiFile(TITLE, true, false, List.of(), 1, List.of(new byte[0])));

            assertThat(extractor.extractCover(file)).isNull();
        }

        @Test
        @DisplayName("a file too small to hold a PalmDB header throws CoverExtractionException")
        void tooSmallFile_throws() throws IOException {
            File file = writeFile("tiny-cover.mobi", new byte[10]);

            assertThatThrownBy(() -> extractor.extractCover(file))
                    .isInstanceOf(CoverExtractionException.class)
                    .hasMessageContaining("Unreadable PalmDB header");
        }

        @Test
        @DisplayName("a file missing the MOBI identifier throws CoverExtractionException")
        void missingMobiIdentifier_throws() throws IOException {
            File file = writeFile("no-mobi-id-cover.mobi", buildMobiFile(TITLE, false, false, List.of(), 0, List.of()));

            assertThatThrownBy(() -> extractor.extractCover(file))
                    .isInstanceOf(CoverExtractionException.class)
                    .hasMessageContaining("Unreadable MOBI header");
        }

        @Test
        @DisplayName("a file that cannot be opened at all throws CoverExtractionException wrapping the cause")
        void unreadableFile_throwsWrapped() {
            File missing = tempDir.resolve("does-not-exist-cover.mobi").toFile();

            assertThatThrownBy(() -> extractor.extractCover(missing))
                    .isInstanceOf(CoverExtractionException.class)
                    .hasCauseInstanceOf(Exception.class);
        }
    }
}
