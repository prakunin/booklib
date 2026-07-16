package org.booklore.exception;

import org.springframework.http.HttpStatus;

/**
 * A persisted book points at a ZIP entry the archive no longer contains - typically because the
 * archive was replaced or rewritten under the same name.
 * <p>
 * Distinct from a generic read error so a full archive scan can retire the orphaned row instead of
 * only counting it as failed. It is a 404 rather than the 500 a read failure reports: the content
 * is genuinely gone, which is not a server fault.
 */
public class ArchiveEntryMissingException extends APIException {

    public ArchiveEntryMissingException(String entryName) {
        super("Archived book entry is missing: " + entryName, HttpStatus.NOT_FOUND);
    }
}
