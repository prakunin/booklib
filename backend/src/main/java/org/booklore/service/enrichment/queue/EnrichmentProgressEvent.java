package org.booklore.service.enrichment.queue;

import lombok.Builder;

import java.util.List;

/**
 * What the worker reports as it moves through a job. Sent to the user who asked, never broadcast.
 */
@Builder
public record EnrichmentProgressEvent(
        String jobId,
        long bookId,
        long total,
        long completed,
        long outstanding,
        boolean finished,
        boolean bookChanged,
        List<String> notes) {
}
