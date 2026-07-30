CREATE TABLE book_semantic_embedding
(
    book_id       BIGINT      NOT NULL,
    embedding     VECTOR(128) NOT NULL,
    model_version VARCHAR(48) CHARACTER SET ascii NOT NULL,
    content_hash  BINARY(32)  NOT NULL,
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (book_id, model_version),
    CONSTRAINT fk_book_semantic_embedding_book
        FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE
);

CREATE VECTOR INDEX idx_book_semantic_embedding_cosine
    ON book_semantic_embedding (embedding) M = 8 DISTANCE = cosine;

CREATE TABLE recommendation_embedding_state
(
    id           TINYINT     NOT NULL,
    active_model VARCHAR(64) NOT NULL,
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_recommendation_embedding_state_singleton CHECK (id = 1)
);

INSERT INTO recommendation_embedding_state (id, active_model)
VALUES (1, 'feature-hash-v1');
