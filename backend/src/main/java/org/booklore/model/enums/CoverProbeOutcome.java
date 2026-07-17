package org.booklore.model.enums;

/**
 * Result of an explicit attempt to find a cover in a book's source file, precise enough to tell
 * "the source was read successfully and has no cover" apart from "the source could not be read"
 * (IO error, corrupt or temporarily unavailable archive, etc.).
 * <p>
 * The distinction matters for callers that persist a "no cover" marker: only {@link #NO_COVER_FOUND}
 * is a completed probe. {@link #READ_FAILED} must never be recorded as a permanent answer, since a
 * transient failure today does not mean the source has no cover.
 */
public enum CoverProbeOutcome {
    COVER_FOUND,
    NO_COVER_FOUND,
    READ_FAILED
}
