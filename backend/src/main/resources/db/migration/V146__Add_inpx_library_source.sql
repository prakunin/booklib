ALTER TABLE library
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'FILESYSTEM',
    ADD COLUMN inpx_path VARCHAR(1000) NULL,
    ADD COLUMN inpx_archive_path VARCHAR(1000) NULL;

ALTER TABLE book_file
    ADD COLUMN source_archive VARCHAR(1000) NULL,
    ADD COLUMN source_archive_entry VARCHAR(1000) NULL;

CREATE INDEX idx_book_file_archive_source
    ON book_file (source_archive(255), source_archive_entry(255));
