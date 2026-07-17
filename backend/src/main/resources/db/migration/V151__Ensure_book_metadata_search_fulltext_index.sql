-- Some dev databases applied the old V148 cover_probed_at migration before V148 was reused for
-- the command-palette FULLTEXT index. In those databases Flyway considers V148 already applied,
-- so repeat the idempotent index creation under a new version.
CREATE FULLTEXT INDEX IF NOT EXISTS idx_book_metadata_search_text
    ON book_metadata (search_text);
