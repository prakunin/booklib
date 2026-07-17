-- The command palette searches a denormalized title/series/author document.
-- A regular BTREE cannot serve its previous LIKE '%query%' predicates, while
-- FULLTEXT lets MariaDB resolve large-catalog quick searches from an inverted index.
-- Creating the first InnoDB FULLTEXT index rebuilds book_metadata; upgrades of
-- very large catalogs should therefore allow extra startup time and disk space.

CREATE FULLTEXT INDEX IF NOT EXISTS idx_book_metadata_search_text
    ON book_metadata (search_text);
