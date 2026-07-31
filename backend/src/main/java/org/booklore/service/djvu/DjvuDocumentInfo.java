package org.booklore.service.djvu;

import java.util.List;
import java.util.Map;

/**
 * What one {@code djvused} probe can say about a DjVu document without rendering anything.
 * <p>
 * Page sizes come from the document's own annotations rather than from a rendered image, so the
 * reader can lay out every page without decoding a single one. {@code metadata} holds the raw
 * {@code print-meta} keys as the file spells them; interpreting them is the extractor's job.
 *
 * @param pageCount number of pages, or 0 when the document could not be counted
 * @param pageSizes natural size of each page in document order; may be shorter than
 *                  {@code pageCount} if djvused reported fewer, and empty if sizes were not probed
 * @param metadata  embedded metadata keys, empty when the file carries none (the common case)
 */
public record DjvuDocumentInfo(int pageCount, List<PageSize> pageSizes, Map<String, String> metadata) {

    public DjvuDocumentInfo {
        pageSizes = pageSizes == null ? List.of() : List.copyOf(pageSizes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record PageSize(int width, int height) {
    }
}
