package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.net.ConnectException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LubimyCzytacParserTest {

    @Mock
    private AppSettingService appSettingService;

    private MockedStatic<Jsoup> mockJsoup;

    private LubimyCzytacParser parser;

    private String exampleSearchHtmlFixture;

    private String exampleBookHtmlFixture;

    @BeforeEach
    void setUp() throws IOException {
        parser = new LubimyCzytacParser(appSettingService, new ObjectMapper());

        exampleSearchHtmlFixture = readFixture("example-search.html");
        exampleBookHtmlFixture = readFixture("example-book.html");

        mockJsoup = mockStatic(Jsoup.class);

    }

    @AfterEach
    void tearDown() {
        mockJsoup.close();
    }

    @Test
    void testFetchMetadata_ProviderDisabled() {
        // Given
        Book book = Book.builder()
            .title("Test Book")
            .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
            .title("Test Book")
            .build();

        // Mock disabled provider
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Lubimyczytac lubimyCzytac = new MetadataProviderSettings.Lubimyczytac();
        lubimyCzytac.setEnabled(false);
        providerSettings.setLubimyczytac(lubimyCzytac);
        appSettings.setMetadataProviderSettings(providerSettings);

        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        // Then
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Should return empty list when provider is disabled");
        verify(appSettingService).getAppSettings();
    }

    @Test
    void testFetchMetadata_ProviderSettingsNull() {
        // Given
        Book book = Book.builder()
            .title("Test Book")
            .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
            .title("Test Book")
            .build();

        // Mock null settings
        AppSettings appSettings = new AppSettings();
        appSettings.setMetadataProviderSettings(null);

        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        // Then
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Should return empty list when settings are null");
        verify(appSettingService).getAppSettings();
    }

    @Test
    void testFetchMetadata_EmptyQuery() {
        // Given
        Book book = Book.builder()
            .title("Test Book")
            .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
            .build();
        // Empty query - no title or ISBN

        // Mock enabled provider
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Lubimyczytac lubimyCzytac = new MetadataProviderSettings.Lubimyczytac();
        lubimyCzytac.setEnabled(true);
        providerSettings.setLubimyczytac(lubimyCzytac);
        appSettings.setMetadataProviderSettings(providerSettings);

        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        // Then
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Should return empty list when query is empty");
        verify(appSettingService).getAppSettings();
    }

    @Test
    void testFetchMetadata_parsesBook() throws Exception {
        // Given
        Book book = Book.builder()
            .title("Sklepy cynamonowe")
            .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
            .title("Sklepy cynamonowe")
            .author("Bruno Schulz")
            .build();

        // Mock enabled provider
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Lubimyczytac lubimyCzytac = new MetadataProviderSettings.Lubimyczytac();
        lubimyCzytac.setEnabled(true);
        providerSettings.setLubimyczytac(lubimyCzytac);
        appSettings.setMetadataProviderSettings(providerSettings);

        // Two expected URLs
        mockResponse("https://lubimyczytac.pl/szukaj/ksiazki", exampleSearchHtmlFixture);
        mockResponse("https://lubimyczytac.pl/ksiazka/", exampleBookHtmlFixture);

        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        // Then
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Should return results for real book");

        BookMetadata result = results.getFirst();
        assertEquals("Sklepy cynamonowe", result.getTitle());
        assertNull(result.getIsbn10());
        assertEquals("9788384258149", result.getIsbn13());
        assertEquals("5223841", result.getLubimyczytacId());
        assertNotNull(result.getAuthors());
        assertEquals(1, result.getAuthors().size());
        assertEquals("Bruno Schulz", result.getAuthors().getFirst());

        // The description is very long, but we need to make sure we're in the right ballpark.
        assertEquals("Jedno z najoryginalniejszych", result.getDescription().substring(0, 28));
        assertEquals(827, result.getDescription().length());
    }

    @Test
    void testFetchMetadata_stopsWithRegularException() throws Exception {
        // Given
        Book book = Book.builder()
                .title("Sklepy cynamonowe")
                .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("Sklepy cynamonowe")
                .author("Bruno Schulz")
                .build();

        // Mock enabled provider
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Lubimyczytac lubimyCzytac = new MetadataProviderSettings.Lubimyczytac();
        lubimyCzytac.setEnabled(true);
        providerSettings.setLubimyczytac(lubimyCzytac);
        appSettings.setMetadataProviderSettings(providerSettings);

        mockResponseThrow("https://lubimyczytac.pl/szukaj/ksiazki", new IOException());

        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        assertEquals(0, results.size());
        mockJsoup.verify(() -> Jsoup.connect(anyString()), times(1));
    }

    @Test
    void testFetchMetadata_retriesWithConnectionException() throws Exception {
        // Given
        Book book = Book.builder()
                .title("Sklepy cynamonowe")
                .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("Sklepy cynamonowe")
                .author("Bruno Schulz")
                .build();

        // Mock enabled provider
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Lubimyczytac lubimyCzytac = new MetadataProviderSettings.Lubimyczytac();
        lubimyCzytac.setEnabled(true);
        providerSettings.setLubimyczytac(lubimyCzytac);
        appSettings.setMetadataProviderSettings(providerSettings);

        mockResponseThrow("https://lubimyczytac.pl/szukaj/ksiazki", new ConnectException());

        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        assertEquals(0, results.size());
        mockJsoup.verify(() -> Jsoup.connect(anyString()), times(3));
    }

    @Test
    void testFetchMetadata_retriesWithWrappedConnectionException() throws Exception {
        // Given
        Book book = Book.builder()
                .title("Sklepy cynamonowe")
                .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("Sklepy cynamonowe")
                .author("Bruno Schulz")
                .build();

        // Mock enabled provider
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Lubimyczytac lubimyCzytac = new MetadataProviderSettings.Lubimyczytac();
        lubimyCzytac.setEnabled(true);
        providerSettings.setLubimyczytac(lubimyCzytac);
        appSettings.setMetadataProviderSettings(providerSettings);

        mockResponseThrow("https://lubimyczytac.pl/szukaj/ksiazki", new IOException("Wrapped", new ConnectException()));

        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        assertEquals(0, results.size());
        mockJsoup.verify(() -> Jsoup.connect(anyString()), times(3));
    }

    private String readFixture(String fixtureName) throws IOException {
        String filename = Paths.get("lubimyczytac", fixtureName + ".fixture").toString();

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            assert is != null;

            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void mockResponseThrow(String urlPrefix, Throwable exception) throws Exception {
        Connection mockConnection = mock(Connection.class);

        when(mockConnection.userAgent(any())).thenReturn(mockConnection);
        when(mockConnection.timeout(anyInt())).thenReturn(mockConnection);

        when(mockConnection.get()).thenThrow(exception);

        mockJsoup.when(() -> Jsoup.connect(startsWith(urlPrefix))).thenReturn(mockConnection);
    }

    private void mockResponse(String urlPrefix, String response) throws Exception {
        Connection mockConnection = mock(Connection.class);

        when(mockConnection.userAgent(any())).thenReturn(mockConnection);
        when(mockConnection.timeout(anyInt())).thenReturn(mockConnection);

        // There may be a better way to get the parse to work here.
        // However, this was the quickest and simplest way I could find.
        mockJsoup.when(() -> Jsoup.parse(response)).thenCallRealMethod();

        when(mockConnection.get()).thenAnswer(i -> Jsoup.parse(response));

        mockJsoup.when(() -> Jsoup.connect(startsWith(urlPrefix))).thenReturn(mockConnection);
    }

    private AppSettings enabledProviderSettings() {
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Lubimyczytac lubimyCzytac = new MetadataProviderSettings.Lubimyczytac();
        lubimyCzytac.setEnabled(true);
        providerSettings.setLubimyczytac(lubimyCzytac);
        appSettings.setMetadataProviderSettings(providerSettings);
        return appSettings;
    }

    private String searchPageWithBookCard(String href) {
        return "<html><body><div id=\"ksiazkiPaginator\">"
                + "<div class=\"book-card\"><a class=\"book-card__title\" href=\"" + href + "\">Title</a></div>"
                + "</div></body></html>";
    }

    @Nested
    @DisplayName("isProviderEnabled - additional branches")
    class ProviderEnabledAdditionalTests {

        @Test
        @DisplayName("returns empty results when settings are present but the lubimyczytac block itself is null")
        void lubimyczytacBlockNull_returnsEmpty() {
            Book book = Book.builder().title("Test Book").build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Test Book").build();

            AppSettings appSettings = new AppSettings();
            appSettings.setMetadataProviderSettings(new MetadataProviderSettings());
            when(appSettingService.getAppSettings()).thenReturn(appSettings);

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("search result collection")
    class SearchResultCollectionTests {

        @Test
        @DisplayName("caps collected results at MAX_RESULTS even when more search results are available")
        void capsResultsAtMaxResults() throws Exception {
            Book book = Book.builder().title("Test Book").build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Test Book").build();
            when(appSettingService.getAppSettings()).thenReturn(enabledProviderSettings());

            StringBuilder cards = new StringBuilder("<html><body><div id=\"ksiazkiPaginator\">");
            for (int i = 0; i < 11; i++) {
                cards.append("<div class=\"book-card\"><a class=\"book-card__title\" href=\"/ksiazka/")
                        .append(i).append("/title-").append(i).append("\">Title</a></div>");
            }
            cards.append("</div></body></html>");

            mockResponse("https://lubimyczytac.pl/szukaj/ksiazki", cards.toString());
            mockResponse("https://lubimyczytac.pl/ksiazka/", "<html><body><h1 class=\"book__title\">Some Title</h1></body></html>");

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(10);
        }

        @Test
        @DisplayName("excludes a parsed result whose ISBN doesn't match the search ISBN")
        void excludesResultWithMismatchedIsbn() throws Exception {
            Book book = Book.builder().title("Test Book").build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Test Book").isbn("9780000000000").build();
            when(appSettingService.getAppSettings()).thenReturn(enabledProviderSettings());

            mockResponse("https://lubimyczytac.pl/szukaj/ksiazki", searchPageWithBookCard("/ksiazka/42/title"));
            String detailHtml = "<html><body><h1 class=\"book__title\">Other Book</h1>"
                    + "<meta property=\"books:isbn\" content=\"9789999999999\"></body></html>";
            mockResponse("https://lubimyczytac.pl/ksiazka/", detailHtml);

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("a book-card whose URL doesn't match the /ksiazka/{id} pattern still parses, with a null lubimyczytacId")
        void nonKsiazkaUrl_stillParsesWithNullId() throws Exception {
            Book book = Book.builder().title("Test Book").build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Test Book").build();
            when(appSettingService.getAppSettings()).thenReturn(enabledProviderSettings());

            mockResponse("https://lubimyczytac.pl/szukaj/ksiazki", searchPageWithBookCard("/audiobook/xyz/title"));
            mockResponse("https://lubimyczytac.pl/audiobook/", "<html><body><h1 class=\"book__title\">Audio Title</h1></body></html>");

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getLubimyczytacId()).isNull();
            assertThat(results.getFirst().getTitle()).isEqualTo("Audio Title");
        }
    }

    @Nested
    @DisplayName("book detail field parsing")
    class BookDetailFieldParsingTests {

        @Test
        @DisplayName("parses a 10-digit ISBN, tags, a 'Cykl:' series with a tom number, and JSON-LD array authors/genre/pages/date")
        void richDetailPage_parsesAllAdditionalFields() throws Exception {
            Book book = Book.builder().title("Test Book").build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Test Book").build();
            when(appSettingService.getAppSettings()).thenReturn(enabledProviderSettings());

            mockResponse("https://lubimyczytac.pl/szukaj/ksiazki", searchPageWithBookCard("/ksiazka/42/title"));
            String detailHtml = """
                    <html><body>
                    <h1 class="book__title">Rich Book</h1>
                    <meta property="books:isbn" content="0393341769">
                    <meta property="books:rating:value" content="not-a-number">
                    <a href="/ksiazki/t/fantasy">Fantasy</a>
                    <a href="/ksiazki/t/adventure">Adventure</a>
                    <span class="d-none d-sm-block mt-1">Cykl: Rich Series (tom 3)</span>
                    <script type="application/ld+json">
                    {"numberOfPages": 350, "datePublished": "2020-05-01",
                     "author": [{"name": "Author One"}, {"name": "Author Two"}],
                     "genre": "https://lubimyczytac.pl/ksiazki/k/69/fantastyka"}
                    </script>
                    </body></html>
                    """;
            mockResponse("https://lubimyczytac.pl/ksiazka/", detailHtml);

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            BookMetadata metadata = results.getFirst();
            assertThat(metadata.getIsbn10()).isEqualTo("0393341769");
            assertThat(metadata.getIsbn13()).isNull();
            // A non-numeric rating is silently ignored rather than throwing.
            assertThat(metadata.getLubimyczytacRating()).isNull();
            assertThat(metadata.getTags()).containsExactlyInAnyOrder("Fantasy", "Adventure");
            assertThat(metadata.getSeriesName()).isEqualTo("Rich Series");
            assertThat(metadata.getSeriesNumber()).isEqualTo(3.0f);
            assertThat(metadata.getPageCount()).isEqualTo(350);
            assertThat(metadata.getPublishedDate()).isEqualTo(java.time.LocalDate.of(2020, java.time.Month.MAY, 1));
            assertThat(metadata.getAuthors()).containsExactly("Author One", "Author Two");
            assertThat(metadata.getCategories()).containsExactly("fantastyka");
        }

        @Test
        @DisplayName("a 'Cykl:' series without a '(tom X)' number uses the whole text as the series name")
        void seriesWithoutTomNumber_usesFullTextAsSeriesName() throws Exception {
            Book book = Book.builder().title("Test Book").build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Test Book").build();
            when(appSettingService.getAppSettings()).thenReturn(enabledProviderSettings());

            mockResponse("https://lubimyczytac.pl/szukaj/ksiazki", searchPageWithBookCard("/ksiazka/42/title"));
            String detailHtml = "<html><body><h1 class=\"book__title\">Standalone</h1>"
                    + "<span class=\"d-none d-sm-block mt-1\">Cykl: Simple Series</span>"
                    + "</body></html>";
            mockResponse("https://lubimyczytac.pl/ksiazka/", detailHtml);

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getSeriesName()).isEqualTo("Simple Series");
            assertThat(results.getFirst().getSeriesNumber()).isNull();
        }
    }

    @Nested
    @DisplayName("isConnectivityError - additional branches")
    class ConnectivityErrorAdditionalTests {

        @Test
        @DisplayName("a SocketTimeoutException is treated as a connectivity error and retried")
        void socketTimeoutException_retries() throws Exception {
            Book book = Book.builder().title("Test Book").build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Test Book").build();
            when(appSettingService.getAppSettings()).thenReturn(enabledProviderSettings());

            mockResponseThrow("https://lubimyczytac.pl/szukaj/ksiazki", new SocketTimeoutException());

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
            mockJsoup.verify(() -> Jsoup.connect(anyString()), times(3));
        }
    }
}
