-- Sweep the INPX extraction cache nightly at 02:30 (6-field Spring cron), after the facet-count
-- recompute rather than alongside it. Books inside INPX archives are extracted to disk so they can
-- be read and the copy is kept for the next read; nothing removed it, so the cache grew to the size
-- of the library uncompressed. created_by = -1 is the system-user convention used by the other rows.
INSERT INTO task_cron_configuration (task_type, cron_expression, enabled, created_by)
SELECT 'CLEANUP_INPX_EXTRACTION_CACHE', '0 30 2 * * *', TRUE, -1
WHERE NOT EXISTS (
    SELECT 1 FROM task_cron_configuration WHERE task_type = 'CLEANUP_INPX_EXTRACTION_CACHE'
);
