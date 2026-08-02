package org.booklore.repository;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.BookSemanticEmbedding;
import org.booklore.repository.projection.BookEmbeddingCandidate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class BookEmbeddingVectorRepository {

    private static final String BOOK_ID_COLUMN = "book_id";
    private static final String SCORE_COLUMN = "score";
    private static final String SERIES_NAME_COLUMN = "series_name";

    public static final String MODEL_VERSION = "feature-hash-v1";

    private static final String FIND_NEAREST_FEATURE_SQL = """
            SELECT candidate.book_id,
                   1 - VEC_DISTANCE_COSINE(candidate.embedding, VEC_FROMTEXT(?)) AS score,
                   metadata.series_name
            FROM book_embedding candidate
                     JOIN book ON book.id = candidate.book_id
                     LEFT JOIN book_metadata metadata ON metadata.book_id = candidate.book_id
            WHERE candidate.book_id <> ?
              AND book.deleted = FALSE
            ORDER BY VEC_DISTANCE_COSINE(candidate.embedding, VEC_FROMTEXT(?))
            LIMIT ?
            """;

    private static final String FIND_NEAREST_SEMANTIC_SQL = """
            SELECT candidate.book_id,
                   1 - VEC_DISTANCE_COSINE(candidate.embedding, VEC_FROMTEXT(?)) AS score,
                   metadata.series_name
            FROM book_semantic_embedding candidate
                     JOIN book ON book.id = candidate.book_id
                     LEFT JOIN book_metadata metadata ON metadata.book_id = candidate.book_id
            WHERE candidate.model_version = ?
              AND candidate.book_id <> ?
              AND book.deleted = FALSE
            ORDER BY VEC_DISTANCE_COSINE(candidate.embedding, VEC_FROMTEXT(?))
            LIMIT ?
            """;

    private static final String FIND_NEAREST_BY_VECTOR_SQL = """
            SELECT candidate.book_id,
                   1 - VEC_DISTANCE_COSINE(candidate.embedding, VEC_FROMTEXT(?)) AS score,
                   metadata.series_name
            FROM book_semantic_embedding candidate
                     JOIN book ON book.id = candidate.book_id
                     LEFT JOIN book_metadata metadata ON metadata.book_id = candidate.book_id
            WHERE candidate.model_version = ?
              AND book.deleted = FALSE
            ORDER BY VEC_DISTANCE_COSINE(candidate.embedding, VEC_FROMTEXT(?))
            LIMIT ?
            """;

    private static final String UPSERT_FEATURE_SQL = """
            INSERT INTO book_embedding (book_id, embedding, model_version, embedding_hash, updated_at)
            VALUES (?, VEC_FROMTEXT(?), ?, UNHEX(SHA2(?, 256)), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
                embedding = IF(embedding_hash <> VALUES(embedding_hash), VALUES(embedding), embedding),
                model_version = IF(embedding_hash <> VALUES(embedding_hash), VALUES(model_version), model_version),
                updated_at = IF(embedding_hash <> VALUES(embedding_hash), VALUES(updated_at), updated_at),
                embedding_hash = VALUES(embedding_hash)
            """;

    private static final String UPSERT_SEMANTIC_SQL = """
            INSERT INTO book_semantic_embedding (book_id, embedding, model_version, content_hash, updated_at)
            VALUES (?, VEC_FROMTEXT(?), ?, UNHEX(?), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
                embedding = IF(content_hash <> VALUES(content_hash), VALUES(embedding), embedding),
                updated_at = IF(content_hash <> VALUES(content_hash), VALUES(updated_at), updated_at),
                content_hash = VALUES(content_hash),
                model_version = VALUES(model_version)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public List<BookEmbeddingCandidate> findNearestCandidates(
            long excludedBookId,
            String targetVectorJson,
            int limit,
            String semanticModelVersion) {
        if (semanticModelVersion != null) {
            return jdbcTemplate.query(
                    FIND_NEAREST_SEMANTIC_SQL,
                    (resultSet, rowNumber) -> new BookEmbeddingCandidate(
                            resultSet.getLong(BOOK_ID_COLUMN),
                            resultSet.getDouble(SCORE_COLUMN),
                            resultSet.getString(SERIES_NAME_COLUMN)),
                    targetVectorJson,
                    semanticModelVersion,
                    excludedBookId,
                    targetVectorJson,
                    limit);
        }
        return jdbcTemplate.query(
                FIND_NEAREST_FEATURE_SQL,
                (resultSet, rowNumber) -> new BookEmbeddingCandidate(
                        resultSet.getLong(BOOK_ID_COLUMN),
                        resultSet.getDouble(SCORE_COLUMN),
                        resultSet.getString(SERIES_NAME_COLUMN)),
                targetVectorJson,
                excludedBookId,
                targetVectorJson,
                limit);
    }

    /**
     * Nearest neighbours of a free-standing query vector across the whole catalog.
     * Unlike {@link #findNearestCandidates} no book is excluded: the vector does not belong to one.
     */
    public List<BookEmbeddingCandidate> findNearestByVector(String queryVectorJson,
                                                            int limit,
                                                            String semanticModelVersion) {
        return jdbcTemplate.query(
                FIND_NEAREST_BY_VECTOR_SQL,
                (resultSet, rowNumber) -> new BookEmbeddingCandidate(
                        resultSet.getLong(BOOK_ID_COLUMN),
                        resultSet.getDouble(SCORE_COLUMN),
                        resultSet.getString(SERIES_NAME_COLUMN)),
                queryVectorJson,
                semanticModelVersion,
                queryVectorJson,
                limit);
    }

    /**
     * Whether any book is already indexed for the given model version. Semantic search runs against
     * partial coverage, so this only guards against embedding a query that could not match anything.
     */
    public boolean hasEmbeddingsForModel(String modelVersion) {
        List<Integer> present = jdbcTemplate.queryForList(
                "SELECT 1 FROM book_semantic_embedding WHERE model_version = ? LIMIT 1",
                Integer.class,
                modelVersion);
        return !present.isEmpty();
    }

    public boolean isSemanticActive() {
        return !MODEL_VERSION.equals(activeModel());
    }

    public String activeModel() {
        return jdbcTemplate.queryForObject(
                "SELECT active_model FROM recommendation_embedding_state WHERE id = 1",
                String.class);
    }

    public Optional<String> findSemanticVectorJson(long bookId, String modelVersion) {
        List<String> vectors = jdbcTemplate.query(
                """
                SELECT VEC_TOTEXT(embedding)
                FROM book_semantic_embedding
                WHERE book_id = ? AND model_version = ?
                """,
                (resultSet, rowNumber) -> resultSet.getString(1),
                bookId,
                modelVersion);
        return vectors.stream().findFirst();
    }

    public Map<Long, String> findSemanticContentHashes(Collection<Long> bookIds, String modelVersion) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> fingerprints = new HashMap<>();
        namedParameterJdbcTemplate.query("""
                        SELECT book_id,
                               LOWER(HEX(content_hash)) AS content_hash
                        FROM book_semantic_embedding
                        WHERE book_id IN (:bookIds)
                          AND model_version = :modelVersion
                        """,
                Map.of("bookIds", bookIds, "modelVersion", modelVersion),
                (RowCallbackHandler) resultSet -> fingerprints.put(
                        resultSet.getLong(BOOK_ID_COLUMN),
                        resultSet.getString("content_hash")));
        return fingerprints;
    }

    @Transactional
    public void upsertSemantic(List<BookSemanticEmbedding> embeddings, String modelVersion) {
        if (embeddings.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                UPSERT_SEMANTIC_SQL,
                embeddings,
                embeddings.size(),
                (statement, embedding) -> {
                    statement.setLong(1, embedding.bookId());
                    statement.setString(2, embedding.vectorJson());
                    statement.setString(3, modelVersion);
                    statement.setString(4, embedding.contentHash());
                });
    }

    public long countSemanticEmbeddingsForActiveBooks(String modelVersion) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM book_semantic_embedding embedding
                         JOIN book ON book.id = embedding.book_id
                WHERE book.deleted = FALSE
                  AND embedding.model_version = ?
                """, Long.class, modelVersion);
        return count == null ? 0 : count;
    }

    @Transactional
    public boolean activateSemantic(String modelVersion) {
        return jdbcTemplate.update("""
                UPDATE recommendation_embedding_state
                SET active_model = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = 1 AND active_model <> ?
                """, modelVersion, modelVersion) > 0;
    }

    public void upsertAll(Map<Long, String> embeddingJsonByBookId) {
        if (embeddingJsonByBookId.isEmpty()) {
            return;
        }
        List<Map.Entry<Long, String>> entries = new ArrayList<>(embeddingJsonByBookId.entrySet());
        jdbcTemplate.batchUpdate(
                UPSERT_FEATURE_SQL,
                entries,
                entries.size(),
                (PreparedStatement statement, Map.Entry<Long, String> entry) -> {
                    statement.setLong(1, entry.getKey());
                    statement.setString(2, entry.getValue());
                    statement.setString(3, MODEL_VERSION);
                    statement.setString(4, entry.getValue());
                });
    }
}
