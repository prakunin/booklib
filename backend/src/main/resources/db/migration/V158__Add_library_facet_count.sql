-- Materialized per-library facet counts for the filter-options panel. Computing these live is a
-- whole-catalog aggregation (~14s across ~30 GROUP BY COUNT(DISTINCT book) queries when one library
-- holds the bulk of the catalog); the in-memory FilterOptionsCache masks it but is lost on restart
-- and wiped on every book add. This table holds the unrestricted, per-library facet counts so the
-- read path serves them instantly, refreshed off the request path by a scheduled recompute task.
--
-- Only GLOBAL facets live here (derived from book/book_metadata/book_file and their mapping tables).
-- Per-user facets (read status, personal rating) and content-restricted / shelf-filtered views stay
-- on the live path. Counts are per single library and additive across libraries (a book belongs to
-- exactly one library), so a multi-library scope is served by summing rows.
CREATE TABLE IF NOT EXISTS library_facet_count (
    library_id  BIGINT       NOT NULL,
    facet_type  VARCHAR(32)  NOT NULL,
    facet_value VARCHAR(255) NOT NULL,
    book_count  BIGINT       NOT NULL,
    PRIMARY KEY (library_id, facet_type, facet_value),
    CONSTRAINT fk_library_facet_count_library
        FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_library_facet_count_lookup
    ON library_facet_count (library_id, facet_type);

-- Per-library recompute bookkeeping: the dirty-flag sweep recomputes a library only when its books
-- have changed since computed_at (MAX(added_on, scanned_on, metadata_updated_at, deleted_at) > it).
CREATE TABLE IF NOT EXISTS library_facet_state (
    library_id  BIGINT    NOT NULL,
    computed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (library_id),
    CONSTRAINT fk_library_facet_state_library
        FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE
);
