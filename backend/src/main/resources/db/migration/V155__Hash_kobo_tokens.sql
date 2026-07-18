ALTER TABLE kobo_user_settings
    ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS token_expires_at TIMESTAMP NULL;

UPDATE kobo_user_settings
SET token_hash = SHA2(token, 256),
    token_expires_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 90 DAY)
WHERE token_hash IS NULL
  AND token IS NOT NULL;

ALTER TABLE kobo_user_settings
    MODIFY token_hash VARCHAR(64) NOT NULL,
    MODIFY token_expires_at TIMESTAMP NOT NULL,
    DROP COLUMN IF EXISTS token;
