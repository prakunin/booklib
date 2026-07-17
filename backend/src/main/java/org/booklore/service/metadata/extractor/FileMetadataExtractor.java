package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;

import java.io.File;

public interface FileMetadataExtractor {

    BookMetadata extractMetadata(File file);

    /**
     * Reads the cover image out of {@code file} without writing anything.
     * <p>
     * This layer is the only one that can tell a clean miss from a failed read - by the time the
     * bytes (or their absence) reach a processor, the reason is gone - so it is the layer that has
     * to report the difference. The return value is therefore narrow on purpose:
     * <ul>
     *   <li>a non-empty array: the cover was found and these are its bytes. They are the file's raw
     *       cover bytes and are <em>not</em> promised to be decodable - an EPUB whose cover is an
     *       SVG returns the SVG. Deciding what can be turned into an image is
     *       {@code FileService.saveCoverImageFromBytes}' job, and it reports that separately.</li>
     *   <li>{@code null}: <strong>and only when</strong> the source was read through to the end and
     *       genuinely holds no cover. This is a permanent fact about the file, and callers persist
     *       it as one, so it must never be used as a shrug.</li>
     * </ul>
     *
     * @return the cover bytes, or {@code null} if and only if the file was read and has no cover
     * @throws CoverExtractionException if the file could not be read, parsed or decoded far enough
     *                                  to know whether it has a cover. Anything ambiguous belongs
     *                                  here rather than in a {@code null}: a missing file, an IO
     *                                  error, a truncated or malformed document, corrupt base64.
     *                                  Callers may retry; they must not record it as a verdict.
     */
    byte[] extractCover(File file);
}
