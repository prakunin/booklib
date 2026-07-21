-- Author authority control foundation: redirect pointer + normalized blocking key.
ALTER TABLE author ADD COLUMN IF NOT EXISTS merged_into_author_id BIGINT NULL;
ALTER TABLE author ADD COLUMN IF NOT EXISTS normalized_name VARCHAR(255) NULL;

ALTER TABLE author
    ADD CONSTRAINT fk_author_merged_into
    FOREIGN KEY (merged_into_author_id) REFERENCES author (id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_author_merged_into ON author (merged_into_author_id);
CREATE INDEX IF NOT EXISTS idx_author_normalized_name ON author (normalized_name);
