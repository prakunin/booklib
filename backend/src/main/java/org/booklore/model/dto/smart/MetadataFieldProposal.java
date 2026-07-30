package org.booklore.model.dto.smart;

/**
 * One field the enrichment run suggests changing, alongside what is there now and where the new
 * value came from. Nothing is written from this: the user applies proposals field by field, which
 * is what makes a wrong match a visible mistake rather than a silent one.
 */
public record MetadataFieldProposal(
        String field,
        String currentValue,
        String proposedValue,
        String source,
        String sourceUrl,
        /*
         * A locked field still yields a proposal so the run stays legible — hiding it would make the
         * result look like nothing was found. The frontend must not offer to apply it.
         */
        boolean locked
) {
}
