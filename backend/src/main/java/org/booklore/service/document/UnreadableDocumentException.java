package org.booklore.service.document;

import java.io.IOException;

public class UnreadableDocumentException extends IOException {

    public UnreadableDocumentException() {
        super("Document cannot be read");
    }
}
