-- Composite indexes for the default ORDER BY of the hottest book-listing endpoints.
-- AppBookService list/search/recently-added sort by added_on, recently-scanned by
-- scanned_on, and every one of those queries also filters on the soft-delete flag.
-- Leading with `deleted` (almost always FALSE) lets the engine walk the index in sort
-- order and skip the filesort that a large library would otherwise pay on every page.
-- Additive index-only change (no data backfill); on a very large book table the build
-- may still briefly hold a metadata lock.

CREATE INDEX IF NOT EXISTS idx_book_deleted_added_on ON book (deleted, added_on);
CREATE INDEX IF NOT EXISTS idx_book_deleted_scanned_on ON book (deleted, scanned_on);
