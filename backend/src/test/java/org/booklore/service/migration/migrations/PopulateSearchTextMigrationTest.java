package org.booklore.service.migration.migrations;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PopulateSearchTextMigrationTest {

    private static final int BATCH_SIZE = 1000;

    @Mock
    private BookRepository bookRepository;

    private PopulateSearchTextMigration migration() {
        return new PopulateSearchTextMigration(bookRepository);
    }

    @Test
    @DisplayName("getKey and getDescription report the migration's identity")
    void keyAndDescription() {
        PopulateSearchTextMigration migration = migration();

        assertThat(migration.getKey()).isEqualTo("populateSearchText");
        assertThat(migration.getDescription()).isEqualTo("Populate search_text column for all books");
    }

    private BookEntity bookWithTitle(long id, String title) {
        BookMetadataEntity metadata = BookMetadataEntity.builder().title(title).build();
        return BookEntity.builder().id(id).metadata(metadata).build();
    }

    @Nested
    @DisplayName("execute - batching")
    class BatchingTests {

        @Test
        @DisplayName("does nothing and never saves when there are no books to migrate")
        void noBooks_doesNothing() {
            when(bookRepository.findBooksForMigrationBatch(eq(0L), any(Pageable.class))).thenReturn(List.of());
            PopulateSearchTextMigration migration = migration();

            migration.execute();

            verify(bookRepository, never()).findBooksWithMetadataAndAuthors(any());
            verify(bookRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("processes a single batch smaller than the batch size and stops")
        void singleSmallBatch_processesOnce() {
            BookEntity book1 = bookWithTitle(1L, "Book One");
            BookEntity book2 = bookWithTitle(2L, "Book Two");
            when(bookRepository.findBooksForMigrationBatch(eq(0L), any(Pageable.class)))
                    .thenReturn(List.of(book1, book2));
            when(bookRepository.findBooksWithMetadataAndAuthors(List.of(1L, 2L)))
                    .thenReturn(List.of(book1, book2));
            PopulateSearchTextMigration migration = migration();

            migration.execute();

            ArgumentCaptor<List<BookEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(bookRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).containsExactly(book1, book2);
            assertThat(book1.getMetadata().getSearchText()).contains("book one");
            assertThat(book2.getMetadata().getSearchText()).contains("book two");
            // Only one findBooksForMigrationBatch call: the batch was smaller than BATCH_SIZE, so the loop stops.
            verify(bookRepository, never()).findBooksForMigrationBatch(eq(2L), any(Pageable.class));
        }

        @Test
        @DisplayName("continues to a second batch when the first is exactly a full batch, then stops on a smaller one")
        void fullBatchThenSmallBatch_continuesLoop() {
            List<BookEntity> firstBatch = new ArrayList<>();
            LongStream.rangeClosed(1, BATCH_SIZE).forEach(id -> firstBatch.add(bookWithTitle(id, "Book " + id)));
            BookEntity lastOfSecondBatch = bookWithTitle(BATCH_SIZE + 1L, "Final Book");

            when(bookRepository.findBooksForMigrationBatch(eq(0L), any(Pageable.class))).thenReturn(firstBatch);
            when(bookRepository.findBooksForMigrationBatch(eq((long) BATCH_SIZE), any(Pageable.class)))
                    .thenReturn(List.of(lastOfSecondBatch));
            when(bookRepository.findBooksWithMetadataAndAuthors(any())).thenReturn(firstBatch, List.of(lastOfSecondBatch));

            PopulateSearchTextMigration migration = migration();
            migration.execute();

            verify(bookRepository, never()).findBooksForMigrationBatch(eq(BATCH_SIZE + 1L), any(Pageable.class));
            verify(bookRepository, times(2)).saveAll(any());
        }
    }

    @Nested
    @DisplayName("execute - per-book search text handling")
    class SearchTextHandlingTests {

        @Test
        @DisplayName("skips books with no metadata without failing the batch")
        void bookWithoutMetadata_skippedQuietly() {
            BookEntity noMetadataBook = BookEntity.builder().id(1L).metadata(null).build();
            when(bookRepository.findBooksForMigrationBatch(eq(0L), any(Pageable.class)))
                    .thenReturn(List.of(noMetadataBook));
            when(bookRepository.findBooksWithMetadataAndAuthors(List.of(1L))).thenReturn(List.of(noMetadataBook));
            PopulateSearchTextMigration migration = migration();

            migration.execute();

            ArgumentCaptor<List<BookEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(bookRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).containsExactly(noMetadataBook);
        }

        @Test
        @DisplayName("logs and continues when building the search text throws for one book")
        void buildSearchTextThrows_logsAndContinuesBatch() {
            // getTitle() is read outside BookUtils.buildSearchText's own internal (author-list) try/catch,
            // so stubbing it to throw exercises the migration's own defensive catch around the whole call.
            BookMetadataEntity brokenMetadata = mock(BookMetadataEntity.class);
            when(brokenMetadata.getTitle()).thenThrow(new RuntimeException("boom"));
            BookEntity brokenBook = BookEntity.builder().id(1L).metadata(brokenMetadata).build();
            BookEntity healthyBook = bookWithTitle(2L, "Healthy Book");

            when(bookRepository.findBooksForMigrationBatch(eq(0L), any(Pageable.class)))
                    .thenReturn(List.of(brokenBook, healthyBook));
            when(bookRepository.findBooksWithMetadataAndAuthors(List.of(1L, 2L)))
                    .thenReturn(List.of(brokenBook, healthyBook));
            PopulateSearchTextMigration migration = migration();

            migration.execute();

            ArgumentCaptor<List<BookEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(bookRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).containsExactly(brokenBook, healthyBook);
            assertThat(healthyBook.getMetadata().getSearchText()).contains("healthy book");
            verify(brokenMetadata, never()).setSearchText(any());
        }
    }
}
