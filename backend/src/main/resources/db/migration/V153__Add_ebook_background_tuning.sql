ALTER TABLE ebook_viewer_preference
    ADD COLUMN IF NOT EXISTS background_saturation INT NOT NULL DEFAULT 100 AFTER flow;

ALTER TABLE ebook_viewer_preference
    ADD COLUMN IF NOT EXISTS background_transparency INT NOT NULL DEFAULT 0 AFTER background_saturation;
