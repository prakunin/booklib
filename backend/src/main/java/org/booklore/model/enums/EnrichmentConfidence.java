package org.booklore.model.enums;

/**
 * How much a contribution is trusted. Only {@link #HIGH} is ever written automatically; anything
 * below becomes a suggestion for the user to accept.
 */
public enum EnrichmentConfidence {

    /** An identifier matched — ISBN, ASIN, a provider id, or an exact local-catalog key. */
    HIGH,

    /** Author and title matched after normalization, with no identifier to confirm it. */
    MEDIUM,

    /** Unverified: agent output, or a work-cache hit that no identifier corroborates. */
    LOW;

    /**
     * One step less trusted, floored at {@link #LOW}. Used where a result is reused rather than
     * established — the same data is worth less when it was matched by proxy.
     */
    public EnrichmentConfidence demoted() {
        return switch (this) {
            case HIGH -> MEDIUM;
            case MEDIUM, LOW -> LOW;
        };
    }

    public boolean isAtLeast(EnrichmentConfidence other) {
        return compareTo(other) <= 0;
    }
}
