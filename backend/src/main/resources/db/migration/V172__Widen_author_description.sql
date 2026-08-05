-- Author biographies are long-form prose supplied by a catalog, not a short blurb typed into a form,
-- and TEXT counts bytes rather than characters: a Cyrillic biography costs about two bytes per
-- character, so it overflows at roughly 32,000 characters. 102 of the 56,853 biographies in the
-- shipped fb2.Flibusta.Net.FLibrary.etc catalog are larger than TEXT can hold, the longest being
-- 559,153 bytes, and a measured backfill lost 19 books in 14,000 (~0.14%) to it.
--
-- The loss is not limited to the biography. The write happens inside the book's enrichment
-- transaction, so "Data too long for column 'description'" rolls that book back entirely and it
-- loses its description, series and reviews as well. Truncating would hide the damage instead of
-- avoiding it, so the column is widened.
--
-- MEDIUMTEXT holds 16,777,215 bytes — thirty times the longest biography this catalog ships, and
-- the smallest of the wider types that fits. InnoDB stores it off-page exactly as it did TEXT, so
-- the row format does not change.

ALTER TABLE author
    MODIFY COLUMN description MEDIUMTEXT NULL;
