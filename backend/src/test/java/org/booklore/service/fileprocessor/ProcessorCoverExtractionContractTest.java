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
import org.booklore.service.metadata.extractor.Azw3MetadataExtractor;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.service.metadata.extractor.EpubMetadataExtractor;
import org.booklore.service.metadata.extractor.MobiMetadataExtractor;
import org.booklore.service.metadata.extractor.PdfMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The contract every {@link BookFileProcessor#extractCover} implementation owes its callers, applied
 * to each of them in turn.
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
 * These are deliberately exercised against real processor instances. Stubbing {@code extractCover}
 * on a {@code mock(BookFileProcessor.class)} proves nothing about any of this - it tests the mock.
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

    private static final byte[] COVER_BYTES = {(byte) 0x89, 'P', 'N', 'G'};

    /**
     * A book whose file resolves to a concrete path. The file need not exist: every extractor is
     * mocked, so nothing opens it.
     */
    private static BookEntity bookWithFile(BookFileType type, String fileName) {
        BookEntity book = BookEntity.builder()
                .id(42L)
                .libraryPath(LibraryPathEntity.builder().path("/library").build())
                .build();
        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .bookType(type)
                .isBookFormat(true)
                .fileName(fileName)
                .fileSubPath("")
                .build();
        book.setBookFiles(new java.util.ArrayList<>(java.util.List.of(bookFile)))
        ;
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

    @Nested
    class Epub {

        @Mock private EpubMetadataExtractor epubMetadataExtractor;

        private EpubProcessor processor() {
            return new EpubProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, epubMetadataExtractor);
        }

        @Test
        void returnsTheBytesWhenTheEpubHasACover() {
            BookEntity book = bookWithFile(BookFileType.EPUB, "book.epub");
            when(epubMetadataExtractor.extractCover(any(File.class))).thenReturn(COVER_BYTES);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(COVER_BYTES);
        }

        /**
         * The commonest real case, and the one the write-in-place default made unreachable: an EPUB
         * that genuinely has no cover. epub4j proves it by returning null rather than failing, so
         * this must be the definitive verdict and not a hedge.
         */
        @Test
        void reportsNoCoverFoundWhenTheEpubHasNone() {
            BookEntity book = bookWithFile(BookFileType.EPUB, "book.epub");
            when(epubMetadataExtractor.extractCover(any(File.class))).thenReturn(null);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        @Test
        void reportsReadFailedWhenTheEpubCannotBeRead() {
            BookEntity book = bookWithFile(BookFileType.EPUB, "book.epub");
            when(epubMetadataExtractor.extractCover(any(File.class))).thenThrow(new RuntimeException("corrupt zip"));

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouched() {
            BookEntity book = bookWithFile(BookFileType.EPUB, "book.epub");
            when(epubMetadataExtractor.extractCover(any(File.class))).thenReturn(COVER_BYTES);

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }

    @Nested
    class Mobi {

        @Mock private MobiMetadataExtractor mobiMetadataExtractor;

        private MobiProcessor processor() {
            return new MobiProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, mobiMetadataExtractor);
        }

        @Test
        void returnsTheBytesWhenTheMobiHasACover() {
            BookEntity book = bookWithFile(BookFileType.MOBI, "book.mobi");
            when(mobiMetadataExtractor.extractCover(any(File.class))).thenReturn(COVER_BYTES);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(COVER_BYTES);
        }

        @Test
        void reportsNoCoverFoundWhenTheMobiHasNone() {
            BookEntity book = bookWithFile(BookFileType.MOBI, "book.mobi");
            when(mobiMetadataExtractor.extractCover(any(File.class))).thenReturn(null);

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        @Test
        void reportsReadFailedWhenTheMobiCannotBeRead() {
            BookEntity book = bookWithFile(BookFileType.MOBI, "book.mobi");
            when(mobiMetadataExtractor.extractCover(any(File.class))).thenThrow(new RuntimeException("bad palmdoc"));

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouched() {
            BookEntity book = bookWithFile(BookFileType.MOBI, "book.mobi");
            when(mobiMetadataExtractor.extractCover(any(File.class))).thenReturn(COVER_BYTES);

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }

    @Nested
    class Azw3 {

        @Mock private Azw3MetadataExtractor azw3MetadataExtractor;

        private Azw3Processor processor() {
            return new Azw3Processor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, azw3MetadataExtractor);
        }

        @Test
        void returnsTheBytesWhenTheAzw3HasACover() {
            BookEntity book = bookWithFile(BookFileType.AZW3, "book.azw3");
            when(azw3MetadataExtractor.extractCover(any(File.class))).thenReturn(COVER_BYTES);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(COVER_BYTES);
        }

        @Test
        void reportsNoCoverFoundWhenTheAzw3HasNone() {
            BookEntity book = bookWithFile(BookFileType.AZW3, "book.azw3");
            when(azw3MetadataExtractor.extractCover(any(File.class))).thenReturn(new byte[0]);

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        @Test
        void reportsReadFailedWhenTheAzw3CannotBeRead() {
            BookEntity book = bookWithFile(BookFileType.AZW3, "book.azw3");
            when(azw3MetadataExtractor.extractCover(any(File.class))).thenThrow(new RuntimeException("bad exth"));

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouched() {
            BookEntity book = bookWithFile(BookFileType.AZW3, "book.azw3");
            when(azw3MetadataExtractor.extractCover(any(File.class))).thenReturn(COVER_BYTES);

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }

    @Nested
    class Cbx {

        @Mock private CbxMetadataExtractor cbxMetadataExtractor;

        private CbxProcessor processor() {
            return new CbxProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter, cbxMetadataExtractor);
        }

        @Test
        void returnsTheBytesWhenTheArchiveHasAFirstPage() {
            BookEntity book = bookWithFile(BookFileType.CBX, "book.cbz");
            when(cbxMetadataExtractor.extractCover(any(Path.class))).thenReturn(COVER_BYTES);

            CoverExtraction extraction = processor().extractCover(book, fileOf(book));

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(COVER_BYTES);
        }

        @Test
        void reportsNoCoverFoundWhenTheArchiveHoldsNoImages() {
            BookEntity book = bookWithFile(BookFileType.CBX, "book.cbz");
            when(cbxMetadataExtractor.extractCover(any(Path.class))).thenReturn(null);

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
        }

        /**
         * Previously indistinguishable: the whole read sat under one catch that logged an unopenable
         * archive as "could not find cover image in archive" - the same as an archive with no pages.
         */
        @Test
        void reportsReadFailedWhenTheArchiveCannotBeOpened() {
            BookEntity book = bookWithFile(BookFileType.CBX, "book.cbz");
            when(cbxMetadataExtractor.extractCover(any(Path.class))).thenThrow(new RuntimeException("unsupported rar5"));

            assertThat(processor().extractCover(book, fileOf(book)).outcome())
                    .isEqualTo(CoverProbeOutcome.READ_FAILED);
        }

        @Test
        void writesNothingAndLeavesTheEntityUntouched() {
            BookEntity book = bookWithFile(BookFileType.CBX, "book.cbz");
            when(cbxMetadataExtractor.extractCover(any(Path.class))).thenReturn(COVER_BYTES);

            assertPureRead(book, () -> processor().extractCover(book, fileOf(book)));
        }
    }

    /**
     * PDF has no mocked seam for its cover: it is rendered by the native pdfium binding rather than
     * read out of the file, so these go through the real thing. See {@code PdfProcessorCoverTest}
     * for the cases that need a real document; the one here needs no PDF at all.
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
