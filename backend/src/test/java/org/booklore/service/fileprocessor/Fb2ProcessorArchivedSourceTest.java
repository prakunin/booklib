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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

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

        /**
         * Drives the same processor call that {@code BookCoverService.tryGenerateMissingInpxCover}
         * makes on the processor. Before this fix, an exception thrown by the extractor escaped
         * the probe entirely instead of being reported as a failed read.
         */
        @Test
        void extractionThrowingDuringExtractCoverIsReportedAsReadFailedNotPropagated() {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri())))
                    .thenThrow(new RuntimeException("malformed FB2"));

            CoverExtraction extraction = processor.extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        /**
         * Drives the same processor call that {@code BookCoverService.regenerateCover} makes on the
         * processor for explicit, user-triggered regeneration. Before this fix, this call would
         * throw instead of returning false, turning one malformed FB2 into a repeated HTTP 500.
         */
        @Test
        void extractionThrowingDuringGenerateCoverReturnsFalseWithoutPropagating() {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri())))
                    .thenThrow(new RuntimeException("malformed FB2"));

            boolean generated = processor.generateCover(book, bookFile);

            assertThat(generated).isFalse();
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
            when(fileService.saveCoverImageFromBytes(42L, png)).thenReturn(CoverSaveOutcome.WRITE_FAILED);

            assertThat(processor.generateCover(book, bookFile)).isFalse();
        }

        /**
         * generateCover is an unconditional overwrite: it has no marker to set and no claim to
         * release, so an undecodable cover is simply a failure to produce one. The permanent-vs-
         * transient distinction it discards here is the lazy probe's business, not its own.
         */
        @Test
        void generateCoverReportsFailureWhenTheCoverCannotBeDecoded() throws IOException {
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
}
