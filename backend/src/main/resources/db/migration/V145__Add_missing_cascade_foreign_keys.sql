DELETE p
FROM pdf_viewer_preference p
LEFT JOIN users u ON u.id = p.user_id
WHERE u.id IS NULL;

DELETE p
FROM pdf_viewer_preference p
LEFT JOIN book b ON b.id = p.book_id
WHERE b.id IS NULL;

DELETE p
FROM epub_viewer_preference p
LEFT JOIN users u ON u.id = p.user_id
WHERE u.id IS NULL;

DELETE p
FROM epub_viewer_preference p
LEFT JOIN book b ON b.id = p.book_id
WHERE b.id IS NULL;

DELETE p
FROM cbx_viewer_preference p
LEFT JOIN users u ON u.id = p.user_id
WHERE u.id IS NULL;

DELETE p
FROM cbx_viewer_preference p
LEFT JOIN book b ON b.id = p.book_id
WHERE b.id IS NULL;

DELETE p
FROM new_pdf_viewer_preference p
LEFT JOIN users u ON u.id = p.user_id
WHERE u.id IS NULL;

DELETE p
FROM new_pdf_viewer_preference p
LEFT JOIN book b ON b.id = p.book_id
WHERE b.id IS NULL;

DELETE ms
FROM magic_shelf ms
LEFT JOIN users u ON u.id = ms.user_id
WHERE u.id IS NULL;

DELETE ksb
FROM kobo_library_snapshot_book ksb
LEFT JOIN book b ON b.id = ksb.book_id
WHERE b.id IS NULL;

DELETE krbt
FROM kobo_removed_books_tracking krbt
LEFT JOIN book b ON b.id = krbt.book_id_synced
WHERE b.id IS NULL;

DELETE krs
FROM kobo_reading_state krs
LEFT JOIN book b ON b.id = CAST(krs.entitlement_id AS UNSIGNED)
WHERE krs.entitlement_id REGEXP '^[0-9]+$'
  AND b.id IS NULL;

DELETE ku
FROM koreader_user ku
LEFT JOIN users u ON u.id = ku.booklore_user_id
WHERE ku.booklore_user_id IS NOT NULL
  AND u.id IS NULL;

ALTER TABLE pdf_viewer_preference
    ADD CONSTRAINT fk_pdf_viewer_preference_user
        FOREIGN KEY IF NOT EXISTS (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE pdf_viewer_preference
    ADD CONSTRAINT fk_pdf_viewer_preference_book
        FOREIGN KEY IF NOT EXISTS (book_id) REFERENCES book (id) ON DELETE CASCADE;

ALTER TABLE epub_viewer_preference
    ADD CONSTRAINT fk_epub_viewer_preference_user
        FOREIGN KEY IF NOT EXISTS (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE epub_viewer_preference
    ADD CONSTRAINT fk_epub_viewer_preference_book
        FOREIGN KEY IF NOT EXISTS (book_id) REFERENCES book (id) ON DELETE CASCADE;

ALTER TABLE cbx_viewer_preference
    ADD CONSTRAINT fk_cbx_viewer_preference_user
        FOREIGN KEY IF NOT EXISTS (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE cbx_viewer_preference
    ADD CONSTRAINT fk_cbx_viewer_preference_book
        FOREIGN KEY IF NOT EXISTS (book_id) REFERENCES book (id) ON DELETE CASCADE;

ALTER TABLE new_pdf_viewer_preference
    ADD CONSTRAINT fk_new_pdf_viewer_preference_user
        FOREIGN KEY IF NOT EXISTS (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE new_pdf_viewer_preference
    ADD CONSTRAINT fk_new_pdf_viewer_preference_book
        FOREIGN KEY IF NOT EXISTS (book_id) REFERENCES book (id) ON DELETE CASCADE;

ALTER TABLE magic_shelf
    ADD CONSTRAINT fk_magic_shelf_user
        FOREIGN KEY IF NOT EXISTS (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE kobo_library_snapshot_book
    ADD CONSTRAINT fk_kobo_snapshot_book_book
        FOREIGN KEY IF NOT EXISTS (book_id) REFERENCES book (id) ON DELETE CASCADE;

ALTER TABLE kobo_removed_books_tracking
    ADD CONSTRAINT fk_kobo_removed_books_tracking_book
        FOREIGN KEY IF NOT EXISTS (book_id_synced) REFERENCES book (id) ON DELETE CASCADE;

ALTER TABLE koreader_user
    DROP FOREIGN KEY IF EXISTS fk_booklore_user;

ALTER TABLE koreader_user
    ADD CONSTRAINT fk_koreader_user_booklore_user
        FOREIGN KEY IF NOT EXISTS (booklore_user_id) REFERENCES users (id) ON DELETE CASCADE;
