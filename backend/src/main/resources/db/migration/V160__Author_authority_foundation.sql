-- Author authority control foundation: redirect pointer + normalized blocking key.
ALTER TABLE author ADD COLUMN IF NOT EXISTS merged_into_author_id BIGINT NULL;
ALTER TABLE author ADD COLUMN IF NOT EXISTS normalized_name VARCHAR(255) NULL;

ALTER TABLE author
    ADD CONSTRAINT fk_author_merged_into
    FOREIGN KEY (merged_into_author_id) REFERENCES author (id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_author_merged_into ON author (merged_into_author_id);
CREATE INDEX IF NOT EXISTS idx_author_normalized_name ON author (normalized_name);

CREATE TABLE IF NOT EXISTS author_reconcile_state
(
    author_id         BIGINT       NOT NULL,
    state             VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    evidence_hash     CHAR(64)     NULL,
    algorithm_version INT          NOT NULL DEFAULT 1,
    attempt_count     INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP    NULL,
    last_error        VARCHAR(1000) NULL,
    reconciled_at     TIMESTAMP    NULL,
    PRIMARY KEY (author_id),
    CONSTRAINT fk_author_reconcile_state_author FOREIGN KEY (author_id) REFERENCES author (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_author_reconcile_state_pending
    ON author_reconcile_state (state, next_attempt_at);

CREATE TABLE IF NOT EXISTS author_alias
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    author_id        BIGINT       NOT NULL,
    name             VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) NOT NULL,
    language         VARCHAR(35)  NOT NULL DEFAULT 'und',
    kind             VARCHAR(32)  NULL,
    source           VARCHAR(32)  NOT NULL,
    resolvable       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_author_alias_author FOREIGN KEY (author_id) REFERENCES author (id) ON DELETE CASCADE,
    CONSTRAINT uq_author_alias UNIQUE (author_id, normalized_alias, language, source)
);

CREATE INDEX IF NOT EXISTS idx_author_alias_lookup ON author_alias (normalized_alias, resolvable);
