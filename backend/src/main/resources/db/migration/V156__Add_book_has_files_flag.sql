-- Denormalized "book has at least one book_file row" flag. MariaDB executes the previous
-- per-query predicate (EXISTS(SELECT 1 FROM book_file ...) OR is_physical) as a correlated
-- subquery per book row - ~2.5s per query on a 630k-book catalog.
ALTER TABLE book ADD COLUMN IF NOT EXISTS has_files BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE book
SET has_files = EXISTS(SELECT 1 FROM book_file bf WHERE bf.book_id = book.id);

CREATE INDEX IF NOT EXISTS idx_book_has_files_physical ON book (has_files, is_physical);
