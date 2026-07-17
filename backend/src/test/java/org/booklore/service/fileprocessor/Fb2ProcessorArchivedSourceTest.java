package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.CoverProbeOutcome;
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
    class ProbeCoverOutcomes {

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

            CoverProbeOutcome outcome = processor.probeCover(book, bookFile);

            assertThat(outcome).isEqualTo(CoverProbeOutcome.READ_FAILED);
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

            CoverProbeOutcome outcome = processor.probeCover(book, bookFile);

            assertThat(outcome).isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        @Test
        void successfulReadWithCoverBinaryIsReportedAsCoverFound() throws IOException {
            Fb2Processor processor = buildProcessor();
            BookEntity book = BookEntity.builder().id(42L).build();
            BookFileEntity bookFile = buildArchivedFile(book);
            Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
            when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);
            when(fb2MetadataExtractor.extractCover(new File(cachedFile.toUri()))).thenReturn(encodePng());
            when(fileService.saveCoverImages(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(42L)))
                    .thenReturn(true);

            CoverProbeOutcome outcome = processor.probeCover(book, bookFile);

            assertThat(outcome).isEqualTo(CoverProbeOutcome.COVER_FOUND);
        }

        private byte[] encodePng() throws IOException {
            var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }
}
