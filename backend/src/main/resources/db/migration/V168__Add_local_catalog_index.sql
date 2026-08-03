-- Reverse index into a library's local metadata catalog (the fb2.Flibusta.Net "FLibrary.etc" layout).
--
-- Two of the catalog's data sets cannot be addressed directly: reader reviews are spread over 229
-- monthly archives and author biographies over 79 numbered buckets, and in neither case can the
-- container be derived from the key — the key is a book's (archive, entry) pair or the MD5 of an
-- author name, and which archive holds it is arbitrary. Scanning 300+ 7z containers per lookup is
-- not an option, so the containers are walked once and the mapping is kept here.
--
-- Annotations and the per-language contents listings are deliberately absent: they are addressed by
-- the archive name, which is already the entry name inside their own container, so they need no
-- index at all.
--
-- Compilations use the payload column instead of a container: the value is the small list of parts
-- itself, and a second archive read to fetch a few hundred bytes would cost more than storing them.

CREATE TABLE IF NOT EXISTS local_catalog_index
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    library_id  BIGINT       NOT NULL,
    -- REVIEW, AUTHOR_BIO, COMPILATION.
    source_type VARCHAR(32)  NOT NULL,
    entry_key   VARCHAR(320) NOT NULL,
    container   VARCHAR(255) NULL,
    payload     TEXT         NULL,
    CONSTRAINT uk_local_catalog_index UNIQUE (library_id, source_type, entry_key),
    CONSTRAINT fk_local_catalog_index_library
        FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE
);
