-- Give a database that could not take V173's column narrowing a second chance.
--
-- V173 narrows book_file.source_archive/source_archive_entry to VARCHAR(255)/VARCHAR(500) so a
-- non-prefix index can cover the local-catalog backfill's keyset cursor, but it is guarded: if any
-- row was too wide at the time it ran, V173 no-ops (and, since that fix round, WARNs about it) and
-- Flyway still records it success=1 — permanently, because Flyway never re-runs a version it has
-- already applied. A database that trips that guard has no way back to the fast plan without a new
-- migration version, even after the offending row is fixed. This is that version.
--
-- It is idempotent both ways:
--   - if `idx_book_file_archive_cursor` already exists, V173 already succeeded here and this file
--     does nothing;
--   - otherwise it repeats V173's exact guarded attempt (narrow, index, drop the old prefix index),
--     and WARNs again, the same way V173 does, if the data still does not fit.
--
-- Same lock disclosure as V173: narrowing a VARCHAR forces InnoDB's copying algorithm, so the ALTER
-- below is explicit about ALGORITHM=COPY, LOCK=SHARED — readers may keep reading book_file for the
-- rebuild's duration, writers block for it, and it scales with table size.

SET @book_file_archive_indexed = (
    SELECT COUNT(*) > 0
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'book_file'
      AND INDEX_NAME = 'idx_book_file_archive_cursor'
);

SET @book_file_archive_fits = (
    SELECT COUNT(*) = 0
    FROM book_file
    WHERE CHAR_LENGTH(source_archive) > 255
       OR CHAR_LENGTH(source_archive_entry) > 500
);

-- Only attempt the work V173 skipped: the index is missing and the data now fits.
SET @book_file_archive_needs_retry = (NOT @book_file_archive_indexed) AND @book_file_archive_fits;

SET @narrow_sql = IF(@book_file_archive_needs_retry,
    'ALTER TABLE book_file
        MODIFY COLUMN source_archive VARCHAR(255) NULL,
        MODIFY COLUMN source_archive_entry VARCHAR(500) NULL,
        ALGORITHM = COPY, LOCK = SHARED',
    'SELECT 1');
PREPARE narrow_stmt FROM @narrow_sql;
EXECUTE narrow_stmt;
DEALLOCATE PREPARE narrow_stmt;

SET @create_sql = IF(@book_file_archive_needs_retry,
    'CREATE INDEX IF NOT EXISTS idx_book_file_archive_cursor
        ON book_file (source_archive, source_archive_entry, book_id)',
    'SELECT 1');
PREPARE create_stmt FROM @create_sql;
EXECUTE create_stmt;
DEALLOCATE PREPARE create_stmt;

SET @drop_sql = IF(@book_file_archive_needs_retry,
    'DROP INDEX IF EXISTS idx_book_file_archive_source ON book_file',
    'SELECT 1');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- WARN again if the retry was needed and still could not fit; stay silent if V173 already
-- succeeded (nothing to report) or if this retry just fixed it (nothing wrong to report).
DROP PROCEDURE IF EXISTS book_file_archive_narrowing_skipped_v175;

CREATE PROCEDURE book_file_archive_narrowing_skipped_v175()
BEGIN
    IF (NOT @book_file_archive_indexed) AND (NOT @book_file_archive_fits) THEN
        SIGNAL SQLSTATE '01000'
            SET MESSAGE_TEXT = 'V175: book_file archive/entry still too wide to narrow; cursor keeps filesorting; clean data and rerun';
    END IF;
END;

CALL book_file_archive_narrowing_skipped_v175();
DROP PROCEDURE book_file_archive_narrowing_skipped_v175;
