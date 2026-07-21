package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import org.booklore.app.dto.AppBookDetail;
import org.booklore.app.dto.AppBookProgressResponse;
import org.booklore.app.dto.AppBookSummary;
import org.booklore.app.dto.AppPageResponse;
import org.booklore.app.mapper.AppBookMapper;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserBookFileProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.service.browse.BookSortRegistry;
import org.booklore.service.opds.MagicShelfBookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppBookServiceCoreTest {

    @Mock private BookRepository bookRepository;
    @Mock private UserBookProgressRepository userBookProgressRepository;
    @Mock private UserBookFileProgressRepository userBookFileProgressRepository;
    @Mock private ShelfRepository shelfRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private AppBookMapper mobileBookMapper;
    @Mock private AppBookProgressService appBookProgressService;
    @Mock private MagicShelfBookService magicShelfBookService;
    @Mock private EntityManager entityManager;
    @Mock private UserContentRestrictionRepository restrictionRepository;
    @Mock private BookSortRegistry bookSortRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AppBookService service;

    private static final Long USER_ID = 7L;
    private static final Long BOOK_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new AppBookService(
                bookRepository, userBookProgressRepository, userBookFileProgressRepository,
                shelfRepository, authenticationService, mobileBookMapper, appBookProgressService,
                magicShelfBookService, entityManager, restrictionRepository,
                new AppContentRestrictionQueryService(restrictionRepository), bookSortRegistry,
                eventPublisher, new CatalogSummaryCache(), new FilterOptionsCache(), new ShellBookIdsCache());
    }

    private BookLoreUser adminUser() {
        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        return BookLoreUser.builder().id(USER_ID).username("alice").permissions(permissions).build();
    }

    @Nested
    @DisplayName("getBookDetail")
    class GetBookDetailTests {

        @Test
        @DisplayName("returns mapped detail when the book exists and is accessible")
        void returnsDetailForAccessibleBook() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            BookEntity book = BookEntity.builder().id(BOOK_ID)
                    .library(LibraryEntity.builder().id(1L).build()).build();
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(book));
            when(bookRepository.exists(any(Specification.class))).thenReturn(true);
            when(userBookProgressRepository.findByUserIdAndBookId(USER_ID, BOOK_ID)).thenReturn(Optional.empty());
            when(userBookFileProgressRepository.findMostRecentAudiobookProgressByUserIdAndBookId(USER_ID, BOOK_ID))
                    .thenReturn(Optional.empty());
            AppBookDetail expected = mock(AppBookDetail.class);
            when(mobileBookMapper.toDetail(book, null, null)).thenReturn(expected);

            AppBookDetail result = service.getBookDetail(BOOK_ID);

            assertThat(result).isSameAs(expected);
        }

        @Test
        @DisplayName("throws BOOK_NOT_FOUND when the book doesn't exist")
        void throwsWhenBookMissing() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBookDetail(BOOK_ID)).isInstanceOf(APIException.class);
        }

        @Test
        @DisplayName("throws FORBIDDEN when the book's content is restricted for this user")
        void throwsWhenContentRestricted() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            BookEntity book = BookEntity.builder().id(BOOK_ID)
                    .library(LibraryEntity.builder().id(1L).build()).build();
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(book));
            when(bookRepository.exists(any(Specification.class))).thenReturn(false);

            assertThatThrownBy(() -> service.getBookDetail(BOOK_ID)).isInstanceOf(APIException.class);
        }
    }

    @Nested
    @DisplayName("getBookProgress")
    class GetBookProgressTests {

        @Test
        @DisplayName("returns mapped progress response for an accessible book")
        void returnsProgressForAccessibleBook() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            BookEntity book = BookEntity.builder().id(BOOK_ID)
                    .library(LibraryEntity.builder().id(1L).build()).build();
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(book));
            when(bookRepository.exists(any(Specification.class))).thenReturn(true);
            UserBookProgressEntity progress = new UserBookProgressEntity();
            when(userBookProgressRepository.findByUserIdAndBookId(USER_ID, BOOK_ID)).thenReturn(Optional.of(progress));
            when(userBookFileProgressRepository.findMostRecentAudiobookProgressByUserIdAndBookId(USER_ID, BOOK_ID))
                    .thenReturn(Optional.empty());
            AppBookProgressResponse expected = mock(AppBookProgressResponse.class);
            when(mobileBookMapper.toProgressResponse(progress, null)).thenReturn(expected);

            AppBookProgressResponse result = service.getBookProgress(BOOK_ID);

            assertThat(result).isSameAs(expected);
        }
    }

    @Nested
    @DisplayName("progress/status delegation")
    class DelegationTests {

        @Test
        @DisplayName("updateBookProgress delegates to AppBookProgressService")
        void updateBookProgressDelegates() {
            var request = new org.booklore.app.dto.UpdateProgressRequest();

            service.updateBookProgress(BOOK_ID, request);

            verify(appBookProgressService).updateBookProgress(BOOK_ID, request);
        }

        @Test
        @DisplayName("updateReadStatus delegates to AppBookProgressService")
        void updateReadStatusDelegates() {
            service.updateReadStatus(BOOK_ID, ReadStatus.READ);

            verify(appBookProgressService).updateReadStatus(BOOK_ID, ReadStatus.READ);
        }

        @Test
        @DisplayName("updatePersonalRating delegates to AppBookProgressService")
        void updatePersonalRatingDelegates() {
            service.updatePersonalRating(BOOK_ID, 5);

            verify(appBookProgressService).updatePersonalRating(BOOK_ID, 5);
        }
    }

    @Nested
    @DisplayName("searchBooks")
    class SearchBooksTests {

        @Test
        @DisplayName("throws INVALID_QUERY_PARAMETERS when the query is blank")
        void blankQuery_throws() {
            assertThatThrownBy(() -> service.searchBooks("   ", 0, 20)).isInstanceOf(APIException.class);
        }

        @Test
        @DisplayName("throws INVALID_QUERY_PARAMETERS when the query is null")
        void nullQuery_throws() {
            assertThatThrownBy(() -> service.searchBooks(null, 0, 20)).isInstanceOf(APIException.class);
        }
    }

    @Nested
    @DisplayName("getContinueReading / getContinueListening")
    class ContinueReadingListeningTests {

        @Test
        @DisplayName("getContinueReading returns an empty list without enriching when there are no progress entries")
        void continueReading_empty() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            when(userBookProgressRepository.findTopContinueReadingBookIds(eq(USER_ID), any(), any()))
                    .thenReturn(List.of());

            List<AppBookSummary> result = service.getContinueReading(5);

            assertThat(result).isEmpty();
            verify(bookRepository, never()).findAll(any(Specification.class));
        }

        @Test
        @DisplayName("getContinueReading enriches and orders results by the progress-ranked ids")
        void continueReading_returnsOrderedSummaries() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            when(userBookProgressRepository.findTopContinueReadingBookIds(eq(USER_ID), any(), any()))
                    .thenReturn(List.of(2L, 1L));
            when(userBookProgressRepository.findByUserIdAndBookIdIn(eq(USER_ID), anySet())).thenReturn(List.of());
            BookEntity book1 = BookEntity.builder().id(1L).build();
            BookEntity book2 = BookEntity.builder().id(2L).build();
            when(bookRepository.findAll(any(Specification.class))).thenReturn(List.of(book1, book2));
            AppBookSummary summary1 = mock(AppBookSummary.class);
            AppBookSummary summary2 = mock(AppBookSummary.class);
            when(mobileBookMapper.toSummary(book1, null)).thenReturn(summary1);
            when(mobileBookMapper.toSummary(book2, null)).thenReturn(summary2);

            List<AppBookSummary> result = service.getContinueReading(5);

            assertThat(result).containsExactly(summary2, summary1);
        }

        @Test
        @DisplayName("getContinueListening returns an empty list when there are no progress entries")
        void continueListening_empty() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            when(userBookProgressRepository.findTopContinueListeningBookIds(eq(USER_ID), any(), any()))
                    .thenReturn(List.of());

            List<AppBookSummary> result = service.getContinueListening(5);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getRecentlyAdded / getRecentlyScanned")
    class RecentlyAddedScannedTests {

        @Test
        @DisplayName("getRecentlyAdded maps the page content to summaries")
        void recentlyAdded_mapsSummaries() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            BookEntity book = BookEntity.builder().id(BOOK_ID).build();
            when(bookRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(book)));
            when(userBookProgressRepository.findByUserIdAndBookIdIn(eq(USER_ID), anySet())).thenReturn(List.of());
            AppBookSummary summary = mock(AppBookSummary.class);
            when(mobileBookMapper.toSummary(book, null)).thenReturn(summary);

            List<AppBookSummary> result = service.getRecentlyAdded(10);

            assertThat(result).containsExactly(summary);
        }

        @Test
        @DisplayName("getRecentlyScanned maps the page content to summaries")
        void recentlyScanned_mapsSummaries() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            BookEntity book = BookEntity.builder().id(BOOK_ID).build();
            when(bookRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(book)));
            when(userBookProgressRepository.findByUserIdAndBookIdIn(eq(USER_ID), anySet())).thenReturn(List.of());
            AppBookSummary summary = mock(AppBookSummary.class);
            when(mobileBookMapper.toSummary(book, null)).thenReturn(summary);

            List<AppBookSummary> result = service.getRecentlyScanned(10);

            assertThat(result).containsExactly(summary);
        }
    }

    @Nested
    @DisplayName("getRandomBooks")
    class GetRandomBooksTests {

        @Test
        @DisplayName("returns an empty page without querying further when there are no matching books")
        void noBooks_returnsEmptyPage() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            when(bookRepository.count(any(Specification.class))).thenReturn(0L);

            AppPageResponse<AppBookSummary> result = service.getRandomBooks(0, 20, null);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(bookRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("returns a page of summaries when matching books exist")
        void hasBooks_returnsPage() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            when(bookRepository.count(any(Specification.class))).thenReturn(5L);
            BookEntity book = BookEntity.builder().id(BOOK_ID).build();
            when(bookRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(book)));
            when(userBookProgressRepository.findByUserIdAndBookIdIn(eq(USER_ID), anySet())).thenReturn(List.of());
            AppBookSummary summary = mock(AppBookSummary.class);
            when(mobileBookMapper.toSummary(book, null)).thenReturn(summary);

            AppPageResponse<AppBookSummary> result = service.getRandomBooks(0, 20, null);

            assertThat(result.getContent()).containsExactly(summary);
        }
    }

    @Nested
    @DisplayName("getBooksByMagicShelf")
    class GetBooksByMagicShelfTests {

        @Test
        @DisplayName("returns an empty page when the magic shelf has no matching books")
        void emptyShelf_returnsEmptyPage() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            when(magicShelfBookService.getBooksByMagicShelfId(USER_ID, 9L, 0, 20))
                    .thenReturn(new PageImpl<>(List.of()));

            AppPageResponse<AppBookSummary> result = service.getBooksByMagicShelf(9L, 0, 20);

            assertThat(result.getContent()).isEmpty();
            verify(bookRepository, never()).findAllById(any());
        }

        @Test
        @DisplayName("filters out books with no files that aren't physical, preserving shelf order")
        void filtersShellBooksAndPreservesOrder() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            Book shelfBook1 = Book.builder().id(1L).build();
            Book shelfBook2 = Book.builder().id(2L).build();
            when(magicShelfBookService.getBooksByMagicShelfId(USER_ID, 9L, 0, 20))
                    .thenReturn(new PageImpl<>(List.of(shelfBook1, shelfBook2), Pageable.ofSize(20), 2));

            BookEntity entity1 = BookEntity.builder().id(1L).isPhysical(true).build();
            BookEntity shellEntity2 = BookEntity.builder().id(2L).isPhysical(false).build();
            when(bookRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(entity1, shellEntity2));
            when(userBookProgressRepository.findByUserIdAndBookIdIn(eq(USER_ID), anySet())).thenReturn(List.of());
            AppBookSummary summary1 = mock(AppBookSummary.class);
            when(mobileBookMapper.toSummary(entity1, null)).thenReturn(summary1);

            AppPageResponse<AppBookSummary> result = service.getBooksByMagicShelf(9L, 0, 20);

            assertThat(result.getContent()).containsExactly(summary1);
            assertThat(result.getTotalElements()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("getBookSummariesByIds")
    class GetBookSummariesByIdsTests {

        @Test
        @DisplayName("returns an empty list without touching the repository when bookIds is null")
        void nullBookIds_returnsEmpty() {
            List<AppBookSummary> result = service.getBookSummariesByIds(null);

            assertThat(result).isEmpty();
            verify(bookRepository, never()).findAll(any(Specification.class));
        }

        @Test
        @DisplayName("returns an empty list without touching the repository when bookIds is empty")
        void emptyBookIds_returnsEmpty() {
            List<AppBookSummary> result = service.getBookSummariesByIds(Set.of());

            assertThat(result).isEmpty();
            verify(bookRepository, never()).findAll(any(Specification.class));
        }

        @Test
        @DisplayName("maps matching books to summaries")
        void nonEmptyBookIds_returnsSummaries() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());
            BookEntity book = BookEntity.builder().id(BOOK_ID).build();
            when(bookRepository.findAll(any(Specification.class))).thenReturn(List.of(book));
            when(userBookProgressRepository.findByUserIdAndBookIdIn(eq(USER_ID), anySet())).thenReturn(List.of());
            AppBookSummary summary = mock(AppBookSummary.class);
            when(mobileBookMapper.toSummary(book, null)).thenReturn(summary);

            List<AppBookSummary> result = service.getBookSummariesByIds(Set.of(BOOK_ID));

            assertThat(result).containsExactly(summary);
        }
    }

    @Nested
    @DisplayName("findExistingIsbns")
    class FindExistingIsbnsTests {

        @Test
        @DisplayName("returns an empty set without querying when requestedIsbns is null")
        void nullIsbns_returnsEmpty() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());

            Set<String> result = service.findExistingIsbns(1L, null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns an empty set without querying when requestedIsbns is empty")
        void emptyIsbns_returnsEmpty() {
            when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser());

            Set<String> result = service.findExistingIsbns(1L, Set.of());

            assertThat(result).isEmpty();
        }
    }
}
