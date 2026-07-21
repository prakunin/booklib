package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoubanBookParserTest {

    @Mock
    private AppSettingService appSettingService;

    private DoubanBookParser parser;

    private MockedStatic<Jsoup> mockJsoup;

    private String searchResponseFixture;
    private String bookPageFixture;
    private String bookPageEmptyFixture;
    private String bookPageAltFixture;

    @BeforeEach
    void setUp() throws IOException {
        parser = new DoubanBookParser(appSettingService, new ObjectMapper());

        searchResponseFixture = readFixture("douban-search-response-minimal.html");
        bookPageFixture = readFixture("douban-book-page-minimal.html");
        bookPageEmptyFixture = readFixture("douban-book-page-empty.html");
        bookPageAltFixture = readFixture("douban-book-page-alt.html");

        mockJsoup = mockStatic(Jsoup.class);
        // cleanDescriptionHtml() calls the single-arg Jsoup.parse(String) overload directly.
        // Without this stub, the mocked static class would return null and silently fall back to raw HTML.
        mockJsoup.when(() -> Jsoup.parse(any(String.class))).thenCallRealMethod();
    }

    @AfterEach
    void tearDown() {
        mockJsoup.close();
    }

    private String readFixture(String fixtureName) throws IOException {
        String filename = Paths.get("douban", fixtureName).toString();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            assert is != null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void mockConnect(String url, String html) throws Exception {
        Connection mockConnection = mock(Connection.class);
        Connection.Response mockResponse = mock(Connection.Response.class);

        when(mockConnection.header(any(String.class), any(String.class))).thenReturn(mockConnection);
        when(mockConnection.method(any(Connection.Method.class))).thenReturn(mockConnection);
        when(mockConnection.timeout(any(Integer.class))).thenReturn(mockConnection);
        when(mockConnection.ignoreContentType(any(Boolean.class))).thenReturn(mockConnection);
        when(mockConnection.maxBodySize(any(Integer.class))).thenReturn(mockConnection);
        when(mockConnection.followRedirects(any(Boolean.class))).thenReturn(mockConnection);
        java.net.URL mockUrl = mock(java.net.URL.class);
        when(mockUrl.toString()).thenReturn(url);

        when(mockConnection.execute()).thenReturn(mockResponse);
        when(mockResponse.body()).thenReturn(html);
        when(mockResponse.url()).thenReturn(mockUrl);

        mockJsoup.when(() -> Jsoup.connect(url)).thenReturn(mockConnection);
        mockJsoup.when(() -> Jsoup.parse(html, url)).thenCallRealMethod();
    }

    private void mockConnectThrowing(String url, IOException toThrow) throws Exception {
        Connection mockConnection = mock(Connection.class);
        when(mockConnection.header(any(String.class), any(String.class))).thenReturn(mockConnection);
        when(mockConnection.method(any(Connection.Method.class))).thenReturn(mockConnection);
        when(mockConnection.timeout(any(Integer.class))).thenReturn(mockConnection);
        when(mockConnection.ignoreContentType(any(Boolean.class))).thenReturn(mockConnection);
        when(mockConnection.maxBodySize(any(Integer.class))).thenReturn(mockConnection);
        when(mockConnection.followRedirects(any(Boolean.class))).thenReturn(mockConnection);
        when(mockConnection.execute()).thenThrow(toThrow);

        mockJsoup.when(() -> Jsoup.connect(url)).thenReturn(mockConnection);
    }

    private Book bookWithTitle(String title) {
        return Book.builder().title(title).build();
    }

    private void mockReviewsSetting(boolean enabled, int maxReviews) {
        MetadataPublicReviewsSettings.ReviewProviderConfig cfg = MetadataPublicReviewsSettings.ReviewProviderConfig.builder()
                .provider(MetadataProvider.Douban)
                .enabled(enabled)
                .maxReviews(maxReviews)
                .build();
        MetadataPublicReviewsSettings settings = MetadataPublicReviewsSettings.builder()
                .providers(Set.of(cfg))
                .build();
        when(appSettingService.getAppSettings()).thenReturn(
                AppSettings.builder().metadataPublicReviewsSettings(settings).build());
    }

    private void mockReviewsSettingEmpty() {
        MetadataPublicReviewsSettings settings = MetadataPublicReviewsSettings.builder()
                .providers(Collections.emptySet())
                .build();
        when(appSettingService.getAppSettings()).thenReturn(
                AppSettings.builder().metadataPublicReviewsSettings(settings).build());
    }

    @Nested
    @DisplayName("fetchMetadata (search + detail merge)")
    class FetchMetadataTests {

        @Test
        @DisplayName("returns empty list when title and filename are both absent")
        void returnsEmptyWhenNoQueryTerm() {
            // No search term means the parser never reaches the Douban settings lookup.
            Book book = Book.builder().build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("builds search term from cleaned file name when title is absent")
        void buildsQueryFromFileName() throws Exception {
            // BookUtils strips the extension; the cleaned filename becomes the query term.
            // No matching items are returned, so the Douban settings lookup is never reached.
            mockConnect("https://search.douban.com/book/subject_search?search_text=3体", "<html><body></body></html>");

            BookFile file = BookFile.builder().fileName("3体.epub").build();
            Book book = Book.builder().primaryFile(file).build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("parses search results and fetches up to 3 detailed metadata entries")
        void parsesSearchResultsAndFetchesDetails() throws Exception {
            mockReviewsSettingEmpty();
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            mockConnect("https://book.douban.com/subject/2567698", bookPageFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            BookMetadata metadata = results.getFirst();
            assertThat(metadata.getTitle()).isEqualTo("三体");
            assertThat(metadata.getDoubanId()).isEqualTo("2567698");
            assertThat(metadata.getAuthors()).containsExactly("刘慈欣", "无");
            assertThat(metadata.getPublisher()).isEqualTo("重庆出版社");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2008, Month.JANUARY, 1));
            assertThat(metadata.getIsbn13()).isEqualTo("9787536692930");
            assertThat(metadata.getIsbn10()).isNull();
            assertThat(metadata.getSeriesName()).isEqualTo("中国科幻基石丛书");
            assertThat(metadata.getPageCount()).isEqualTo(302);
            assertThat(metadata.getDoubanRating()).isEqualTo(8.8);
            assertThat(metadata.getDoubanReviewCount()).isEqualTo(124589);
            assertThat(metadata.getThumbnailUrl()).contains("s2768378.jpg");
            assertThat(metadata.getDescription()).contains("地球文明向宇宙发出的第一声啼鸣");
            assertThat(metadata.getBookReviews()).isEmpty();
        }

        @Test
        @DisplayName("populates reviews when the Douban review provider is enabled")
        void populatesReviewsWhenEnabled() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            mockConnect("https://book.douban.com/subject/2567698", bookPageFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getBookReviews()).hasSize(1);
            var review = results.getFirst().getBookReviews().getFirst();
            assertThat(review.getReviewerName()).isEqualTo("读者甲");
            assertThat(review.getRating()).isEqualTo(20.0f);
            assertThat(review.getBody()).isEqualTo("很好看的一本书，值得反复阅读。");
            assertThat(review.getDate()).isNotNull();
        }

        @ParameterizedTest(name = "returns empty list when {0}")
        @MethodSource("unusableWindowDataHtml")
        void returnsEmptyForUnusableWindowData(String scenario, String html) throws Exception {
            mockConnect("https://search.douban.com/book/subject_search?search_text=Foo", html);

            Book book = bookWithTitle("Foo");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Foo").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }

        static Stream<Arguments> unusableWindowDataHtml() {
            return Stream.of(
                    Arguments.of("window.__DATA__ JSON is absent", "<html><body>no data here</body></html>"),
                    Arguments.of("window.__DATA__ JSON has no items array",
                            "<html><body><script>window.__DATA__ = {\"foo\": 1};</script></body></html>"),
                    Arguments.of("items array is empty",
                            "<html><body><script>window.__DATA__ = {\"items\": []};</script></body></html>"),
                    Arguments.of("window.__DATA__ JSON is malformed",
                            "<html><body><script>window.__DATA__ = {not valid json};</script></body></html>"));
        }

        @Test
        @DisplayName("returns empty list when fetching the search page throws an IOException")
        void returnsEmptyWhenSearchFetchThrows() throws Exception {
            mockConnectThrowing("https://search.douban.com/book/subject_search?search_text=Foo",
                    new IOException("connection reset"));

            Book book = bookWithTitle("Foo");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Foo").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("skips a search item whose required fields are missing instead of throwing")
        void skipsMalformedSearchItem() throws Exception {
            mockReviewsSettingEmpty();
            String html = "<html><body><script>window.__DATA__ = {\"items\": ["
                    + "{\"title\": \"Broken\", \"url\": \"https://book.douban.com/subject/555/\"},"
                    + "{\"title\": \"三体\", \"url\": \"https://book.douban.com/subject/2567698/\", "
                    + "\"cover_url\": \"\", \"abstract\": \"\"}"
                    + "]};</script></body></html>";
            mockConnect("https://search.douban.com/book/subject_search?search_text=Mixed", html);
            mockConnect("https://book.douban.com/subject/2567698", bookPageFixture);

            Book book = bookWithTitle("Mixed");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Mixed").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            // The malformed item (missing cover_url/abstract) throws inside parseSearchResultItem
            // and is skipped; only the well-formed item survives.
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getDoubanId()).isEqualTo("2567698");
        }
    }

    @Nested
    @DisplayName("fetchTopMetadata")
    class FetchTopMetadataTests {

        @Test
        @DisplayName("returns only the search-result-level metadata when reviews are disabled")
        void returnsSearchResultOnlyWhenReviewsDisabled() throws Exception {
            mockReviewsSettingEmpty();
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("三体");
            assertThat(metadata.getDoubanId()).isEqualTo("2567698");
            // Detail page was never fetched, so page count (only available from the detail page) is unset.
            assertThat(metadata.getPageCount()).isNull();
        }

        @Test
        @DisplayName("merges detailed metadata with search defaults when reviews are enabled")
        void mergesDetailedMetadataWhenReviewsEnabled() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            mockConnect("https://book.douban.com/subject/2567698", bookPageFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getPageCount()).isEqualTo(302);
            assertThat(metadata.getBookReviews()).hasSize(1);
        }

        @Test
        @DisplayName("fills in missing detailed fields (authors, thumbnail, publisher, rating) from the search result")
        void mergesMissingFieldsFromSearchResult() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            // The alt fixture's detail page has no author, publisher, thumbnail, or rating markup.
            mockConnect("https://book.douban.com/subject/2567698", bookPageAltFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Alt Book Title");
            assertThat(metadata.getAuthors()).containsExactly("刘慈欣");
            assertThat(metadata.getThumbnailUrl()).contains("s2768378.jpg");
            assertThat(metadata.getPublisher()).isEqualTo("重庆出版社");
            assertThat(metadata.getDoubanRating()).isEqualTo(8.8);
        }

        @Test
        @DisplayName("returns null when there are no search results")
        void returnsNullWhenNoResults() throws Exception {
            // No search results means the review-enabled check is never reached.
            mockConnect("https://search.douban.com/book/subject_search?search_text=Foo",
                    "<html><body><script>window.__DATA__ = {\"items\": []};</script></body></html>");

            Book book = bookWithTitle("Foo");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Foo").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata).isNull();
        }

        @Test
        @DisplayName("fills in every missing detailed field from the search result when the detail page is entirely empty")
        void mergesAllMissingFieldsFromSearchResultWhenDetailPageEmpty() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            mockConnect("https://book.douban.com/subject/2567698", bookPageEmptyFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getThumbnailUrl()).contains("s2768378.jpg");
            assertThat(metadata.getAuthors()).containsExactly("刘慈欣");
            assertThat(metadata.getPublisher()).isEqualTo("重庆出版社");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2008, Month.JANUARY, 1));
            assertThat(metadata.getDoubanRating()).isEqualTo(8.8);
        }
    }

    @Nested
    @DisplayName("book detail page parsing - missing/fallback branches")
    class DetailPageFallbackTests {

        @Test
        @DisplayName("all fields fall back to null when the detail page has no recognizable structure")
        void handlesEmptyDetailPage() throws Exception {
            mockReviewsSettingEmpty();
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            mockConnect("https://book.douban.com/subject/2567698", bookPageEmptyFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            BookMetadata metadata = results.getFirst();
            assertThat(metadata.getTitle()).isNull();
            assertThat(metadata.getAuthors()).isEmpty();
            assertThat(metadata.getPublisher()).isNull();
            assertThat(metadata.getPublishedDate()).isNull();
            assertThat(metadata.getIsbn10()).isNull();
            assertThat(metadata.getIsbn13()).isNull();
            assertThat(metadata.getSeriesName()).isNull();
            assertThat(metadata.getPageCount()).isNull();
            // fetchMetadata() backfills a missing detail-page thumbnail from the search result.
            assertThat(metadata.getThumbnailUrl()).contains("s2768378.jpg");
            assertThat(metadata.getDoubanRating()).isNull();
            assertThat(metadata.getDoubanReviewCount()).isNull();
            assertThat(metadata.getDescription()).isNull();
        }

        @Test
        @DisplayName("parses 10-digit ISBN, Chinese full date, fallback description selector, and fallback review selector")
        void handlesAlternatePageStructure() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            mockConnect("https://book.douban.com/subject/2567698", bookPageAltFixture);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            BookMetadata metadata = results.getFirst();
            assertThat(metadata.getTitle()).isEqualTo("Alt Book Title");
            assertThat(metadata.getIsbn10()).isEqualTo("0393341769");
            assertThat(metadata.getIsbn13()).isNull();
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2011, Month.JANUARY, 10));
            assertThat(metadata.getDescription()).isEqualTo("Fallback description content.");
            assertThat(metadata.getBookReviews()).hasSize(1);
            assertThat(metadata.getBookReviews().getFirst().getReviewerName()).isEqualTo("Reader C");
            assertThat(metadata.getBookReviews().getFirst().getBody()).isEqualTo("Fallback-selector review body.");
        }

        @Test
        @DisplayName("falls back to the broadest [class*=comment] selector when neither standard selector matches")
        void fallsBackToBroadestCommentSelector() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            String detailHtml = "<html><body>"
                    + "<div class=\"reader-comment-panel\">"
                    + "<div class=\"comment-info\"><a>Reader Z</a></div>"
                    + "<p class=\"comment-content\">Broadest fallback body.</p>"
                    + "</div>"
                    + "</body></html>";
            mockConnect("https://book.douban.com/subject/2567698", detailHtml);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            // The broadest fallback selector also matches nested review elements, so it may return
            // several candidates; what matters here is that it fired and found the review body text.
            assertThat(results.getFirst().getBookReviews())
                    .isNotEmpty()
                    .anyMatch(review -> "Broadest fallback body.".equals(review.getBody()));
        }
    }

    @Nested
    @DisplayName("search result abstract parsing")
    class AbstractParsingTests {

        @Test
        @DisplayName("uses the short fallback format when the abstract has fewer than 4 parts")
        void usesShortAbstractFallback() throws Exception {
            mockReviewsSettingEmpty();
            String html = "<html><body><script>window.__DATA__ = {\"items\": ["
                    + "{\"title\": \"Short\", \"url\": \"https://book.douban.com/subject/111/\", "
                    + "\"cover_url\": \"\", \"abstract\": \"ignored / OnlyAuthor / OnlyPublisher\"}"
                    + "]};</script></body></html>";
            mockConnect("https://search.douban.com/book/subject_search?search_text=Short", html);

            Book book = bookWithTitle("Short");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Short").build();

            // fetchTopMetadata with reviews disabled returns the search-result-level metadata
            // directly (no detail-page fetch), so we can assert the short-abstract-derived fields.
            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getAuthors()).containsExactly("OnlyAuthor");
            assertThat(metadata.getPublisher()).isEqualTo("OnlyPublisher");
        }

        @Test
        @DisplayName("skips items without a resolvable subject id")
        void skipsItemsWithoutSubjectId() throws Exception {
            // The item is filtered out before any detail fetch, so settings are never consulted.
            String html = "<html><body><script>window.__DATA__ = {\"items\": ["
                    + "{\"title\": \"NoId\", \"url\": \"https://book.douban.com/nope\", "
                    + "\"cover_url\": \"\", \"abstract\": \"\"}"
                    + "]};</script></body></html>";
            mockConnect("https://search.douban.com/book/subject_search?search_text=NoId", html);

            Book book = bookWithTitle("NoId");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("NoId").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("a single-part abstract (no slash separators) yields no authors, publisher, or date")
        void singlePartAbstract_yieldsNoAuthorsPublisherOrDate() throws Exception {
            mockReviewsSettingEmpty();
            String html = "<html><body><script>window.__DATA__ = {\"items\": ["
                    + "{\"title\": \"Solo\", \"url\": \"https://book.douban.com/subject/222/\", "
                    + "\"cover_url\": \"\", \"abstract\": \"JustOnePart\"}"
                    + "]};</script></body></html>";
            mockConnect("https://search.douban.com/book/subject_search?search_text=Solo", html);

            Book book = bookWithTitle("Solo");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Solo").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getAuthors()).isEmpty();
            assertThat(metadata.getPublisher()).isNull();
            assertThat(metadata.getPublishedDate()).isNull();
        }
    }

    @Nested
    @DisplayName("fetchMetadata detail count cap")
    class DetailCountCapTests {

        @Test
        @DisplayName("stops fetching detail pages once 3 have been collected, even with more search results available")
        void capsDetailFetchesAtThree() throws Exception {
            mockReviewsSettingEmpty();
            String html = "<html><body><script>window.__DATA__ = {\"items\": ["
                    + "{\"title\": \"One\", \"url\": \"https://book.douban.com/subject/101/\", \"cover_url\": \"\", \"abstract\": \"\"},"
                    + "{\"title\": \"Two\", \"url\": \"https://book.douban.com/subject/102/\", \"cover_url\": \"\", \"abstract\": \"\"},"
                    + "{\"title\": \"Three\", \"url\": \"https://book.douban.com/subject/103/\", \"cover_url\": \"\", \"abstract\": \"\"},"
                    + "{\"title\": \"Four\", \"url\": \"https://book.douban.com/subject/104/\", \"cover_url\": \"\", \"abstract\": \"\"}"
                    + "]};</script></body></html>";
            mockConnect("https://search.douban.com/book/subject_search?search_text=Many", html);
            mockConnect("https://book.douban.com/subject/101", "<html><body><div id=\"wrapper\"><h1>One</h1></div></body></html>");
            mockConnect("https://book.douban.com/subject/102", "<html><body><div id=\"wrapper\"><h1>Two</h1></div></body></html>");
            mockConnect("https://book.douban.com/subject/103", "<html><body><div id=\"wrapper\"><h1>Three</h1></div></body></html>");

            Book book = bookWithTitle("Many");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("Many").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(3);
            assertThat(results).extracting(BookMetadata::getDoubanId).containsExactly("101", "102", "103");
            // The fourth item's detail page is never connected to, since the loop breaks before reaching it.
            mockJsoup.verify(() -> Jsoup.connect("https://book.douban.com/subject/104"), never());
        }
    }

    @Nested
    @DisplayName("review parsing edge cases")
    class ReviewParsingEdgeCaseTests {

        private String detailPageWithComments(String commentsHtml) {
            return "<html><body><div id=\"wrapper\"><h1>Title</h1></div><div id=\"comments\">"
                    + commentsHtml + "</div></body></html>";
        }

        @Test
        @DisplayName("a review with no rating element present has a null rating")
        void reviewWithoutRatingElement_hasNullRating() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            String detailHtml = detailPageWithComments(
                    "<div class=\"comment-item\">"
                            + "<div class=\"comment-info\"><a>Reader X</a></div>"
                            + "<p class=\"comment-content\">No rating here.</p>"
                            + "</div>");
            mockConnect("https://book.douban.com/subject/2567698", detailHtml);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).hasSize(1);
            assertThat(metadata.getBookReviews().getFirst().getRating()).isNull();
        }

        @Test
        @DisplayName("a review with an unparseable date keeps the review but leaves the date null")
        void reviewWithMalformedDate_dateIsNullButReviewKept() throws Exception {
            mockReviewsSetting(true, 5);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            String detailHtml = detailPageWithComments(
                    "<div class=\"comment-item\">"
                            + "<div class=\"comment-info\"><a>Reader Y</a><span class=\"comment-time\">not-a-date</span></div>"
                            + "<p class=\"comment-content\">Body text.</p>"
                            + "</div>");
            mockConnect("https://book.douban.com/subject/2567698", detailHtml);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).hasSize(1);
            assertThat(metadata.getBookReviews().getFirst().getDate()).isNull();
            assertThat(metadata.getBookReviews().getFirst().getBody()).isEqualTo("Body text.");
        }

        @Test
        @DisplayName("stops collecting reviews once maxReviews is reached, even though more review elements exist")
        void maxReviewsCutoff_stopsBeforeExhaustingElements() throws Exception {
            mockReviewsSetting(true, 1);
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            String detailHtml = detailPageWithComments(
                    "<div class=\"comment-item\"><p class=\"comment-content\">First review.</p></div>"
                            + "<div class=\"comment-item\"><p class=\"comment-content\">Second review.</p></div>");
            mockConnect("https://book.douban.com/subject/2567698", detailHtml);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            BookMetadata metadata = parser.fetchTopMetadata(book, request);

            assertThat(metadata.getBookReviews()).hasSize(1);
            assertThat(metadata.getBookReviews().getFirst().getBody()).isEqualTo("First review.");
        }
    }

    @Nested
    @DisplayName("date parsing edge cases")
    class DateParsingEdgeCaseTests {

        @Test
        @DisplayName("an out-of-range day/month (e.g. 2011-13-45) is rejected and yields a null publication date")
        void invalidCalendarDate_yieldsNullPublicationDate() throws Exception {
            mockReviewsSettingEmpty();
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体", searchResponseFixture);
            String detailHtml = "<html><body><div id=\"wrapper\"><h1>Title</h1></div>"
                    + "<div id=\"info\"><span>出版年:</span> 2011-13-45<br/></div>"
                    + "</body></html>";
            mockConnect("https://book.douban.com/subject/2567698", detailHtml);

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").build();

            // fetchMetadata() (unlike fetchTopMetadata()) doesn't backfill publishedDate from the
            // search result, so the invalid detail-page date is observable as null here.
            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getPublishedDate()).isNull();
        }
    }

    @Nested
    @DisplayName("buildQueryUrl - additional branches")
    class BuildQueryUrlAdditionalTests {

        @Test
        @DisplayName("appends the author after a title-derived search term")
        void appendsAuthorAfterTitle() throws Exception {
            mockConnect("https://search.douban.com/book/subject_search?search_text=三体+刘慈欣",
                    "<html><body></body></html>");

            Book book = bookWithTitle("三体");
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("三体").author("刘慈欣").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
            mockJsoup.verify(() -> Jsoup.connect("https://search.douban.com/book/subject_search?search_text=三体+刘慈欣"));
        }
    }
}
