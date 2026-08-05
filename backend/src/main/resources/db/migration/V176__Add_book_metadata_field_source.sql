-- Records which provider supplied each individual metadata field.
--
-- book_metadata carries 21 *_locked booleans and no provider column, so once a refresh has run there
-- is no way to tell a description the user typed from one scraped off Goodreads from one read out of
-- a library's local catalog. This table answers that question per field.
--
-- A side table rather than ~21 *_source columns on book_metadata: the set of attributable fields
-- moves whenever a provider is added, and a row per attributed field also encodes "not attributed" as
-- the absence of a row, which a column cannot do without a sentinel value.
--
-- A row asserts that the value currently stored in book_metadata came from `provider`. It is written
-- only when the updater actually wrote the field, and deleted when the field is cleared or when it is
-- changed by something that carries no provider (a manual edit). Books filled before this table
-- existed get no rows: an absent row means "unknown", never "manual", and is not backfilled.

CREATE TABLE IF NOT EXISTS book_metadata_field_source
(
    book_id    BIGINT      NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    provider   VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (book_id, field_name),
    CONSTRAINT fk_field_source_book
        FOREIGN KEY (book_id) REFERENCES book_metadata (book_id) ON DELETE CASCADE
);
