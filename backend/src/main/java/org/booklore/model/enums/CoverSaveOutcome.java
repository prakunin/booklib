package org.booklore.model.enums;

/**
 * Result of turning extracted cover bytes into the book's cover files on disk, precise enough to
 * tell "these bytes are not an image we can decode" apart from "we could not write the files".
 * <p>
 * The distinction is the mirror image of {@link CoverProbeOutcome}'s, one step further along: it
 * separates a permanent fact about the source file from a transient fact about this attempt.
 * {@link #UNDECODABLE} is a property of the bytes themselves - an SVG cover, or a format no
 * {@code ImageIO} reader understands - so re-reading the archive will produce the same bytes and
 * fail the same way, forever. {@link #WRITE_FAILED} says nothing about the bytes: a full disk or a
 * missing directory may well be gone by the next attempt.
 * <p>
 * Collapsing the two into a boolean is what let {@code BookCoverService#tryGenerateMissingInpxCover}
 * re-open the same ZIP archive on every scan for a book whose cover was an SVG - the read succeeded,
 * the decode never could, and nothing durable recorded that.
 */
public enum CoverSaveOutcome {

    /**
     * The bytes were decoded and the cover and thumbnail files are on disk.
     */
    SAVED,

    /**
     * The bytes are not a decodable image (SVG, an unsupported format, or dimensions that look like
     * a decompression bomb). Permanent for these bytes: a caller may record it as a final answer
     * about the source file.
     */
    UNDECODABLE,

    /**
     * The bytes decoded fine but the files could not be written. Never a permanent answer - a caller
     * must leave the book eligible for a later retry.
     */
    WRITE_FAILED
}
