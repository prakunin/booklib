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
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
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
            verify(jdbcTemplate)
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

    @Test
    void mapsFeatureAndSemanticNearestCandidates() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("book_id")).thenReturn(9L);
        when(resultSet.getDouble("score")).thenReturn(0.75);
        when(resultSet.getString("series_name")).thenReturn("Series");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<BookEmbeddingCandidate> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        assertThat(repository.findNearestCandidates(1L, "[1]", 3, null))
                .containsExactly(new BookEmbeddingCandidate(9L, 0.75, "Series"));
        assertThat(repository.findNearestCandidates(1L, "[1]", 3, "semantic-v1"))
                .containsExactly(new BookEmbeddingCandidate(9L, 0.75, "Series"));
    }

    @Test
    void reportsSemanticAvailabilityAndActiveModel() {
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), eq("semantic-v1")))
                .thenReturn(List.of(1));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenReturn("semantic-v1", BookEmbeddingVectorRepository.MODEL_VERSION);

        assertThat(repository.hasEmbeddingsForModel("semantic-v1")).isTrue();
        assertThat(repository.isSemanticActive()).isTrue();
        assertThat(repository.isSemanticActive()).isFalse();
    }

    @Test
    void readsVectorsAndContentHashes() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("[0.1]");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<String> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        doAnswer(invocation -> {
            RowCallbackHandler callback = invocation.getArgument(2);
            ResultSet row = mock(ResultSet.class);
            when(row.getLong("book_id")).thenReturn(3L);
            when(row.getString("content_hash")).thenReturn("abc");
            callback.processRow(row);
            return null;
        }).when(namedParameterJdbcTemplate).query(anyString(), anyMap(), any(RowCallbackHandler.class));

        assertThat(repository.findSemanticVectorJson(3L, "semantic-v1")).contains("[0.1]");
        assertThat(repository.findSemanticContentHashes(List.of(), "semantic-v1")).isEmpty();
        assertThat(repository.findSemanticContentHashes(List.of(3L), "semantic-v1"))
                .containsEntry(3L, "abc");
    }

    @Test
    void writesBothEmbeddingKindsAndSkipsEmptyBatches() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        doAnswer(invocation -> executeBatchSetter(invocation.getArgument(1), invocation.getArgument(3), statement))
                .when(jdbcTemplate).batchUpdate(anyString(), any(Collection.class), anyInt(),
                        any(ParameterizedPreparedStatementSetter.class));

        repository.upsertSemantic(List.of(new org.booklore.model.dto.BookSemanticEmbedding(
                4L, "[0.4]", "hash")), "semantic-v1");
        repository.upsertAll(Map.of(5L, "[0.5]"));
        repository.upsertSemantic(List.of(), "semantic-v1");
        repository.upsertAll(Map.of());

        verify(statement).setLong(1, 4L);
        verify(statement).setString(3, "semantic-v1");
        verify(statement).setLong(1, 5L);
        verify(statement).setString(3, BookEmbeddingVectorRepository.MODEL_VERSION);
    }

    @Test
    void countsAndActivatesSemanticEmbeddings() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("semantic-v1")))
                .thenReturn(7L)
                .thenReturn((Long) null);
        when(jdbcTemplate.update(anyString(), eq("semantic-v1"), eq("semantic-v1")))
                .thenReturn(1, 0);

        assertThat(repository.countSemanticEmbeddingsForActiveBooks("semantic-v1")).isEqualTo(7L);
        assertThat(repository.countSemanticEmbeddingsForActiveBooks("semantic-v1")).isZero();
        assertThat(repository.activateSemantic("semantic-v1")).isTrue();
        assertThat(repository.activateSemantic("semantic-v1")).isFalse();
    }

    private int[][] executeBatchSetter(Collection items, ParameterizedPreparedStatementSetter setter,
                                       PreparedStatement statement) throws Exception {
        for (Object item : items) {
            setter.setValues(statement, item);
        }
        return new int[][]{{1}};
    }
}
