-- Schedule the materialized facet-count recompute hourly (6-field Spring cron: top of every hour).
-- The task recomputes only libraries whose books changed since their last recompute, so an idle
-- catalog costs nothing. created_by = -1 is the system-user convention used by the other seeded rows.
INSERT INTO task_cron_configuration (task_type, cron_expression, enabled, created_by)
SELECT 'RECOMPUTE_FACET_COUNTS', '0 0 * * * *', TRUE, -1
WHERE NOT EXISTS (
    SELECT 1 FROM task_cron_configuration WHERE task_type = 'RECOMPUTE_FACET_COUNTS'
);
