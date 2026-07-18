package org.booklore.controller;

import org.booklore.config.security.annotation.CheckLibraryAccess;
import org.booklore.exception.APIException;
import org.booklore.model.dto.Book;
import org.booklore.service.library.LibraryHealthService;
import org.booklore.service.library.LibraryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every {libraryId} endpoint in this controller must carry @CheckLibraryAccess, because
 * authorization here is per-user library assignment, not the global canManageLibrary()
 * boolean. cancelScan previously only carried @PreAuthorize, which let a non-admin holding
 * canManageLibrary() cancel the scan of a library they are not assigned to.
 */
class LibraryControllerTest {

    private final LibraryService libraryService = mock(LibraryService.class);
    private LibraryController controller;

    @BeforeEach
    void setUp() {
        controller = new LibraryController(libraryService, mock(LibraryHealthService.class));
    }

    @Test
    void cancelScan_enforcesPerLibraryAccessLikeTheOtherLibraryIdEndpoints() throws NoSuchMethodException {
        Method cancelScan = LibraryController.class.getMethod("cancelScan", long.class);

        CheckLibraryAccess annotation = cancelScan.getAnnotation(CheckLibraryAccess.class);

        assertThat(annotation)
                .as("cancelScan must be annotated with @CheckLibraryAccess, exactly like rescanLibrary")
                .isNotNull();
        assertThat(annotation.libraryIdParam()).isEqualTo("libraryId");
    }

    @Test
    void getBooks_rejectsLargeLibraryBeforeMaterializingCatalog() {
        when(libraryService.getBookCount(1L)).thenReturn(10_001L);

        assertThatThrownBy(() -> controller.getBooks(1L))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("library books endpoint is disabled");
        verify(libraryService, never()).getBooks(1L);
    }

    @Test
    void getBooks_allowsLibraryAtSafetyLimit() {
        List<Book> books = List.of(mock(Book.class));
        when(libraryService.getBookCount(1L)).thenReturn(10_000L);
        when(libraryService.getBooks(1L)).thenReturn(books);

        assertThat(controller.getBooks(1L).getBody()).isEqualTo(books);
        verify(libraryService).getBooks(1L);
    }
}
