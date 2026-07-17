-- Explicit ALGORITHM=INSTANT so this fails loudly on the 623k-row `book` table if the server
-- can't add the column instantly, instead of silently falling back to an in-place or copying
-- rebuild that holds a metadata lock. Supported on the MariaDB 12.3.2 deployment target.
-- The conditional keeps dev databases that already applied this column as the old V148 migration
-- bootable after the migration was renumbered, while preserving the strict ALTER for fresh DBs.
SET @add_cover_probed_at_sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'book'
              AND COLUMN_NAME = 'cover_probed_at'
        ),
        'SELECT 1',
        'ALTER TABLE book ADD COLUMN cover_probed_at TIMESTAMP NULL, ALGORITHM = INSTANT, LOCK = NONE'
    )
);
PREPARE add_cover_probed_at_stmt FROM @add_cover_probed_at_sql;
EXECUTE add_cover_probed_at_stmt;
DEALLOCATE PREPARE add_cover_probed_at_stmt;
