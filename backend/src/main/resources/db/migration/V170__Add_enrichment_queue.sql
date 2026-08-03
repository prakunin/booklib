-- Work list for background enrichment.
--
-- Persistent rather than the in-memory BlockingQueue the ingest paths use, because the runs this
-- serves are not measured in minutes: an INPX library is hundreds of thousands of books, providers
-- are rate-limited and the agent takes minutes per call, so a full pass runs for days. A container
-- restart in the middle of that must not throw the work away.
--
-- Priority is fixed in code, not configurable: a button on one book (100) outranks an explicit
-- selection (80), which outranks a book the user just opened (50), a top-up after import (20) and a
-- library-wide sweep (10). The index is ordered to match the claim query exactly.

CREATE TABLE IF NOT EXISTS enrichment_queue
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id               VARCHAR(100)  NOT NULL,
    book_id              BIGINT        NOT NULL,
    -- Comma-separated EnrichmentStepType names; null means "every step the request allows".
    steps                VARCHAR(255)  NULL,
    agent_allowed        BOOLEAN       NOT NULL DEFAULT FALSE,
    -- AUTO, AUTO_IF_EMPTY, PROPOSE.
    write_policy         VARCHAR(20)   NOT NULL,
    priority             TINYINT       NOT NULL DEFAULT 10,
    -- QUEUED, RUNNING, DONE, FAILED, SKIPPED, CANCELLED.
    status               VARCHAR(20)   NOT NULL,
    attempts             INT           NOT NULL DEFAULT 0,
    last_error           VARCHAR(1000) NULL,
    requested_by_user_id BIGINT        NULL,
    requested_at         DATETIME(6)   NOT NULL,
    started_at           DATETIME(6)   NULL,
    finished_at          DATETIME(6)   NULL,
    -- Carries the book id only while the row is outstanding, so the unique index below constrains
    -- pending work without also forbidding a book from having more than one finished row. A partial
    -- index would say this directly, but MariaDB has none; a stored generated column plus the fact
    -- that unique indexes ignore NULLs is the equivalent.
    pending_book_id      BIGINT AS (IF(status IN ('QUEUED', 'RUNNING'), book_id, NULL)) STORED,
    CONSTRAINT fk_enrichment_queue_book
        FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE
);

CREATE INDEX idx_enrichment_queue_claim ON enrichment_queue (status, priority DESC, requested_at);
CREATE INDEX idx_enrichment_queue_job ON enrichment_queue (job_id);

-- Re-queueing a book that is already waiting should raise its priority, not add a second identical
-- unit of work.
CREATE UNIQUE INDEX uk_enrichment_queue_pending ON enrichment_queue (pending_book_id);
