package org.booklore.model.dto.inpx;

import lombok.Builder;
import org.booklore.model.enums.InpxArchiveScanStatus;

import java.time.Instant;

@Builder
public record InpxArchiveDto(
        String archiveName,
        long sizeBytes,
        Long fb2Count,
        Long importedBookCount,
        Long coveredBookCount,
        Instant fileModifiedAt,
        Instant addedAt,
        Instant lastScannedAt,
        InpxArchiveScanStatus status,
        String errorMessage) {
}
