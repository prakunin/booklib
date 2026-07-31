package org.booklore.service.djvu;

/**
 * Thrown when a djvulibre invocation could not be made or did not succeed: the binary is missing
 * from the image, the process exited non-zero, it timed out, or its output could not be parsed.
 * <p>
 * Unchecked on purpose, and deliberately undifferentiated. Every caller treats all of these the
 * same way - the file cannot be decoded right now - and none of them can act on the distinction:
 * a missing binary and a corrupt page both mean "fall back to what we already know". Callers that
 * must not fail because of it (ingest) catch it and keep the filename baseline; callers serving a
 * page let it surface as a failed request.
 */
public class DjvuToolException extends RuntimeException {

    public DjvuToolException(String message) {
        super(message);
    }

    public DjvuToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
