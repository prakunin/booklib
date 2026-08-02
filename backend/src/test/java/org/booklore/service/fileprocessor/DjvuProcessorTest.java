package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverSaveOutcome;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.djvu.DjvuDocumentInfo;
import org.booklore.service.djvu.DjvuRenditionService;
import org.booklore.service.djvu.DjvuToolException;
import org.booklore.service.djvu.DjvuToolRunner;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.CoverExtractionException;
import org.booklore.service.metadata.extractor.DjvuMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DjvuProcessorTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private DjvuMetadataExtractor djvuMetadataExtractor;
    @Mock private DjvuToolRunner toolRunner;
    @Mock private DjvuRenditionService renditionService;
    @Mock private LibraryFile libraryFile;

    @TempDir
    Path tempDir;

    private DjvuProcessor processor;
    private BookEntity book;

    @BeforeEach
    void setUp() {
        processor = new DjvuProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                bookMapper, fileService, metadataMatchService, sidecarMetadataWriter,
                djvuMetadataExtractor, toolRunner, renditionService);

        BookFileEntity bookFile = BookFileEntity.builder()
                .fileName("zhurnal_Radio_1972_10.djvu")
                .fileSubPath("")
                .bookType(BookFileType.DJVU)
                .isBookFormat(true)
                .build();
        book = BookEntity.builder()
                .id(11L)
                .bookFiles(List.of(bookFile))
                .libraryPath(LibraryPathEntity.builder().path(tempDir.toString()).build())
                .metadata(BookMetadataEntity.builder().build())
                .build();
        lenient().when(bookCreatorService.createShellBook(libraryFile, BookFileType.DJVU)).thenReturn(book);
        lenient().when(toolRunner.probe(any())).thenReturn(new DjvuDocumentInfo(48, List.of(), Map.of()));
    }

    @Test
    void servesTheDjvuType() {
        assertThat(processor.getSupportedTypes()).containsExactly(BookFileType.DJVU);
    }

    @Test
    void appliesEmbeddedMetadataAndPageCount() {
        when(djvuMetadataExtractor.extractMetadata(any())).thenReturn(org.booklore.model.dto.BookMetadata.builder()
                .title("Radio Magazine")
                .authors(List.of("A. Popov"))
                .publisher("Svyaz")
                .language("ru")
                .publishedDate(LocalDate.of(1972, Month.JANUARY, 1))
                .build());
        when(djvuMetadataExtractor.extractCover(any())).thenReturn(new byte[]{1, 2, 3});
        when(fileService.saveCoverImageFromBytes(anyLong(), any())).thenReturn(CoverSaveOutcome.SAVED);

        BookEntity result = processor.processNewFile(libraryFile);

        assertThat(result.getMetadata().getTitle()).isEqualTo("Radio Magazine");
        assertThat(result.getMetadata().getPublisher()).isEqualTo("Svyaz");
        assertThat(result.getMetadata().getLanguage()).isEqualTo("ru");
        assertThat(result.getMetadata().getPublishedDate()).isEqualTo(LocalDate.of(1972, Month.JANUARY, 1));
        assertThat(result.getMetadata().getPageCount()).isEqualTo(48);
        verify(bookCreatorService).addAuthorsToBook(Set.of("A. Popov"), book);
    }

    @Test
    void fallsBackToTheFilenameWhenTheDocumentSaysNothing() {
        when(djvuMetadataExtractor.extractMetadata(any())).thenReturn(null);
        when(djvuMetadataExtractor.extractCover(any())).thenReturn(new byte[]{1, 2, 3});
        when(fileService.saveCoverImageFromBytes(anyLong(), any())).thenReturn(CoverSaveOutcome.SAVED);

        BookEntity result = processor.processNewFile(libraryFile);

        assertThat(result.getMetadata().getTitle()).isEqualTo("zhurnal_Radio_1972_10");
    }

    @Test
    void aDecoderFailureStillLeavesTheBookInTheCatalog() {
        // A shell book with a filename title is worth more to the user than a file that silently
        // never appeared - and Smart Enrichment can still fill it in from the providers afterwards.
        when(djvuMetadataExtractor.extractMetadata(any())).thenThrow(new DjvuToolException("no ddjvu"));
        when(djvuMetadataExtractor.extractCover(any())).thenThrow(new CoverExtractionException("no ddjvu"));

        assertThatNoException().isThrownBy(() -> processor.processNewFile(libraryFile));
        assertThat(book.getMetadata().getTitle()).isEqualTo("zhurnal_Radio_1972_10");
    }

    @Test
    void anUnreadablePageCountIsLeftBlankRatherThanGuessed() {
        when(djvuMetadataExtractor.extractMetadata(any())).thenReturn(null);
        when(djvuMetadataExtractor.extractCover(any())).thenReturn(new byte[]{1, 2, 3});
        when(fileService.saveCoverImageFromBytes(anyLong(), any())).thenReturn(CoverSaveOutcome.SAVED);
        when(toolRunner.probe(any())).thenThrow(new DjvuToolException("boom"));

        BookEntity result = processor.processNewFile(libraryFile);

        assertThat(result.getMetadata().getPageCount()).isNull();
    }

    @Test
    void aCoverThatCannotBeSavedLeavesNoCoverHashBehind() {
        when(djvuMetadataExtractor.extractMetadata(any())).thenReturn(null);
        when(djvuMetadataExtractor.extractCover(any())).thenReturn(new byte[]{1, 2, 3});
        when(fileService.saveCoverImageFromBytes(anyLong(), any())).thenReturn(CoverSaveOutcome.SAVE_FAILED);

        BookEntity result = processor.processNewFile(libraryFile);

        assertThat(result.getBookCoverHash()).isNull();
    }

    @Test
    void aFailedCoverReadIsNeverWritten() {
        when(djvuMetadataExtractor.extractMetadata(any())).thenReturn(null);
        when(djvuMetadataExtractor.extractCover(any())).thenThrow(new CoverExtractionException("boom"));

        processor.processNewFile(libraryFile);

        verify(fileService, never()).saveCoverImageFromBytes(anyLong(), any());
    }
}
