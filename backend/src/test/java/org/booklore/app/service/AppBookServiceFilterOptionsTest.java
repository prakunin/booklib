package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.app.dto.BookListRequest;
import org.booklore.app.dto.AppFilterOptions;
import org.booklore.app.mapper.AppBookMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserBookFileProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.service.browse.BookSortRegistry;
import org.booklore.service.opds.MagicShelfBookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import jakarta.persistence.criteria.Predicate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppBookServiceFilterOptionsTest {

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
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private AppBookService service;

    private final Long userId = 1L;

    @BeforeEach
    void setUp() {
        service = new AppBookService(
                bookRepository, userBookProgressRepository, userBookFileProgressRepository,
                shelfRepository, authenticationService, mobileBookMapper,
                appBookProgressService, magicShelfBookService, entityManager, restrictionRepository, bookSortRegistry, eventPublisher,
                new CatalogSummaryCache(), new FilterOptionsCache()
        );
    }

    // -------------------------------------------------------------------------
    // Global (no scoping params)
    // -------------------------------------------------------------------------

    @Test
    void getFilterOptions_noParams_returnsGlobalOptions() {
        mockAdminUser();
        mockJpqlQueries();

        AppFilterOptions result = service.getFilterOptions(null, null, null);

        assertNotNull(result);
        assertNotNull(result.authors());
        assertNotNull(result.languages());
        assertNotNull(result.fileTypes());
        assertNotNull(result.readStatuses());
    }

    // -------------------------------------------------------------------------
    // Library scoping
    // -------------------------------------------------------------------------

    @Test
    void getFilterOptions_withLibraryId_admin_succeeds() {
        mockAdminUser();
        mockJpqlQueries();

        AppFilterOptions result = service.getFilterOptions(5L, null, null);

        assertNotNull(result);
    }

    @Test
    void getFilterOptions_withLibraryId_nonAdminWithAccess_succeeds() {
        mockNonAdminUser(Set.of(5L, 10L));
        mockJpqlQueries();

        AppFilterOptions result = service.getFilterOptions(5L, null, null);

        assertNotNull(result);
    }

    @Test
    void getFilterOptions_withLibraryId_nonAdminNoAccess_throwsForbidden() {
        mockNonAdminUser(Set.of(10L));

        assertThrows(APIException.class, () -> service.getFilterOptions(5L, null, null));
    }

    // -------------------------------------------------------------------------
    // Shelf scoping
    // -------------------------------------------------------------------------

    @Test
    void getFilterOptions_withShelfId_publicShelf_succeeds() {
        mockAdminUser();
        ShelfEntity shelf = ShelfEntity.builder().id(10L).isPublic(true)
                .user(BookLoreUserEntity.builder().id(99L).build()).build();
        when(shelfRepository.findById(10L)).thenReturn(Optional.of(shelf));
        mockJpqlQueries();

        AppFilterOptions result = service.getFilterOptions(null, 10L, null);

        assertNotNull(result);
    }

    @Test
    void getFilterOptions_withShelfId_ownPrivateShelf_succeeds() {
        mockAdminUser();
        ShelfEntity shelf = ShelfEntity.builder().id(10L).isPublic(false)
                .user(BookLoreUserEntity.builder().id(userId).build()).build();
        when(shelfRepository.findById(10L)).thenReturn(Optional.of(shelf));
        mockJpqlQueries();

        AppFilterOptions result = service.getFilterOptions(null, 10L, null);

        assertNotNull(result);
    }

    @Test
    void getFilterOptions_withShelfId_otherPrivateShelf_throwsForbidden() {
        mockAdminUser();
        ShelfEntity shelf = ShelfEntity.builder().id(10L).isPublic(false)
                .user(BookLoreUserEntity.builder().id(99L).build()).build();
        when(shelfRepository.findById(10L)).thenReturn(Optional.of(shelf));

        assertThrows(APIException.class, () -> service.getFilterOptions(null, 10L, null));
    }

    @Test
    void getFilterOptions_withShelfId_notFound_throwsException() {
        mockAdminUser();
        when(shelfRepository.findById(10L)).thenReturn(Optional.empty());

        assertThrows(APIException.class, () -> service.getFilterOptions(null, 10L, null));
    }

    // -------------------------------------------------------------------------
    // Magic shelf scoping
    // -------------------------------------------------------------------------

    @Test
    void getFilterOptions_withMagicShelfId_doesNotMaterializeMatchingBookIds() {
        mockAdminUser();
        mockMagicShelfSpec(7L, Collections.emptyList());
        mockJpqlQueries();

        AppFilterOptions result = service.getFilterOptions(null, null, 7L);

        assertNotNull(result);
        verify(magicShelfBookService).toSpecification(eq(userId), eq(7L));
        verify(entityManager, never()).createQuery(any(CriteriaQuery.class));
    }

    @Test
    void getFilterOptions_withMagicShelfId_withBooks_returnsFilteredOptions() {
        mockAdminUser();
        mockMagicShelfSpec(7L, List.of(100L, 200L));
        mockJpqlQueries();

        AppFilterOptions result = service.getFilterOptions(null, null, 7L);

        assertNotNull(result);
        verify(magicShelfBookService).toSpecification(eq(userId), eq(7L));
    }

    @Test
    void getFilterOptions_withMagicShelfId_serviceThrows_propagatesException() {
        mockAdminUser();
        when(magicShelfBookService.toSpecification(eq(userId), eq(7L)))
                .thenThrow(new RuntimeException("Magic shelf not found"));

        assertThrows(RuntimeException.class, () -> service.getFilterOptions(null, null, 7L));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getAllBookIds_capsCriteriaQueryResults() {
        mockAdminUser();

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<Long> cq = mock(CriteriaQuery.class);
        Root<BookEntity> root = mock(Root.class);
        Path<Object> path = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        jakarta.persistence.criteria.Order order = mock(jakarta.persistence.criteria.Order.class);
        TypedQuery<Long> typedQuery = mock(TypedQuery.class);

        when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        when(cb.createQuery(Long.class)).thenReturn(cq);
        when(cq.from(BookEntity.class)).thenReturn(root);
        when(root.get(anyString())).thenReturn((Path) path);
        when(cq.select(any())).thenReturn(cq);
        when(cq.distinct(true)).thenReturn(cq);
        when(cb.asc(any())).thenReturn(order);
        when(cq.orderBy(order)).thenReturn(cq);
        when(cb.conjunction()).thenReturn(predicate);
        when(cb.isNull(any())).thenReturn(predicate);
        when(cb.equal(any(), any())).thenReturn(predicate);
        when(cb.isNotEmpty(any())).thenReturn(predicate);
        when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
        when(cq.where(predicate)).thenReturn(cq);
        when(entityManager.createQuery(cq)).thenReturn(typedQuery);
        when(typedQuery.setMaxResults(anyInt())).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of(10L, 20L));

        List<Long> result = service.getAllBookIds(emptyBookListRequest());

        assertEquals(List.of(10L, 20L), result);
        verify(cq).distinct(true);
        verify(cq).orderBy(order);
        verify(typedQuery).setMaxResults(AppBookService.MAX_SELECT_ALL_BOOK_IDS);
    }

    // -------------------------------------------------------------------------
    // Caching
    // -------------------------------------------------------------------------

    @Test
    void getFilterOptions_secondIdenticalCall_isServedFromCache() {
        mockAdminUser();
        mockJpqlQueries();

        service.getFilterOptions(null, null, null);
        int invocationsAfterFirstCall = mockingDetails(entityManager).getInvocations().size();
        service.getFilterOptions(null, null, null);

        assertEquals(invocationsAfterFirstCall, mockingDetails(entityManager).getInvocations().size());
    }

    // -------------------------------------------------------------------------
    // Shell-book visibility clause
    // -------------------------------------------------------------------------

    @Test
    void getFilterOptions_noShellBooks_dropsFileExistencePredicate() {
        mockAdminUser();
        mockJpqlQueries();

        service.getFilterOptions(null, null, null);

        List<String> jpql = capturedTupleJpql();
        assertFalse(jpql.isEmpty());
        assertTrue(jpql.stream().noneMatch(q -> q.contains("bookFiles IS NOT EMPTY")),
                "no facet query should pay for the file-existence predicate when there are no shell books");
    }

    @Test
    void getFilterOptions_withShellBooks_excludesThemViaNotInClause() {
        mockAdminUser();
        mockJpqlQueries();
        mockShellBookIds(List.of(43L, 42L));

        service.getFilterOptions(null, null, null);

        List<String> jpql = capturedTupleJpql();
        assertFalse(jpql.isEmpty());
        assertTrue(jpql.stream().allMatch(q -> q.contains("b.id NOT IN (42, 43)")),
                "every facet query must exclude shell books when they exist");
        assertTrue(jpql.stream().noneMatch(q -> q.contains("bookFiles IS NOT EMPTY")));
    }

    @Test
    void getFilterOptions_hugeShellSet_fallsBackToFileExistencePredicate() {
        mockAdminUser();
        mockJpqlQueries();
        mockShellBookIds(java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toList());

        service.getFilterOptions(null, null, null);

        List<String> jpql = capturedTupleJpql();
        assertFalse(jpql.isEmpty());
        assertTrue(jpql.stream().allMatch(q -> q.contains("bookFiles IS NOT EMPTY")),
                "an oversized shell set must fall back to the legacy predicate instead of a huge IN list");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BookListRequest emptyBookListRequest() {
        return new BookListRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null
        );
    }

    private List<String> capturedTupleJpql() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(entityManager, atLeastOnce()).createQuery(captor.capture(), eq(Tuple.class));
        return captor.getAllValues();
    }

    private void mockShellBookIds(List<Long> ids) {
        TypedQuery<Long> shellQuery = mock(TypedQuery.class);
        when(shellQuery.getResultList()).thenReturn(ids);
        when(entityManager.createQuery(
                argThat((String s) -> s != null && s.contains("bookFiles IS EMPTY")), eq(Long.class)))
                .thenReturn(shellQuery);
    }

    private void mockAdminUser() {
        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        BookLoreUser user = BookLoreUser.builder()
                .id(userId)
                .permissions(permissions)
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
    }

    private void mockNonAdminUser(Set<Long> libraryIds) {
        List<Library> assignedLibraries = libraryIds.stream()
                .map(id -> Library.builder().id(id).build())
                .toList();
        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(false);
        BookLoreUser user = BookLoreUser.builder()
                .id(userId)
                .permissions(permissions)
                .assignedLibraries(assignedLibraries)
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
    }

    @SuppressWarnings("unchecked")
    private void mockMagicShelfSpec(Long magicShelfId, List<Long> bookIds) {
        Specification<BookEntity> mockSpec = mock(Specification.class);
        when(magicShelfBookService.toSpecification(eq(userId), eq(magicShelfId)))
                .thenReturn(mockSpec);

        Predicate mockPredicate = mock(Predicate.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<Long> cq = mock(CriteriaQuery.class);
        Root<BookEntity> root = mock(Root.class);
        Path<Long> idPath = mock(Path.class);
        TypedQuery<Long> typedQuery = mock(TypedQuery.class);

        when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        when(cb.createQuery(Long.class)).thenReturn(cq);
        when(cq.from(BookEntity.class)).thenReturn(root);
        when(root.get("id")).thenReturn((Path) idPath);
        when(cq.select(idPath)).thenReturn(cq);
        when(mockSpec.toPredicate(root, cq, cb)).thenReturn(mockPredicate);
        when(cq.where(mockPredicate)).thenReturn(cq);
        when(entityManager.createQuery(cq)).thenReturn(typedQuery);
        when(typedQuery.setMaxResults(anyInt())).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(bookIds);
    }

    @SuppressWarnings("unchecked")
    private TypedQuery<Tuple> mockJpqlQueries() {
        TypedQuery<Tuple> tupleQuery = mock(TypedQuery.class);
        when(tupleQuery.setParameter(anyString(), any())).thenReturn(tupleQuery);
        when(tupleQuery.setMaxResults(anyInt())).thenReturn(tupleQuery);
        when(tupleQuery.getResultList()).thenReturn(Collections.emptyList());

        when(entityManager.createQuery(anyString(), eq(Tuple.class)))
                .thenReturn(tupleQuery);

        TypedQuery<Long> longQuery = mock(TypedQuery.class);
        when(longQuery.setParameter(anyString(), any())).thenReturn(longQuery);
        when(longQuery.getSingleResult()).thenReturn(0L);
        when(longQuery.getResultList()).thenReturn(Collections.emptyList());

        when(entityManager.createQuery(anyString(), eq(Long.class)))
                .thenReturn(longQuery);
        return tupleQuery;
    }
}
