package org.booklore.service.migration.migrations;

import org.booklore.repository.BookMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FoldSearchTextPunctuationMigrationTest {

    private static final int BATCH_SIZE = 500;

    @Mock
    private BookMetadataRepository bookMetadataRepository;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = mock(TransactionTemplate.class);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    private FoldSearchTextPunctuationMigration migration() {
        return new FoldSearchTextPunctuationMigration(bookMetadataRepository, transactionTemplate);
    }

    private BookMetadataRepository.SearchTextView row(long bookId, String searchText) {
        return new SearchTextRow(bookId, searchText);
    }

    private record SearchTextRow(Long getBookId, String getSearchText) implements BookMetadataRepository.SearchTextView {
        @Override
        public Long getBookId() {
            return getBookId;
        }

        @Override
        public String getSearchText() {
            return getSearchText;
        }
    }

    @Test
    @DisplayName("getKey and getDescription report the migration's identity")
    void keyAndDescription() {
        FoldSearchTextPunctuationMigration migration = migration();

        assertThat(migration.getKey()).isEqualTo("foldSearchTextPunctuation");
        assertThat(migration.getDescription()).isEqualTo("Fold typographic quotes, apostrophes and dashes in stored search_text");
    }

    @Test
    @DisplayName("does nothing when no stored text carries typographic punctuation")
    void noRows_writesNothing() {
        when(bookMetadataRepository.findSearchTextsWithTypographicPunctuation(eq(0L), any(Pageable.class)))
                .thenReturn(List.of());

        migration().execute();

        verify(bookMetadataRepository, never()).updateSearchText(anyLong(), any());
    }

    @Test
    @DisplayName("folds guillemets so the stored text matches a query typed without them")
    void foldsQuotesApostrophesAndDashes() {
        when(bookMetadataRepository.findSearchTextsWithTypographicPunctuation(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(
                        row(1L, "generation «p» pelevin"),
                        row(2L, "don’t look now"),
                        row(3L, "1941–1945 war")));

        migration().execute();

        verify(bookMetadataRepository).updateSearchText(1L, "generation p pelevin");
        verify(bookMetadataRepository).updateSearchText(2L, "don't look now");
        verify(bookMetadataRepository).updateSearchText(3L, "1941-1945 war");
    }

    @Test
    @DisplayName("leaves a row alone when folding changes nothing")
    void unchangedRow_isNotWritten() {
        when(bookMetadataRepository.findSearchTextsWithTypographicPunctuation(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(row(7L, "plain text")));

        migration().execute();

        verify(bookMetadataRepository, never()).updateSearchText(anyLong(), any());
    }

    @Test
    @DisplayName("keyset paging continues past a full batch and stops on a short one")
    void pagesByLastBookId() {
        List<BookMetadataRepository.SearchTextView> fullBatch = LongStream.rangeClosed(1, BATCH_SIZE)
                .mapToObj(id -> row(id, "title «x»"))
                .toList();
        when(bookMetadataRepository.findSearchTextsWithTypographicPunctuation(eq(0L), any(Pageable.class)))
                .thenReturn(fullBatch);
        when(bookMetadataRepository.findSearchTextsWithTypographicPunctuation(eq((long) BATCH_SIZE), any(Pageable.class)))
                .thenReturn(List.of(row(BATCH_SIZE + 1L, "tail — dash")));

        migration().execute();

        verify(bookMetadataRepository).findSearchTextsWithTypographicPunctuation(eq((long) BATCH_SIZE), any(Pageable.class));
        verify(bookMetadataRepository).updateSearchText(BATCH_SIZE + 1L, "tail - dash");
    }
}
