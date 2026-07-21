package org.booklore.app.service;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.repository.projection.BookSearchHitProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppBookQuickSearchServiceTest {

    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private UserContentRestrictionRepository restrictionRepository;

    private AppBookQuickSearchService service;

    @BeforeEach
    void setUp() {
        service = new AppBookQuickSearchService(authenticationService, bookRepository, restrictionRepository);
    }

    @Nested
    class QueryNormalization {

        @Test
        void normalizesCyrillicWordsAndAddsPrefixOperators() {
            assertThat(AppBookQuickSearchService.toBooleanSearchQuery("  Путешествие Мишеля!  "))
                    .isEqualTo("+путешествие* +мишеля*");
        }

        @Test
        void ignoresTokensTooShortForTheDefaultInnoDbFulltextIndex() {
            assertThat(AppBookQuickSearchService.toBooleanSearchQuery("de Li")).isEmpty();
            assertThat(AppBookQuickSearchService.toBooleanSearchQuery("de Монтень"))
                    .isEqualTo("+монтень*");
        }

        @Test
        void ignoresDefaultInnoDbStopwordsInsteadOfMakingThemMandatory() {
            assertThat(AppBookQuickSearchService.toBooleanSearchQuery("The Hobbit"))
                    .isEqualTo("+hobbit*");
        }
    }

    @Test
    void returnsAdminResultsInFulltextRankOrderWithoutCounting() {
        mockUser(true, Set.of());
        BookSearchHitProjection second = hit(2L);
        BookSearchHitProjection first = hit(1L);
        when(bookRepository.searchBookIds("+dune*", false, List.of(-1L), 50, 0))
                .thenReturn(List.of(second, first));
        when(bookRepository.findAllForSummaryByIds(anyCollection()))
                .thenReturn(List.of(book(1L, "Dune"), book(2L, "Dune Messiah")));

        var result = service.search("Dune", 50);

        assertThat(result).extracting(r -> r.id()).containsExactly(2L, 1L);
        assertThat(result.getFirst().authors()).containsExactly("Frank Herbert");
        assertThat(result.getFirst().publishedDate()).isEqualTo(LocalDate.of(1969, Month.JANUARY, 1));
        verify(bookRepository, never()).count(any(Specification.class));
        verify(restrictionRepository, never()).findByUserId(any());
    }

    @Test
    void returnsImmediatelyWhenUserHasNoAccessibleLibraries() {
        mockUser(false, Set.of());

        assertThat(service.search("Dune", 50)).isEmpty();

        verify(bookRepository, never()).searchBookIds(anyString(), anyBoolean(), anyCollection(), anyInt(), anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test
    void removesRestrictedCandidatesBeforeHydration() {
        mockUser(false, Set.of(9L));
        UserContentRestrictionEntity restriction = UserContentRestrictionEntity.builder()
                .restrictionType(ContentRestrictionType.CATEGORY)
                .mode(ContentRestrictionMode.EXCLUDE)
                .value("Adult")
                .build();
        when(restrictionRepository.findByUserId(42L)).thenReturn(List.of(restriction));
        BookSearchHitProjection first = hit(1L);
        BookSearchHitProjection second = hit(2L);
        when(bookRepository.searchBookIds("+dune*", true, Set.of(9L), 2_000, 0))
                .thenReturn(List.of(first, second));
        when(bookRepository.findAll(any(Specification.class))).thenReturn(List.of(book(2L, "Allowed")));
        when(bookRepository.findAllForSummaryByIds(anyCollection())).thenReturn(List.of(book(2L, "Allowed")));

        var result = service.search("Dune", 50);

        assertThat(result).extracting(r -> r.id()).containsExactly(2L);
    }

    @Test
    void doesNotBuildRestrictionQueryWhenFulltextSearchHasNoCandidates() {
        mockUser(false, Set.of(9L));
        UserContentRestrictionEntity restriction = UserContentRestrictionEntity.builder()
                .restrictionType(ContentRestrictionType.CATEGORY)
                .mode(ContentRestrictionMode.EXCLUDE)
                .value("Adult")
                .build();
        when(restrictionRepository.findByUserId(42L)).thenReturn(List.of(restriction));
        when(bookRepository.searchBookIds("+dune*", true, Set.of(9L), 2_000, 0)).thenReturn(List.of());

        assertThat(service.search("Dune", 50)).isEmpty();

        verify(bookRepository, never()).findAll(any(Specification.class));
        verify(bookRepository, never()).findAllForSummaryByIds(anyCollection());
    }

    private void mockUser(boolean admin, Set<Long> libraryIds) {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(admin);
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder()
                .id(42L)
                .permissions(permissions)
                .assignedLibraries(libraryIds.stream().map(id -> Library.builder().id(id).build()).toList())
                .build());
    }

    private BookSearchHitProjection hit(long bookId) {
        BookSearchHitProjection hit = mock(BookSearchHitProjection.class);
        when(hit.getBookId()).thenReturn(bookId);
        return hit;
    }

    private BookEntity book(long id, String title) {
        AuthorEntity author = AuthorEntity.builder().name("Frank Herbert").build();
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .bookId(id)
                .title(title)
                .authors(List.of(author))
                .publishedDate(LocalDate.of(1969, Month.JANUARY, 1))
                .build();
        BookFileEntity file = BookFileEntity.builder()
                .fileName(title + ".epub")
                .bookType(BookFileType.EPUB)
                .build();
        return BookEntity.builder().id(id).metadata(metadata).bookFiles(List.of(file)).build();
    }
}
