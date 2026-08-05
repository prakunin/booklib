-- Make the local-catalog backfill's keyset cursor an index range scan instead of a full sort.
--
-- `LocalCatalogBackfillService` walks a library's archived books with a keyset cursor ordered by
-- (source_archive, source_archive_entry, book_id). The only index on those columns was the one
-- V146 created:
--
--     CREATE INDEX idx_book_file_archive_source ON book_file (source_archive(255), source_archive_entry(255));
--
-- which is a *prefix* index, because both columns are VARCHAR(1000) and 4000 bytes of utf8mb4 will
-- not fit in InnoDB's 3072-byte key limit. A prefix index cannot establish an ordering — the rows
-- sharing a prefix are unordered within it — so MariaDB can use it for the range but must sort the
-- whole result before applying LIMIT. Measured on library 19 (704,575 book_file rows):
--
--     EXPLAIN … ORDER BY source_archive, source_archive_entry, book_id LIMIT 500
--       type=range  key=idx_book_file_archive_source  rows=701674  Extra: Using where; Using filesort
--
--     start of walk   5.950 s      offset 14,000   4.826 s
--
-- and the application's own hibernate SQL_SLOW log recorded every one of an 18-page run's cursor
-- queries as slow, mean 5.006 s per page — 13% of that run's wall clock, and flat with depth, which
-- is what a per-page sort of the whole table looks like rather than a keyset walk.
--
-- The fix is an index that is not a prefix, which first requires columns narrow enough to index in
-- full. Both hold a single file name: source_archive is the archive's own name and
-- source_archive_entry the name of the entry inside it. Measured over all 704,575 rows the longest
-- are 23 and 120 characters; VARCHAR(255) and VARCHAR(500) are 11x and 4x that, and 255 characters
-- of utf8mb4 is 1020 bytes, already more than the 255 bytes a filesystem allows a name component.
-- The resulting key is 1023 + 2003 + 8 = 3034 bytes, inside the 3072-byte limit.
--
--     EXPLAIN with the new index, same query, both at the start of the walk and at offset 14,000
--       type=range  key=idx_book_file_archive_cursor  Extra: Using where; Using index   (no filesort)
--
--     start of walk   0.0029 s     offset 14,000   0.0020 s      (~2,000x)
--
-- The three-way OR of the cursor predicate is left exactly as it is: with a non-prefix index
-- MariaDB turns it into a range over all three key parts (key_len 2054), so there is nothing for a
-- row-comparison rewrite to buy.
--
-- Narrowing a column can only lose data, so it is guarded: if any row would not fit, the whole
-- migration is a no-op and the database keeps V146's schema and prefix index — slower, but nothing
-- is truncated and nothing fails to start. On a MariaDB running the default STRICT_TRANS_TABLES the
-- ALTER would have errored rather than truncated, but a migration that refuses to start the
-- application is a worse outcome than a migration that declines to speed it up.
--
-- The skip used to be silent: Flyway would still record V173 as success=1, so it would never run
-- again even after the offending row was fixed, and nothing in any log said why the backfill was
-- still slow. Two changes close that: (1) the ELSE branch below now CALLs a throwaway stored
-- procedure that SIGNALs SQLSTATE '01000' ("warning", not an error, so it cannot abort the
-- migration or fail startup) — read via `java.sql.Statement.getWarnings()`, which Flyway's
-- `JdbcTemplate.extractWarnings` always drains after every statement into a `Warning` whose SQLSTATE
-- ("01000" != "00000") makes `ErrorOverridesSupportStub.printWarning` (the only ServiceLoader-
-- registered `ErrorOverridesSupport`, confirmed against the flyway-core 12.11.0 jar this project
-- pins) log it at WARN through Flyway's own logger — so a skip now writes a WARN line to the
-- application's ordinary startup log instead of nothing. (2) V175 retries this same guarded
-- narrowing, so a database that trips this guard today gets a second, later chance once the
-- offending row is fixed, rather than being stuck on the slow plan forever behind a success=1 row.
--
-- Narrowing a VARCHAR forces InnoDB's copying algorithm — a full table rebuild that holds a
-- metadata lock for its duration — so, matching V150's precedent of stating the algorithm and lock
-- explicitly rather than letting the server pick, the ALTER below declares ALGORITHM=COPY,
-- LOCK=SHARED: readers may keep reading book_file for the rebuild's duration, writers block. This
-- cannot be ALGORITHM=INSTANT like V150's — narrowing is not an instant metadata-only change.
-- Measured 7.9 s on the dev database's 704,575-row book_file; expect it to scale with table size,
-- and expect writers to book_file to block for that long.

SET @book_file_archive_fits = (
    SELECT COUNT(*) = 0
    FROM book_file
    WHERE CHAR_LENGTH(source_archive) > 255
       OR CHAR_LENGTH(source_archive_entry) > 500
);

SET @narrow_sql = IF(@book_file_archive_fits,
    'ALTER TABLE book_file
        MODIFY COLUMN source_archive VARCHAR(255) NULL,
        MODIFY COLUMN source_archive_entry VARCHAR(500) NULL,
        ALGORITHM = COPY, LOCK = SHARED',
    'SELECT 1');
PREPARE narrow_stmt FROM @narrow_sql;
EXECUTE narrow_stmt;
DEALLOCATE PREPARE narrow_stmt;

SET @create_sql = IF(@book_file_archive_fits,
    'CREATE INDEX IF NOT EXISTS idx_book_file_archive_cursor
        ON book_file (source_archive, source_archive_entry, book_id)',
    'SELECT 1');
PREPARE create_stmt FROM @create_sql;
EXECUTE create_stmt;
DEALLOCATE PREPARE create_stmt;

-- idx_book_file_archive_source is now strictly redundant: the new index leads with the same two
-- columns, holds them in full rather than truncated to 255, and adds book_id, so it serves every
-- lookup the old one served and serves it better. Keeping both would double the write cost of the
-- 704,575-row table for nothing.
SET @drop_sql = IF(@book_file_archive_fits,
    'DROP INDEX IF EXISTS idx_book_file_archive_source ON book_file',
    'SELECT 1');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- Make the skip visible. A CREATE-CALL-DROP throwaway procedure is required because SIGNAL is only
-- legal inside a stored program, not as a bare statement.
DROP PROCEDURE IF EXISTS book_file_archive_narrowing_skipped_v173;

CREATE PROCEDURE book_file_archive_narrowing_skipped_v173()
BEGIN
    IF NOT @book_file_archive_fits THEN
        SIGNAL SQLSTATE '01000'
            SET MESSAGE_TEXT = 'V173: book_file archive/entry column narrowing skipped (row too wide); cursor filesort stays; see V175';
    END IF;
END;

CALL book_file_archive_narrowing_skipped_v173();
DROP PROCEDURE book_file_archive_narrowing_skipped_v173;
