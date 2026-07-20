
package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoodReadsParserTest {
    @Mock
    private AppSettingService appSettingService;

    @Mock
    private HttpClient httpClient;

    private GoodReadsParser parser;

    private String exampleSearchJsonFixture;

    private String exampleGraphqlResponseFixture;

    @BeforeEach
    void setUp() throws IOException {
        parser = new GoodReadsParser(
                httpClient,
                appSettingService,
                new ObjectMapper()
        );

        exampleSearchJsonFixture = readFixture("example-search.json");
        exampleGraphqlResponseFixture = readFixture("example-graphql-response.json");
    }

    private void mockSettings(boolean enabled) {
        mockSettings(enabled, 0);
    }

    private void mockSettings(boolean enabled, int maxReviews) {
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Goodreads goodreads = new MetadataProviderSettings.Goodreads();
        goodreads.setEnabled(enabled);
        providerSettings.setGoodReads(goodreads);
        appSettings.setMetadataProviderSettings(providerSettings);

        MetadataPublicReviewsSettings.ReviewProviderConfig provider = MetadataPublicReviewsSettings.ReviewProviderConfig.builder()
                .provider(MetadataProvider.GoodReads)
                .enabled(enabled)
                .maxReviews(maxReviews)
                .build();

        MetadataPublicReviewsSettings reviewSettings = MetadataPublicReviewsSettings.builder()
                .providers(Set.of(provider))
                        .build();

        appSettings.setMetadataPublicReviewsSettings(reviewSettings);

        when(appSettingService.getAppSettings()).thenReturn(appSettings);
    }

    private String readFixture(String fixtureName) throws IOException {
        String filename = Paths.get("goodreads", fixtureName + ".fixture").toString();

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            assert is != null;

            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> getMockResponse(int statusCode, String response) {
        HttpResponse<String> httpResponse = (HttpResponse<String>) mock(HttpResponse.class);

        when(httpResponse.statusCode()).thenReturn(statusCode);

        if (response != null) {
            when(httpResponse.body()).thenReturn(response);
        }

        return httpResponse;
    }

    @SuppressWarnings("unchecked")
    private void mockHttpClientResponse(String urlPrefix, int statusCode, String response) throws Exception {
        when(
                httpClient.<String>send(
                        argThat(r -> r != null && r.uri() != null && r.uri().toString().startsWith(urlPrefix)),
                        any()
                )
        ).thenAnswer(_ -> getMockResponse(statusCode, response));
    }

    // resolveIsbn() sends a GET with BodyHandlers.discarding() and reads the redirected URI's
    // path off the response, rather than a body - so this needs its own HttpResponse<Void> mock.
    @SuppressWarnings("unchecked")
    private void mockIsbnRedirect(String isbn, String redirectedPath) throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.uri()).thenReturn(URI.create("https://www.goodreads.com" + redirectedPath));

        when(
                httpClient.<Void>send(
                        argThat(r -> r != null && r.uri() != null && r.uri().toString().startsWith("https://www.goodreads.com/book/isbn/" + isbn)),
                        any()
                )
        ).thenReturn(response);
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

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        // Then
        assertThat(results).isNotNull().as("Should return empty list when query is empty").isEmpty();
    }

    @Test
    void testFetchMetadata_parsesBook() throws Exception {
        // Given
        Book book = Book.builder()
                .title("A Clockwork Orange")
                .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("A Clockwork Orange")
                .author("Anthony Burgess")
                .build();

        // Mock enabled provider
        mockSettings(true);

        // Two expected URLs: search + GraphQL
        mockHttpClientResponse("https://www.goodreads.com/book/auto_complete", 200, exampleSearchJsonFixture);
        mockHttpClientResponse("https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql", 200, exampleGraphqlResponseFixture);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        // Then
        assertThat(results).isNotNull().as("Should return results for real book").isNotEmpty();

        BookMetadata result = results.getFirst();
        assertThat(result.getTitle()).isEqualTo("A Clockwork Orange");
        assertThat(result.getIsbn10()).isEqualTo("0393341763");
        assertThat(result.getIsbn13()).isEqualTo("9780393341768");
        assertThat(result.getGoodreadsId()).isEqualTo("41817486");
        assertThat(result.getAuthors()).isNotNull();
        assertThat(result.getAuthors()).hasSize(1);
        assertThat(result.getAuthors().getFirst()).isEqualTo("Anthony Burgess");

        // The description is very long, but we need to make sure we're in the right ballpark.
        assertThat(result.getDescription()).startsWith("In Anthony Burgess's influential");
        assertThat(result.getDescription()).hasSize(511);
    }

    @Test
    void testFetchMetadata_withRateLimitingError() throws Exception {
        // Given
        Book book = Book.builder()
                .title("A Clockwork Orange")
                .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("A Clockwork Orange")
                .author("Anthony Burgess")
                .build();

        // Two expected URLs
        mockHttpClientResponse("https://www.goodreads.com/book/auto_complete", 429, null);

        // When
        List<BookMetadata> results = parser.fetchMetadata(book, request);

        // Then
        assertThat(results).isNotNull().as("Should not return results").isEmpty();
    }

    @Nested
    @DisplayName("fetchTopMetadata - existing id shortcut")
    class ExistingIdTests {

        @Test
        @DisplayName("uses a valid stored Goodreads id directly, without searching")
        void fetchTopMetadata_validExistingId_skipsSearch() throws Exception {
            mockSettings(false);
            mockHttpClientResponse("https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql", 200, exampleGraphqlResponseFixture);

            BookMetadata existing = BookMetadata.builder().goodreadsId("41817486").build();
            Book book = Book.builder().title("A Clockwork Orange").metadata(existing).build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();

            BookMetadata result = parser.fetchTopMetadata(book, request);

            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("A Clockwork Orange");
            assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(2015, 3, 21));
            // No search call was needed, only the GraphQL lookup.
            verify(httpClient, times(1)).send(any(), any());
        }

        @Test
        @DisplayName("falls back to a search when the stored id is not numeric")
        void fetchTopMetadata_invalidExistingId_fallsBackToSearch() throws Exception {
            mockSettings(true);
            mockHttpClientResponse("https://www.goodreads.com/book/auto_complete", 200, exampleSearchJsonFixture);
            mockHttpClientResponse("https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql", 200, exampleGraphqlResponseFixture);

            BookMetadata existing = BookMetadata.builder().goodreadsId("not-a-number").build();
            Book book = Book.builder().title("A Clockwork Orange").metadata(existing).build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("A Clockwork Orange").build();

            BookMetadata result = parser.fetchTopMetadata(book, request);

            assertThat(result).isNotNull();
            assertThat(result.getGoodreadsId()).isEqualTo("41817486");
        }

        @Test
        @DisplayName("falls back to a search when the existing id fetch returns nothing")
        void fetchTopMetadata_existingIdFetchFails_fallsBackToSearch() throws Exception {
            // No mockSettings() here: every GraphQL call fails, so parseBookDetails (the only
            // caller of appSettingService) is never reached.
            // Every GraphQL call fails (not just the existing-id one), so the end result is
            // null - but the search endpoint being hit at all proves the fallback branch ran.
            mockHttpClientResponse("https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql", 500, null);
            mockHttpClientResponse("https://www.goodreads.com/book/auto_complete", 200, exampleSearchJsonFixture);

            BookMetadata existing = BookMetadata.builder().goodreadsId("99999999").build();
            Book book = Book.builder().title("A Clockwork Orange").metadata(existing).build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().title("A Clockwork Orange").build();

            BookMetadata result = parser.fetchTopMetadata(book, request);

            assertThat(result).isNull();
            verify(httpClient, atLeastOnce()).send(
                    argThat(r -> r != null && r.uri().toString().startsWith("https://www.goodreads.com/book/auto_complete")),
                    any()
            );
        }
    }

    @Nested
    @DisplayName("fetchDetailedMetadata")
    class FetchDetailedMetadataTests {

        @Test
        @DisplayName("returns the parsed book for a valid id")
        void fetchDetailedMetadata_returnsBook() throws Exception {
            mockSettings(false);
            mockHttpClientResponse("https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql", 200, exampleGraphqlResponseFixture);

            BookMetadata result = parser.fetchDetailedMetadata("41817486");

            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("A Clockwork Orange");
        }

        @Test
        @DisplayName("returns null when the underlying request throws")
        void fetchDetailedMetadata_requestThrows_returnsNull() throws Exception {
            when(httpClient.<String>send(any(), any())).thenThrow(new IOException("boom"));

            BookMetadata result = parser.fetchDetailedMetadata("41817486");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null for a non-numeric id")
        void fetchDetailedMetadata_nonNumericId_returnsNull() {
            BookMetadata result = parser.fetchDetailedMetadata("not-a-number");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("ISBN resolution")
    class IsbnResolutionTests {

        @Test
        @DisplayName("resolves an ISBN to a legacy id and fetches it, skipping the title search entirely")
        void fetchMetadata_isbnOnly_resolvesAndFetches() throws Exception {
            mockSettings(false);
            mockIsbnRedirect("9780393341768", "/book/show/41817486-a-clockwork-orange");
            mockHttpClientResponse("https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql", 200, exampleGraphqlResponseFixture);

            Book book = Book.builder().build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().isbn("9780393341768").build();

            List<BookMetadata> results = parser.fetchMetadata(book, request);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getGoodreadsId()).isEqualTo("41817486");
        }

        @Test
        @DisplayName("falls back silently to title search when the ISBN redirect doesn't resolve to a book path")
        void fetchMetadata_isbnRedirectUnrecognized_fallsBackToTitleSearch() throws Exception {
            mockSettings(true);
            mockIsbnRedirect("0000000000", "/not-a-book-path");
            mockHttpClientResponse("https://www.goodreads.com/book/auto_complete", 200, exampleSearchJsonFixture);
            mockHttpClientResponse("https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql", 200, exampleGraphqlResponseFixture);

            Book book = Book.builder().build();
            FetchMetadataRequest request = FetchMetadataRequest.builder()
                    .isbn("0000000000")
                    .title("A Clockwork Orange")
                    .build();

            // fetchTopMetadata() stops at the first emitted result, so this confirms the ISBN
            // path silently yielded nothing and the title search still produced a match.
            BookMetadata result = parser.fetchTopMetadata(book, request);

            assertThat(result).isNotNull();
            assertThat(result.getGoodreadsId()).isEqualTo("41817486");
        }
    }

    @Nested
    @DisplayName("GraphQL response edge cases")
    class GraphqlEdgeCaseTests {

        @Test
        @DisplayName("returns null when the GraphQL response contains an errors array")
        void fetchDetailedMetadata_graphqlErrors_returnsNull() throws Exception {
            mockHttpClientResponse(
                    "https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql",
                    200,
                    "{\"errors\": [{\"message\": \"not found\"}]}"
            );

            BookMetadata result = parser.fetchDetailedMetadata("41817486");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when getBookByLegacyId is null")
        void fetchDetailedMetadata_nullBookNode_returnsNull() throws Exception {
            mockHttpClientResponse(
                    "https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql",
                    200,
                    "{\"data\": {\"getBookByLegacyId\": null}}"
            );

            BookMetadata result = parser.fetchDetailedMetadata("41817486");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("review extraction")
    class ReviewExtractionTests {

        private String graphqlWithReviews(String reviewsFieldName) {
            return """
                    {
                      "data": {
                        "getBookByLegacyId": {
                          "title": "Reviewed Book",
                          "work": {
                            "%s": {
                              "edges": [
                                {
                                  "node": {
                                    "text": "<p>Loved it!</p>",
                                    "rating": "5",
                                    "spoilerStatus": true,
                                    "updatedAt": 1700000000000,
                                    "creator": {"name": "Alice", "followersCount": 12, "textReviewsCount": 34}
                                  }
                                },
                                {
                                  "node": {
                                    "text": "Also good.",
                                    "rating": "4",
                                    "spoilerStatus": false,
                                    "updatedAt": 1700000001000,
                                    "creator": {"name": "Bob", "followersCount": 1, "textReviewsCount": 2}
                                  }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                    """.formatted(reviewsFieldName);
        }

        @Test
        @DisplayName("limits reviews to maxReviews and strips HTML from the review body")
        void extractsReviews_respectsMaxReviewsAndStripsHtml() throws Exception {
            mockSettings(true, 1);
            mockHttpClientResponse(
                    "https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql",
                    200,
                    graphqlWithReviews("reviews")
            );

            BookMetadata result = parser.fetchDetailedMetadata("41817486");

            assertThat(result).isNotNull();
            assertThat(result.getBookReviews()).hasSize(1);
            var review = result.getBookReviews().getFirst();
            assertThat(review.getBody()).isEqualTo("Loved it!");
            assertThat(review.getRating()).isEqualTo(5.0f);
            assertThat(review.getSpoiler()).isTrue();
            assertThat(review.getReviewerName()).isEqualTo("Alice");
            assertThat(review.getFollowersCount()).isEqualTo(12);
            assertThat(review.getTextReviewsCount()).isEqualTo(34);
        }

        @Test
        @DisplayName("falls back to the reviewStats field name when reviews is absent")
        void extractsReviews_fallsBackToReviewStatsField() throws Exception {
            mockSettings(true, 5);
            mockHttpClientResponse(
                    "https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql",
                    200,
                    graphqlWithReviews("reviewStats")
            );

            BookMetadata result = parser.fetchDetailedMetadata("41817486");

            assertThat(result).isNotNull();
            assertThat(result.getBookReviews()).hasSize(2);
        }

        @Test
        @DisplayName("does not attach reviews when the provider is disabled")
        void extractsReviews_disabledProvider_noReviews() throws Exception {
            mockSettings(false);
            mockHttpClientResponse(
                    "https://kxbwmqov6jgg3daaamb744ycu4.appsync-api.us-east-1.amazonaws.com/graphql",
                    200,
                    graphqlWithReviews("reviews")
            );

            BookMetadata result = parser.fetchDetailedMetadata("41817486");

            assertThat(result).isNotNull();
            assertThat(result.getBookReviews()).isNull();
        }
    }
}
