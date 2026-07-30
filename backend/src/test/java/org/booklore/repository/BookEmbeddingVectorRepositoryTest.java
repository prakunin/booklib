package org.booklore.repository;

import org.booklore.repository.projection.BookEmbeddingCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookEmbeddingVectorRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @InjectMocks
    private BookEmbeddingVectorRepository repository;

    @Nested
    @DisplayName("findNearestByVector")
    class FindNearestByVector {

        @Test
        void searchesTheWholeCatalogWithoutExcludingAnyBook() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of(new BookEmbeddingCandidate(7L, 0.81, "Метро")));

            List<BookEmbeddingCandidate> candidates =
                    repository.findNearestByVector("[0.1,0.2]", 25, "embeddinggemma:300m-512-v2");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            org.mockito.Mockito.verify(jdbcTemplate)
                    .query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());

            assertThat(sqlCaptor.getValue())
                    .doesNotContain("book_id <> ")
                    .contains("model_version = ?")
                    .contains("book.deleted = FALSE")
                    .contains("VEC_DISTANCE_COSINE");
            assertThat(argsCaptor.getValue())
                    .containsExactly("[0.1,0.2]", "embeddinggemma:300m-512-v2", "[0.1,0.2]", 25);
            assertThat(candidates).singleElement()
                    .extracting(BookEmbeddingCandidate::bookId)
                    .isEqualTo(7L);
        }
    }
}
