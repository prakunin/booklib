package org.booklore.model.dto.inpx;

import lombok.Builder;
import org.booklore.model.enums.InpxArchiveScanStatus;

import java.time.Instant;

@Builder
public record InpxArchiveDto(
        String archiveName,
        long sizeBytes,
        long fb2Count,
        long importedBookCount,
        long coveredBookCount,
        Instant fileModifiedAt,
        Instant addedAt,
        Instant lastScannedAt,
        InpxArchiveScanStatus status,
        String errorMessage) {
}
