package org.booklore.service.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookMetadataFieldSourceEntity;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookMetadataFieldSourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookMetadataFieldSourceServiceTest {

    @Mock
    private BookMetadataFieldSourceRepository fieldSourceRepository;

    @InjectMocks
    private BookMetadataFieldSourceService service;

    private static BookMetadataFieldSourceEntity row(long bookId, MetadataField field, MetadataProvider provider) {
        return BookMetadataFieldSourceEntity.builder()
                .bookId(bookId)
                .fieldName(field)
                .provider(provider)
                .updatedAt(Instant.parse("2026-08-04T10:15:30Z"))
                .build();
    }

    private static Book book(long id) {
        return Book.builder()
                .id(id)
                .metadata(BookMetadata.builder().bookId(id).build())
                .build();
    }

    @Nested
    @DisplayName("One book")
    class SingleBook {

        @Test
        void carriesExactlyTheFieldsThatHaveARow() {
            when(fieldSourceRepository.findByBookId(7L)).thenReturn(List.of(
                    row(7L, MetadataField.TITLE, MetadataProvider.GoodReads),
                    row(7L, MetadataField.ISBN_13, MetadataProvider.FlibustaLocal)));

            Book book = book(7L);
            service.attachTo(book);

            assertThat(book.getMetadata().getFieldSources())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            MetadataField.TITLE, MetadataProvider.GoodReads,
                            MetadataField.ISBN_13, MetadataProvider.FlibustaLocal));
        }

        @Test
        void givesABookWithNoRowsAnEmptyMapRatherThanNull() {
            when(fieldSourceRepository.findByBookId(7L)).thenReturn(List.of());

            Book book = book(7L);
            service.attachTo(book);

            assertThat(book.getMetadata().getFieldSources()).isNotNull().isEmpty();
        }

        @Test
        void doesNotQueryForABookThatHasNoMetadataToAttachItTo() {
            service.attachTo(Book.builder().id(7L).build());

            verifyNoInteractions(fieldSourceRepository);
        }
    }

    @Nested
    @DisplayName("A set of books")
    class ManyBooks {

        @Test
        void readsEveryBookInOneQueryRatherThanOnePerBook() {
            List<Book> books = List.of(book(1L), book(2L), book(3L), book(4L), book(5L));
            when(fieldSourceRepository.findByBookIdIn(any())).thenReturn(List.of(
                    row(1L, MetadataField.TITLE, MetadataProvider.GoodReads),
                    row(5L, MetadataField.PUBLISHER, MetadataProvider.Amazon)));

            service.attachTo(books);

            ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.captor();
            verify(fieldSourceRepository, times(1)).findByBookIdIn(ids.capture());
            assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
            verify(fieldSourceRepository, never()).findByBookId(anyLong());
        }

        @Test
        void filesEachRowUnderItsOwnBook() {
            Book one = book(1L);
            Book two = book(2L);
            when(fieldSourceRepository.findByBookIdIn(any())).thenReturn(List.of(
                    row(1L, MetadataField.TITLE, MetadataProvider.GoodReads),
                    row(2L, MetadataField.TITLE, MetadataProvider.FlibustaLocal),
                    row(2L, MetadataField.LANGUAGE, MetadataProvider.FlibustaLocal)));

            service.attachTo(List.of(one, two));

            assertThat(one.getMetadata().getFieldSources())
                    .containsExactly(Map.entry(MetadataField.TITLE, MetadataProvider.GoodReads));
            assertThat(two.getMetadata().getFieldSources())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            MetadataField.TITLE, MetadataProvider.FlibustaLocal,
                            MetadataField.LANGUAGE, MetadataProvider.FlibustaLocal));
        }

        @Test
        void givesABookWithNoRowsInTheBatchAnEmptyMapRatherThanNull() {
            Book attributed = book(1L);
            Book unattributed = book(2L);
            when(fieldSourceRepository.findByBookIdIn(any()))
                    .thenReturn(List.of(row(1L, MetadataField.TITLE, MetadataProvider.GoodReads)));

            service.attachTo(List.of(attributed, unattributed));

            assertThat(unattributed.getMetadata().getFieldSources()).isNotNull().isEmpty();
        }

        @Test
        void touchesTheRepositoryNotAtAllForAnEmptyCollection() {
            service.attachTo(List.of());

            verifyNoInteractions(fieldSourceRepository);
        }

        @Test
        void returnsNothingForAnEmptyIdSetWithoutQuerying() {
            assertThat(service.sourcesForBooks(Set.of())).isEmpty();

            verifyNoInteractions(fieldSourceRepository);
        }
    }

    @Nested
    @DisplayName("Wire contract")
    class WireContract {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        void serialisesTheSourcesSoTheUiCanReadThem() throws Exception {
            BookMetadata metadata = BookMetadata.builder()
                    .bookId(7L)
                    .fieldSources(Map.of(MetadataField.TITLE, MetadataProvider.FlibustaLocal))
                    .build();

            assertThat(objectMapper.writeValueAsString(metadata))
                    .contains("\"fieldSources\":{\"TITLE\":\"FlibustaLocal\"}");
        }

        @Test
        void refusesProvenanceAssertedByAClientOnTheWayIn() throws Exception {
            String forged = "{\"bookId\":7,\"fieldSources\":{\"TITLE\":\"GoodReads\"}}";

            BookMetadata deserialised = objectMapper.readValue(forged, BookMetadata.class);

            assertThat(deserialised.getBookId()).isEqualTo(7L);
            assertThat(deserialised.getFieldSources()).isNull();
        }

        @Test
        void staysOutOfTheJsonEntirelyWhenNothingAttachedIt() throws Exception {
            BookMetadata metadata = BookMetadata.builder().bookId(7L).build();

            assertThat(objectMapper.writeValueAsString(metadata)).doesNotContain("fieldSources");
        }
    }
}
