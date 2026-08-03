package org.booklore.model.enums;

/**
 * A ceiling on what an enrichment run may write, applied after confidence has been decided. It can
 * only ever restrict: a low-confidence contribution is a suggestion under every policy.
 */
public enum EnrichmentWritePolicy {

    /** High-confidence values are written, replacing what is there. */
    AUTO,

    /** High-confidence values are written only into fields that are currently empty. */
    AUTO_IF_EMPTY,

    /** Nothing is written; everything resolved becomes a suggestion. */
    PROPOSE;

    public boolean writesAnything() {
        return this != PROPOSE;
    }

    public MetadataReplaceMode replaceMode() {
        return this == AUTO ? MetadataReplaceMode.REPLACE_ALL : MetadataReplaceMode.REPLACE_MISSING;
    }
}
