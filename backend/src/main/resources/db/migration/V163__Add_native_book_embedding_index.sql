CREATE TABLE book_embedding
(
    book_id        BIGINT      NOT NULL,
    embedding      VECTOR(128) NOT NULL,
    model_version  VARCHAR(64) NOT NULL,
    embedding_hash BINARY(32)  NOT NULL,
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (book_id),
    CONSTRAINT fk_book_embedding_book
        FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE
);

INSERT INTO book_embedding (book_id, embedding, model_version, embedding_hash, updated_at)
SELECT metadata.book_id,
       VEC_FROMTEXT(metadata.embedding_vector),
       'feature-hash-v1',
       UNHEX(SHA2(metadata.embedding_vector, 256)),
       COALESCE(metadata.embedding_updated_at, CURRENT_TIMESTAMP(6))
FROM book_metadata metadata
         JOIN book ON book.id = metadata.book_id
WHERE book.deleted = FALSE
  AND metadata.embedding_vector IS NOT NULL
  AND JSON_VALID(metadata.embedding_vector)
  AND JSON_LENGTH(metadata.embedding_vector) = 128;

CREATE VECTOR INDEX idx_book_embedding_cosine
    ON book_embedding (embedding) M = 8 DISTANCE = cosine;
