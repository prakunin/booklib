package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.CoverExtraction;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.ArchiveService;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.Azw3MetadataExtractor;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.service.metadata.extractor.EpubMetadataExtractor;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.booklore.service.metadata.extractor.MobiMetadataExtractor;
import org.booklore.service.metadata.extractor.PdfMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The contract every {@link BookFileProcessor#extractCover} implementation owes its callers, applied
 * to each of them in turn, <strong>against real extractors reading real files</strong>.
 * <p>
 * Two properties matter, and they are the two the previous fix waves kept losing:
 * <ul>
 *   <li><b>Purity.</b> Extraction reads and nothing more - no disk write, no entity mutation. It is
 *       what lets {@code BookCoverService#tryGenerateMissingInpxCover} claim a book's cover before
 *       committing an image to disk. A processor that writes during extraction silently returns that
 *       path to overwriting covers it has not earned the right to own.</li>
 *   <li><b>Honesty about absence.</b> {@code NO_COVER_FOUND} is a permanent verdict a caller may
 *       persist; a failure to read must never wear it. Every processor that collapses the two hands
 *       the lazy probe a transient error to record forever.</li>
 * </ul>
 * <p>
 * The real extractors are the point of this file, and their absence is why wave 4 shipped it green
 * while production did the opposite. It mocked each extractor and stubbed {@code extractCover(...)}
 * to {@code thenThrow(new RuntimeException("malformed FB2"))}, then asserted {@code READ_FAILED}.
 * The real extractors could not throw - every one caught {@code Exception} and returned {@code null}
 * - so the stub proved only that the processor handles an exception it was never going to receive,
 * while the {@code null} it did receive became {@code NO_COVER_FOUND} and was written to the
 * database as a permanent verdict. A stub that can do what the real collaborator cannot is not a
 * test of the contract; it is a test of the stub.
 * <p>
 * So: real extractors, real files, real corruption. The only stubs left below a processor are
 * {@code ArchiveService} for CBX (the libarchive natives are not guaranteed present, and it is the
 * extractor's own logic rather than the native unzip that has to draw the distinction) and
 * {@code ArchivedBookContentService} for FB2's INPX path, which only resolves an archive entry to a
 * path - the real extractor then really reads it.
 */
@ExtendWith(MockitoExtension.class)
class ProcessorCoverExtractionContractTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;

    @TempDir
    Path tempDir;

    private BookEntity bookWithFile(BookFileType type, String fileName) {
        BookEntity book = BookEntity.builder()
                .id(42L)
                .libraryPath(LibraryPathEntity.builder().path(tempDir.toString()).build())
                .build();
        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .bookType(type)
                .isBookFormat(true)
                .fileName(fileName)
                .fileSubPath("")
                .build();
        book.setBookFiles(new ArrayList<>(List.of(bookFile)));
        return book;
    }

    private static BookFileEntity fileOf(BookEntity book) {
        return book.getBookFiles().getFirst();
    }

    /**
     * The purity assertions, identical for every processor. FileService is the only route to disk a
     * processor has, so never touching it is what "writes nothing" means here.
     */
    private void assertPureRead(BookEntity book, Runnable extraction) {
        String hashBefore = book.getBookCoverHash();
        extraction.run();
        verifyNoInteractions(fileService);
        verifyNoInteractions(bookRepository);
        assertThat(book.getBookCoverHash()).isEqualTo(hashBefore);
        assertThat(book.getCoverProbedAt()).isNull();
    }

    private static byte[] pngBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    // =====================================================================================
    // EPUB - the real epub4j and container readers against real zip files
    // =====================================================================================

    @Nested
    class Epub {

        private EpubProcessor processor() {
            return new EpubProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter,
                    new EpubMetadataExtractor(new ObjectMapper()));
        }

        private BookEntity epub(String manifest, byte[] coverImage) throws IOException {
            BookEntity book = bookWithFile(BookFileType.EPUB, "book.epub");
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Book</dc:title></metadata>
                      <manifest>%s</manifest>
                    </package>""".formatted(manifest);
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempDir.resolve("book.epub").toFile()))) {
                zos.putNextEntry(new ZipEntry("mimetype"));
                zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
                zos.write("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                        </container>""".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
                zos.write(opf.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                if (coverImage != null) {
                    zos.putNextEntry(new ZipEntry("OEBPS/cover.jpg"));
                    zos.write(coverImage);
                    zos.closeEntry();
                }
            }
            return book;
        }

        @Test
        void returnsTheBytesWhenTheEpubHasACover() throws IOException {
            byte[] cover = pngBytes();
            BookEntity book = epub(
                    "<item id=\"cover\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>", cover);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(cover);
        }

        /**
         * The commonest real case, and one that must stay a definitive verdict: a well-formed EPUB
         * that simply has no cover. The container opened and every route through the OPF came up
         * empty, so this is proof of absence rather than a shrug.
         */
        @Test
        void reportsNoCoverFoundWhenTheEpubHasNone() throws IOException {
            BookEntity book = epub("<item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>", null);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        @Test
        void reportsReadFailedForARealCorruptZip() throws IOException {
            BookEntity book = bookWithFile(BookFileType.EPUB, "book.epub");
            Files.write(tempDir.resolve("book.epub"), new byte[]{0x00, 0x01, 0x02, 0x03});

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void reportsReadFailedForAMissingFile() {
            BookEntity book = bookWithFile(BookFileType.EPUB, "absent.epub");

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouched() throws IOException {
            BookEntity book = epub(
                    "<item id=\"cover\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>", pngBytes());

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }

    // =====================================================================================
    // FB2 - the format the defect was found in
    // =====================================================================================

    @Nested
    class Fb2 {

        @Mock private ArchivedBookContentService archivedBookContentService;

        private Fb2Processor processor() {
            return new Fb2Processor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter,
                    new Fb2MetadataExtractor(), archivedBookContentService);
        }

        private BookEntity fb2(String body) throws IOException {
            BookEntity book = bookWithFile(BookFileType.FB2, "book.fb2");
            Path path = tempDir.resolve("book.fb2");
            Files.writeString(path, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                    %s
                    </FictionBook>
                    """.formatted(body), StandardCharsets.UTF_8);
            when(archivedBookContentService.resolve(fileOf(book))).thenReturn(path);
            return book;
        }

        @Test
        void returnsTheBytesWhenTheFb2HasACover() throws IOException {
            byte[] cover = pngBytes();
            BookEntity book = fb2("""
                    <description><title-info/></description>
                    <binary id="cover.png" content-type="image/png">%s</binary>
                    """.formatted(Base64.getEncoder().encodeToString(cover)));

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(cover);
        }

        @Test
        void reportsNoCoverFoundWhenTheFb2HasNone() throws IOException {
            BookEntity book = fb2("<description><title-info/></description>");

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        /**
         * The defect itself, at the processor: a malformed FB2 must not be reported as a book that
         * has no cover, because {@code BookCoverService} writes that answer down and never asks
         * again. This is the assertion wave 4 made with a stub that could not fire.
         */
        @Test
        void reportsReadFailedForARealMalformedFb2() throws IOException {
            BookEntity book = bookWithFile(BookFileType.FB2, "book.fb2");
            Path path = tempDir.resolve("book.fb2");
            Files.writeString(path, "not xml at all");
            when(archivedBookContentService.resolve(fileOf(book))).thenReturn(path);

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void reportsReadFailedForRealCorruptBase64InTheCover() throws IOException {
            BookEntity book = fb2("""
                    <description><title-info/></description>
                    <binary id="cover.png" content-type="image/png">!!!not@@base64###</binary>
                    """);

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void reportsReadFailedForAMissingFile() {
            BookEntity book = bookWithFile(BookFileType.FB2, "absent.fb2");
            when(archivedBookContentService.resolve(fileOf(book))).thenReturn(tempDir.resolve("absent.fb2"));

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouched() throws IOException {
            BookEntity book = fb2("""
                    <description><title-info/></description>
                    <binary id="cover.png" content-type="image/png">%s</binary>
                    """.formatted(Base64.getEncoder().encodeToString(pngBytes())));

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }

    // =====================================================================================
    // MOBI / AZW3 - the real PalmDB reader against real files
    // =====================================================================================

    /**
     * MOBI and AZW3 share {@code MobiBaseMetadataExtractor}, so they share this shape too. Only the
     * failure side is driven here: hand-assembling a PalmDB record list valid enough for the header
     * reader to reach a cover record is a fixture builder in its own right, and the distinction
     * under test lives on the side that fails - which a truncated file reaches honestly. See
     * {@code Fb2} and {@code Epub} above for the full three-outcome sweep.
     */
    @Nested
    class MobiAndAzw3 {

        private MobiProcessor mobiProcessor() {
            return new MobiProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, new MobiMetadataExtractor());
        }

        private Azw3Processor azw3Processor() {
            return new Azw3Processor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, new Azw3MetadataExtractor());
        }

        /**
         * A file too short to hold a PalmDB header is unreadable, not coverless. It used to come
         * back as the same {@code null} a MOBI with no cover record does.
         */
        @Test
        void mobiReportsReadFailedForATruncatedFile() throws IOException {
            BookEntity book = bookWithFile(BookFileType.MOBI, "book.mobi");
            Files.write(tempDir.resolve("book.mobi"), new byte[]{1, 2, 3, 4});

            assertThat(mobiProcessor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void mobiReportsReadFailedForAMissingFile() {
            BookEntity book = bookWithFile(BookFileType.MOBI, "absent.mobi");

            assertThat(mobiProcessor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void azw3ReportsReadFailedForATruncatedFile() throws IOException {
            BookEntity book = bookWithFile(BookFileType.AZW3, "book.azw3");
            Files.write(tempDir.resolve("book.azw3"), new byte[]{1, 2, 3, 4});

            assertThat(azw3Processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void azw3ReportsReadFailedForAMissingFile() {
            BookEntity book = bookWithFile(BookFileType.AZW3, "absent.azw3");

            assertThat(azw3Processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void mobiWritesNothingAndLeavesTheEntityUntouched() throws IOException {
            BookEntity book = bookWithFile(BookFileType.MOBI, "book.mobi");
            Files.write(tempDir.resolve("book.mobi"), new byte[]{1, 2, 3, 4});

            assertPureRead(book, () -> mobiProcessor().extractCover(book, fileOf(book)));
        }
    }

    // =====================================================================================
    // CBX - the real extractor's logic; only the native unzip beneath it is stubbed
    // =====================================================================================

    @Nested
    class Cbx {

        @Mock private ArchiveService archiveService;

        private CbxProcessor processor() {
            return new CbxProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter,
                    new CbxMetadataExtractor(archiveService));
        }

        private BookEntity cbz(Map<String, byte[]> entries) throws IOException {
            BookEntity book = bookWithFile(BookFileType.CBX, "book.cbz");
            Path path = tempDir.resolve("book.cbz");
            Files.write(path, new byte[]{0});
            when(archiveService.streamEntryNames(path)).then(i -> entries.keySet().stream());
            for (var e : entries.entrySet()) {
                // lenient: a non-image entry is filtered out by name and never read, which is
                // itself part of the behaviour under test.
                lenient().when(archiveService.getEntryBytes(path, e.getKey())).thenReturn(e.getValue());
            }
            return book;
        }

        @Test
        void returnsTheBytesWhenTheArchiveHasAFirstPage() throws IOException {
            byte[] page = pngBytes();
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("page001.png", page);
            BookEntity book = cbz(entries);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(page);
        }

        @Test
        void reportsNoCoverFoundWhenTheArchiveHoldsNoImages() throws IOException {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("readme.txt", "no images here".getBytes(StandardCharsets.UTF_8));
            BookEntity book = cbz(entries);

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        /**
         * Previously indistinguishable: the archive listing failure was swallowed into an empty
         * stream, which flowed on and reached the caller as the same "no cover" an empty CBZ gives.
         */
        @Test
        void reportsReadFailedWhenTheArchiveCannotBeOpened() throws IOException {
            BookEntity book = bookWithFile(BookFileType.CBX, "book.cbz");
            Path path = tempDir.resolve("book.cbz");
            Files.write(path, new byte[]{0});
            when(archiveService.streamEntryNames(path)).thenThrow(new IOException("unsupported rar5"));

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        /** Pages that are present but corrupt are not an absence of pages. */
        @Test
        void reportsReadFailedWhenEveryPageIsUndecodable() throws IOException {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("page001.jpg", "not a jpeg".getBytes(StandardCharsets.UTF_8));
            BookEntity book = cbz(entries);

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouched() throws IOException {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("page001.png", pngBytes());
            BookEntity book = cbz(entries);

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }

    /**
     * PDF has no mocked seam for its cover: it is rendered by the native pdfium binding rather than
     * read out of the file, so these go through the real thing. See {@code PdfProcessorCoverTest}
     * for the cases that need a real document; the ones here need no PDF at all.
     */
    @Nested
    class Pdf {

        @Mock private PdfMetadataExtractor pdfMetadataExtractor;

        private PdfProcessor processor() {
            return new PdfProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, pdfMetadataExtractor);
        }

        /**
         * A PDF that cannot be opened is a read failure, never a clean miss - and notably this holds
         * without pdfium present, since a missing file fails before any native call.
         */
        @Test
        void reportsReadFailedWhenThePdfCannotBeOpened() {
            BookEntity book = bookWithFile(BookFileType.PDF, "does-not-exist.pdf");

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouchedEvenWhenTheReadFails() {
            BookEntity book = bookWithFile(BookFileType.PDF, "does-not-exist.pdf");

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }
}
