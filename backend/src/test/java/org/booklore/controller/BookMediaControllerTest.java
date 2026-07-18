package org.booklore.controller;

import org.booklore.service.AuthorMetadataService;
import org.booklore.service.book.BookService;
import org.booklore.service.bookdrop.BookDropService;
import org.booklore.service.reader.CbxReaderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookMediaControllerTest {

    private BookService bookService;
    private AuthorMetadataService authorMetadataService;
    private BookMediaController controller;

    @BeforeEach
    void setUp() {
        bookService = mock(BookService.class);
        authorMetadataService = mock(AuthorMetadataService.class);
        controller = new BookMediaController(
                bookService,
                mock(CbxReaderService.class),
                mock(BookDropService.class),
                authorMetadataService);
    }

    @Test
    void bookThumbnailsUseLongLivedImmutableCacheHeaders() {
        Resource thumbnail = new ByteArrayResource(new byte[]{1, 2, 3});
        when(bookService.getBookThumbnailIfPresent(42L)).thenReturn(Optional.of(thumbnail));

        var response = controller.getBookThumbnail(42L);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("max-age=31536000, private, immutable");
    }

    @Test
    void missingBookThumbnailsAreNotCached() {
        when(bookService.getBookThumbnailIfPresent(42L)).thenReturn(Optional.empty());

        var response = controller.getBookThumbnail(42L);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void authorPhotosUseLongLivedImmutableCacheHeadersAndValidators() throws Exception {
        Resource photo = mock(Resource.class);
        when(photo.lastModified()).thenReturn(123_456L);
        when(photo.contentLength()).thenReturn(789L);
        when(authorMetadataService.getAuthorPhoto(42L)).thenReturn(photo);

        var response = controller.getAuthorPhoto(42L);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("max-age=31536000, private, immutable");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"315-1e240\"");
        assertThat(response.getHeaders().getLastModified()).isEqualTo(123_000L);
    }

    @Test
    void missingAuthorPhotosAreNotCached() {
        when(authorMetadataService.getAuthorPhoto(42L)).thenReturn(null);

        var response = controller.getAuthorPhoto(42L);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }
}
