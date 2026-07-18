package org.booklore.service.book;

import org.booklore.mapper.v2.BookMapperV2;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.Shelf;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.restriction.ContentRestrictionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookQueryServiceTest {

    @Test
    void getAllBooks_usesBoundedLegacyCatalogPage() {
        BookRepository bookRepository = mock(BookRepository.class);
        BookMapperV2 bookMapperV2 = mock(BookMapperV2.class);
        BookQueryService bookQueryService = new BookQueryService(bookRepository, bookMapperV2, null, null);
        BookEntity bookEntity = BookEntity.builder().id(1L).build();
        when(bookRepository.findAllWithMetadataPage(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bookEntity)));
        when(bookMapperV2.toDTO(bookEntity)).thenReturn(Book.builder().id(1L).build());

        List<Book> result = bookQueryService.getAllBooks(false, true);

        assertThat(result).extracting(Book::getId).containsExactly(1L);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(bookRepository).findAllWithMetadataPage(pageableCaptor.capture());
        assertLegacyCatalogCap(pageableCaptor.getValue());
    }

    @Test
    void getAllBooksByLibraryIds_usesBoundedLegacyCatalogPageBeforeRestrictions() {
        BookRepository bookRepository = mock(BookRepository.class);
        BookMapperV2 bookMapperV2 = mock(BookMapperV2.class);
        ContentRestrictionService contentRestrictionService = mock(ContentRestrictionService.class);
        BookQueryService bookQueryService = new BookQueryService(bookRepository, bookMapperV2, contentRestrictionService, null);
        BookEntity bookEntity = BookEntity.builder().id(1L).build();
        Set<Long> libraryIds = Set.of(10L);
        when(bookRepository.findAllWithMetadataByLibraryIdsPage(eq(libraryIds), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bookEntity)));
        when(contentRestrictionService.applyRestrictions(List.of(bookEntity), 7L)).thenReturn(List.of(bookEntity));
        when(bookMapperV2.toDTO(bookEntity)).thenReturn(Book.builder().id(1L).build());

        List<Book> result = bookQueryService.getAllBooksByLibraryIds(libraryIds, false, true, 7L);

        assertThat(result).extracting(Book::getId).containsExactly(1L);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(bookRepository).findAllWithMetadataByLibraryIdsPage(eq(libraryIds), pageableCaptor.capture());
        assertLegacyCatalogCap(pageableCaptor.getValue());
    }

    @Test
    void mapEntitiesToDto_keepsPublicShelvesForUser() {
        BookMapperV2 bookMapperV2 = mock(BookMapperV2.class);
        BookQueryService bookQueryService = new BookQueryService(null, bookMapperV2, null, null);
        BookEntity bookEntity = BookEntity.builder().id(1L).build();
        Shelf ownShelf = Shelf.builder().id(1L).userId(1L).publicShelf(false).build();
        Shelf otherPrivateShelf = Shelf.builder().id(2L).userId(2L).publicShelf(false).build();
        Shelf otherPublicShelf = Shelf.builder().id(3L).userId(2L).publicShelf(true).build();
        when(bookMapperV2.toDTO(bookEntity)).thenReturn(Book.builder()
                .shelves(Set.of(ownShelf, otherPrivateShelf, otherPublicShelf))
                .build());

        List<Book> result = bookQueryService.mapEntitiesToDto(List.of(bookEntity), true, 1L);

        assertThat(result.getFirst().getShelves())
                .containsExactlyInAnyOrder(ownShelf, otherPublicShelf);
    }

    private void assertLegacyCatalogCap(Pageable pageable) {
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(BookQueryService.MAX_LEGACY_FULL_CATALOG_BOOKS);
        Sort.Order idOrder = pageable.getSort().getOrderFor("id");
        assertThat(idOrder).isNotNull();
        assertThat(idOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
    }
}
