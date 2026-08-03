package org.booklore.model.enums;

@SuppressWarnings("java:S115") // constant names are @Enumerated(EnumType.STRING) DB values and JSON values
public enum MetadataProvider {
    Amazon, GoodReads, Google, Hardcover, Comicvine, Douban, Lubimyczytac, Ranobedb, Audible,

    /**
     * A library's local metadata catalog. Unlike every provider above it has no {@code BookParser}:
     * it is not fetched, it is read from disk by the enrichment pipeline, which supplies its
     * contribution directly. It appears here so the per-field priority table can rank it.
     */
    FlibustaLocal,

    /**
     * The agent CLI. Also parser-less, and additionally barred from winning any numeric field — see
     * {@code EnrichmentResolver}. An agent asked for a rating will produce a plausible one, and a
     * plausible wrong number is unfalsifiable once stored.
     */
    Agent
}
