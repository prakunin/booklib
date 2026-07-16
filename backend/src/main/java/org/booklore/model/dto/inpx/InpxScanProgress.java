package org.booklore.model.dto.inpx;

import org.booklore.model.enums.InpxScanStatus;

public record InpxScanProgress(
        long libraryId,
        String libraryName,
        long total,
        long processed,
        long added,
        long skipped,
        InpxScanStatus status) {
}
