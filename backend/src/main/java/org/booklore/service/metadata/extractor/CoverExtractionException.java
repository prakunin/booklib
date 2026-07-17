package org.booklore.service.metadata.extractor;

/**
 * Thrown by {@link FileMetadataExtractor#extractCover(java.io.File)} when the source could not be
 * read, parsed or decoded far enough to say whether it holds a cover.
 * <p>
 * It exists so that the extractor layer can express the one distinction its callers need and it
 * alone can see: <em>we looked and there is nothing</em> ({@code null}) versus <em>we could not
 * look</em> (this exception). Every extractor used to answer both with a bare {@code null}, and the
 * information was gone by the time it reached anyone who cared - {@code BookCoverService}'s lazy
 * INPX probe would take a {@code FileNotFoundException} or a truncated FB2 for a completed probe and
 * durably record "this file has no cover", writing the book's cover off until the next rescan.
 * <p>
 * This is deliberately unchecked. The processors already wrap every {@code extractCover} call in a
 * {@code catch (Exception e) -> CoverExtraction.readFailed()}, which is precisely the right
 * handling and was unreachable while the extractors swallowed; a checked exception would force the
 * same handling to be restated at every call site instead. Callers that genuinely want "null on any
 * failure" - see {@code BookdropMetadataService} - catch it explicitly at the call site, so that the
 * choice to discard the distinction is visible where it is made rather than baked into the layer
 * that knows the answer.
 */
public class CoverExtractionException extends RuntimeException {

    public CoverExtractionException(String message) {
        super(message);
    }

    public CoverExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
