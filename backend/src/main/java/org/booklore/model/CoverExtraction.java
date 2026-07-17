package org.booklore.model;

import org.booklore.model.enums.CoverProbeOutcome;

/**
 * Result of asking a processor to read the cover out of a book's source file: the {@link
 * CoverProbeOutcome} plus, when the processor could hand them back, the raw image bytes.
 * <p>
 * The point of returning bytes rather than writing them is that reading and writing are separable.
 * A caller that must decide whether it is even entitled to own the book's cover (see {@code
 * BookCoverService#tryGenerateMissingInpxCover}) can read first, claim the cover in the database,
 * and only then write the image - so a claim it loses costs nothing but discarded bytes instead of
 * an overwritten file.
 * <p>
 * {@link #writtenInPlace()} is the exception and exists only for processors that have not been
 * taught to extract without writing: they report a cover was produced but have no bytes to give,
 * because the image is already on disk. Such a result is not usable for claim-before-write - by the
 * time the caller sees it, the write has happened.
 * <p>
 * Note this record holds a mutable array and is compared by array identity; it is a carrier, not a
 * value to key on.
 */
public record CoverExtraction(CoverProbeOutcome outcome, byte[] data) {

    /**
     * A cover was read out of the source and its bytes are attached; nothing has been written yet.
     */
    public static CoverExtraction found(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("COVER_FOUND requires cover bytes");
        }
        return new CoverExtraction(CoverProbeOutcome.COVER_FOUND, data);
    }

    /**
     * A cover was produced, but by a processor that writes the image itself and cannot hand the
     * bytes back. Callers that need to claim the cover before writing it must not treat this as a
     * usable extraction - the write already happened.
     */
    public static CoverExtraction writtenInPlace() {
        return new CoverExtraction(CoverProbeOutcome.COVER_FOUND, null);
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

    /**
     * Whether this extraction carries bytes the caller can write itself.
     */
    public boolean hasData() {
        return data != null && data.length > 0;
    }
}
