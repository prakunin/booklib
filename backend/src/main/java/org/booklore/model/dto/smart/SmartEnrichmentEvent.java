package org.booklore.model.dto.smart;

import java.util.List;

/**
 * A step of an enrichment run, streamed to the client as it happens. Resolution takes tens of
 * seconds, so the stages exist to show the user what is being waited on rather than leaving the
 * dialog blank.
 */
public record SmartEnrichmentEvent(
        Stage stage,
        String message,
        ResolvedWorkIdentity identity,
        RatingVerification ratingVerification,
        List<MetadataFieldProposal> proposals
) {

    public enum Stage {
        RESOLVING,
        VERIFYING,
        COMPLETED,
        FAILED
    }

    public static SmartEnrichmentEvent resolving() {
        return new SmartEnrichmentEvent(Stage.RESOLVING, "Identifying the work", null, null, List.of());
    }

    public static SmartEnrichmentEvent verifying(ResolvedWorkIdentity identity) {
        return new SmartEnrichmentEvent(Stage.VERIFYING, "Verifying the rating against Goodreads", identity, null, List.of());
    }

    public static SmartEnrichmentEvent completed(ResolvedWorkIdentity identity,
                                                 RatingVerification ratingVerification,
                                                 List<MetadataFieldProposal> proposals) {
        return new SmartEnrichmentEvent(Stage.COMPLETED, null, identity, ratingVerification, proposals);
    }

    public static SmartEnrichmentEvent failed(String message) {
        return new SmartEnrichmentEvent(Stage.FAILED, message, null, null, List.of());
    }
}
