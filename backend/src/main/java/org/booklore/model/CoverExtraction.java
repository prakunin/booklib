package org.booklore.model;

import org.booklore.model.enums.CoverProbeOutcome;

/**
 * Result of asking a processor to read the cover out of a book's source file: the {@link
 * CoverProbeOutcome} plus, when a cover was found, the raw image bytes.
 * <p>
 * The point of returning bytes rather than writing them is that reading and writing are separable.
 * A caller that must decide whether it is even entitled to own the book's cover (see {@code
 * BookCoverService#tryGenerateMissingInpxCover}) can read first, claim the cover in the database,
 * and only then write the image - so a claim it loses costs nothing but discarded bytes instead of
 * an overwritten file.
 * <p>
 * The outcome and the bytes cannot disagree: the constructor rejects a {@code COVER_FOUND} without
 * bytes and any other outcome with them. This used to be merely conventional, and a
 * {@code COVER_FOUND} carrying no bytes was a real value - it meant "a processor that writes the
 * image itself produced a cover, but the write already happened, so there is nothing to claim
 * before". Every processor now reads without writing ({@link
 * org.booklore.service.fileprocessor.BookFileProcessor#extractCover}), so that value has no
 * producer, and making it unconstructible is what stops it coming back: callers no longer need a
 * runtime guard for a state the type cannot hold.
 * <p>
 * Note this record holds a mutable array and is compared by array identity; it is a carrier, not a
 * value to key on.
 */
public record CoverExtraction(CoverProbeOutcome outcome, byte[] data) {

    public CoverExtraction {
        if (outcome == CoverProbeOutcome.COVER_FOUND) {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("COVER_FOUND requires cover bytes");
            }
        } else if (data != null) {
            throw new IllegalArgumentException(outcome + " cannot carry cover bytes");
        }
    }

    /**
     * A cover was read out of the source and its bytes are attached; nothing has been written yet.
     */
    public static CoverExtraction found(byte[] data) {
        return new CoverExtraction(CoverProbeOutcome.COVER_FOUND, data);
    }

    /**
     * The source was read all the way through and genuinely has no cover. Only a processor that can
     * actually prove this may return it - it is the one outcome a caller may persist as permanent.
     */
    public static CoverExtraction noCoverFound() {
        return new CoverExtraction(CoverProbeOutcome.NO_COVER_FOUND, null);
    }

    /**
     * The source could not be read, or the processor cannot tell a read failure from a clean miss.
     * Never a permanent answer: the cover may well be there next time.
     */
    public static CoverExtraction readFailed() {
        return new CoverExtraction(CoverProbeOutcome.READ_FAILED, null);
    }
}
