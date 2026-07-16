package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.InpxArchiveDto;
import org.booklore.model.dto.inpx.InpxArchiveScanTaskDto;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.InpxArchiveScanPhase;
import org.booklore.model.enums.InpxArchiveScanStatus;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.LibraryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class InpxArchiveCatalogService {

    private final LibraryRepository libraryRepository;
    private final InpxArchiveScanner archiveScanner;
    private final InpxArchiveStatisticsService statisticsService;
    private final ConcurrentMap<ArchiveKey, ScanState> scanStates = new ConcurrentHashMap<>();

    public List<InpxArchiveDto> list(long libraryId) {
        LibraryEntity library = requireInpxLibrary(libraryId);
        Map<String, InpxArchiveStatisticsService.ArchiveStatistics> statistics =
                statisticsService.loadBlocking(libraryId);

        return archiveScanner.listArchives(library.getInpxArchivePath()).stream()
                .map(file -> {
                    InpxArchiveStatisticsService.ArchiveStatistics stats = statistics.getOrDefault(
                            file.archiveName(), InpxArchiveStatisticsService.ArchiveStatistics.EMPTY);
                    ScanState state = scanStates.getOrDefault(
                            new ArchiveKey(libraryId, file.archiveName()), ScanState.IDLE);
                    return InpxArchiveDto.builder()
                            .archiveName(file.archiveName())
                            .sizeBytes(file.sizeBytes())
                            .fb2Count(file.entryCount())
                            .importedBookCount(stats.bookCount())
                            .coveredBookCount(stats.coverCount())
                            .fileModifiedAt(file.modifiedAt())
                            .addedAt(stats.addedAt() != null ? stats.addedAt() : file.modifiedAt())
                            .lastScannedAt(stats.lastScannedAt())
                            .status(state.overallStatus())
                            .errorMessage(state.errorMessage())
                            .build();
                })
                .toList();
    }

    public LibraryEntity requireInpxLibrary(long libraryId) {
        LibraryEntity library = libraryRepository.findByIdWithPaths(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        if (library.getSourceType() != LibrarySourceType.INPX) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Archive management is available only for INPX libraries");
        }
        return library;
    }

    public boolean queue(long libraryId, String archiveName, long totalBooks) {
        ArchiveKey key = new ArchiveKey(libraryId, archiveName);
        boolean[] queued = {false};
        scanStates.compute(key, (ignored, current) -> {
            if (current != null && current.isActive()) {
                return current;
            }
            queued[0] = true;
            return ScanState.queued(totalBooks);
        });
        return queued[0];
    }

    public void importing(long libraryId, String archiveName, long totalBooks) {
        ArchiveKey key = new ArchiveKey(libraryId, archiveName);
        scanStates.computeIfPresent(key, (ignored, state) -> state.startImporting(totalBooks));
    }

    public void refreshing(long libraryId, String archiveName, long totalBooks, long addedBooks) {
        ArchiveKey key = new ArchiveKey(libraryId, archiveName);
        scanStates.computeIfPresent(key, (ignored, state) -> state.startRefreshing(totalBooks, addedBooks));
    }

    public void progress(long libraryId, String archiveName, long processedBooks,
                         long coversGenerated, long failedBooks) {
        ArchiveKey key = new ArchiveKey(libraryId, archiveName);
        scanStates.computeIfPresent(key, (ignored, state) ->
                state.withProgress(processedBooks, coversGenerated, failedBooks));
    }

    public void completed(long libraryId, String archiveName) {
        ArchiveKey key = new ArchiveKey(libraryId, archiveName);
        scanStates.computeIfPresent(key, (ignored, state) -> state.complete());
        statisticsService.invalidate(libraryId);
    }

    public void failed(long libraryId, String archiveName, String errorMessage) {
        ArchiveKey key = new ArchiveKey(libraryId, archiveName);
        scanStates.compute(key, (ignored, state) -> (state == null ? ScanState.IDLE : state).fail(errorMessage));
        statisticsService.invalidate(libraryId);
    }

    public List<InpxArchiveScanTaskDto> listTasks(long libraryId) {
        requireInpxLibrary(libraryId);
        return scanStates.entrySet().stream()
                .filter(entry -> entry.getKey().libraryId() == libraryId)
                .sorted((left, right) -> {
                    int active = Boolean.compare(!left.getValue().isActive(), !right.getValue().isActive());
                    if (active != 0) {
                        return active;
                    }
                    return right.getValue().importStage().queuedAt()
                            .compareTo(left.getValue().importStage().queuedAt());
                })
                .flatMap(entry -> List.of(
                        toTask(entry.getKey(), InpxArchiveScanPhase.IMPORTING,
                                entry.getValue().importStage()),
                        toTask(entry.getKey(), InpxArchiveScanPhase.METADATA_AND_COVERS,
                                entry.getValue().metadataStage())).stream())
                .toList();
    }

    private InpxArchiveScanTaskDto toTask(ArchiveKey key, InpxArchiveScanPhase phase, StageState state) {
        return InpxArchiveScanTaskDto.builder()
                .libraryId(key.libraryId())
                .archiveName(key.archiveName())
                .status(state.status())
                .phase(phase)
                .totalBooks(state.totalBooks())
                .processedBooks(state.processedBooks())
                .remainingBooks(Math.max(0, state.totalBooks() - state.processedBooks()))
                .addedBooks(state.addedBooks())
                .coversGenerated(state.coversGenerated())
                .failedBooks(state.failedBooks())
                .queuedAt(state.queuedAt())
                .startedAt(state.startedAt())
                .completedAt(state.completedAt())
                .errorMessage(state.errorMessage())
                .build();
    }

    private record ArchiveKey(long libraryId, String archiveName) {
    }

    private record ScanState(InpxArchiveScanStatus overallStatus, StageState importStage,
                             StageState metadataStage, String errorMessage) {
        private static final ScanState IDLE = new ScanState(InpxArchiveScanStatus.IDLE, null, null, null);

        private static ScanState queued(long totalBooks) {
            Instant queuedAt = Instant.now();
            return new ScanState(InpxArchiveScanStatus.QUEUED,
                    StageState.queued(totalBooks, queuedAt),
                    StageState.queued(totalBooks, queuedAt), null);
        }

        private boolean isActive() {
            return overallStatus == InpxArchiveScanStatus.QUEUED
                    || overallStatus == InpxArchiveScanStatus.SCANNING;
        }

        private ScanState startImporting(long importTotalBooks) {
            return new ScanState(InpxArchiveScanStatus.SCANNING,
                    importStage.start(importTotalBooks), metadataStage, null);
        }

        private ScanState startRefreshing(long refreshedTotalBooks, long refreshedAddedBooks) {
            return new ScanState(InpxArchiveScanStatus.SCANNING,
                    importStage.complete(refreshedAddedBooks), metadataStage.start(refreshedTotalBooks), null);
        }

        private ScanState withProgress(long refreshedProcessedBooks, long refreshedCoversGenerated,
                                       long refreshedFailedBooks) {
            if (metadataStage.status() == InpxArchiveScanStatus.SCANNING) {
                return new ScanState(overallStatus, importStage,
                        metadataStage.withProgress(refreshedProcessedBooks,
                                refreshedCoversGenerated, refreshedFailedBooks), errorMessage);
            }
            return new ScanState(overallStatus,
                    importStage.withProgress(refreshedProcessedBooks,
                            refreshedCoversGenerated, refreshedFailedBooks), metadataStage, errorMessage);
        }

        private ScanState complete() {
            return new ScanState(InpxArchiveScanStatus.COMPLETED,
                    importStage, metadataStage.complete(metadataStage.addedBooks()), null);
        }

        private ScanState fail(String message) {
            if (importStage == null) {
                Instant now = Instant.now();
                return new ScanState(InpxArchiveScanStatus.FAILED,
                        StageState.failed(0, now, message), StageState.skipped(0, now), message);
            }
            if (metadataStage.status() == InpxArchiveScanStatus.QUEUED) {
                return new ScanState(InpxArchiveScanStatus.FAILED,
                        importStage.fail(message), metadataStage.skip(), message);
            }
            return new ScanState(InpxArchiveScanStatus.FAILED,
                    importStage, metadataStage.fail(message), message);
        }
    }

    private record StageState(InpxArchiveScanStatus status, long totalBooks, long processedBooks,
                              long addedBooks, long coversGenerated, long failedBooks,
                              Instant queuedAt, Instant startedAt, Instant completedAt,
                              String errorMessage) {

        private static StageState queued(long totalBooks, Instant queuedAt) {
            return new StageState(InpxArchiveScanStatus.QUEUED, totalBooks, 0,
                    0, 0, 0, queuedAt, null, null, null);
        }

        private static StageState failed(long totalBooks, Instant queuedAt, String message) {
            return queued(totalBooks, queuedAt).fail(message);
        }

        private static StageState skipped(long totalBooks, Instant queuedAt) {
            return queued(totalBooks, queuedAt).skip();
        }

        private StageState start(long refreshedTotalBooks) {
            return new StageState(InpxArchiveScanStatus.SCANNING, refreshedTotalBooks, 0,
                    0, 0, 0, queuedAt, Instant.now(), null, null);
        }

        private StageState withProgress(long refreshedProcessedBooks, long refreshedCoversGenerated,
                                        long refreshedFailedBooks) {
            return new StageState(status, totalBooks, refreshedProcessedBooks, addedBooks,
                    refreshedCoversGenerated, refreshedFailedBooks,
                    queuedAt, startedAt, completedAt, errorMessage);
        }

        private StageState complete(long refreshedAddedBooks) {
            return new StageState(InpxArchiveScanStatus.COMPLETED, totalBooks, totalBooks,
                    refreshedAddedBooks, coversGenerated, failedBooks,
                    queuedAt, startedAt, Instant.now(), null);
        }

        private StageState fail(String message) {
            return new StageState(InpxArchiveScanStatus.FAILED, totalBooks, processedBooks,
                    addedBooks, coversGenerated, failedBooks,
                    queuedAt, startedAt, Instant.now(), message);
        }

        private StageState skip() {
            return new StageState(InpxArchiveScanStatus.SKIPPED, totalBooks, 0,
                    0, 0, 0, queuedAt, null, Instant.now(), null);
        }
    }

}
