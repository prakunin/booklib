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
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.PdfMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.test.RequiresPdfium;
import org.booklore.util.FileService;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.model.PageSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PDF cover reading against a real document and the real pdfium binding.
 * <p>
 * PDF is the one processor whose cover is <em>rendered</em> rather than extracted, so it is the one
 * whose purity cannot be shown by stubbing an extractor - there is nothing to stub between it and
 * the native library. Skipped, not failed, where the natives are absent (see {@link RequiresPdfium}),
 * which means a green run elsewhere is not evidence about this file.
 */
@RequiresPdfium
@ExtendWith(MockitoExtension.class)
class PdfProcessorCoverTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private PdfMetadataExtractor pdfMetadataExtractor;

    @TempDir
    Path tempDir;

    private PdfProcessor processor() {
        return new PdfProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, pdfMetadataExtractor);
    }

    private BookEntity bookWithRealPdf() throws Exception {
        Path pdf = tempDir.resolve("book.pdf");
        try (PdfDocument doc = PdfDocument.create()) {
            doc.insertBlankPage(0, PageSize.A4);
            doc.save(pdf);
        }
        BookEntity book = BookEntity.builder()
                .id(42L)
                .libraryPath(LibraryPathEntity.builder().path(tempDir.toString()).build())
                .build();
        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .bookType(BookFileType.PDF)
                .isBookFormat(true)
                .fileName("book.pdf")
                .fileSubPath("")
                .build();
        book.setBookFiles(new ArrayList<>(List.of(bookFile)));
        return book;
    }

    @Test
    void rendersTheFirstPageIntoDecodableBytes() throws Exception {
        BookEntity book = bookWithRealPdf();

        CoverExtraction extraction = processor().extractCover(book, book.getBookFiles().getFirst());

        assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
        // The bytes must be a real image, not merely non-empty: the caller's next move is to hand
        // them to FileService, which decodes them.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(extraction.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isPositive();
        assertThat(decoded.getHeight()).isPositive();
    }

    /**
     * The property the lazy claim-before-write path rests on, for the processor most likely to lose
     * it: rendering is not writing.
     */
    @Test
    void extractCoverWritesNothingAndLeavesTheEntityUntouched() throws Exception {
        BookEntity book = bookWithRealPdf();

        processor().extractCover(book, book.getBookFiles().getFirst());

        verifyNoInteractions(fileService);
        verifyNoInteractions(bookRepository);
        assertThat(book.getBookCoverHash()).isNull();
        assertThat(book.getCoverProbedAt()).isNull();
    }

    /**
     * A PDF has no embedded cover that could be absent, so this processor must never claim a clean
     * miss - a caller would persist it as a permanent verdict. An unopenable file is READ_FAILED.
     */
    @Test
    void reportsReadFailedRatherThanNoCoverFoundForAnUnreadablePdf() throws Exception {
        BookEntity book = bookWithRealPdf();
        java.nio.file.Files.writeString(tempDir.resolve("book.pdf"), "this is not a pdf");

        CoverExtraction extraction = processor().extractCover(book, book.getBookFiles().getFirst());

        assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
    }
}
