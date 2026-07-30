DELETE FROM book_semantic_embedding;

UPDATE recommendation_embedding_state
SET active_model = 'feature-hash-v1',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = 1;
