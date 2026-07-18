ALTER TABLE koreader_user
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

UPDATE koreader_user
SET password_hash = password_md5
WHERE password_hash IS NULL
  AND password_md5 IS NOT NULL;

ALTER TABLE koreader_user
    MODIFY password_hash VARCHAR(255) NOT NULL,
    DROP COLUMN IF EXISTS password,
    DROP COLUMN IF EXISTS password_md5;
