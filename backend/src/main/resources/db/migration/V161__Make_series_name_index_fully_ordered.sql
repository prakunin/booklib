-- MariaDB silently created idx_book_metadata_series_name as a 768-character prefix index
-- while series_name was VARCHAR(1000). Prefix indexes cannot stream the series GROUP BY,
-- so every page built and sorted a temporary table for the entire catalog.
-- 767 utf8mb4 characters fit in InnoDB's 3072-byte key limit including key overhead.
ALTER TABLE book_metadata
    DROP INDEX idx_book_metadata_series_name,
    MODIFY COLUMN series_name VARCHAR(767),
    ADD INDEX idx_book_metadata_series_name (series_name);
