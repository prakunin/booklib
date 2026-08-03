-- Path to the local metadata catalog that ships next to a library — for INPX libraries this is the
-- sibling "*.FLibrary.etc" directory holding annotations, reviews, author biographies and
-- compilations. Null means the library has no local catalog, which is the case for every existing
-- library, so no backfill is needed.

ALTER TABLE library
    ADD COLUMN metadata_sidecar_path VARCHAR(1000) NULL,
    ALGORITHM = INSTANT,
    LOCK = NONE;
