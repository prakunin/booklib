package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.CoverExtraction;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.CoverProbeOutcome;
import org.booklore.model.enums.CoverSaveOutcome;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Fb2ProcessorArchivedSourceTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private Fb2MetadataExtractor fb2MetadataExtractor;
    @Mock private ArchivedBookContentService archivedBookContentService;

    private Fb2Processor buildProcessor() {
        return new Fb2Processor(
                bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                fileService, metadataMatchService, sidecarMetadataWriter, fb2MetadataExtractor,
                archivedBookContentService);
    }

    @Test
    void resolvesArchivedFb2BeforeExtractingItsCover() {
        Fb2Processor processor = buildProcessor();
        BookEntity book = BookEntity.builder().id(42L).build();
        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .fileName("book.fb2")
                .fileSubPath("")
                .sourceArchive("catalog.zip")
                .sourceArchiveEntry("book.fb2")
                .build();
        Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
        when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);

        boolean generated = processor.generateCover(book, bookFile);

        assertThat(generated).isFalse();
        verify(fb2MetadataExtractor).extractCover(new File(cachedFile.toUri()));
    }

    /**
     * Covers the distinction that {@link org.booklore.service.metadata.BookCoverService}'s lazy INPX
     * cover probe depends on: a completed read that found nothing must be reported differently from
     * a failure to read the archive at all, so a transient failure is never mistaken for "no cover".
     * <p>
     * The cases that turn on what the extractor does with a malformed FB2 are driven through a
     * <strong>real</strong> {@link Fb2MetadataExtractor} reading a <strong>real</strong> file, in
     * {@link RealExtractorOutcomes} below. They used to be here, stubbing the mock extractor with
     * {@code thenThrow(new RuntimeException("malformed FB2"))} and asserting {@code READ_FAILED} -
     * and they passed, for four consecutive fix waves, while production did the exact opposite. The
     * real extractor caught {@code Exception} and returned {@code null}, so it could not throw; the
     * {@code null} it actually returned became {@code NO_COVER_FOUND}, and the probe wrote that down
     * as permanent. The stub described a collaborator that did not exist.
     */
    @Nested
    class ExtractCoverOutcomes {

        private BookFileEntity buildArchivedFile(BookEntity book) {
            return BookFileEntity.builder()
                    .book(book)
                    .fileName("book.fb2")
                    .fileSubPath("")
                    .sourceArchive("catalog.zip")
                    .sourceArchiveEntry("book.fb2")
                    .build();
        }

        /**
         * Resolving is the archive read, and it happens before the extractor is reached at all -
         * this one is genuinely about {@code ArchivedBookContentService}, so stubbing it is the
         * subject of the test rather than a stand-in for one.
         */
        @Test
        void archiveReadFailureIsReportedAsReadFailedNotNoCover() {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            when(archivedBookContentService.resolve(bookFile))
                    .thenThrow(new RuntimeException("archive temporarily unavailable"));

            CoverExtraction extraction = processor.extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
            verifyNoInteractions(fb2MetadataExtractor);
        }

        @Test
        void successfulReadWithNoCoverBinaryIsReportedAsNoCoverFound() {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri()))).thenReturn(null);

            CoverExtraction extraction = processor.extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        @Test
        void successfulReadWithCoverBinaryReturnsTheBytes() throws IOException {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            byte[] png = encodePng();
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri()))).thenReturn(png);

            CoverExtraction extraction = processor.extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(png);
        }

        /**
         * The contract {@code BookCoverService}'s claim-before-write ordering rests on: extraction
         * reads and nothing more. If this ever starts writing again, the lazy path silently goes
         * back to overwriting covers it hasn't yet earned the right to own.
         */
        @Test
        void extractCoverWritesNothingAndLeavesTheEntityUntouched() throws IOException {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri()))).thenReturn(encodePng());

            processor.extractCover(book, bookFile);

            verifyNoInteractions(fileService);
            verifyNoInteractions(bookRepository);
            assertThat(book.getBookCoverHash()).isNull();
        }

        /**
         * generateCover is the read plus the write, and must funnel its write through the one shared
         * decode-and-save helper rather than growing its own copy.
         */
        @Test
        void generateCoverWritesTheExtractedBytesThroughTheSharedHelper() throws IOException {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            byte[] png = encodePng();
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri()))).thenReturn(png);
            when(fileService.saveCoverImageFromBytes(42L, png)).thenReturn(CoverSaveOutcome.SAVED);

            boolean generated = processor.generateCover(book, bookFile);

            assertThat(generated).isTrue();
            verify(fileService).saveCoverImageFromBytes(42L, png);
        }

        @Test
        void generateCoverReportsFailureWhenTheImageCannotBeSaved() throws IOException {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            byte[] png = encodePng();
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri()))).thenReturn(png);
            when(fileService.saveCoverImageFromBytes(42L, png)).thenReturn(CoverSaveOutcome.SAVE_FAILED);

            assertThat(processor.generateCover(book, bookFile)).isFalse();
        }

        /**
         * generateCover is an unconditional overwrite: it has no marker to set and no claim to
         * release, so an undecodable cover is simply a failure to produce one. The permanent-vs-
         * transient distinction it discards here is the lazy probe's business, not its own.
         */
        @Test
        void generateCoverReportsFailureWhenTheCoverCannotBeDecoded() {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes();
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri()))).thenReturn(svg);
            when(fileService.saveCoverImageFromBytes(42L, svg)).thenReturn(CoverSaveOutcome.UNDECODABLE);

            assertThat(processor.generateCover(book, bookFile)).isFalse();
        }

        private byte[] encodePng() throws IOException {
            var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    /**
     * The same processor, wired to a <em>real</em> {@link Fb2MetadataExtractor} reading real files
     * that a real INPX archive could hand it.
     * <p>
     * This is the pairing the whole defect hid between. Everything above stubs the extractor and so
     * can only ever assert what {@code Fb2Processor} does with an answer someone decided to give it;
     * these assert what the extractor actually answers, and therefore what the probe actually
     * records. Only {@code ArchivedBookContentService} is stubbed, and only to resolve an archive
     * entry to a path - the file at that path is real, and the extractor really reads it.
     */
    @Nested
    class RealExtractorOutcomes {

        @TempDir
        Path tempDir;

        private Fb2Processor processorWithRealExtractor() {
            return new Fb2Processor(
                    bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                    fileService, metadataMatchService, sidecarMetadataWriter, new Fb2MetadataExtractor(),
                    archivedBookContentService);
        }

        private BookFileEntity archivedFileResolvingTo(BookEntity book, Path realFile) {
            BookFileEntity bookFile = BookFileEntity.builder()
                    .book(book)
                    .fileName("book.fb2")
                    .fileSubPath("")
                    .sourceArchive("catalog.zip")
                    .sourceArchiveEntry("book.fb2")
                    .build();
            when(archivedBookContentService.resolve(bookFile)).thenReturn(realFile);
            return bookFile;
        }

        private Path writeFb2(String body) throws IOException {
            Path path = tempDir.resolve("book.fb2");
            Files.writeString(path, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                    %s
                    </FictionBook>
                    """.formatted(body), StandardCharsets.UTF_8);
            return path;
        }

        /**
         * The defect, exactly: a real malformed FB2 pulled out of a real archive must be reported as
         * a failed read. If this says {@code NO_COVER_FOUND}, {@code BookCoverService} stamps
         * {@code cover_probed_at} and the book's cover is gone until someone rescans the library.
         */
        @Test
        void aRealMalformedFb2IsReportedAsReadFailedNotNoCover() throws IOException {
            BookEntity book = BookEntity.builder().id(42L).build();
            Path path = tempDir.resolve("book.fb2");
            Files.writeString(path, "not xml at all");
            BookFileEntity bookFile = archivedFileResolvingTo(book, path);

            CoverExtraction extraction = processorWithRealExtractor().extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        /** The FB2 parses; the cover binary's base64 does not. Still a read failure, not a miss. */
        @Test
        void aRealFb2WithCorruptCoverBase64IsReportedAsReadFailedNotNoCover() throws IOException {
            BookEntity book = BookEntity.builder().id(42L).build();
            Path path = writeFb2("""
                    <description><title-info/></description>
                    <binary id="cover.png" content-type="image/png">!!!not@@base64###</binary>
                    """);
            BookFileEntity bookFile = archivedFileResolvingTo(book, path);

            CoverExtraction extraction = processorWithRealExtractor().extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void anArchiveEntryResolvingToAMissingFileIsReportedAsReadFailedNotNoCover() {
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = archivedFileResolvingTo(book, tempDir.resolve("absent.fb2"));

            CoverExtraction extraction = processorWithRealExtractor().extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        /**
         * The other half, and the reason "throw on anything" would be no better than swallowing: a
         * real FB2 that really has no cover must still get the definitive verdict, because that is
         * the answer the marker exists to record and skipping it re-reads the archive forever.
         */
        @Test
        void aRealFb2WithNoCoverIsReportedAsNoCoverFound() throws IOException {
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = archivedFileResolvingTo(book, writeFb2("<description><title-info/></description>"));

            CoverExtraction extraction = processorWithRealExtractor().extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        @Test
        void aRealFb2WithACoverReturnsItsBytes() throws IOException {
            BookEntity book = BookEntity.builder().id(42L).build();
            byte[] cover = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
            BookFileEntity bookFile = archivedFileResolvingTo(book, writeFb2("""
                    <description><title-info/></description>
                    <binary id="cover.png" content-type="image/png">%s</binary>
                    """.formatted(Base64.getEncoder().encodeToString(cover))));

            CoverExtraction extraction = processorWithRealExtractor().extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(cover);
        }

        /**
         * The user-facing half: an explicit regeneration of a malformed FB2's cover must return
         * false rather than propagate, so one bad file is a failed request and not a repeated 500.
         * The {@code catch (Exception)} in {@code Fb2Processor.extractCover} that guarantees this
         * was dead code until the extractor started throwing - this is what brings it to life.
         */
        @Test
        void generateCoverOnARealMalformedFb2ReturnsFalseWithoutPropagating() throws IOException {
            BookEntity book = BookEntity.builder().id(42L).build();
            Path path = tempDir.resolve("book.fb2");
            Files.writeString(path, "not xml at all");
            BookFileEntity bookFile = archivedFileResolvingTo(book, path);

            assertThat(processorWithRealExtractor().generateCover(book, bookFile)).isFalse();
            verifyNoInteractions(fileService);
        }
    }
}
