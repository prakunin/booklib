-- Identity resolved once per literary work, reused by every edition of it.
--
-- The same book sits in an INPX library many times over — different scans, different formats,
-- different digitisers — and resolving each copy separately multiplies the one genuinely expensive
-- step, the agent call, by the number of duplicates. Keying on the work instead means one call per
-- book rather than one per file, and it has a second effect worth as much: every edition of a work
-- ends up describing it the same way.
--
-- work_key is the normalized "author|title". Once an agent returns an original title the key is
-- rebuilt from it, so translations converge on the same row as the original.
--
-- A hit here is never authority to write. Normalization can collapse genuinely different works —
-- same author, same title, different contents, which is exactly what compilations and reissues look
-- like — so a hit that no identifier corroborates is demoted a confidence step and becomes a
-- suggestion. book_work_link records how sure the link itself was.

CREATE TABLE IF NOT EXISTS work_identity
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_key             VARCHAR(512) NOT NULL,
    original_title       VARCHAR(1000) NULL,
    original_author      VARCHAR(512)  NULL,
    original_language    VARCHAR(32)   NULL,
    goodreads_id         VARCHAR(64)   NULL,
    isbn13               VARCHAR(20)   NULL,
    isbn10               VARCHAR(20)   NULL,
    first_published_year INT           NULL,
    description          TEXT          NULL,
    description_language VARCHAR(32)   NULL,
    -- HIGH, MEDIUM, LOW.
    confidence           VARCHAR(16)   NOT NULL,
    -- LOCAL, PROVIDER, AGENT.
    resolved_by          VARCHAR(16)   NOT NULL,
    resolved_at          DATETIME(6)   NOT NULL,
    CONSTRAINT uk_work_identity_key UNIQUE (work_key)
);

CREATE TABLE IF NOT EXISTS book_work_link
(
    book_id          BIGINT      NOT NULL PRIMARY KEY,
    work_identity_id BIGINT      NOT NULL,
    match_confidence VARCHAR(16) NOT NULL,
    linked_at        DATETIME(6) NOT NULL,
    CONSTRAINT fk_book_work_link_book
        FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE,
    CONSTRAINT fk_book_work_link_work
        FOREIGN KEY (work_identity_id) REFERENCES work_identity (id) ON DELETE CASCADE
);

CREATE INDEX idx_book_work_link_work ON book_work_link (work_identity_id);
