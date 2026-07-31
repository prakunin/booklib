package org.booklore.service.reader;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PageImageSourceResolverTest {

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final PageImageSource cbxSource = source(BookFileType.CBX);
    private final PageImageSource djvuSource = source(BookFileType.DJVU);

    private final PageImageSourceResolver resolver =
            new PageImageSourceResolver(bookRepository, List.of(cbxSource, djvuSource));

    private static PageImageSource source(BookFileType type) {
        PageImageSource source = mock(PageImageSource.class);
        when(source.supportedType()).thenReturn(type);
        return source;
    }

    private void bookHasPrimaryType(long bookId, BookFileType type) {
        BookFileEntity primaryFile = mock(BookFileEntity.class);
        when(primaryFile.getBookType()).thenReturn(type);
        BookEntity book = mock(BookEntity.class);
        when(book.getPrimaryBookFile()).thenReturn(primaryFile);
        when(bookRepository.findByIdForStreaming(bookId)).thenReturn(Optional.of(book));
    }

    @Test
    void anExplicitBookTypeDecidesTheSourceWithoutLoadingTheBook() {
        assertThat(resolver.resolve(1L, "DJVU")).isSameAs(djvuSource);
        assertThat(resolver.resolve(1L, "CBX")).isSameAs(cbxSource);

        // The alternative-format parameter is the answer; reading the book would only risk
        // disagreeing with it.
        verifyNoInteractions(bookRepository);
    }

    @Test
    void withoutOneThePrimaryFileDecides() {
        bookHasPrimaryType(7L, BookFileType.DJVU);

        assertThat(resolver.resolve(7L, null)).isSameAs(djvuSource);
    }

    @Test
    void aFormatThatIsNotReadAsPagesIsRejected() {
        bookHasPrimaryType(8L, BookFileType.EPUB);

        assertThatThrownBy(() -> resolver.resolve(8L, null))
                .hasMessageContaining("not read as page images");
    }

    @Test
    void anUnknownBookTypeNameIsRejected() {
        assertThatThrownBy(() -> resolver.resolve(9L, "NOT_A_TYPE"))
                .hasMessageContaining("Invalid book type");
    }

    @Test
    void aBookWithoutAPrimaryFileIsRejected() {
        BookEntity book = mock(BookEntity.class);
        when(book.getPrimaryBookFile()).thenReturn(null);
        when(bookRepository.findByIdForStreaming(10L)).thenReturn(Optional.of(book));

        assertThatThrownBy(() -> resolver.resolve(10L, null))
                .hasMessageContaining("no readable primary file");
    }
}
