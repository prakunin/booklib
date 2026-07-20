package org.booklore.service;

import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.*;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.repository.BookOpdsRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.library.LibraryService;
import org.booklore.service.opds.OpdsBookService;
import org.booklore.service.restriction.ContentRestrictionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OpdsBookServiceTest {

    @Mock private BookOpdsRepository bookOpdsRepository;
    @Mock private BookRepository bookRepository;
    @Mock private BookMapper bookMapper;
    @Mock private UserRepository userRepository;
    @Mock private BookLoreUserTransformer bookLoreUserTransformer;
    @Mock private ShelfRepository shelfRepository;
    @Mock private LibraryService libraryService;
    @Mock private ContentRestrictionService contentRestrictionService;

    @InjectMocks private OpdsBookService opdsBookService;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(contentRestrictionService.applyRestrictions(anyList(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    private OpdsUserDetails legacyUserDetails() {
        OpdsUserV2 v2 = OpdsUserV2.builder().userId(999L).username("legacy").build();
        return new OpdsUserDetails(v2);
    }

    private OpdsUserDetails v2UserDetails(Long userId, boolean isAdmin, Set<Long> libraryIds) {
        OpdsUserV2 v2 = OpdsUserV2.builder().userId(userId).username("v2user").build();
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);

        when(userRepository.findByIdWithDetails(userId)).thenReturn(Optional.of(entity));
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(isAdmin);
        when(perms.isCanAccessOpds()).thenReturn(true);

        List<Library> libraries = new ArrayList<>();
        for (Long id : libraryIds) {
            libraries.add(Library.builder().id(id).name("Lib" + id).watch(false).build());
        }
        when(user.getAssignedLibraries()).thenReturn(libraries);

        return new OpdsUserDetails(v2);
    }

    @Test
    void getAccessibleLibraries_returnsEmptyList_whenUserIdIsNull() {
        List<Library> result = opdsBookService.getAccessibleLibraries(null);

        assertThat(result).isEmpty();
    }

    @Test
    void getAccessibleLibraries_returnsAssignedLibraries_forNonAdmin() {
        OpdsUserDetails details = v2UserDetails(1L, false, Set.of(2L, 3L));
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));
        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        List<Library> assigned = List.of(Library.builder().id(2L).watch(false).build());
        when(user.getAssignedLibraries()).thenReturn(assigned);

        List<Library> result = opdsBookService.getAccessibleLibraries(details.getOpdsUserV2().getUserId());

        assertThat(result).isEqualTo(assigned);
    }

    @Test
    void getAccessibleLibraries_returnsAllLibraries_forAdmin() {
        OpdsUserDetails details = v2UserDetails(1L, true, Set.of(1L));
        List<Library> allLibs = List.of(Library.builder().id(1L).watch(false).build());
        when(libraryService.getAllLibraries()).thenReturn(allLibs);

        List<Library> result = opdsBookService.getAccessibleLibraries(details.getOpdsUserV2().getUserId());

        assertThat(result).isEqualTo(allLibs);
    }

    @Test
    void getBooksPage_legacyUser_delegatesToLegacyMethod() {
        OpdsUserDetails details = legacyUserDetails();
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(999L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));
        when(user.getId()).thenReturn(999L);

        // Mock shelf access
        ShelfEntity shelf = mock(ShelfEntity.class);
        BookLoreUserEntity shelfUser = mock(BookLoreUserEntity.class);
        when(shelfUser.getId()).thenReturn(999L);
        when(shelf.getUser()).thenReturn(shelfUser);
        when(shelfRepository.findByIdWithUser(2L)).thenReturn(Optional.of(shelf));

        when(bookOpdsRepository.findBookIds(any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findRecentBookIds(any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByLibraryIds(anySet(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByShelfId(anyLong(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByShelfIds(anySet(), anySet(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByMetadataSearch(anyString(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByMetadataSearchAndLibraryIds(anyString(), anySet(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByMetadataSearchAndShelfIds(anyString(), anySet(), anySet(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findAllWithMetadataByIds(anyList())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(anyList(), anySet())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithMetadataByIdsAndShelfId(anyList(), anyLong())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithMetadataByIdsAndShelfIds(anyList(), anySet(), anySet())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithFullMetadataByIds(anyList())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithFullMetadataByIdsAndLibraryIds(anyList(), anySet())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithFullMetadataByIdsAndShelfIds(anyList(), anySet(), anySet())).thenReturn(List.of());

        Page<Book> result = opdsBookService.getBooksPage(details.getOpdsUserV2().getUserId(), "q", 1L, Set.of(2L), 0, 10);

        assertThat(result.getContent()).isEmpty();
        verify(bookOpdsRepository).findBookIdsByMetadataSearchAndShelfIds(eq("q"), anySet(), eq(Set.of(2L)), any());
    }

    @Test
    void getBooksPage_v2User_delegatesToV2Method() {
        OpdsUserDetails details = v2UserDetails(1L, true, Set.of(1L));
        when(bookOpdsRepository.findBookIds(any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findRecentBookIds(any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByLibraryIds(anySet(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByShelfId(anyLong(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByShelfIds(anySet(), anySet(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByMetadataSearch(anyString(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findBookIdsByMetadataSearchAndShelfIds(anyString(), anySet(), anySet(), any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findAllWithMetadataByIds(anyList())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(anyList(), anySet())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithMetadataByIdsAndShelfId(anyList(), anyLong())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithMetadataByIdsAndShelfIds(anyList(), anySet(), anySet())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithFullMetadataByIds(anyList())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithFullMetadataByIdsAndLibraryIds(anyList(), anySet())).thenReturn(List.of());
        when(bookOpdsRepository.findAllWithFullMetadataByIdsAndShelfIds(anyList(), anySet(), anySet())).thenReturn(List.of());

        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(true);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));
        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(true);
        when(user.getId()).thenReturn(1L);

        ShelfEntity shelf = mock(ShelfEntity.class);
        BookLoreUserEntity shelfUser = mock(BookLoreUserEntity.class);
        when(shelfUser.getId()).thenReturn(1L);
        when(shelf.getUser()).thenReturn(shelfUser);
        when(shelfRepository.findByIdWithUser(anyLong())).thenReturn(Optional.of(shelf));

        Page<Book> result = opdsBookService.getBooksPage(details.getOpdsUserV2().getUserId(), "q", 1L, Set.of(2L), 0, 10);

        assertThat(result.getContent()).isEmpty();
        verify(bookOpdsRepository).findBookIdsByMetadataSearchAndShelfIds(eq("q"), anySet(), eq(Set.of(2L)), any());
    }

    @Test
    void getRecentBooksPage_returnsRecentBooks_forLegacyUser() {
        OpdsUserDetails details = legacyUserDetails();
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        when(userRepository.findByIdWithDetails(999L)).thenReturn(Optional.of(entity));
        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(true);

        when(bookOpdsRepository.findRecentBookIds(any())).thenReturn(Page.empty());
        when(bookOpdsRepository.findAllWithMetadataByIds(anyList())).thenReturn(List.of());

        Page<Book> result = opdsBookService.getRecentBooksPage(details.getOpdsUserV2().getUserId(), 0, 10);

        assertThat(result.getContent()).isEmpty();
        verify(bookOpdsRepository).findRecentBookIds(any());
    }

    @Test
    void getRecentBooksPage_appliesBookFilters_forNonAdminV2User() {
        OpdsUserDetails details = v2UserDetails(2L, false, Set.of(1L, 2L));
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        when(userRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(entity));
        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        List<Library> libs = List.of(Library.builder().id(1L).watch(false).build());
        when(user.getAssignedLibraries()).thenReturn(libs);

        Book book = Book.builder().id(1L).shelves(Set.of(Shelf.builder().userId(2L).build())).build();
        BookEntity bookEntity = mock(BookEntity.class);
        when(bookEntity.getId()).thenReturn(1L);

        when(bookOpdsRepository.findRecentBookIdsByLibraryIds(anySet(), any())).thenReturn(new PageImpl<>(List.of(1L)));
        when(bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(anyList(), anySet())).thenReturn(List.of(bookEntity));
        when(bookMapper.toBook(bookEntity)).thenReturn(book);

        Page<Book> result = opdsBookService.getRecentBooksPage(details.getOpdsUserV2().getUserId(), 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getShelves()).allMatch(shelf -> shelf.getUserId().equals(2L));
    }

    @Test
    void getLibraryName_returnsName_whenFound() {
        List<Library> libs = List.of(Library.builder().id(1L).name("Lib1").watch(false).build());
        when(libraryService.getAllLibraries()).thenReturn(libs);

        String name = opdsBookService.getLibraryName(1L);

        assertThat(name).isEqualTo("Lib1");
    }

    @Test
    void getLibraryName_returnsDefault_whenNotFound() {
        when(libraryService.getAllLibraries()).thenReturn(List.of());

        String name = opdsBookService.getLibraryName(99L);

        assertThat(name).isEqualTo("Library Books");
    }

    @Test
    void getShelfName_returnsShelfName_whenFound() {
        ShelfEntity shelf = mock(ShelfEntity.class);
        when(shelf.getName()).thenReturn("Shelf1");
        when(shelfRepository.findById(1L)).thenReturn(Optional.of(shelf));

        String name = opdsBookService.getShelfName(1L);

        assertThat(name).isEqualTo("Shelf1 - Shelf");
    }

    @Test
    void getShelfName_returnsDefault_whenNotFound() {
        when(shelfRepository.findById(1L)).thenReturn(Optional.empty());

        String name = opdsBookService.getShelfName(1L);

        assertThat(name).isEqualTo("Shelf Books");
    }

    @Test
    void getUserShelves_returnsShelves() {
        List<ShelfEntity> shelves = List.of(mock(ShelfEntity.class));
        when(shelfRepository.findByUserId(1L)).thenReturn(shelves);

        List<ShelfEntity> result = opdsBookService.getUserShelves(1L);

        assertThat(result).isEqualTo(shelves);
    }

    @Test
    void getRandomBooks_returnsBooks_whenLibrariesAccessible() {
        OpdsUserDetails details = v2UserDetails(1L, true, Set.of(1L));
        OpdsBookService spy = spy(opdsBookService);
        List<Library> libs = List.of(Library.builder().id(1L).watch(false).build());
        doReturn(libs).when(spy).getAccessibleLibraries(details.getOpdsUserV2().getUserId());

        when(bookOpdsRepository.findRandomBookIdsByLibraryIds(anyList(), any())).thenReturn(List.of(1L, 2L));
        BookEntity entity = mock(BookEntity.class);
        when(bookOpdsRepository.findAllWithMetadataByIds(anyList())).thenReturn(List.of(entity));
        Book book = Book.builder().id(1L).build();
        when(bookMapper.toBook(entity)).thenReturn(book);

        List<Book> result = spy.getRandomBooks(details.getOpdsUserV2().getUserId(), 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void getRandomBooks_returnsEmpty_whenNoLibraries() {
        OpdsUserDetails details = v2UserDetails(1L, false, Set.of());
        OpdsBookService spy = spy(opdsBookService);
        doReturn(List.of()).when(spy).getAccessibleLibraries(details.getOpdsUserV2().getUserId());

        List<Book> result = spy.getRandomBooks(details.getOpdsUserV2().getUserId(), 1);

        assertThat(result).isEmpty();
    }

    @Test
    void getBooksPageForV2User_throwsForbidden_whenNoPermission() {
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(false);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() ->
                opdsBookService.getBooksPage(1L, null, null, null, 0, 10)
        ).hasMessageContaining("You are not allowed to access this resource");
    }

    @Test
    void getBooksPage_withSingleShelfId_returnsShelfBooks() {
        v2UserDetails(1L, false, Set.of(1L));
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(perms.isCanAccessOpds()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        ShelfEntity shelf = mock(ShelfEntity.class);
        BookLoreUserEntity shelfUser = mock(BookLoreUserEntity.class);
        when(shelfUser.getId()).thenReturn(1L);
        when(shelf.getUser()).thenReturn(shelfUser);
        when(shelfRepository.findByIdWithUser(10L)).thenReturn(Optional.of(shelf));

        BookEntity bookEntity = mock(BookEntity.class);
        when(bookEntity.getId()).thenReturn(1L);
        Book book = Book.builder().id(1L).build();
        when(bookMapper.toBook(bookEntity)).thenReturn(book);

        when(bookOpdsRepository.findBookIdsByShelfIds(anySet(), eq(Set.of(10L)), any())).thenReturn(new PageImpl<>(List.of(1L)));
        when(bookOpdsRepository.findAllWithMetadataByIdsAndShelfIds(eq(List.of(1L)), anySet(), eq(Set.of(10L)))).thenReturn(List.of(bookEntity));

        Page<Book> result = opdsBookService.getBooksPage(1L, null, null, Set.of(10L), 0, 10);

        assertThat(result.getContent()).hasSize(1);
        verify(bookOpdsRepository).findBookIdsByShelfIds(anySet(), eq(Set.of(10L)), any());
    }

    @Test
    void getBooksPage_withMultipleShelfIds_returnsShelfBooks() {
        v2UserDetails(1L, false, Set.of(1L));
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(perms.isCanAccessOpds()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        ShelfEntity shelf1 = mock(ShelfEntity.class);
        ShelfEntity shelf2 = mock(ShelfEntity.class);
        BookLoreUserEntity shelfUser = mock(BookLoreUserEntity.class);
        when(shelfUser.getId()).thenReturn(1L);
        when(shelf1.getUser()).thenReturn(shelfUser);
        when(shelf2.getUser()).thenReturn(shelfUser);
        when(shelfRepository.findByIdWithUser(10L)).thenReturn(Optional.of(shelf1));
        when(shelfRepository.findByIdWithUser(20L)).thenReturn(Optional.of(shelf2));

        BookEntity bookEntity1 = mock(BookEntity.class);
        BookEntity bookEntity2 = mock(BookEntity.class);
        when(bookEntity1.getId()).thenReturn(1L);
        when(bookEntity2.getId()).thenReturn(2L);
        Book book1 = Book.builder().id(1L).build();
        Book book2 = Book.builder().id(2L).build();
        when(bookMapper.toBook(bookEntity1)).thenReturn(book1);
        when(bookMapper.toBook(bookEntity2)).thenReturn(book2);

        when(bookOpdsRepository.findBookIdsByShelfIds(anySet(), eq(Set.of(10L, 20L)), any())).thenReturn(new PageImpl<>(List.of(1L, 2L)));
        when(bookOpdsRepository.findAllWithMetadataByIdsAndShelfIds(eq(List.of(1L, 2L)), anySet(), eq(Set.of(10L, 20L)))).thenReturn(List.of(bookEntity1, bookEntity2));

        Page<Book> result = opdsBookService.getBooksPage(1L, null, null, Set.of(10L, 20L), 0, 10);

        assertThat(result.getContent()).hasSize(2);
        verify(bookOpdsRepository).findBookIdsByShelfIds(anySet(), eq(Set.of(10L, 20L)), any());
    }

    @Test
    void getBooksPage_withShelfIdAndQuery_searchesInShelf() {
        v2UserDetails(1L, false, Set.of(1L));
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(perms.isCanAccessOpds()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        ShelfEntity shelf = mock(ShelfEntity.class);
        BookLoreUserEntity shelfUser = mock(BookLoreUserEntity.class);
        when(shelfUser.getId()).thenReturn(1L);
        when(shelf.getUser()).thenReturn(shelfUser);
        when(shelfRepository.findByIdWithUser(10L)).thenReturn(Optional.of(shelf));

        BookEntity bookEntity = mock(BookEntity.class);
        when(bookEntity.getId()).thenReturn(1L);
        Book book = Book.builder().id(1L).build();
        when(bookMapper.toBook(bookEntity)).thenReturn(book);

        when(bookOpdsRepository.findBookIdsByMetadataSearchAndShelfIds(eq("test"), anySet(), eq(Set.of(10L)), any())).thenReturn(new PageImpl<>(List.of(1L)));
        when(bookOpdsRepository.findAllWithFullMetadataByIdsAndShelfIds(eq(List.of(1L)), anySet(), eq(Set.of(10L)))).thenReturn(List.of(bookEntity));

        Page<Book> result = opdsBookService.getBooksPage(1L, "test", null, Set.of(10L), 0, 10);

        assertThat(result.getContent()).hasSize(1);
        verify(bookOpdsRepository).findBookIdsByMetadataSearchAndShelfIds(eq("test"), anySet(), eq(Set.of(10L)), any());
    }

    @Test
    void getBooksPage_withShelfId_throwsForbidden_whenNotOwner() {
        v2UserDetails(1L, false, Set.of(1L));
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(perms.isCanAccessOpds()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        ShelfEntity shelf = mock(ShelfEntity.class);
        BookLoreUserEntity shelfUser = mock(BookLoreUserEntity.class);
        when(shelfUser.getId()).thenReturn(999L); // Different user
        when(shelf.getUser()).thenReturn(shelfUser);
        when(shelfRepository.findByIdWithUser(10L)).thenReturn(Optional.of(shelf));

        assertThatThrownBy(() ->
                opdsBookService.getBooksPage(1L, null, null, Set.of(10L), 0, 10)
        ).hasMessageContaining("You are not allowed to access this shelf");
    }

    @Test
    void getBooksPage_withShelfId_allowsAdmin_evenIfNotOwner() {
        v2UserDetails(1L, true, Set.of(1L));
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(true);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(true);
        when(perms.isCanAccessOpds()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        ShelfEntity shelf = mock(ShelfEntity.class);
        BookLoreUserEntity shelfUser = mock(BookLoreUserEntity.class);
        when(shelfUser.getId()).thenReturn(999L); // Different user, but admin
        when(shelf.getUser()).thenReturn(shelfUser);
        when(shelfRepository.findByIdWithUser(10L)).thenReturn(Optional.of(shelf));

        when(bookOpdsRepository.findBookIdsByShelfIds(anySet(), eq(Set.of(10L)), any())).thenReturn(Page.empty());

        Page<Book> result = opdsBookService.getBooksPage(1L, null, null, Set.of(10L), 0, 10);

        assertThat(result).isNotNull();
        verify(bookOpdsRepository).findBookIdsByShelfIds(anySet(), eq(Set.of(10L)), any());
    }

    // ==================== validateBookContentAccess ====================

    @Test
    void validateBookContentAccess_throwsForbidden_whenUserIdIsNull() {
        assertThatThrownBy(() ->
                opdsBookService.validateBookContentAccess(1L, null)
        ).hasMessageContaining("Authentication required");
    }

    @Test
    void validateBookContentAccess_allowsAdmin() {
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(true);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findById(1L)).thenReturn(Optional.of(entity));

        opdsBookService.validateBookContentAccess(99L, 1L);

        verify(bookRepository, never()).findById(anyLong());
        verify(contentRestrictionService, never()).applyRestrictions(anyList(), eq(1L));
    }

    @Test
    void validateBookContentAccess_throwsForbidden_whenNoLibraryAccess() {
        BookLoreUserEntity userEntity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(userEntity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findById(2L)).thenReturn(Optional.of(userEntity));

        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(userEntity)).thenReturn(user);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(5L).watch(false).build()));

        BookEntity book = mock(BookEntity.class);
        LibraryEntity library = mock(LibraryEntity.class);
        when(library.getId()).thenReturn(99L);
        when(book.getLibrary()).thenReturn(library);
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

        assertThatThrownBy(() ->
                opdsBookService.validateBookContentAccess(1L, 2L)
        ).hasMessageContaining("You are not authorized to access this book.");
    }

    @Test
    void validateBookContentAccess_throwsForbidden_whenContentRestricted() {
        BookLoreUserEntity userEntity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(userEntity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findById(2L)).thenReturn(Optional.of(userEntity));

        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(userEntity)).thenReturn(user);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(5L).watch(false).build()));

        BookEntity book = mock(BookEntity.class);
        LibraryEntity library = mock(LibraryEntity.class);
        when(library.getId()).thenReturn(5L);
        when(book.getLibrary()).thenReturn(library);
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

        when(contentRestrictionService.applyRestrictions(List.of(book), 2L))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                opdsBookService.validateBookContentAccess(1L, 2L)
        ).hasMessageContaining("You are not authorized to access this book.");
    }

    @Test
    void validateBookContentAccess_allowsAccess_whenBookPassesRestrictions() {
        BookLoreUserEntity userEntity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(userEntity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findById(2L)).thenReturn(Optional.of(userEntity));

        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(userEntity)).thenReturn(user);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(5L).watch(false).build()));

        BookEntity book = mock(BookEntity.class);
        LibraryEntity library = mock(LibraryEntity.class);
        when(library.getId()).thenReturn(5L);
        when(book.getLibrary()).thenReturn(library);
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

        when(contentRestrictionService.applyRestrictions(List.of(book), 2L))
                .thenReturn(List.of(book));

        opdsBookService.validateBookContentAccess(1L, 2L);

        verify(contentRestrictionService).applyRestrictions(List.of(book), 2L);
    }

    // ==================== Content restriction filtering in feeds ====================

    @Test
    void getBooksPage_appliesContentRestrictions_forNonAdmin() {
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(user.getId()).thenReturn(1L);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        BookEntity allowedEntity = mock(BookEntity.class);
        BookEntity restrictedEntity = mock(BookEntity.class);
        when(allowedEntity.getId()).thenReturn(1L);
        when(restrictedEntity.getId()).thenReturn(2L);

        Book allowedBook = Book.builder().id(1L).build();
        when(bookMapper.toBook(allowedEntity)).thenReturn(allowedBook);

        when(bookOpdsRepository.findBookIdsByLibraryIds(anySet(), any()))
                .thenReturn(new PageImpl<>(List.of(1L, 2L)));
        when(bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(anyList(), anySet()))
                .thenReturn(List.of(allowedEntity, restrictedEntity));

        when(contentRestrictionService.applyRestrictions(anyList(), eq(1L)))
                .thenReturn(List.of(allowedEntity));

        Page<Book> result = opdsBookService.getBooksPage(1L, null, null, null, 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getId()).isEqualTo(1L);
        verify(contentRestrictionService).applyRestrictions(anyList(), eq(1L));
    }

    @Test
    void getBooksPage_skipsContentRestrictions_forAdmin() {
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(true);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(true);
        when(user.getId()).thenReturn(1L);

        when(bookOpdsRepository.findBookIds(any())).thenReturn(Page.empty());

        opdsBookService.getBooksPage(1L, null, null, null, 0, 10);

        verify(contentRestrictionService, never()).applyRestrictions(anyList(), eq(1L));
    }

    @Test
    void getRecentBooksPage_appliesContentRestrictions_forNonAdmin() {
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        when(userRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(entity));
        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        BookEntity bookEntity = mock(BookEntity.class);
        when(bookEntity.getId()).thenReturn(1L);
        Book book = Book.builder().id(1L).build();
        when(bookMapper.toBook(bookEntity)).thenReturn(book);

        when(bookOpdsRepository.findRecentBookIdsByLibraryIds(anySet(), any()))
                .thenReturn(new PageImpl<>(List.of(1L)));
        when(bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(anyList(), anySet()))
                .thenReturn(List.of(bookEntity));

        opdsBookService.getRecentBooksPage(2L, 0, 10);

        verify(contentRestrictionService).applyRestrictions(anyList(), eq(2L));
    }

    @Test
    void getRecentBooksPage_skipsContentRestrictions_forAdmin() {
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));
        BookLoreUser user = mock(BookLoreUser.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(true);

        when(bookOpdsRepository.findRecentBookIds(any())).thenReturn(Page.empty());

        opdsBookService.getRecentBooksPage(1L, 0, 10);

        verify(contentRestrictionService, never()).applyRestrictions(anyList(), eq(1L));
    }

    @Test
    void getRandomBooks_appliesContentRestrictions() {
        OpdsBookService spy = spy(opdsBookService);
        doReturn(List.of(Library.builder().id(1L).watch(false).build()))
                .when(spy).getAccessibleLibraries(2L);

        BookEntity allowedEntity = mock(BookEntity.class);
        BookEntity restrictedEntity = mock(BookEntity.class);

        when(bookOpdsRepository.findRandomBookIdsByLibraryIds(anyList(), any())).thenReturn(List.of(1L, 2L));
        when(bookOpdsRepository.findAllWithMetadataByIds(anyList()))
                .thenReturn(List.of(allowedEntity, restrictedEntity));

        when(contentRestrictionService.applyRestrictions(anyList(), eq(2L)))
                .thenReturn(List.of(allowedEntity));

        Book book = Book.builder().id(1L).build();
        when(bookMapper.toBook(allowedEntity)).thenReturn(book);

        List<Book> result = spy.getRandomBooks(2L, 5);

        assertThat(result).hasSize(1);
        verify(contentRestrictionService).applyRestrictions(anyList(), eq(2L));
    }

    @Test
    void getBooksPage_contentRestrictions_filtersFromPage() {
        BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
        var permissionsEntity = mock(UserPermissionsEntity.class);
        when(permissionsEntity.isPermissionAccessOpds()).thenReturn(true);
        when(permissionsEntity.isPermissionAdmin()).thenReturn(false);
        when(entity.getPermissions()).thenReturn(permissionsEntity);
        when(userRepository.findByIdWithDetails(3L)).thenReturn(Optional.of(entity));

        BookLoreUser user = mock(BookLoreUser.class);
        BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
        when(user.getPermissions()).thenReturn(perms);
        when(perms.isAdmin()).thenReturn(false);
        when(user.getId()).thenReturn(3L);
        when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(1L).watch(false).build()));

        BookEntity book1 = mock(BookEntity.class);
        BookEntity book2 = mock(BookEntity.class);
        BookEntity book3 = mock(BookEntity.class);
        when(book1.getId()).thenReturn(1L);
        when(book2.getId()).thenReturn(2L);
        when(book3.getId()).thenReturn(3L);

        when(bookMapper.toBook(book1)).thenReturn(Book.builder().id(1L).build());
        when(bookMapper.toBook(book3)).thenReturn(Book.builder().id(3L).build());

        when(bookOpdsRepository.findBookIdsByLibraryIds(anySet(), any()))
                .thenReturn(new PageImpl<>(List.of(1L, 2L, 3L)));
        when(bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(anyList(), anySet()))
                .thenReturn(List.of(book1, book2, book3));

        when(contentRestrictionService.applyRestrictions(anyList(), eq(3L)))
                .thenReturn(List.of(book1, book3));

        Page<Book> result = opdsBookService.getBooksPage(3L, null, null, null, 0, 10);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).extracting(Book::getId).containsExactly(1L, 3L);
    }

    // ==================== getDistinctAuthors / getDistinctSeries ====================

    @Nested
    class DistinctAuthorsAndSeries {

        @Test
        void getDistinctAuthors_returnsEmptyList_whenUserIdIsNull() {
            assertThat(opdsBookService.getDistinctAuthors(null)).isEmpty();
        }

        @Test
        void getDistinctAuthors_admin_returnsSortedDistinctNonNullNames() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(true);

            AuthorEntity beta = mock(AuthorEntity.class);
            when(beta.getName()).thenReturn("Beta Author");
            AuthorEntity alpha = mock(AuthorEntity.class);
            when(alpha.getName()).thenReturn("Alpha Author");
            AuthorEntity noName = mock(AuthorEntity.class);
            when(noName.getName()).thenReturn(null);
            when(bookOpdsRepository.findDistinctAuthors()).thenReturn(List.of(beta, alpha, noName));

            List<String> result = opdsBookService.getDistinctAuthors(1L);

            assertThat(result).containsExactly("Alpha Author", "Beta Author");
        }

        @Test
        void getDistinctAuthors_nonAdmin_usesAssignedLibraryIds() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(false);
            when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(7L).watch(false).build()));

            AuthorEntity author = mock(AuthorEntity.class);
            when(author.getName()).thenReturn("Library Author");
            when(bookOpdsRepository.findDistinctAuthorsByLibraryIds(Set.of(7L))).thenReturn(List.of(author));

            List<String> result = opdsBookService.getDistinctAuthors(2L);

            assertThat(result).containsExactly("Library Author");
        }

        @Test
        void getDistinctSeries_returnsEmptyList_whenUserIdIsNull() {
            assertThat(opdsBookService.getDistinctSeries(null)).isEmpty();
        }

        @Test
        void getDistinctSeries_admin_delegatesToRepository() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(true);
            when(bookOpdsRepository.findDistinctSeries()).thenReturn(List.of("Series A", "Series B"));

            List<String> result = opdsBookService.getDistinctSeries(1L);

            assertThat(result).containsExactly("Series A", "Series B");
        }

        @Test
        void getDistinctSeries_nonAdmin_usesAssignedLibraryIds() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(false);
            when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(7L).watch(false).build()));
            when(bookOpdsRepository.findDistinctSeriesByLibraryIds(Set.of(7L))).thenReturn(List.of("Series C"));

            List<String> result = opdsBookService.getDistinctSeries(2L);

            assertThat(result).containsExactly("Series C");
        }
    }

    // ==================== getBooksByAuthorName / getBooksBySeriesName ====================

    @Nested
    class BooksByAuthorNameAndSeriesName {

        @Test
        void getBooksByAuthorName_throwsForbidden_whenUserIdIsNull() {
            assertThatThrownBy(() -> opdsBookService.getBooksByAuthorName(null, "Someone", 0, 10))
                    .hasMessageContaining("Authentication required");
        }

        @Test
        void getBooksByAuthorName_admin_emptyIdPage_returnsEmptyPage() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(true);
            when(bookOpdsRepository.findBookIdsByAuthorName(eq("Author X"), any())).thenReturn(Page.empty());

            Page<Book> result = opdsBookService.getBooksByAuthorName(1L, "Author X", 0, 10);

            assertThat(result.getContent()).isEmpty();
            verify(bookOpdsRepository, never()).findAllWithFullMetadataByIds(anyList());
        }

        @Test
        void getBooksByAuthorName_nonAdmin_returnsBooksAndAppliesRestrictions() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(false);
            when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(7L).watch(false).build()));

            BookEntity bookEntity = mock(BookEntity.class);
            when(bookEntity.getId()).thenReturn(1L);
            Book book = Book.builder().id(1L).build();
            when(bookMapper.toBook(bookEntity)).thenReturn(book);

            when(bookOpdsRepository.findBookIdsByAuthorNameAndLibraryIds(eq("Author Y"), eq(Set.of(7L)), any()))
                    .thenReturn(new PageImpl<>(List.of(1L)));
            when(bookOpdsRepository.findAllWithFullMetadataByIdsAndLibraryIds(List.of(1L), Set.of(7L)))
                    .thenReturn(List.of(bookEntity));

            Page<Book> result = opdsBookService.getBooksByAuthorName(2L, "Author Y", 0, 10);

            assertThat(result.getContent()).hasSize(1);
            verify(contentRestrictionService).applyRestrictions(anyList(), eq(2L));
        }

        @Test
        void getBooksBySeriesName_throwsForbidden_whenUserIdIsNull() {
            assertThatThrownBy(() -> opdsBookService.getBooksBySeriesName(null, "Some Series", 0, 10))
                    .hasMessageContaining("Authentication required");
        }

        @Test
        void getBooksBySeriesName_admin_emptyIdPage_returnsEmptyPage() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(true);
            when(bookOpdsRepository.findBookIdsBySeriesName(eq("Series X"), any())).thenReturn(Page.empty());

            Page<Book> result = opdsBookService.getBooksBySeriesName(1L, "Series X", 0, 10);

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        void getBooksBySeriesName_nonAdmin_returnsBooksAndAppliesRestrictions() {
            BookLoreUserEntity entity = mock(BookLoreUserEntity.class);
            when(userRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(entity));
            BookLoreUser user = mock(BookLoreUser.class);
            when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);
            BookLoreUser.UserPermissions perms = mock(BookLoreUser.UserPermissions.class);
            when(user.getPermissions()).thenReturn(perms);
            when(perms.isAdmin()).thenReturn(false);
            when(user.getAssignedLibraries()).thenReturn(List.of(Library.builder().id(7L).watch(false).build()));

            BookEntity bookEntity = mock(BookEntity.class);
            when(bookEntity.getId()).thenReturn(1L);
            Book book = Book.builder().id(1L).build();
            when(bookMapper.toBook(bookEntity)).thenReturn(book);

            when(bookOpdsRepository.findBookIdsBySeriesNameAndLibraryIds(eq("Series Y"), eq(Set.of(7L)), any()))
                    .thenReturn(new PageImpl<>(List.of(1L)));
            when(bookOpdsRepository.findAllWithFullMetadataByIdsAndLibraryIds(List.of(1L), Set.of(7L)))
                    .thenReturn(List.of(bookEntity));

            Page<Book> result = opdsBookService.getBooksBySeriesName(2L, "Series Y", 0, 10);

            assertThat(result.getContent()).hasSize(1);
            verify(contentRestrictionService).applyRestrictions(anyList(), eq(2L));
        }
    }

    // ==================== applySortOrder ====================

    @Nested
    class ApplySortOrder {

        private Book bookOf(Long id, String title, String firstAuthor, String seriesName, Float seriesNumber,
                             Double amazonRating, Double goodreadsRating, Instant addedOn) {
            return Book.builder()
                    .id(id)
                    .addedOn(addedOn)
                    .metadata(BookMetadata.builder()
                            .title(title)
                            .authors(firstAuthor != null ? List.of(firstAuthor) : List.of())
                            .seriesName(seriesName)
                            .seriesNumber(seriesNumber)
                            .amazonRating(amazonRating)
                            .goodreadsRating(goodreadsRating)
                            .build())
                    .build();
        }

        private Page<Book> pageOf(Book... books) {
            return new PageImpl<>(List.of(books), PageRequest.of(0, 10), books.length);
        }

        @Test
        void nullSortOrder_returnsPageUnchanged() {
            Page<Book> page = pageOf(bookOf(1L, "B", "A", null, null, null, null, Instant.now()));

            Page<Book> result = opdsBookService.applySortOrder(page, null);

            assertThat(result).isSameAs(page);
        }

        @Test
        void recentSortOrder_returnsPageUnchanged() {
            Page<Book> page = pageOf(bookOf(1L, "B", "A", null, null, null, null, Instant.now()));

            Page<Book> result = opdsBookService.applySortOrder(page, OpdsSortOrder.RECENT);

            assertThat(result).isSameAs(page);
        }

        @Test
        void titleAscAndDesc_sortByTitleIgnoringCase() {
            Book zeta = bookOf(1L, "Zeta", "A", null, null, null, null, Instant.now());
            Book alpha = bookOf(2L, "alpha", "A", null, null, null, null, Instant.now());
            Page<Book> page = pageOf(zeta, alpha);

            Page<Book> asc = opdsBookService.applySortOrder(page, OpdsSortOrder.TITLE_ASC);
            assertThat(asc.getContent()).extracting(Book::getId).containsExactly(2L, 1L);

            Page<Book> desc = opdsBookService.applySortOrder(page, OpdsSortOrder.TITLE_DESC);
            assertThat(desc.getContent()).extracting(Book::getId).containsExactly(1L, 2L);
        }

        @Test
        void authorAscAndDesc_sortByFirstAuthorIgnoringCase() {
            Book zed = bookOf(1L, "T1", "Zed Author", null, null, null, null, Instant.now());
            Book adam = bookOf(2L, "T2", "adam Author", null, null, null, null, Instant.now());
            Page<Book> page = pageOf(zed, adam);

            Page<Book> asc = opdsBookService.applySortOrder(page, OpdsSortOrder.AUTHOR_ASC);
            assertThat(asc.getContent()).extracting(Book::getId).containsExactly(2L, 1L);

            Page<Book> desc = opdsBookService.applySortOrder(page, OpdsSortOrder.AUTHOR_DESC);
            assertThat(desc.getContent()).extracting(Book::getId).containsExactly(1L, 2L);
        }

        @Test
        void seriesAsc_booksWithoutSeriesSortAfter_andFallBackToAddedOnDescending() {
            Instant older = Instant.now().minusSeconds(120);
            Instant newer = Instant.now();
            Book noSeriesOld = bookOf(1L, "T1", "A", null, null, null, null, older);
            Book noSeriesNew = bookOf(2L, "T2", "A", null, null, null, null, newer);
            Book seriesB2 = bookOf(3L, "T3", "A", "Beta Series", 2f, null, null, newer);
            Book seriesA1 = bookOf(4L, "T4", "A", "Alpha Series", 1f, null, null, newer);

            Page<Book> page = pageOf(noSeriesOld, noSeriesNew, seriesB2, seriesA1);

            Page<Book> asc = opdsBookService.applySortOrder(page, OpdsSortOrder.SERIES_ASC);

            // Series books come first (Alpha before Beta), then no-series books newest-first
            assertThat(asc.getContent()).extracting(Book::getId).containsExactly(4L, 3L, 2L, 1L);
        }

        @Test
        void seriesDesc_booksWithoutSeriesSortAfter_higherSeriesNameFirst() {
            Book seriesB2 = bookOf(1L, "T1", "A", "Beta Series", 2f, null, null, Instant.now());
            Book seriesA1 = bookOf(2L, "T2", "A", "Alpha Series", 1f, null, null, Instant.now());
            Book noSeries = bookOf(3L, "T3", "A", null, null, null, null, Instant.now());

            Page<Book> page = pageOf(seriesA1, seriesB2, noSeries);

            Page<Book> desc = opdsBookService.applySortOrder(page, OpdsSortOrder.SERIES_DESC);

            assertThat(desc.getContent()).extracting(Book::getId).containsExactly(1L, 2L, 3L);
        }

        @Test
        void ratingAscAndDesc_booksWithoutRatingSortAfter() {
            Instant now = Instant.now();
            Book highRated = bookOf(1L, "T1", "A", null, null, 4.5, null, now);
            Book lowRated = bookOf(2L, "T2", "A", null, null, 2.0, null, now);
            Book unrated = bookOf(3L, "T3", "A", null, null, null, null, now);

            Page<Book> page = pageOf(highRated, lowRated, unrated);

            Page<Book> asc = opdsBookService.applySortOrder(page, OpdsSortOrder.RATING_ASC);
            assertThat(asc.getContent()).extracting(Book::getId).containsExactly(2L, 1L, 3L);

            Page<Book> desc = opdsBookService.applySortOrder(page, OpdsSortOrder.RATING_DESC);
            assertThat(desc.getContent()).extracting(Book::getId).containsExactly(1L, 2L, 3L);
        }

        @Test
        void ratingTie_fallsBackToAddedOnDescending() {
            Instant older = Instant.now().minusSeconds(120);
            Instant newer = Instant.now();
            Book olderSameRating = bookOf(1L, "T1", "A", null, null, 3.0, null, older);
            Book newerSameRating = bookOf(2L, "T2", "A", null, null, 3.0, null, newer);

            Page<Book> page = pageOf(olderSameRating, newerSameRating);

            Page<Book> asc = opdsBookService.applySortOrder(page, OpdsSortOrder.RATING_ASC);

            assertThat(asc.getContent()).extracting(Book::getId).containsExactly(2L, 1L);
        }
    }

}
