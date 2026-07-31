package org.booklore.model.document;

import java.util.List;

/**
 * A Word document reduced to what this application actually reads: ordered text blocks and their
 * heading hierarchy. Produced once per document and shared by every consumer - metadata, the
 * full-text index, and the reader rendition - so a document is never parsed twice for two purposes.
 */
public record DocumentContent(List<DocumentBlock> blocks) {

    public DocumentContent {
        blocks = List.copyOf(blocks);
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }
}
