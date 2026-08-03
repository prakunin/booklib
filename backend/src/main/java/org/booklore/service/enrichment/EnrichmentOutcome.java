package org.booklore.service.enrichment;

import lombok.Builder;
import lombok.Getter;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.EnrichmentStepType;

import java.util.List;
import java.util.Set;

/**
 * What one book's enrichment produced: the metadata that was written, the metadata offered for
 * review, and which steps actually ran.
 */
@Getter
@Builder
public class EnrichmentOutcome {

    private final long bookId;

    /** Written to the book, or null when the policy or the confidence forbade writing. */
    private final BookMetadata applied;

    /** Stored as a proposal, or null when everything resolved was written outright. */
    private final BookMetadata proposed;

    private final Set<EnrichmentStepType> stepsRun;

    private final List<String> notes;

    public boolean changedAnything() {
        return applied != null || proposed != null;
    }
}
