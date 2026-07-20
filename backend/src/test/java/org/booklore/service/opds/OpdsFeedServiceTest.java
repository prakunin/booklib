package org.booklore.service.opds;

import jakarta.servlet.http.HttpServletRequest;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.exception.APIException;
import org.booklore.model.dto.*;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.service.MagicShelfService;
import org.booklore.util.ArchiveUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OpdsFeedServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2025-01-01T12:00:00Z");
    private static final Long TEST_USER_ID = 42L;

    private AuthenticationService authenticationService;
    private OpdsBookService opdsBookService;
    private MagicShelfService magicShelfService;
    private MagicShelfBookService magicShelfBookService;
    private OpdsFeedService opdsFeedService;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        opdsBookService = mock(OpdsBookService.class);
        magicShelfService = mock(MagicShelfService.class);
        magicShelfBookService = mock(MagicShelfBookService.class);
        opdsFeedService = new OpdsFeedService(authenticationService, opdsBookService, magicShelfService, magicShelfBookService);
        request = mock(HttpServletRequest.class);
    }

    private OpdsUserDetails mockAuthenticatedUser() {
        OpdsUserDetails userDetails = mock(OpdsUserDetails.class);
        OpdsUserV2 v2 = mock(OpdsUserV2.class);
        when(userDetails.getOpdsUserV2()).thenReturn(v2);
        when(v2.getUserId()).thenReturn(TEST_USER_ID);
        when(v2.getSortOrder()).thenReturn(OpdsSortOrder.RECENT);
        when(authenticationService.getOpdsUser()).thenReturn(userDetails);
        return userDetails;
    }

    @Test
    void generateRootNavigation_shouldContainAllSections() {
        String xml = opdsFeedService.generateRootNavigation();
        assertThat(xml)
                .contains("All Books")
                .contains("Recently Added")
                .contains("Libraries")
                .contains("Shelves")
                .contains("Magic Shelves")
                .contains("Surprise Me")
                .contains("<?xml")
                .contains("</feed>");
    }

    @Test
    void generateLibrariesNavigation_shouldListLibraries() {
        mockAuthenticatedUser();

        Library lib = Library.builder().id(1L).name("Test Library").watch(false).build();
        when(opdsBookService.getAccessibleLibraries(TEST_USER_ID)).thenReturn(List.of(lib));

        String xml = opdsFeedService.generateLibrariesNavigation();
        assertThat(xml)
                .contains("Test Library")
                .contains("urn:booklore:library:1")
                .contains("</feed>");
        verify(opdsBookService).getAccessibleLibraries(TEST_USER_ID);
    }

    @Test
    void generateLibrariesNavigation_shouldHandleNoLibraries() {
        mockAuthenticatedUser();
        when(opdsBookService.getAccessibleLibraries(TEST_USER_ID)).thenReturn(Collections.emptyList());

        String xml = opdsFeedService.generateLibrariesNavigation();
        assertThat(xml).contains("</feed>");
    }

    @Test
    void generateShelvesNavigation_shouldListShelves() {
        mockAuthenticatedUser();

        ShelfEntity shelfEntity = ShelfEntity.builder().id(5L).name("Favorites").build();
        when(opdsBookService.getUserShelves(TEST_USER_ID)).thenReturn(Collections.singletonList(shelfEntity));

        String xml = opdsFeedService.generateShelvesNavigation();
        assertThat(xml)
                .contains("Favorites")
                .contains("urn:booklore:shelf:5")
                .contains("</feed>");
        verify(opdsBookService).getUserShelves(TEST_USER_ID);
    }

    @Test
    void generateShelvesNavigation_shouldHandleNoShelves() {
        mockAuthenticatedUser();
        when(opdsBookService.getUserShelves(TEST_USER_ID)).thenReturn(Collections.emptyList());

        String xml = opdsFeedService.generateShelvesNavigation();
        assertThat(xml).contains("</feed>");
    }

    @Test
    void generateShelvesNavigation_shouldThrowWhenNotAuthenticated() {
        when(authenticationService.getOpdsUser()).thenReturn(null);
        assertThatThrownBy(() -> opdsFeedService.generateShelvesNavigation())
                .isInstanceOf(APIException.class)
                .hasMessageContaining("OPDS authentication required");
        verify(opdsBookService, never()).getUserShelves(any());
    }

    @Test
    void generateCatalogFeed_shouldReturnFeedWithBooks() {
        mockAuthenticatedUser();

        when(request.getParameter("libraryId")).thenReturn(null);
        when(request.getParameter("shelfId")).thenReturn(null);
        when(request.getParameter("magicShelfId")).thenReturn(null);
        when(request.getParameter("q")).thenReturn(null);
        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn(null);

        Book book = Book.builder()
                .id(10L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder()
                        .title("Book Title")
                        .authors(List.of("Author A"))
                        .publisher("Publisher X")
                        .language("en")
                        .categories(Set.of("Fiction"))
                        .description("A book description")
                        .isbn10("1234567890")
                        .build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), any(), any(), any(), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml)
                .contains("Book Title")
                .contains("Author A")
                .contains("Publisher X")
                .contains("urn:booklore:book:10")
                .contains("application/epub+zip")
                .contains("</feed>");
        verify(opdsBookService).getBooksPage(TEST_USER_ID, null, null, null, 0, 50);
    }

    @Test
    void generateCatalogFeed_shouldEmbedDownloadTokenInAcquisitionLink() {
        mockAuthenticatedUser();
        when(authenticationService.generateDownloadToken(TEST_USER_ID)).thenReturn("test.jwt.token");

        when(request.getParameter(anyString())).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn(null);

        Book book = Book.builder()
                .id(10L)
                .primaryFile(BookFile.builder().id(7L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder().title("Token Book").build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), any(), any(), any(), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);

        // Acquisition link carries the token so header-less clients authenticate via ?token=.
        // The '&' separator must be XML-escaped inside the attribute value.
        assertThat(xml)
                .contains("/api/v1/opds/10/download?fileId=7&amp;token=test.jwt.token")
                .doesNotContain("fileId=7&token=");
        verify(authenticationService).generateDownloadToken(TEST_USER_ID);
    }

    @Test
    void generateCatalogFeed_shouldOmitTokenWhenNoneAvailable() {
        mockAuthenticatedUser();
        when(authenticationService.generateDownloadToken(TEST_USER_ID)).thenReturn(null);

        when(request.getParameter(anyString())).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn(null);

        Book book = Book.builder()
                .id(10L)
                .primaryFile(BookFile.builder().id(7L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder().title("No Token Book").build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), any(), any(), any(), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);

        assertThat(xml)
                .contains("/api/v1/opds/10/download?fileId=7\"")
                .doesNotContain("token=");
    }

    @Test
    void generateCatalogFeed_shouldOmitCoverWhenNotUpdatedOn() {
        mockAuthenticatedUser();

        when(request.getParameter("libraryId")).thenReturn(null);
        when(request.getParameter("shelfId")).thenReturn(null);
        when(request.getParameter("magicShelfId")).thenReturn(null);
        when(request.getParameter("q")).thenReturn(null);
        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn(null);

        Book book = Book.builder()
                .id(10L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder()
                        .build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), any(), any(), any(), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml).doesNotContain("link rel=\"http://opds-spec.org/image\" href=\"/api/v1/opds/10/cover");
        verify(opdsBookService).getBooksPage(TEST_USER_ID, null, null, null, 0, 50);
    }

    @Test
    void generateCatalogFeed_shouldShowEbookCover() {
        mockAuthenticatedUser();

        when(request.getParameter("libraryId")).thenReturn(null);
        when(request.getParameter("shelfId")).thenReturn(null);
        when(request.getParameter("magicShelfId")).thenReturn(null);
        when(request.getParameter("q")).thenReturn(null);
        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn(null);

        Book book = Book.builder()
                .id(10L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder()
                        .coverUpdatedOn(FIXED_INSTANT)
                        .build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), any(), any(), any(), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml).contains("link rel=\"http://opds-spec.org/image\" href=\"/api/v1/opds/10/cover");
        verify(opdsBookService).getBooksPage(TEST_USER_ID, null, null, null, 0, 50);
    }

    @Test
    void generateCatalogFeed_shouldShowAudiobookCover() {
        mockAuthenticatedUser();

        when(request.getParameter("libraryId")).thenReturn(null);
        when(request.getParameter("shelfId")).thenReturn(null);
        when(request.getParameter("magicShelfId")).thenReturn(null);
        when(request.getParameter("q")).thenReturn(null);
        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn(null);

        Book book = Book.builder()
                .id(10L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder()
                        .audiobookCoverUpdatedOn(FIXED_INSTANT)
                        .build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), any(), any(), any(), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml).contains("link rel=\"http://opds-spec.org/image\" href=\"/api/v1/opds/10/cover");
        verify(opdsBookService).getBooksPage(TEST_USER_ID, null, null, null, 0, 50);
    }

    @Test
    void generateCatalogFeed_shouldHandleEmptyPage() {
        mockAuthenticatedUser();

        when(request.getParameter(anyString())).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn(null);

        Page<Book> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0);
        when(opdsBookService.getBooksPage(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml).contains("</feed>");
    }

    @Test
    void generateRecentFeed_shouldReturnFeedWithBooks() {
        mockAuthenticatedUser();

        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/recent");
        when(request.getQueryString()).thenReturn(null);

        Book book = Book.builder()
                .id(11L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.PDF).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder().title("Recent Book").build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getRecentBooksPage(TEST_USER_ID, 0, 50)).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateRecentFeed(request);
        assertThat(xml)
                .contains("Recent Book")
                .contains("application/pdf")
                .contains("</feed>");
        verify(opdsBookService).getRecentBooksPage(TEST_USER_ID, 0, 50);
    }

    @Test
    void generateRecentFeed_shouldHandleEmptyPage() {
        mockAuthenticatedUser();

        when(request.getParameter(anyString())).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/recent");
        when(request.getQueryString()).thenReturn(null);

        Page<Book> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0);
        when(opdsBookService.getRecentBooksPage(any(), anyInt(), anyInt())).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateRecentFeed(request);
        assertThat(xml).contains("</feed>");
    }

    @Test
    void generateSurpriseFeed_shouldReturnFeedWithBooks() {
        mockAuthenticatedUser();

        Book book = Book.builder()
                .id(12L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder().title("Surprise Book").build())
                .build();

        when(opdsBookService.getRandomBooks(TEST_USER_ID, 25)).thenReturn(List.of(book));

        String xml = opdsFeedService.generateSurpriseFeed();
        assertThat(xml)
                .contains("Surprise Book")
                .contains("urn:booklore:book:12")
                .contains("</feed>");
        verify(opdsBookService).getRandomBooks(TEST_USER_ID, 25);
    }

    @Test
    void generateSurpriseFeed_shouldHandleNoBooks() {
        mockAuthenticatedUser();
        when(opdsBookService.getRandomBooks(TEST_USER_ID, 25)).thenReturn(Collections.emptyList());

        String xml = opdsFeedService.generateSurpriseFeed();
        assertThat(xml).contains("</feed>");
    }

    @Test
    void getOpenSearchDescription_shouldReturnValidXml() {
        String xml = opdsFeedService.getOpenSearchDescription();
        assertThat(xml)
                .contains("<OpenSearchDescription")
                .contains("Search Booklore catalog")
                .contains("</OpenSearchDescription>");
    }

    @Test
    void escapeXml_shouldEscapeSpecialCharacters() throws Exception {
        var method = OpdsFeedService.class.getDeclaredMethod("escapeXml", String.class);
        method.setAccessible(true);
        String input = "a&b<c>d\"e'f";
        String expected = "a&amp;b&lt;c&gt;d&quot;e&apos;f";
        String result = (String) method.invoke(opdsFeedService, input);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void parseLongParam_shouldHandleValidAndInvalidValues() throws Exception {
        var method = OpdsFeedService.class.getDeclaredMethod("parseLongParam", HttpServletRequest.class, String.class, Long.class);
        method.setAccessible(true);

        when(request.getParameter("valid")).thenReturn("123");
        when(request.getParameter("invalid")).thenReturn("abc");
        when(request.getParameter("missing")).thenReturn(null);

        Long valid = (Long) method.invoke(opdsFeedService, request, "valid", 42L);
        Long invalid = (Long) method.invoke(opdsFeedService, request, "invalid", 42L);
        Long missing = (Long) method.invoke(opdsFeedService, request, "missing", 42L);

        assertThat(valid).isEqualTo(123L);
        assertThat(invalid).isEqualTo(42L);
        assertThat(missing).isEqualTo(42L);
    }

    @Test
    void getUserId_shouldReturnUserId() throws Exception {
        mockAuthenticatedUser();

        var method = OpdsFeedService.class.getDeclaredMethod("getUserId");
        method.setAccessible(true);
        Long userId = (Long) method.invoke(opdsFeedService);

        assertThat(userId).isEqualTo(TEST_USER_ID);
    }

    @Test
    void getUserId_shouldThrowWhenNotAuthenticated() throws Exception {
        when(authenticationService.getOpdsUser()).thenReturn(null);

        var method = OpdsFeedService.class.getDeclaredMethod("getUserId");
        method.setAccessible(true);
        assertThatThrownBy(() -> method.invoke(opdsFeedService))
                .hasCauseInstanceOf(APIException.class)
                .hasRootCauseMessage("OPDS authentication required");
    }

    @Test
    void parseShelfIds_shouldParseSingleShelfId() throws Exception {
        when(request.getParameter("shelfId")).thenReturn("10");
        when(request.getParameter("shelfIds")).thenReturn(null);

        var method = OpdsFeedService.class.getDeclaredMethod("parseShelfIds", HttpServletRequest.class);
        method.setAccessible(true);
        Set<Long> result = (Set<Long>) method.invoke(opdsFeedService, request);

        assertThat(result).containsExactly(10L);
    }

    @Test
    void parseShelfIds_shouldParseCommaSeparatedShelfIds() throws Exception {
        when(request.getParameter("shelfId")).thenReturn(null);
        when(request.getParameter("shelfIds")).thenReturn("10,20,30");

        var method = OpdsFeedService.class.getDeclaredMethod("parseShelfIds", HttpServletRequest.class);
        method.setAccessible(true);
        Set<Long> result = (Set<Long>) method.invoke(opdsFeedService, request);

        assertThat(result).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    void parseShelfIds_shouldCombineShelfIdAndShelfIds() throws Exception {
        when(request.getParameter("shelfId")).thenReturn("5");
        when(request.getParameter("shelfIds")).thenReturn("10,20");

        var method = OpdsFeedService.class.getDeclaredMethod("parseShelfIds", HttpServletRequest.class);
        method.setAccessible(true);
        Set<Long> result = (Set<Long>) method.invoke(opdsFeedService, request);

        assertThat(result).containsExactlyInAnyOrder(5L, 10L, 20L);
    }

    @Test
    void parseShelfIds_shouldReturnNullWhenNoShelfIds() throws Exception {
        when(request.getParameter("shelfId")).thenReturn(null);
        when(request.getParameter("shelfIds")).thenReturn(null);

        var method = OpdsFeedService.class.getDeclaredMethod("parseShelfIds", HttpServletRequest.class);
        method.setAccessible(true);
        Set<Long> result = (Set<Long>) method.invoke(opdsFeedService, request);

        assertThat(result).isNull();
    }

    @Test
    void parseShelfIds_shouldHandleInvalidIds() throws Exception {
        when(request.getParameter("shelfId")).thenReturn("invalid");
        when(request.getParameter("shelfIds")).thenReturn("10,abc,20");

        var method = OpdsFeedService.class.getDeclaredMethod("parseShelfIds", HttpServletRequest.class);
        method.setAccessible(true);
        Set<Long> result = (Set<Long>) method.invoke(opdsFeedService, request);

        // Should only parse valid IDs
        assertThat(result).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void generateCatalogFeed_withSingleShelfId_shouldFilterByShelf() {
        mockAuthenticatedUser();

        when(request.getParameter("libraryId")).thenReturn(null);
        when(request.getParameter("shelfId")).thenReturn("10");
        when(request.getParameter("shelfIds")).thenReturn(null);
        when(request.getParameter("magicShelfId")).thenReturn(null);
        when(request.getParameter("q")).thenReturn(null);
        when(request.getParameter("author")).thenReturn(null);
        when(request.getParameter("series")).thenReturn(null);
        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn("shelfId=10");

        Book book = Book.builder()
                .id(1L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder().title("Shelf Book").build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), isNull(), isNull(), eq(Set.of(10L)), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);
        when(opdsBookService.getShelfName(10L)).thenReturn("My Shelf - Shelf");

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml)
                .contains("Shelf Book")
                .contains("My Shelf - Shelf")
                .contains("</feed>");
        verify(opdsBookService).getBooksPage(TEST_USER_ID, null, null, Set.of(10L), 0, 50);
    }

    @Test
    void generateCatalogFeed_withMultipleShelfIds_shouldFilterByMultipleShelves() {
        mockAuthenticatedUser();

        when(request.getParameter("libraryId")).thenReturn(null);
        when(request.getParameter("shelfId")).thenReturn(null);
        when(request.getParameter("shelfIds")).thenReturn("10,20");
        when(request.getParameter("magicShelfId")).thenReturn(null);
        when(request.getParameter("q")).thenReturn(null);
        when(request.getParameter("author")).thenReturn(null);
        when(request.getParameter("series")).thenReturn(null);
        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn("shelfIds=10,20");

        Book book = Book.builder()
                .id(1L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder().title("Multi Shelf Book").build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), isNull(), isNull(), eq(Set.of(10L, 20L)), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml)
                .contains("Multi Shelf Book")
                .contains("Multiple Shelves")
                .contains("</feed>");
        verify(opdsBookService).getBooksPage(TEST_USER_ID, null, null, Set.of(10L, 20L), 0, 50);
    }

    @Test
    void generateCatalogFeed_withShelfIdAndQuery_shouldSearchInShelf() {
        mockAuthenticatedUser();

        when(request.getParameter("libraryId")).thenReturn(null);
        when(request.getParameter("shelfId")).thenReturn("10");
        when(request.getParameter("shelfIds")).thenReturn(null);
        when(request.getParameter("magicShelfId")).thenReturn(null);
        when(request.getParameter("q")).thenReturn("fantasy");
        when(request.getParameter("author")).thenReturn(null);
        when(request.getParameter("series")).thenReturn(null);
        when(request.getParameter("page")).thenReturn(null);
        when(request.getParameter("size")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        when(request.getQueryString()).thenReturn("shelfId=10&q=fantasy");

        Book book = Book.builder()
                .id(1L)
                .primaryFile(BookFile.builder().id(1L).bookType(BookFileType.EPUB).build())
                .addedOn(FIXED_INSTANT)
                .metadata(BookMetadata.builder().title("Fantasy Book").build())
                .build();

        Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
        when(opdsBookService.getBooksPage(eq(TEST_USER_ID), eq("fantasy"), isNull(), eq(Set.of(10L)), eq(0), eq(50))).thenReturn(page);
        when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);
        when(opdsBookService.getShelfName(10L)).thenReturn("Fantasy Shelf - Shelf");

        String xml = opdsFeedService.generateCatalogFeed(request);
        assertThat(xml)
                .contains("Fantasy Book")
                .contains("</feed>");
        verify(opdsBookService).getBooksPage(TEST_USER_ID, "fantasy", null, Set.of(10L), 0, 50);
    }
    @Test
    void fileMimeType_shouldReturnCorrectMimeTypeForCbz() throws Exception {
        var method = OpdsFeedService.class.getDeclaredMethod("fileMimeType", BookFile.class);
        method.setAccessible(true);

        BookFile bookFile = BookFile.builder()
                .bookType(BookFileType.CBX)
                .fileName("comic.cbz")
                .archiveType(ArchiveUtils.ArchiveType.UNKNOWN)
                .build();

        String mimeType = (String) method.invoke(opdsFeedService, bookFile);
        assertThat(mimeType).isEqualTo("application/vnd.comicbook+zip");
    }

    @Test
    void fileMimeType_shouldReturnCorrectMimeTypeForCbr() throws Exception {
        var method = OpdsFeedService.class.getDeclaredMethod("fileMimeType", BookFile.class);
        method.setAccessible(true);

        // The reliable path: archive type is cached during indexing
        BookFile bookFile = BookFile.builder()
                .bookType(BookFileType.CBX)
                .fileName("comic.cbr")
                .archiveType(ArchiveUtils.ArchiveType.RAR)
                .build();

        String mimeType = (String) method.invoke(opdsFeedService, bookFile);
        assertThat(mimeType).isEqualTo("application/vnd.comicbook-rar");
    }

    @Nested
    class MagicShelvesAuthorsSeriesNavigation {

        @Test
        void generateMagicShelvesNavigation_shouldListShelves() {
            mockAuthenticatedUser();
            MagicShelf shelf = new MagicShelf();
            shelf.setId(7L);
            shelf.setName("Unread Favorites");
            when(magicShelfService.getUserShelvesForOpds(TEST_USER_ID)).thenReturn(List.of(shelf));

            String xml = opdsFeedService.generateMagicShelvesNavigation();

            assertThat(xml)
                    .contains("Unread Favorites")
                    .contains("urn:booklore:magic-shelf:7")
                    .contains("</feed>");
        }

        @Test
        void generateMagicShelvesNavigation_shouldHandleNoShelves() {
            mockAuthenticatedUser();
            when(magicShelfService.getUserShelvesForOpds(TEST_USER_ID)).thenReturn(Collections.emptyList());

            String xml = opdsFeedService.generateMagicShelvesNavigation();

            assertThat(xml).contains("</feed>").doesNotContain("magic-shelf:");
        }

        @Test
        void generateAuthorsNavigation_shouldListAuthors() {
            mockAuthenticatedUser();
            when(opdsBookService.getDistinctAuthors(TEST_USER_ID)).thenReturn(List.of("Jane Austen"));

            String xml = opdsFeedService.generateAuthorsNavigation();

            assertThat(xml)
                    .contains("Jane Austen")
                    .contains("Books by Jane Austen")
                    .contains("</feed>");
        }

        @Test
        void generateSeriesNavigation_shouldListSeries() {
            mockAuthenticatedUser();
            when(opdsBookService.getDistinctSeries(TEST_USER_ID)).thenReturn(List.of("Discworld"));

            String xml = opdsFeedService.generateSeriesNavigation();

            assertThat(xml)
                    .contains("Discworld")
                    .contains("Books in the Discworld series")
                    .contains("</feed>");
        }
    }

    @Nested
    class CatalogFeedByMagicShelfAuthorSeries {

        private void stubCommonRequestParams() {
            when(request.getParameter("libraryId")).thenReturn(null);
            when(request.getParameter("shelfId")).thenReturn(null);
            when(request.getParameter("shelfIds")).thenReturn(null);
            when(request.getParameter("q")).thenReturn(null);
            when(request.getParameter("page")).thenReturn(null);
            when(request.getParameter("size")).thenReturn(null);
            when(request.getRequestURI()).thenReturn("/api/v1/opds/catalog");
        }

        @Test
        void generateCatalogFeed_withMagicShelfId_usesMagicShelfBookService() {
            mockAuthenticatedUser();
            stubCommonRequestParams();
            when(request.getParameter("magicShelfId")).thenReturn("7");
            when(request.getParameter("author")).thenReturn(null);
            when(request.getParameter("series")).thenReturn(null);

            Book book = Book.builder().id(1L).addedOn(FIXED_INSTANT)
                    .metadata(BookMetadata.builder().title("Magic Shelf Book").build())
                    .build();
            Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
            when(magicShelfBookService.getBooksByMagicShelfId(TEST_USER_ID, 7L, 0, 50)).thenReturn(page);
            when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);
            when(magicShelfBookService.getMagicShelfName(7L)).thenReturn("Unread Favorites");

            String xml = opdsFeedService.generateCatalogFeed(request);

            assertThat(xml)
                    .contains("Magic Shelf Book")
                    .contains("urn:booklore:magic-shelf:7")
                    .contains("Unread Favorites")
                    .contains("</feed>");
            verify(magicShelfBookService).getBooksByMagicShelfId(TEST_USER_ID, 7L, 0, 50);
            verify(opdsBookService, never()).getBooksPage(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void generateCatalogFeed_withAuthor_searchesByAuthorName() {
            mockAuthenticatedUser();
            stubCommonRequestParams();
            when(request.getParameter("magicShelfId")).thenReturn(null);
            when(request.getParameter("author")).thenReturn("Jane Austen");
            when(request.getParameter("series")).thenReturn(null);

            Book book = Book.builder().id(1L).addedOn(FIXED_INSTANT)
                    .metadata(BookMetadata.builder().title("Pride and Prejudice").build())
                    .build();
            Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
            when(opdsBookService.getBooksByAuthorName(TEST_USER_ID, "Jane Austen", 0, 50)).thenReturn(page);
            when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

            String xml = opdsFeedService.generateCatalogFeed(request);

            assertThat(xml)
                    .contains("Pride and Prejudice")
                    .contains("Books by Jane Austen")
                    .contains("urn:booklore:author:Jane Austen")
                    .contains("</feed>");
            verify(opdsBookService).getBooksByAuthorName(TEST_USER_ID, "Jane Austen", 0, 50);
        }

        @Test
        void generateCatalogFeed_withSeries_searchesBySeriesName() {
            mockAuthenticatedUser();
            stubCommonRequestParams();
            when(request.getParameter("magicShelfId")).thenReturn(null);
            when(request.getParameter("author")).thenReturn(null);
            when(request.getParameter("series")).thenReturn("Discworld");

            Book book = Book.builder().id(1L).addedOn(FIXED_INSTANT)
                    .metadata(BookMetadata.builder().title("Guards! Guards!").build())
                    .build();
            Page<Book> page = new PageImpl<>(List.of(book), PageRequest.of(0, 50), 1);
            when(opdsBookService.getBooksBySeriesName(TEST_USER_ID, "Discworld", 0, 50)).thenReturn(page);
            when(opdsBookService.applySortOrder(any(), any())).thenReturn(page);

            String xml = opdsFeedService.generateCatalogFeed(request);

            assertThat(xml)
                    .contains("Guards! Guards!")
                    .contains("Discworld series")
                    .contains("urn:booklore:series:Discworld")
                    .contains("</feed>");
            verify(opdsBookService).getBooksBySeriesName(TEST_USER_ID, "Discworld", 0, 50);
        }
    }

    @Nested
    class GetSortOrder {

        private OpdsSortOrder invokeGetSortOrder() throws Exception {
            var method = OpdsFeedService.class.getDeclaredMethod("getSortOrder");
            method.setAccessible(true);
            return (OpdsSortOrder) method.invoke(opdsFeedService);
        }

        @Test
        void returnsUserPreferredSortOrder_whenSet() throws Exception {
            OpdsUserDetails userDetails = mock(OpdsUserDetails.class);
            OpdsUserV2 v2 = mock(OpdsUserV2.class);
            when(userDetails.getOpdsUserV2()).thenReturn(v2);
            when(v2.getSortOrder()).thenReturn(OpdsSortOrder.TITLE_ASC);
            when(authenticationService.getOpdsUser()).thenReturn(userDetails);

            assertThat(invokeGetSortOrder()).isEqualTo(OpdsSortOrder.TITLE_ASC);
        }

        @Test
        void returnsRecent_whenSortOrderIsNull() throws Exception {
            OpdsUserDetails userDetails = mock(OpdsUserDetails.class);
            OpdsUserV2 v2 = mock(OpdsUserV2.class);
            when(userDetails.getOpdsUserV2()).thenReturn(v2);
            when(v2.getSortOrder()).thenReturn(null);
            when(authenticationService.getOpdsUser()).thenReturn(userDetails);

            assertThat(invokeGetSortOrder()).isEqualTo(OpdsSortOrder.RECENT);
        }

        @Test
        void returnsRecent_whenNotAuthenticated() throws Exception {
            when(authenticationService.getOpdsUser()).thenReturn(null);

            assertThat(invokeGetSortOrder()).isEqualTo(OpdsSortOrder.RECENT);
        }
    }
}
