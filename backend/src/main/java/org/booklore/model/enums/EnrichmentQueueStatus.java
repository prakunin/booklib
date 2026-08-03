package org.booklore.model.enums;

public enum EnrichmentQueueStatus {
    QUEUED,
    RUNNING,
    DONE,
    /** Ran to completion but no source had anything to add. */
    SKIPPED,
    FAILED,
    CANCELLED;

    public boolean isOutstanding() {
        return this == QUEUED || this == RUNNING;
    }
}
