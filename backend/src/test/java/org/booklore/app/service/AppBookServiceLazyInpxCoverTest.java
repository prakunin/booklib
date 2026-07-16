package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import org.booklore.app.dto.AppBookSummary;
import org.booklore.app.mapper.AppBookMapper;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserBookFileProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.service.book.BookService;
import org.booklore.service.browse.BookSortRegistry;
import org.booklore.service.inpx.InpxCoverGenerationRequestedEvent;
import org.booklore.service.opds.MagicShelfBookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppBookServiceLazyInpxCoverTest {

    @Mock private BookRepository bookRepository;
    @Mock private UserBookProgressRepository userBookProgressRepository;
    @Mock private UserBookFileProgressRepository userBookFileProgressRepository;
    @Mock private ShelfRepository shelfRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private AppBookMapper mobileBookMapper;
    @Mock private BookService bookService;
    @Mock private MagicShelfBookService magicShelfBookService;
    @Mock private EntityManager entityManager;
    @Mock private UserContentRestrictionRepository restrictionRepository;
    @Mock private BookSortRegistry bookSortRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AppBookService service;

    @BeforeEach
    void setUp() {
        service = new AppBookService(
                bookRepository, userBookProgressRepository, userBookFileProgressRepository,
                shelfRepository, authenticationService, mobileBookMapper, bookService,
                magicShelfBookService, entityManager, restrictionRepository, bookSortRegistry,
                eventPublisher, new CatalogSummaryCache());
    }

    @Test
    void searchPublishesOnlyReturnedInpxBooksWithoutCovers() {
        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        BookLoreUser user = BookLoreUser.builder()
                .id(7L)
                .username("alice")
                .permissions(permissions)
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        BookEntity missingInpxCover = book(1L, LibrarySourceType.INPX, null);
        BookEntity existingInpxCover = book(2L, LibrarySourceType.INPX, "hash");
        BookEntity filesystemBook = book(3L, LibrarySourceType.FILESYSTEM, null);
        when(bookRepository.findAll(
                any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(missingInpxCover, existingInpxCover, filesystemBook)));
        when(userBookProgressRepository.findByUserIdAndBookIdIn(eq(7L), anySet()))
                .thenReturn(List.of());
        when(mobileBookMapper.toSummary(any(), isNull())).thenReturn(mock(AppBookSummary.class));

        service.searchBooks("interesting", 0, 20);

        ArgumentCaptor<InpxCoverGenerationRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(InpxCoverGenerationRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().bookIds()).containsExactly(1L);
        assertThat(eventCaptor.getValue().username()).isEqualTo("alice");
    }

    private BookEntity book(long id, LibrarySourceType sourceType, String coverHash) {
        return BookEntity.builder()
                .id(id)
                .bookCoverHash(coverHash)
                .library(LibraryEntity.builder().sourceType(sourceType).build())
                .build();
    }
}
