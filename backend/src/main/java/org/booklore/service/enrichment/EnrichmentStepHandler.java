package org.booklore.service.enrichment;

import org.booklore.model.enums.EnrichmentStepType;

/**
 * One source of enrichment data.
 * <p>
 * Discovered as beans and ordered with {@link org.springframework.core.annotation.Order} by cost, so
 * adding a source means adding a bean — unlike {@code MetadataExtractorFactory}, whose hand-written
 * switch has to be remembered and is the most common way a new format ends up half-supported.
 * <p>
 * A step contributes to the context and never writes to the database. What is written, and whether
 * anything is written at all, is {@link EnrichmentResolver}'s decision alone.
 */
public interface EnrichmentStepHandler {

    EnrichmentStepType type();

    /**
     * Whether this step has anything to do for this book — the source is configured, the data it
     * needs is present, and the step has not been ruled out by what earlier steps already found.
     */
    boolean supports(EnrichmentContext context);

    void run(EnrichmentContext context);
}
