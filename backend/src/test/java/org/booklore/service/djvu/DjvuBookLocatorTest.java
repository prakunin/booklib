package org.booklore.service.djvu;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DjvuBookLocatorTest {

    private static final long BOOK_ID = 1494366L;

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final ArchivedBookContentService archivedBookContentService = mock(ArchivedBookContentService.class);

    private final DjvuBookLocator locator = new DjvuBookLocator(bookRepository, archivedBookContentService);

    private BookFileEntity bookWith(String fileName, String sourceArchive, String sourceArchiveEntry) {
        BookEntity book = BookEntity.builder()
                .id(BOOK_ID)
                .libraryPath(LibraryPathEntity.builder().path("/books/fb2.Flibusta.Net").build())
                .build();
        BookFileEntity file = BookFileEntity.builder()
                .id(7L)
                .book(book)
                .bookType(BookFileType.DJVU)
                .isBookFormat(true)
                .fileName(fileName)
                .fileSubPath("")
                .sourceArchive(sourceArchive)
                .sourceArchiveEntry(sourceArchiveEntry)
                .build();
        book.setBookFiles(List.of(file));
        when(bookRepository.findByIdForStreaming(BOOK_ID)).thenReturn(Optional.of(book));
        return file;
    }

    @Test
    void anArchivedBookIsMaterialisedOutOfItsArchive() {
        // The whole Flibusta-style library is INPX archives: not one of these books has a file in
        // the library folder, so resolving to a library path is resolving to something that cannot
        // exist. This is what the reader hit as NoSuchFileException on a doubled ".djvu.djvu".
        BookFileEntity file = bookWith("book.djvu", "d.fb2-009373-367300.zip", "12345.djvu");
        Path extracted = Path.of("/data/cache/inpx/3/7/12345.djvu");
        when(archivedBookContentService.resolve(file)).thenReturn(extracted);

        assertThat(locator.locate(BOOK_ID, null)).isEqualTo(extracted);
    }

    @Test
    void anAlternativeFormatIsMaterialisedTheSameWay() {
        BookFileEntity file = bookWith("book.djvu", "d.fb2.zip", "12345.djvu");
        Path extracted = Path.of("/data/cache/inpx/3/7/12345.djvu");
        when(archivedBookContentService.resolve(file)).thenReturn(extracted);

        assertThat(locator.locate(BOOK_ID, "DJVU")).isEqualTo(extracted);
    }

    @Test
    void aPlainFileStillResolvesToItsPlaceInTheLibrary() {
        // resolve() hands a non-archived file straight back, so one path covers both cases and the
        // reader never has to ask which kind of book it has.
        BookFileEntity file = bookWith("scan.djvu", null, null);
        when(archivedBookContentService.resolve(file)).thenReturn(file.getFullFilePath());

        assertThat(locator.locate(BOOK_ID, null))
                .isEqualTo(Path.of("/books/fb2.Flibusta.Net/scan.djvu"));
    }

    @Test
    void anUnknownBookIsRejected() {
        when(bookRepository.findByIdForStreaming(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locator.locate(99L, null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aRequestedFormatTheBookDoesNotHaveIsRejected() {
        bookWith("scan.djvu", null, null);

        assertThatThrownBy(() -> locator.locate(BOOK_ID, "EPUB"))
                .hasMessageContaining("No file of type EPUB");
    }
}
