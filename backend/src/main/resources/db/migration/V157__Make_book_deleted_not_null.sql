-- The nullable `deleted` column forced every catalog predicate to emit
-- "(deleted IS NULL OR deleted = false)", an OR across NULL/false that MariaDB cannot resolve
-- through idx_book_deleted, so those filters degraded to full scans. New books always default
-- to FALSE (BookEntity @Builder.Default), so a NULL here only ever meant "never soft-deleted".
-- Backfilling and pinning the column NOT NULL lets the predicate collapse to a sargable
-- "deleted = false".
UPDATE book SET deleted = FALSE WHERE deleted IS NULL;

ALTER TABLE book MODIFY COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
