ALTER TABLE ebook_viewer_preference
    ADD COLUMN IF NOT EXISTS page_margin INT NOT NULL DEFAULT 40 AFTER max_inline_size;
