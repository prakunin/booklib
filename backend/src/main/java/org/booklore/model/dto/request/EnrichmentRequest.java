package org.booklore.model.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.EnrichmentWritePolicy;

import java.util.EnumSet;
import java.util.Set;

/**
 * One enrichment ask: what to enrich, with which steps, and how much of the result may be written.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentRequest {

    @NotNull(message = "Enrichment scope cannot be null")
    private Scope scope;

    private Long libraryId;

    private Set<Long> bookIds;

    /**
     * Which steps are allowed. Null means every step except the agent, which always needs the
     * explicit flag below — the difference between a cheap run and a run that costs minutes per book
     * should never be something a caller enables by omission.
     */
    private Set<EnrichmentStepType> steps;

    @Builder.Default
    private EnrichmentWritePolicy writePolicy = EnrichmentWritePolicy.AUTO_IF_EMPTY;

    @Builder.Default
    private boolean agentAllowed = false;

    public enum Scope {
        BOOK, BOOKS, LIBRARY
    }

    public Set<EnrichmentStepType> resolvedSteps() {
        Set<EnrichmentStepType> resolved = steps == null || steps.isEmpty()
                ? EnumSet.allOf(EnrichmentStepType.class)
                : EnumSet.copyOf(steps);
        if (!agentAllowed) {
            resolved.remove(EnrichmentStepType.AGENT_IDENTITY);
            resolved.remove(EnrichmentStepType.PROVIDERS_RETRY);
        }
        return resolved;
    }
}
