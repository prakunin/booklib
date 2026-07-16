package org.booklore.model.dto.inpx;

import lombok.Builder;
import org.booklore.model.enums.InpxArchiveScanPhase;
import org.booklore.model.enums.InpxArchiveScanStatus;

import java.time.Instant;

@Builder
public record InpxArchiveScanTaskDto(
        long libraryId,
        String archiveName,
        InpxArchiveScanStatus status,
        InpxArchiveScanPhase phase,
        long totalBooks,
        long processedBooks,
        long remainingBooks,
        long addedBooks,
        long coversGenerated,
        long failedBooks,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage) {
}
