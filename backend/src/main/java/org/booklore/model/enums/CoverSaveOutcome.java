package org.booklore.model.enums;

/**
 * Result of turning extracted cover bytes into the book's cover files on disk, precise enough to
 * tell "these bytes will never be a cover" apart from "this attempt did not work".
 * <p>
 * The distinction is the mirror image of {@link CoverProbeOutcome}'s, one step further along: it
 * separates a permanent fact about the source file from a transient fact about this attempt.
 * {@link #UNDECODABLE} is a property of the bytes themselves - an SVG cover, a format no
 * {@code ImageIO} reader understands, or an image whose proportions cannot be turned into a cover -
 * so re-reading the archive will produce the same bytes and fail the same way, forever.
 * {@link #SAVE_FAILED} says nothing about the bytes: a full disk or an exhausted heap may well be
 * gone by the next attempt.
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
     * The bytes cannot be made into a cover image, and never will be: an SVG or unsupported format,
     * dimensions that look like a decompression bomb, or proportions that no amount of scaling turns
     * into a picture. Permanent for these bytes, so a caller may record it as a final answer about
     * the source file.
     */
    UNDECODABLE,

    /**
     * The save did not complete for a reason that has nothing to do with the bytes - the files could
     * not be written, or the server ran out of heap part way through. Never a permanent answer: a
     * caller must leave the book eligible for a later retry.
     * <p>
     * Named for the whole operation rather than the write step alone, because it is not only the
     * write that can fail this way: an {@code OutOfMemoryError} while decoding is equally transient
     * and equally not the file's fault. Calling that {@code WRITE_FAILED} - as this constant was
     * named - would be the same kind of lie this enum exists to stop: a name that misreports which
     * fact was established.
     */
    SAVE_FAILED
}
