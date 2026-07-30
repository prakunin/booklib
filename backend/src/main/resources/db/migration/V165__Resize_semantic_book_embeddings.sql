DROP TABLE book_semantic_embedding;

CREATE TABLE book_semantic_embedding
(
    book_id       BIGINT      NOT NULL,
    embedding     VECTOR(512) NOT NULL,
    model_version VARCHAR(48) CHARACTER SET ascii NOT NULL,
    content_hash  BINARY(32)  NOT NULL,
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (book_id, model_version),
    CONSTRAINT fk_book_semantic_embedding_book
        FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE
);

CREATE VECTOR INDEX idx_book_semantic_embedding_cosine
    ON book_semantic_embedding (embedding) M = 8 DISTANCE = cosine;

UPDATE recommendation_embedding_state
SET active_model = 'feature-hash-v1',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = 1;

UPDATE app_settings
SET val = JSON_SET(
        val,
        '$.model', 'embeddinggemma:300m',
        '$.dimensions', 512,
        '$.batchSize', 64
          )
WHERE name = 'RECOMMENDATION_EMBEDDING_SETTINGS'
  AND JSON_VALID(val);
