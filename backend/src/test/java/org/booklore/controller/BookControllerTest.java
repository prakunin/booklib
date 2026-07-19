package org.booklore.controller;

import org.booklore.app.service.AppBookService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.Book;
import org.booklore.service.book.BookFileAttachmentService;
import org.booklore.service.book.BookService;
import org.booklore.service.book.BookUpdateService;
import org.booklore.service.book.DuplicateDetectionService;
import org.booklore.service.book.PhysicalBookService;
import org.booklore.service.browse.BookBrowseService;
import org.booklore.service.metadata.BookMetadataService;
import org.booklore.service.progress.ReadingProgressService;
import org.booklore.service.recommender.BookRecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookControllerTest {

    private final BookService bookService = mock(BookService.class);
    private final AppBookService appBookService = mock(AppBookService.class);
    private BookController controller;

    @BeforeEach
    void setUp() {
        controller = new BookController(
                bookService,
                appBookService,
                mock(BookBrowseService.class),
                mock(BookUpdateService.class),
                mock(BookRecommendationService.class),
                mock(BookFileAttachmentService.class),
                mock(BookMetadataService.class),
                mock(ReadingProgressService.class),
                mock(PhysicalBookService.class),
                mock(DuplicateDetectionService.class));
    }

    @Test
    void rejectsFlatCatalogBeforeMaterializingLargeLibrary() {
        when(appBookService.getCatalogBookCount()).thenReturn(10_001L);

        assertThatThrownBy(() -> controller.getBooks(false, true))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("flat books endpoint is disabled");
        verify(bookService, never()).getBookDTOs(false, true);
    }

    @Test
    void allowsFlatCatalogBelowSafetyLimit() {
        List<Book> books = List.of(mock(Book.class));
        when(appBookService.getCatalogBookCount()).thenReturn(10_000L);
        when(bookService.getBookDTOs(false, true)).thenReturn(books);

        assertThat(controller.getBooks(false, true).getBody()).isEqualTo(books);
        verify(bookService).getBookDTOs(false, true);
    }

    @Test
    void bookContentReturnsNotModifiedWhenClientCacheIsFresh() {
        Resource resource = mock(Resource.class);
        when(bookService.getBookContent(42L, null)).thenReturn(ResponseEntity.ok()
                .lastModified(123_000L)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(resource));

        ResponseEntity<Resource> response = controller.getBookContent(
                42L,
                null,
                DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(123_000L).atZone(ZoneOffset.UTC)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getLastModified()).isEqualTo(123_000L);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache, private");
    }
}
