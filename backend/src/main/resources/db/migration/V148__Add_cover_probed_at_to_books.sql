-- Explicit ALGORITHM=INSTANT so this fails loudly on the 623k-row `book` table if the server
-- can't add the column instantly, instead of silently falling back to an in-place or copying
-- rebuild that holds a metadata lock. Supported on the MariaDB 12.3.2 deployment target.
-- No IF NOT EXISTS: this migration is unreleased, and IF NOT EXISTS would silently accept a
-- pre-existing column with an incompatible definition rather than failing the deployment.
ALTER TABLE book
    ADD COLUMN cover_probed_at TIMESTAMP NULL,
    ALGORITHM = INSTANT,
    LOCK = NONE;
