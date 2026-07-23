-- Materialized library statistics for the statistics screen. Computing them live is a whole-catalog
-- aggregation (author/series/publisher distinct counts, per-month book counts, per-author book/page
-- sums) that runs to tens of seconds on a large catalog; the in-memory caches mask it but are lost on
-- restart. These tables hold the user-independent aggregates so the read path serves them instantly,
-- refreshed off the request path by the same scheduled recompute that maintains the facet counts.
--
-- Split across two scopes because distinct counts are NOT additive across libraries (one author can
-- appear in several libraries): per-library rows (additive counters summed at read time; distinct
-- counts used for the single-library scope) and separate catalog-wide tables (the exact whole-catalog
-- distinct counts and top authors, no FK to library since they represent the union of all libraries).

CREATE TABLE IF NOT EXISTS library_stat (
    library_id BIGINT      NOT NULL,
    -- TOTAL_BOOKS, TOTAL_SIZE_KB, TOTAL_AUTHORS, TOTAL_SERIES, TOTAL_PUBLISHERS.
    stat_key   VARCHAR(32) NOT NULL,
    stat_value BIGINT      NOT NULL,
    PRIMARY KEY (library_id, stat_key),
    CONSTRAINT fk_library_stat_library
        FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS library_stat_month (
    library_id BIGINT NOT NULL,
    year       INT    NOT NULL,
    month      INT    NOT NULL,
    book_count BIGINT NOT NULL,
    PRIMARY KEY (library_id, year, month),
    CONSTRAINT fk_library_stat_month_library
        FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS library_author_stat (
    library_id  BIGINT NOT NULL,
    author_id   BIGINT NOT NULL,
    book_count  BIGINT NOT NULL,
    total_pages BIGINT NOT NULL,
    PRIMARY KEY (library_id, author_id),
    CONSTRAINT fk_library_author_stat_library
        FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE,
    CONSTRAINT fk_library_author_stat_author
        FOREIGN KEY (author_id) REFERENCES author (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_library_author_stat_lookup
    ON library_author_stat (library_id, book_count);

-- Per-library recompute bookkeeping, separate from library_facet_state: on an existing installation
-- the facet state is already fresh while these tables are empty, so a shared computed_at would make
-- the sweep skip the empty statistics.
CREATE TABLE IF NOT EXISTS library_stat_state (
    library_id  BIGINT    NOT NULL,
    computed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (library_id),
    CONSTRAINT fk_library_stat_state_library
        FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE
);

-- Whole-catalog scope: only the non-additive values (distinct counts + top authors). No FK to library.
CREATE TABLE IF NOT EXISTS catalog_stat (
    stat_key   VARCHAR(32) NOT NULL,
    stat_value BIGINT      NOT NULL,
    PRIMARY KEY (stat_key)
);

CREATE TABLE IF NOT EXISTS catalog_author_stat (
    author_id   BIGINT NOT NULL,
    book_count  BIGINT NOT NULL,
    total_pages BIGINT NOT NULL,
    PRIMARY KEY (author_id),
    CONSTRAINT fk_catalog_author_stat_author
        FOREIGN KEY (author_id) REFERENCES author (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_catalog_author_stat_rank
    ON catalog_author_stat (book_count);

CREATE TABLE IF NOT EXISTS catalog_stat_state (
    id          INT       NOT NULL,
    computed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
