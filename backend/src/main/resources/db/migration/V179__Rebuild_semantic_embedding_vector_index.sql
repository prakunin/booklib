-- V166 emptied a ~700k-row book_semantic_embedding with a bulk DELETE, which left the HNSW graph
-- behind idx_book_semantic_embedding_cosine unusable: `ORDER BY VEC_DISTANCE_COSINE(...) LIMIT n`
-- returned zero rows for every query vector, while the same rows ranked correctly when scanned
-- without the index. Semantic search therefore answered every search with an empty list.
--
-- ALTER TABLE ... FORCE does not rebuild a vector index; only dropping and recreating it does.
-- Reset embeddings by dropping and recreating the table the way V165 does, never with a bulk DELETE.
ALTER TABLE book_semantic_embedding DROP INDEX idx_book_semantic_embedding_cosine;

ALTER TABLE book_semantic_embedding
    ADD VECTOR INDEX idx_book_semantic_embedding_cosine (embedding) M = 8 DISTANCE = cosine;
