package org.booklore.service.metadata.parser;


import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComicvineBookParserTest {
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private HttpClient httpClient;

    @Mock
    private AppSettingService mockAppSettingService;

    @InjectMocks
    private ComicvineBookParser comicvineBookParser;

    @SuppressWarnings("unchecked")
    private HttpResponse<String> getResponse(int statusCode, String payload) {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(statusCode);
        when(mockResponse.body()).thenReturn(payload);

        return mockResponse;
    }

    private void mockResponse(String uri, int statusCode, String payload) throws IOException, InterruptedException {
        HttpResponse<String> response = getResponse(statusCode, payload);
        when(
            httpClient.<String>send(
                argThat(arg -> arg != null && arg.uri().toString().contains(uri)),
                any()
            )
        ).thenReturn(response);
    }

    private String readFixture(String fixtureName) throws IOException {
        String filename = "comicvinebookparser/" + fixtureName + ".fixture";

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            assert is != null;

            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Book getBook(String comicvineId) {
        BookMetadata bookMetadata = BookMetadata.builder()
                .comicvineId(comicvineId)
                .build();

        return Book.builder()
                .title("Example")
                .metadata(bookMetadata)
                .build();
    }

    private AppSettings getAppSettings() {
        MetadataProviderSettings.Comicvine comicvineSettings = new MetadataProviderSettings.Comicvine();
        comicvineSettings.setEnabled(true);
        comicvineSettings.setApiKey("example");

        MetadataProviderSettings metadataProviderSettings = new MetadataProviderSettings();
        metadataProviderSettings.setComicvine(comicvineSettings);

        return AppSettings.builder()
                .metadataProviderSettings(metadataProviderSettings)
                .build();
    }

    private AppSettings getAppSettingsNoApiKey() {
        MetadataProviderSettings.Comicvine comicvineSettings = new MetadataProviderSettings.Comicvine();
        comicvineSettings.setEnabled(true);

        MetadataProviderSettings metadataProviderSettings = new MetadataProviderSettings();
        metadataProviderSettings.setComicvine(comicvineSettings);

        return AppSettings.builder()
                .metadataProviderSettings(metadataProviderSettings)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void mockRateLimitedResponse(String uri, String retryAfterValue) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);

        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.allValues("Retry-After")).thenReturn(retryAfterValue == null ? List.of() : List.of(retryAfterValue));
        when(response.headers()).thenReturn(headers);

        when(
            httpClient.<String>send(
                argThat(arg -> arg != null && arg.uri().toString().contains(uri)),
                any()
            )
        ).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() throws IOException, InterruptedException {
        lenient().when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettings());
        // Default catch-all so tests that only care about one endpoint don't need to stub
        // every other call the parser might make (e.g. alternative-name volume lookups).
        // Built by hand (rather than via getResponse()) so every stub involved - including
        // statusCode()/body() - is marked lenient; otherwise tests whose code path never
        // reaches httpClient.send() at all would fail strict-stubbing checks on those inner stubs.
        HttpResponse<String> emptyResultsResponse = mock(HttpResponse.class);
        lenient().when(emptyResultsResponse.statusCode()).thenReturn(200);
        lenient().when(emptyResultsResponse.body()).thenReturn("{\"results\": []}");
        lenient().when(httpClient.<String>send(any(), any())).thenReturn(emptyResultsResponse);
    }

    @Test
    void volumeSearchCacheHasMaximumSizeAndTtl() {
        var eviction = comicvineBookParser.volumeCache().policy().eviction();
        var expiration = comicvineBookParser.volumeCache().policy().expireAfterWrite();

        assertThat(eviction).isPresent();
        assertThat(eviction.orElseThrow().getMaximum()).isEqualTo(ComicvineBookParser.VOLUME_CACHE_MAX_ENTRIES);
        assertThat(expiration).isPresent();
        assertThat(expiration.orElseThrow().getExpiresAfter(TimeUnit.MINUTES)).isEqualTo(ComicvineBookParser.VOLUME_CACHE_TTL.toMinutes());
    }

    @Test
    void fetchTopMetadata_getsTitle() throws IOException, InterruptedException {
        mockResponse(
                "/search/",
                200,
                readFixture("search.json")
        );

        Book book = getBook("EXAMPLE");
        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("Example")
                .build();

        BookMetadata metadata = comicvineBookParser.fetchTopMetadata(book, request);

        assertNotNull(metadata);
        assertEquals("The Example", metadata.getTitle());
    }

    @Test
    void fetchTopMetadata_parsesSeriesInfoFromTitle() throws IOException, InterruptedException {
        // The parser first tries /search/ endpoint for volumes
        mockResponse(
                "/search/?api_key=example&format=json&resources=volume&query=The%20Example",
                200,
                "{\"results\": []}"
        );

        mockResponse(
                "/volumes/?api_key=example&format=json&filter=name:The%20Example",
                200,
                readFixture("search.json")
        );

        mockResponse(
                "/issues/?api_key=example&format=json&filter=volume:60593,issue_number:1",
                200,
                readFixture("issues.json")
        );

        Book book = getBook("EXAMPLE");
        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("The Example #1")
                .build();

        BookMetadata metadata = comicvineBookParser.fetchTopMetadata(book, request);

        assertNotNull(metadata);
        assertEquals("The Example", metadata.getSeriesName());
        assertEquals(1.0f, metadata.getSeriesNumber());
    }

    @Nested
    @DisplayName("API token / ISBN search")
    class ApiTokenAndIsbnTests {

        @Test
        @DisplayName("returns empty results and makes no HTTP calls when no API key is configured")
        void fetchMetadata_missingApiKey_returnsEmptyWithoutCalling() throws IOException, InterruptedException {
            when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettingsNoApiKey());

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().isbn("9780134685991").build();

            List<BookMetadata> results = comicvineBookParser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
            verify(httpClient, times(0)).send(any(), any());
        }

        @Test
        @DisplayName("searches by cleaned ISBN and returns the matching volume")
        void fetchMetadata_isbnSearch_returnsVolume() throws IOException, InterruptedException {
            mockResponse("/search/?api_key=example&format=json&resources=volume,issue&query=9780134685991", 200, readFixture("search.json"));

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().isbn("978-0-13-468599-1").build();

            List<BookMetadata> results = comicvineBookParser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            BookMetadata metadata = results.getFirst();
            assertThat(metadata.getComicvineId()).isEqualTo("4050-60593");
            assertThat(metadata.getTitle()).isEqualTo("The Example");
            assertThat(metadata.getPublisher()).isEqualTo("Gestalt Comics");
            assertThat(metadata.getExternalUrl()).isEqualTo("https://comicvine.gamespot.com/the-example/4050-60593/");
        }

        @Test
        @DisplayName("falls back to a title search when the ISBN search returns nothing")
        void fetchMetadata_isbnMiss_fallsBackToTitleSearch() throws IOException, InterruptedException {
            mockResponse("/search/?api_key=example&format=json&resources=volume,issue&query=0000000000000", 200, "{\"results\": []}");
            mockResponse("/search/?api_key=example&format=json&resources=volume,issue&query=Some%20Random%20Comic", 200, readFixture("search.json"));

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder()
                    .isbn("0000000000000")
                    .title("Some Random Comic")
                    .build();

            List<BookMetadata> results = comicvineBookParser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getTitle()).isEqualTo("The Example");
        }
    }

    @Nested
    @DisplayName("fetchDetailedMetadata")
    class FetchDetailedMetadataTests {

        @Test
        @DisplayName("fetches a volume by its 4050- prefixed id")
        void fetchDetailedMetadata_volume() throws IOException, InterruptedException {
            mockResponse("/volume/4050-60593/", 200, readFixture("volume-detail.json"));

            BookMetadata metadata = comicvineBookParser.fetchDetailedMetadata("4050-60593");

            assertThat(metadata).isNotNull();
            assertThat(metadata.getComicvineId()).isEqualTo("4050-60593");
            assertThat(metadata.getTitle()).isEqualTo("The Example");
            assertThat(metadata.getSeriesTotal()).isEqualTo(3);
            assertThat(metadata.getPublisher()).isEqualTo("Gestalt Comics");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2009, 1, 1));
        }

        @Test
        @DisplayName("fetches an issue by its 4000- prefixed id and populates creator credits")
        void fetchDetailedMetadata_issue_populatesCredits() throws IOException, InterruptedException {
            mockResponse("/issue/4000-400275/", 200, readFixture("issue.json"));

            BookMetadata metadata = comicvineBookParser.fetchDetailedMetadata("4000-400275");

            assertThat(metadata).isNotNull();
            assertThat(metadata.getComicvineId()).isEqualTo("4000-400275");
            assertThat(metadata.getSeriesName()).isEqualTo("The Example");
            // "writer" role maps to authors
            assertThat(metadata.getAuthors()).containsExactly("Tom Taylor");
            // "artist, cover" maps to both pencillers and coverArtists; "cover" alone also maps to coverArtists
            assertThat(metadata.getComicMetadata().getPencillers()).containsExactly("Colin Wilson");
            assertThat(metadata.getComicMetadata().getCoverArtists()).containsExactlyInAnyOrder("Colin Wilson", "Justin Randall");
        }

        @Test
        @DisplayName("returns null for a non-numeric id")
        void fetchDetailedMetadata_invalidId_returnsNull() {
            BookMetadata metadata = comicvineBookParser.fetchDetailedMetadata("not-a-number");

            assertNull(metadata);
        }

        @Test
        @DisplayName("returns null for a null or empty id")
        void fetchDetailedMetadata_nullOrEmptyId_returnsNull() {
            assertNull(comicvineBookParser.fetchDetailedMetadata(null));
            assertNull(comicvineBookParser.fetchDetailedMetadata(""));
        }

        @Test
        @DisplayName("returns null for an unsupported resource prefix")
        void fetchDetailedMetadata_unsupportedPrefix_returnsNull() {
            BookMetadata metadata = comicvineBookParser.fetchDetailedMetadata("9999-123");

            assertNull(metadata);
        }
    }

    @Nested
    @DisplayName("rate limiting")
    class RateLimitingTests {

        @Test
        @DisplayName("a numeric Retry-After blocks subsequent requests until it elapses")
        void numericRetryAfter_blocksSubsequentRequests() throws IOException, InterruptedException {
            mockRateLimitedResponse("/search/", "120");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().isbn("9780134685991").build();

            List<BookMetadata> first = comicvineBookParser.fetchMetadata(book, request);
            List<BookMetadata> second = comicvineBookParser.fetchMetadata(book, request);

            assertThat(first).isEmpty();
            assertThat(second).isEmpty();
            // The second call should be short-circuited by the rate-limit flag rather than issuing a new request.
            verify(httpClient, times(1)).send(any(), any());
        }

        @Test
        @DisplayName("an instant Retry-After is parsed without error")
        void instantRetryAfter_isParsed() throws IOException, InterruptedException {
            mockRateLimitedResponse("/search/", "2099-01-01T00:00:00Z");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().isbn("9780134685991").build();

            List<BookMetadata> results = comicvineBookParser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("a malformed Retry-After falls back to the default delay without throwing")
        void malformedRetryAfter_usesDefaultDelay() throws IOException, InterruptedException {
            mockRateLimitedResponse("/search/", "not-a-valid-value");

            Book book = getBook(null);
            FetchMetadataRequest request = FetchMetadataRequest.builder().isbn("9780134685991").build();

            List<BookMetadata> results = comicvineBookParser.fetchMetadata(book, request);

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("getMetadataListByTerm - series/issue extraction")
    class SeriesAndIssueExtractionTests {

        @Test
        @DisplayName("falls straight through to a general search when no issue number is present")
        void noIssueNumber_usesGeneralSearch() throws IOException, InterruptedException {
            mockResponse("/search/?api_key=example&format=json&resources=volume,issue&query=Some%20Random%20Comic", 200, readFixture("search.json"));

            List<BookMetadata> results = comicvineBookParser.getMetadataListByTerm("Some Random Comic");

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getTitle()).isEqualTo("The Example");
        }

        @Test
        @DisplayName("an annual reference without an explicit number falls through to a general search")
        void annualWithoutNumber_usesGeneralSearch() throws IOException, InterruptedException {
            mockResponse("/search/?api_key=example&format=json&resources=volume,issue&query=Justice%20League%20Annual", 200, readFixture("search.json"));

            List<BookMetadata> results = comicvineBookParser.getMetadataListByTerm("Justice League Annual");

            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("an annual reference with an explicit number is resolved via a structured volume+issue search")
        void annualWithNumber_resolvesStructuredMatch() throws IOException, InterruptedException {
            mockResponse("/search/?api_key=example&format=json&resources=volume&query=Justice%20League", 200, "{\"results\": []}");
            mockResponse("/volumes/?api_key=example&format=json&filter=name:Justice%20League", 200, readFixture("search.json"));
            mockResponse("/issues/?api_key=example&format=json&filter=volume:60593,issue_number:5", 200, issuesFixtureWithIssueNumber("5"));

            List<BookMetadata> results = comicvineBookParser.getMetadataListByTerm("Justice League Annual 5");

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getSeriesNumber()).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("a decimal issue number is normalized (trailing zero stripped) before matching")
        void decimalIssueNumber_isNormalized() throws IOException, InterruptedException {
            mockResponse("/search/?api_key=example&format=json&resources=volume&query=Justice%20League", 200, "{\"results\": []}");
            mockResponse("/volumes/?api_key=example&format=json&filter=name:Justice%20League", 200, readFixture("search.json"));
            mockResponse("/issues/?api_key=example&format=json&filter=volume:60593,issue_number:1.5", 200, issuesFixtureWithIssueNumber("1.5"));

            List<BookMetadata> results = comicvineBookParser.getMetadataListByTerm("Justice League #1.50");

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getSeriesNumber()).isEqualTo(1.5f);
        }

        private String issuesFixtureWithIssueNumber(String issueNumber) throws IOException {
            return readFixture("issues.json").replace("\"issue_number\": \"1\"", "\"issue_number\": \"" + issueNumber + "\"");
        }
    }
}
