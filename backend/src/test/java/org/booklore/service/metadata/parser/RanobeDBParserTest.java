package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RanobeDbParserTest {

    private static final String BOOKS_PATH = "/books";
    private static final String BOOK_12345_PATH = "/book/12345";
    private static final String BOOK_67890_PATH = "/book/67890";
    private static final String BOOK_12345_ID = "12345";
    private static final String SEARCH_TERM = "Re:Zero";
    private static final String FIXTURE_SEARCH_ONE_BOOK = "search-response-one-book";
    private static final String FIXTURE_SEARCH_TWO_BOOKS = "search-response-two-books";
    private static final String FIXTURE_BOOK_FULL = "book-response-full";
    private static final String FIXTURE_BOOK_NO_SERIES = "book-response-no-series";

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private HttpClient httpClient;

    private RanobeDbParser parser;

    @BeforeEach
    void setUp() {
        parser = new RanobeDbParser(new ObjectMapper(), appSettingService);
        // RanobeDbParser builds its own HttpClient inline rather than accepting one via
        // constructor injection, so a mock has to be substituted onto the field directly.
        ReflectionTestUtils.setField(parser, "httpClient", httpClient);
    }

    private String readFixture(String fixtureName) throws IOException {
        String filename = "ranobedbparser/" + fixtureName + ".json.fixture";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            assert is != null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> getResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (body != null) {
            when(response.body()).thenReturn(body);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private void mockResponse(String urlSubstring, int statusCode, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = getResponse(statusCode, body);
        when(
                httpClient.<String>send(
                        argThat(r -> r != null && r.uri().toString().contains(urlSubstring)),
                        any()
                )
        ).thenReturn(response);
    }

    @Test
    @DisplayName("fetchMetadata returns an empty list when there is no usable search term")
    void testFetchMetadata_EmptyQuery() {
        Book book = Book.builder().title("Test Book").build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().build();

        List<BookMetadata> results = parser.fetchMetadata(book, request);

        assertThat(results).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("fetchTopMetadata returns null when there is no usable search term")
    void testFetchTopMetadata_EmptyQuery() {
        Book book = Book.builder().title("Test Book").build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().build();

        assertThat(parser.fetchTopMetadata(book, request)).isNull();
    }

    @Test
    @DisplayName("falls back to the cleaned primary file name when no title is provided")
    void testFetchMetadata_UsesFileNameWhenNoTitle() throws Exception {
        mockResponse(BOOKS_PATH, 200, "{\"books\": []}");

        BookFile file = BookFile.builder().fileName("My Light Novel Vol 1.epub").build();
        Book book = Book.builder().primaryFile(file).build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().build();

        List<BookMetadata> results = parser.fetchMetadata(book, request);

        assertThat(results).isEmpty();
        verify(httpClient).send(argThat(r -> r.uri().toString().contains("My") && r.uri().toString().contains("Light")), any());
    }

    @Test
    @Disabled("Integration test - requires network access to RanobeDB.org")
    @DisplayName("Integration: fetches real metadata from RanobeDB.org")
    void testFetchMetadata_Integration_RealBook() {
        Book book = Book.builder()
                .title("That Time I Got Reincarnated as a Slime, Vol. 1")
                .build();

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title("That Time I Got Reincarnated as a Slime, Vol. 1")
                .author("Fuse")
                .build();

        List<BookMetadata> results = parser.fetchMetadata(book, request);

        assertThat(results).isNotEmpty();

        BookMetadata firstResult = results.getFirst();
        assertThat(firstResult.getTitle()).isNotNull();
        assertThat(firstResult.getRanobedbId()).isNotNull();
        assertThat(firstResult.getAuthors()).isNotEmpty();
    }

    @Nested
    @DisplayName("getMetadataListByTerm")
    class GetMetadataListByTermTests {

        @Test
        @DisplayName("maps a search hit to full metadata, preferring English release/publisher/title data")
        void mapsFullBookMetadata() throws Exception {
            mockResponse(BOOKS_PATH, 200, readFixture(FIXTURE_SEARCH_ONE_BOOK));
            mockResponse(BOOK_12345_PATH, 200, readFixture(FIXTURE_BOOK_FULL));

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, false);

            assertThat(results).hasSize(1);
            BookMetadata metadata = results.getFirst();
            assertThat(metadata.getRanobedbId()).isEqualTo(BOOK_12345_ID);
            assertThat(metadata.getTitle()).isEqualTo(SEARCH_TERM);
            assertThat(metadata.getSubtitle()).isEqualTo("Starting Life in a Different World");
            assertThat(metadata.getAuthors()).containsExactly("Tappei Nagatsuki", "John Doe");
            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("Isekai", "Space Opera");
            assertThat(metadata.getPublisher()).isEqualTo("Yen Press");
            assertThat(metadata.getThumbnailUrl()).isEqualTo("https://images.ranobedb.org/cover-123.jpg");
            assertThat(metadata.getDescription()).isEqualTo("A story about starting over in another world.");
            assertThat(metadata.getLanguage()).isEqualTo("en");
            assertThat(metadata.getSeriesName()).isEqualTo(SEARCH_TERM);
            assertThat(metadata.getSeriesNumber()).isEqualTo(2.0f);
            assertThat(metadata.getSeriesTotal()).isEqualTo(3);
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2020, Month.MARCH, 10));
            assertThat(metadata.getRanobedbRating()).isEqualTo(4.2);
        }

        @Test
        @DisplayName("falls back to book-level language, release date and title when there is no English release, publisher or title entry")
        void mapsFallbackFieldsWhenNoEnglishData() throws Exception {
            mockResponse(BOOKS_PATH, 200, "{\"books\": [{\"id\": 67890}]}");
            mockResponse(BOOK_67890_PATH, 200, readFixture(FIXTURE_BOOK_NO_SERIES));

            List<BookMetadata> results = parser.getMetadataListByTerm("Some Original Title", false);

            assertThat(results).hasSize(1);
            BookMetadata metadata = results.getFirst();
            assertThat(metadata.getTitle()).isEqualTo("Some Original Title");
            assertThat(metadata.getSubtitle()).isNull();
            assertThat(metadata.getAuthors()).containsExactly("Jane Doe");
            assertThat(metadata.getPublisher()).isNull();
            assertThat(metadata.getThumbnailUrl()).isNull();
            assertThat(metadata.getLanguage()).isEqualTo("ja");
            assertThat(metadata.getSeriesName()).isNull();
            assertThat(metadata.getSeriesNumber()).isNull();
            assertThat(metadata.getSeriesTotal()).isNull();
            assertThat(metadata.getCategories()).isEmpty();
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2015, Month.JUNE, 20));
        }

        @Test
        @DisplayName("fetchTop=true only fetches details for the first search hit")
        void fetchTop_onlyFetchesFirstHit() throws Exception {
            mockResponse(BOOKS_PATH, 200, readFixture(FIXTURE_SEARCH_TWO_BOOKS));
            mockResponse(BOOK_12345_PATH, 200, readFixture(FIXTURE_BOOK_FULL));

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, true);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getRanobedbId()).isEqualTo(BOOK_12345_ID);
            verify(httpClient, never()).send(argThat(r -> r.uri().toString().contains(BOOK_67890_PATH)), any());
        }

        @Test
        @DisplayName("maps every search hit when not restricted to the top result")
        void notFetchTop_mapsEveryHit() throws Exception {
            mockResponse(BOOKS_PATH, 200, readFixture(FIXTURE_SEARCH_TWO_BOOKS));
            mockResponse(BOOK_12345_PATH, 200, readFixture(FIXTURE_BOOK_FULL));
            mockResponse(BOOK_67890_PATH, 200, readFixture(FIXTURE_BOOK_NO_SERIES));

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, false);

            assertThat(results).extracting(BookMetadata::getRanobedbId).containsExactly(BOOK_12345_ID, "67890");
        }

        @Test
        @DisplayName("returns an empty list when the search response has no books element")
        void searchResponseWithoutBooks_returnsEmpty() throws Exception {
            mockResponse(BOOKS_PATH, 200, "{\"count\": \"0\"}");

            List<BookMetadata> results = parser.getMetadataListByTerm("Nothing", false);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("returns an empty list when the search API responds with a non-200 status")
        void searchNon200_returnsEmpty() throws Exception {
            mockResponse(BOOKS_PATH, 500, null);

            List<BookMetadata> results = parser.getMetadataListByTerm("Anything", false);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("a failed book-detail fetch yields a null entry rather than throwing, when mapping every hit")
        void bookDetailNon200_yieldsNullEntry() throws Exception {
            mockResponse(BOOKS_PATH, 200, readFixture(FIXTURE_SEARCH_ONE_BOOK));
            mockResponse(BOOK_12345_PATH, 500, null);

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, false);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst()).isNull();
        }

        @Test
        @DisplayName("fetchTop=true returns an empty list when the top hit's detail fetch fails")
        void fetchTop_bookDetailFails_returnsEmpty() throws Exception {
            mockResponse(BOOKS_PATH, 200, readFixture(FIXTURE_SEARCH_ONE_BOOK));
            mockResponse(BOOK_12345_PATH, 500, null);

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, true);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("a book-detail response missing the book field maps to a null entry")
        void bookDetailMissingBookField_yieldsNullEntry() throws Exception {
            mockResponse(BOOKS_PATH, 200, readFixture(FIXTURE_SEARCH_ONE_BOOK));
            mockResponse(BOOK_12345_PATH, 200, "{}");

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, false);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst()).isNull();
        }

        @Test
        @DisplayName("an IOException while calling the search API is swallowed and yields an empty list")
        void ioExceptionDuringSearch_returnsEmpty() throws Exception {
            when(httpClient.<String>send(any(), any())).thenThrow(new IOException("boom"));

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, false);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("an InterruptedException while calling the search API is swallowed and re-sets the interrupt flag")
        void interruptedDuringSearch_returnsEmptyAndSetsInterruptFlag() throws Exception {
            when(httpClient.<String>send(any(), any())).thenThrow(new InterruptedException("boom"));

            List<BookMetadata> results = parser.getMetadataListByTerm(SEARCH_TERM, false);

            assertThat(results).isEmpty();
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
