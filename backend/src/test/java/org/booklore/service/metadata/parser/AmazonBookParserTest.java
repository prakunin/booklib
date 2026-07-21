package org.booklore.service.metadata.parser;


import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AmazonBookParserTest {
    @Mock private AppSettingService mockAppSettingService;

    @InjectMocks
    private AmazonBookParser amazonBookParser;

    private MockedStatic<Jsoup> mockJsoup;

    private AppSettings getAppSettings(String domain) {
        MetadataProviderSettings.Amazon amazonSettings = new MetadataProviderSettings.Amazon();
        amazonSettings.setEnabled(true);
        amazonSettings.setDomain(domain);

        MetadataProviderSettings metadataProviderSettings = new MetadataProviderSettings();
        metadataProviderSettings.setAmazon(amazonSettings);

        MetadataPublicReviewsSettings publicReviewsSettings = MetadataPublicReviewsSettings.builder()
                .providers(Collections.emptySet())
                .build();

        return AppSettings
                .builder()
                .metadataPublicReviewsSettings(publicReviewsSettings)
                .metadataProviderSettings(metadataProviderSettings)
                .build();
    }

    private Book getBook(String asin) {
        BookMetadata bookMetadata = BookMetadata.builder()
                .asin(asin)
                .build();

        return Book.builder()
                .title("Example")
                .metadata(bookMetadata)
                .build();
    }

    private Connection getConnection(Document document) throws IOException {
        Connection mockConnection = mock(Connection.class);

        Connection.Response mockResponse = mock(Connection.Response.class);

        when(mockConnection.header(any(String.class), any(String.class))).thenReturn(mockConnection);
        when(mockConnection.method(any(Connection.Method.class))).thenReturn(mockConnection);
        when(mockConnection.execute()).thenReturn(mockResponse);

        when(mockResponse.parse()).thenReturn(document);

        return mockConnection;
    }

    private void mockJsoupConnect(String url, String html) throws Exception {
        Document document = Parser.parse(html, "");
        Connection connection = getConnection(document);

        mockJsoup.when(() -> Jsoup.connect(url))
                .thenReturn(connection);
    }

    private void mockJsoupConnectThrowing(String url, Exception toThrow) throws Exception {
        Connection mockConnection = mock(Connection.class);
        when(mockConnection.header(any(String.class), any(String.class))).thenReturn(mockConnection);
        when(mockConnection.method(any(Connection.Method.class))).thenReturn(mockConnection);
        when(mockConnection.execute()).thenThrow(toThrow);

        mockJsoup.when(() -> Jsoup.connect(url)).thenReturn(mockConnection);
    }

    private String readFixture(String fixtureName) throws IOException {
        String filename = "amazon/" + fixtureName;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            assert is != null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private AppSettings getAppSettingsWithReviews(String domain, int maxReviews) {
        MetadataProviderSettings.Amazon amazonSettings = new MetadataProviderSettings.Amazon();
        amazonSettings.setEnabled(true);
        amazonSettings.setDomain(domain);

        MetadataProviderSettings metadataProviderSettings = new MetadataProviderSettings();
        metadataProviderSettings.setAmazon(amazonSettings);

        MetadataPublicReviewsSettings.ReviewProviderConfig cfg = MetadataPublicReviewsSettings.ReviewProviderConfig.builder()
                .provider(MetadataProvider.Amazon)
                .enabled(true)
                .maxReviews(maxReviews)
                .build();
        MetadataPublicReviewsSettings publicReviewsSettings = MetadataPublicReviewsSettings.builder()
                .providers(Set.of(cfg))
                .build();

        return AppSettings.builder()
                .metadataPublicReviewsSettings(publicReviewsSettings)
                .metadataProviderSettings(metadataProviderSettings)
                .build();
    }

    private void mockAmazonIDSearch(String keyword) throws Exception {
        mockJsoupConnect(
                "https://www.amazon.com/s?k=" + keyword,
                """
                <html><body>
                <span data-component-type="s-search-results">
                <div div role="listitem" data-index>
                    <div data-cy="title-recipe">Example</div>
                    <a href="https://www.amazon.com/dp/SEARCHASIN">Paperback</a>
                </div>
                </span>
                </body></html>
                """
        );

    }

    @BeforeEach
    void setup() {
        when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettings( "com"));

        mockJsoup = mockStatic(Jsoup.class);
    }

    @AfterEach
    void tearDown() {
        mockJsoup.close();
    }

    @ParameterizedTest(name = "asin=\"{0}\" is normalized before connecting")
    @ValueSource(strings = {"EXAMPLESKU", "  EXAMPLESKU  ", "@EXAMPLESKU!!"})
    void fetchTopMetadata_normalizesAsinFromBook(String rawAsin) throws Exception {
        mockJsoupConnect("https://www.amazon.com/dp/EXAMPLESKU", "<html />");

        Book book = getBook(rawAsin);
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/EXAMPLESKU"));
    }

    @Test
    void fetchTopMetadata_useDomain() throws Exception {
        mockJsoupConnect("https://www.amazon.co.jp/dp/EXAMPLESKU", "<html />");
        when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettings( "co.jp"));

        Book book = getBook("EXAMPLESKU");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.co.jp/dp/EXAMPLESKU"));
    }

    @Test
    void fetchTopMetadata_ignoresInvalidBookASINUsesLink() throws Exception {
        mockAmazonIDSearch("Example");
        mockJsoupConnect("https://www.amazon.com/dp/SEARCHASIN", "<html />");

        // Not 10 characters, alpha-numeric.
        Book book = getBook("bad asin");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().title("Example").build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/SEARCHASIN"));
    }

    @Test
    void fetchTopMetadata_ignoresMissingBookASINUsesLink() throws Exception {
        mockAmazonIDSearch("Example");
        mockJsoupConnect("https://www.amazon.com/dp/SEARCHASIN", "<html />");

        Book book = getBook(null);
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().title("Example").build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/SEARCHASIN"));
    }

    @Test
    void fetchTopMetadata_cleansLineBreaks() throws Exception {
        String fakePageHtml = """
        <html><body>
        <div class="product-description">
        <br /><br   ><br><div>Hello</div><br /><br /><br><div>World</div><br /><br   ><br>
        </div>
        </body></html>
        """;

        mockAmazonIDSearch("Example");
        mockJsoupConnect(
                "https://www.amazon.com/dp/SEARCHASIN", fakePageHtml);

        Book book = getBook(null);
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().title("Example").build();

        mockJsoup.when(() -> Jsoup.parse(anyString())).thenCallRealMethod();

        BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        String actual = metadata.getDescription().replace("\n", "");
        assertEquals("<div>Hello</div><br><br><div>World</div>", actual);
    }

    @Nested
    @DisplayName("full detail page parsing")
    class FullDetailPageTests {

        @Test
        @DisplayName("parses every metadata field, including reviews, from a full RPI-driven detail page")
        @SuppressWarnings("java:S5961") // one thorough end-to-end assertion of every parsed field from a single fixture
        void parsesAllFieldsFromFullPage() throws Exception {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsWithReviews("com", 5));
            mockJsoupConnect("https://www.amazon.com/dp/EXAMPLEA1B", readFixture("amazon-book-page-full.html"));

            Book book = getBook("EXAMPLEA1B");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Example Book");
            assertThat(metadata.getSubtitle()).isEqualTo("A Subtitle");
            assertThat(metadata.getAuthors()).containsExactly("Author One", "Author Two");
            assertThat(metadata.getDescription()).contains("First line.").contains("Second line.");
            assertThat(metadata.getIsbn10()).isEqualTo("042519078X");
            assertThat(metadata.getIsbn13()).isEqualTo("9780425190780");
            assertThat(metadata.getPublisher()).isEqualTo("Ace");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2019, Month.APRIL, 1));
            assertThat(metadata.getSeriesName()).isEqualTo("The Example Series");
            assertThat(metadata.getSeriesNumber()).isEqualTo(3.0f);
            assertThat(metadata.getSeriesTotal()).isEqualTo(6);
            assertThat(metadata.getLanguage()).isEqualTo("en");
            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("Science Fiction", "Adventure");
            assertThat(metadata.getAmazonRating()).isEqualTo(4.5);
            assertThat(metadata.getAmazonReviewCount()).isEqualTo(1234);
            assertThat(metadata.getThumbnailUrl()).isEqualTo("https://example.com/hires.jpg");
            assertThat(metadata.getPageCount()).isEqualTo(412);
            assertThat(metadata.getAsin()).isEqualTo("EXAMPLEA1B");

            assertThat(metadata.getBookReviews()).hasSize(1);
            var review = metadata.getBookReviews().getFirst();
            assertThat(review.getReviewerName()).isEqualTo("Jane Reader");
            assertThat(review.getTitle()).isEqualTo("Loved it");
            assertThat(review.getRating()).isEqualTo(4.0f);
            assertThat(review.getCountry()).isEqualTo("United States");
            assertThat(review.getBody()).isEqualTo("Great book overall.");
            assertThat(review.getDate()).isEqualTo(LocalDate.of(2019, Month.APRIL, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        }

        @Test
        @DisplayName("falls back to detailBullets, parenthetical dates, alternate-language publisher headers, and a src thumbnail when RPI data is absent")
        void fallsBackWhenRpiDataAbsent() throws Exception {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsWithReviews("de", 5));
            mockJsoupConnect("https://www.amazon.de/dp/FALLBACK01", readFixture("amazon-book-page-fallbacks.html"));

            Book book = getBook("FALLBACK01");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Fallback Title");
            assertThat(metadata.getSubtitle()).isEqualTo("Fallback Subtitle");
            assertThat(metadata.getAuthors()).containsExactly("Fallback Author");
            assertThat(metadata.getDescription()).isEqualTo("Noscript description text.");
            assertThat(metadata.getIsbn10()).isEqualTo("042519078X");
            assertThat(metadata.getIsbn13()).isEqualTo("9780425190780");
            assertThat(metadata.getPublisher()).isEqualTo("Beispielverlag");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2019, Month.APRIL, 1));
            assertThat(metadata.getAmazonRating()).isEqualTo(3.8);
            assertThat(metadata.getAmazonReviewCount()).isNull();
            assertThat(metadata.getThumbnailUrl()).isEqualTo("https://example.com/fallback-only.jpg");
            assertThat(metadata.getPageCount()).isNull();

            assertThat(metadata.getBookReviews()).hasSize(1);
            var review = metadata.getBookReviews().getFirst();
            assertThat(review.getReviewerName()).isEqualTo("German Reader");
            assertThat(review.getRating()).isEqualTo(5.0f);
            // The "vom ... Januar ..." date only parses under the German-locale fallback loop,
            // since "Januar" isn't recognized as a month name by the default English locale.
            assertThat(review.getCountry()).isEqualTo("Deutschland");
            assertThat(review.getDate()).isEqualTo(LocalDate.of(2019, Month.JANUARY, 2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            assertThat(review.getBody()).isEqualTo("Sehr gutes Buch.");
        }

        @Test
        @DisplayName("returns null fields when the detail page has no recognizable structure")
        void handlesEmptyDetailPage() throws Exception {
            mockJsoupConnect("https://www.amazon.com/dp/EMPTYPAGE1", "<html><body></body></html>");

            Book book = getBook("EMPTYPAGE1");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isNull();
            assertThat(metadata.getAuthors()).isEmpty();
            assertThat(metadata.getIsbn10()).isNull();
            assertThat(metadata.getIsbn13()).isNull();
            assertThat(metadata.getPublisher()).isNull();
            assertThat(metadata.getPublishedDate()).isNull();
            assertThat(metadata.getSeriesName()).isNull();
            assertThat(metadata.getLanguage()).isNull();
            assertThat(metadata.getCategories()).isEmpty();
            assertThat(metadata.getAmazonRating()).isNull();
            assertThat(metadata.getAmazonReviewCount()).isNull();
            assertThat(metadata.getThumbnailUrl()).isNull();
            assertThat(metadata.getPageCount()).isNull();
        }

        @Test
        @DisplayName("falls back to the small rating selector when the base rating selector is absent")
        void fallsBackToSmallRatingSelector() throws Exception {
            String html = """
                    <html><body>
                    <div id="averageCustomerReviews_feature_div">
                    <span id="acrPopover"><span class="a-size-small a-color-base">4.2</span></span>
                    </div>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/SMALLRATNG", html);

            Book book = getBook("SMALLRATNG");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata.getAmazonRating()).isEqualTo(4.2);
        }

        @Test
        @DisplayName("strips a leading 'the' from the review country when reviews are enabled")
        void stripsLeadingTheFromReviewCountry() throws Exception {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsWithReviews("com", 5));
            String html = """
                    <html><body>
                    <li data-hook="review">
                    <div data-hook="review-date">Reviewed in the United Kingdom on January 1, 2020</div>
                    <div data-hook="review-body">Nice book.</div>
                    </li>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/THEPREFIX1", html);

            Book book = getBook("THEPREFIX1");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).hasSize(1);
            assertThat(metadata.getBookReviews().getFirst().getCountry()).isEqualTo("United Kingdom");
        }
    }

    @Nested
    @DisplayName("search result filtering (getAmazonBookIds / resolveNewBookId / extractAmazonBookId)")
    class SearchResultFilteringTests {

        @Test
        @DisplayName("skips box-set/collection/empty/missing-title items and dedupes by ASIN, picking the first valid item")
        void fetchTopMetadata_pickFirstValidSearchResult() throws Exception {
            mockJsoupConnect("https://www.amazon.com/s?k=Example", readFixture("amazon-search-results.html"));
            mockJsoupConnect("https://www.amazon.com/dp/ASINONE1XX", "<html><body><span id=\"productTitle\">Example Book One</span></body></html>");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Example").build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getAsin()).isEqualTo("ASINONE1XX");
        }

        @Test
        @DisplayName("fetchMetadata collects distinct valid results, including the data-asin fallback item")
        void fetchMetadata_collectsDistinctResults() throws Exception {
            mockJsoupConnect("https://www.amazon.com/s?k=Example", readFixture("amazon-search-results.html"));
            mockJsoupConnect("https://www.amazon.com/dp/ASINONE1XX", "<html/>");
            mockJsoupConnect("https://www.amazon.com/dp/DATAASIN1X", "<html/>");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Example").build();

            List<BookMetadata> results = amazonBookParser.fetchMetadata(book, request);

            assertThat(results).extracting(BookMetadata::getAsin).containsExactlyInAnyOrder("ASINONE1XX", "DATAASIN1X");
        }

        @Test
        @DisplayName("skips an ASIN whose detail fetch throws, but still returns metadata for the remaining ones")
        void fetchMetadata_skipsAsinThatThrowsDuringDetailFetch() throws Exception {
            mockJsoupConnect("https://www.amazon.com/s?k=Example", readFixture("amazon-search-results.html"));
            mockJsoupConnectThrowing("https://www.amazon.com/dp/ASINONE1XX", new IOException("network hiccup"));
            mockJsoupConnect("https://www.amazon.com/dp/DATAASIN1X", "<html/>");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Example").build();

            List<BookMetadata> results = amazonBookParser.fetchMetadata(book, request);

            assertThat(results).extracting(BookMetadata::getAsin).containsExactly("DATAASIN1X");
        }

        @Test
        @DisplayName("getAmazonBookIds returns no ids and does not throw when the search request fails with a plain IOException")
        void fetchTopMetadata_searchThrowsPlainIOException_returnsNull() throws Exception {
            mockJsoupConnectThrowing("https://www.amazon.com/s?k=Example", new IOException("connection reset"));

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Example").build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata).isNull();
        }
    }

    @Nested
    @DisplayName("getExistingAsin / getTopAmazonBookId")
    class ExistingAsinTests {

        @Test
        @DisplayName("falls back to search when the book itself is null")
        void fetchTopMetadata_nullBook_fallsBackToSearch() throws Exception {
            mockAmazonIDSearch("Example");
            mockJsoupConnect("https://www.amazon.com/dp/SEARCHASIN", "<html />");

            FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().title("Example").build();

            amazonBookParser.fetchTopMetadata(null, fetchMetadataRequest);

            mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/SEARCHASIN"));
        }

        @Test
        @DisplayName("falls back to search when the book has no metadata")
        void fetchTopMetadata_bookWithNullMetadata_fallsBackToSearch() throws Exception {
            mockAmazonIDSearch("Example");
            mockJsoupConnect("https://www.amazon.com/dp/SEARCHASIN", "<html />");

            Book book = Book.builder().title("Example").metadata(null).build();
            FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().title("Example").build();

            amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

            mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/SEARCHASIN"));
        }
    }

    @Nested
    @DisplayName("buildQueryUrl")
    class BuildQueryUrlTests {

        @Test
        @DisplayName("prefers a cleaned ISBN over title/author when present")
        void usesIsbnWhenPresent() throws Exception {
            mockJsoupConnect("https://www.amazon.com/s?k=978-0425190780", "<html/>");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder()
                    .isbn("978-0425190780")
                    .title("Ignored Title")
                    .build();

            amazonBookParser.fetchMetadata(book, request);

            mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/s?k=978-0425190780"));
        }

        @Test
        @DisplayName("builds the search term from the cleaned file name when there is no title")
        void usesCleanedFileNameWhenNoTitle() throws Exception {
            mockJsoupConnect("https://www.amazon.com/s?k=My+Book", "<html/>");

            BookFile file = BookFile.builder().fileName("My Book (2020).epub").build();
            Book book = Book.builder().primaryFile(file).build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            amazonBookParser.fetchMetadata(book, request);

            mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/s?k=My+Book"));
        }

        @Test
        @DisplayName("appends the author to the title-derived search term")
        void appendsAuthorToTitle() throws Exception {
            mockJsoupConnect("https://www.amazon.com/s?k=Foo+Bar+Baz", "<html/>");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Foo").author("Bar Baz").build();

            amazonBookParser.fetchMetadata(book, request);

            mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/s?k=Foo+Bar+Baz"));
        }

        @Test
        @DisplayName("returns empty results and never connects when there is no isbn, title, filename, or author")
        void returnsEmptyWithoutConnecting() {
            Book book = Book.builder().build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            List<BookMetadata> results = amazonBookParser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
            mockJsoup.verify(() -> Jsoup.connect(anyString()), never());
        }
    }

    @Nested
    @DisplayName("fetchDetailedMetadata")
    class FetchDetailedMetadataTests {

        @Test
        @DisplayName("fetches metadata directly for a given ASIN")
        void fetchesDirectlyByAsin() throws Exception {
            mockJsoupConnect("https://www.amazon.com/dp/DIRECTASIN", "<html><body><span id=\"productTitle\">Direct Fetch Title</span></body></html>");

            BookMetadata metadata = amazonBookParser.fetchDetailedMetadata("DIRECTASIN");

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Direct Fetch Title");
        }
    }

    @Nested
    @DisplayName("fetchDocument error handling")
    class FetchDocumentErrorTests {

        @Test
        @DisplayName("a 503 response is treated as anti-scraping and yields a null result, not an exception")
        void status503_returnsNullGracefully() throws Exception {
            mockJsoupConnectThrowing("https://www.amazon.com/dp/BLOCKED0001", new HttpStatusException("Service Unavailable", 503, "https://www.amazon.com/dp/BLOCKED0001"));

            BookMetadata metadata = amazonBookParser.fetchDetailedMetadata("BLOCKED0001");

            assertThat(metadata).isNull();
        }

        @Test
        @DisplayName("a 500 response is also treated as anti-scraping and yields a null result")
        void status500_returnsNullGracefully() throws Exception {
            mockJsoupConnectThrowing("https://www.amazon.com/dp/SERVERERR01", new HttpStatusException("Internal Server Error", 500, "https://www.amazon.com/dp/SERVERERR01"));

            BookMetadata metadata = amazonBookParser.fetchDetailedMetadata("SERVERERR01");

            assertThat(metadata).isNull();
        }

        @Test
        @DisplayName("a 503 during search yields no book ids, not an exception")
        void status503DuringSearch_returnsNullGracefully() throws Exception {
            mockJsoupConnectThrowing("https://www.amazon.com/s?k=Example", new HttpStatusException("Service Unavailable", 503, "https://www.amazon.com/s?k=Example"));

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Example").build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata).isNull();
        }

        @Test
        @DisplayName("a non-antiscraping HTTP status (e.g. 404) propagates as an UncheckedIOException")
        void otherHttpStatus_propagatesUncheckedIOException() throws Exception {
            mockJsoupConnectThrowing("https://www.amazon.com/dp/NOTFOUND001", new HttpStatusException("Not Found", 404, "https://www.amazon.com/dp/NOTFOUND001"));

            assertThrows(UncheckedIOException.class, () -> amazonBookParser.fetchDetailedMetadata("NOTFOUND001"));
        }

        @Test
        @DisplayName("a plain network IOException propagates as an UncheckedIOException")
        void plainIOException_propagatesUncheckedIOException() throws Exception {
            mockJsoupConnectThrowing("https://www.amazon.com/dp/NETDOWN0001", new IOException("network down"));

            assertThrows(UncheckedIOException.class, () -> amazonBookParser.fetchDetailedMetadata("NETDOWN0001"));
        }
    }

    @Nested
    @DisplayName("review parsing edge cases")
    class ReviewParsingEdgeCaseTests {

        @Test
        @DisplayName("a Japanese-formatted review date is recognized when it doesn't match the 'Reviewed in ... on ...' pattern")
        void japaneseReviewDate_isParsed() throws Exception {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsWithReviews("co.jp", 5));
            String html = """
                    <html><body>
                    <li data-hook="review">
                    <div data-hook="review-date">2019年4月1日にレビュー済み</div>
                    <div data-hook="review-body">良い本です。</div>
                    </li>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.co.jp/dp/JAPANDATE1", html);

            Book book = getBook("JAPANDATE1");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).hasSize(1);
            var review = metadata.getBookReviews().getFirst();
            assertThat(review.getCountry()).isEqualTo("日本");
            assertThat(review.getDate()).isEqualTo(LocalDate.of(2019, Month.APRIL, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        }

        @Test
        @DisplayName("a trailing ' Read more' is stripped from the review body")
        void readMoreSuffix_isStrippedFromBody() throws Exception {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsWithReviews("com", 5));
            String html = """
                    <html><body>
                    <li data-hook="review">
                    <div data-hook="review-body">Great book overall. Read more</div>
                    </li>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/READMORE01", html);

            Book book = getBook("READMORE01");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).hasSize(1);
            assertThat(metadata.getBookReviews().getFirst().getBody()).isEqualTo("Great book overall.");
        }

        @Test
        @DisplayName("stops collecting reviews once maxReviews is reached, even though more review elements exist")
        void maxReviewsCutoff_stopsBeforeExhaustingElements() throws Exception {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsWithReviews("com", 1));
            String html = """
                    <html><body>
                    <li data-hook="review"><div data-hook="review-body">First review.</div></li>
                    <li data-hook="review"><div data-hook="review-body">Second review.</div></li>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/MAXREVIEW0", html);

            Book book = getBook("MAXREVIEW0");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).hasSize(1);
            assertThat(metadata.getBookReviews().getFirst().getBody()).isEqualTo("First review.");
        }

        @Test
        @DisplayName("a review with a blank body is skipped entirely")
        void blankReviewBody_isSkipped() throws Exception {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsWithReviews("com", 5));
            String html = """
                    <html><body>
                    <li data-hook="review"><div data-hook="review-body"></div></li>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/BLANKBODY1", html);

            Book book = getBook("BLANKBODY1");
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).isEmpty();
        }
    }

    @Nested
    @DisplayName("page count / review count parsing edge cases")
    class CountParsingEdgeCaseTests {

        @Test
        @DisplayName("a non-numeric page count value is left as null rather than throwing")
        void nonNumericPageCount_returnsNull() throws Exception {
            String html = """
                    <html><body>
                    <div id="rpi-attribute-book_details-fiona_pages" class="rpi-attribute">
                    <div class="rpi-attribute-value"><span>N/A</span></div>
                    </div>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/BADPAGECNT1", html);

            BookMetadata metadata = amazonBookParser.fetchDetailedMetadata("BADPAGECNT1");

            assertThat(metadata.getPageCount()).isNull();
        }

        @Test
        @DisplayName("a review count with no digits after cleanup is left as null")
        void nonNumericReviewCount_returnsNull() throws Exception {
            String html = """
                    <html><body>
                    <div id="averageCustomerReviews_feature_div">
                    <span id="acrCustomerReviewText">many ratings</span>
                    </div>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/BADREVCNT01", html);

            BookMetadata metadata = amazonBookParser.fetchDetailedMetadata("BADREVCNT01");

            assertThat(metadata.getAmazonReviewCount()).isNull();
        }
    }

    @Nested
    @DisplayName("buildQueryUrl - additional branches")
    class BuildQueryUrlAdditionalTests {

        @Test
        @DisplayName("falls back to just the author when the title is blank and there is no primary file")
        void blankTitleWithAuthor_usesAuthorOnly() throws Exception {
            mockJsoupConnect("https://www.amazon.com/s?k=Some+Author", "<html/>");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("").author("Some Author").build();

            amazonBookParser.fetchMetadata(book, request);

            mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/s?k=Some+Author"));
        }
    }

    @Nested
    @DisplayName("parseSeriesInfo - additional branches")
    class SeriesInfoAdditionalTests {

        @Test
        @DisplayName("a series name without a parseable 'Book X of Y' label yields a name but no number/total")
        void seriesNameWithoutBookOfYLabel_yieldsNameOnly() throws Exception {
            String html = """
                    <html><body>
                    <div id="rpi-attribute-book_details-series" class="rpi-attribute">
                    <div class="rpi-attribute-value"><a href="/series"><span>The Example Series</span></a></div>
                    </div>
                    </body></html>
                    """;
            mockJsoupConnect("https://www.amazon.com/dp/SERIESNOLBL", html);

            BookMetadata metadata = amazonBookParser.fetchDetailedMetadata("SERIESNOLBL");

            assertThat(metadata.getSeriesName()).isEqualTo("The Example Series");
            assertThat(metadata.getSeriesNumber()).isNull();
            assertThat(metadata.getSeriesTotal()).isNull();
        }
    }
}
