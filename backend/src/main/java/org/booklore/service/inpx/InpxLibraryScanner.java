package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.dto.inpx.InpxScanProgress;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.InpxScanStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates an INPX scan. Deliberately NOT transactional: {@link InpxBatchWriter}
 * owns one transaction per batch, so neither memory nor transaction duration grows
 * with the size of the index (real catalogs run to ~500k records).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InpxLibraryScanner {

    private static final int BATCH_SIZE = 500;

    private final InpxParser inpxParser;
    private final InpxArchiveScanner archiveScanner;
    private final LibraryRepository libraryRepository;
    private final InpxBatchWriter batchWriter;
    private final InpxScanControl scanControl;
    private final NotificationService notificationService;

    public ScanResult scan(long libraryId) {
        // Declared outside the try so a failure at any point - including the initial
        // library lookup - can report the real totals reached so far on the FAILED
        // event, instead of the previous behaviour of always reporting zeros.
        Counters counters = new Counters();
        String[] libraryNameHolder = {null};

        // No defensive clear here: LibraryService.beginScan already cleared any leftover flag
        // atomically with opening the scan guard. Clearing again would wipe a cancellation that
        // legitimately arrived after the guard opened but before this scan reached its first batch.
        try {
            LibraryEntity library = libraryRepository.findByIdWithPaths(libraryId)
                    .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
            LibraryPathEntity libraryPath = library.getLibraryPaths().stream().findFirst()
                    .orElseThrow(() -> ApiError.GENERIC_BAD_REQUEST.createException("INPX library requires an archive path"));
            String inpxPath = library.getInpxPath();
            libraryNameHolder[0] = library.getName();
            long libraryPathId = libraryPath.getId();

            InpxScanCaches caches = new InpxScanCaches();
            List<InpxBookDto> batch = new ArrayList<>(BATCH_SIZE);
            boolean[] cancelled = {false};
            ScanContext scanContext = new ScanContext(libraryId, libraryPathId, library.getInpxArchivePath(),
                    caches, counters, libraryNameHolder[0]);

            inpxParser.forEach(inpxPath, book -> processScannedBook(book, cancelled, batch, scanContext));

            // Flush index records first. Discovery can then compare each ZIP's FB2 entry
            // count with the freshly committed database count, so archives already covered
            // by the INPX file are not needlessly parsed a second time.
            flushRemaining(cancelled, batch, scanContext);

            if (!cancelled[0]) {
                counters.total = Math.max(counters.total, counters.processed);
                InpxArchiveScanner.Discovery discovery = archiveScanner.discover(
                        libraryId, library.getInpxArchivePath());
                counters.total += discovery.totalEntries();

                archiveScanner.forEach(discovery, book -> processScannedBook(book, cancelled, batch, scanContext),
                        () -> cancelled[0]);

                flushRemaining(cancelled, batch, scanContext);
            }

            // An index smaller than BATCH_SIZE never reaches the in-loop check above, and a
            // cancel requested while/after the trailing partial batch is flushed is otherwise
            // never observed. Check once more here so both cases are still honoured - the
            // trailing batch itself is always kept (already flushed above), only the reported
            // status changes.
            if (!cancelled[0] && scanControl.isCancelRequested(libraryId)) {
                cancelled[0] = true;
            }

            InpxScanStatus status = cancelled[0] ? InpxScanStatus.CANCELLED : InpxScanStatus.COMPLETED;
            publish(libraryId, libraryNameHolder[0], counters, status);
            log.info("INPX scan {} for library {}: total={}, processed={}, added={}, skipped={}",
                    status, libraryId, counters.total, counters.processed, counters.added, counters.skipped);
            return counters.toResult(cancelled[0]);
        } catch (RuntimeException e) {
            log.error("INPX scan failed for library {}: {}", libraryId, e.getMessage(), e);
            notificationService.sendMessage(Topic.LIBRARY_SCAN_PROGRESS, new InpxScanProgress(
                    libraryId, libraryNameHolder[0], counters.total, counters.processed,
                    counters.added, counters.skipped, InpxScanStatus.FAILED));
            throw e;
        } finally {
            scanControl.clear(libraryId);
        }
    }

    private void processScannedBook(InpxBookDto book, boolean[] cancelled, List<InpxBookDto> batch,
                                    ScanContext scanContext) {
        if (cancelled[0]) {
            return;
        }
        batch.add(book);
        if (batch.size() >= BATCH_SIZE) {
            // Checked once a batch has filled but before it is written: a cancel
            // requested during/after the previous batch's write stops this batch
            // from ever being persisted, so everything already committed stays
            // and the scan halts on a batch boundary.
            if (scanControl.isCancelRequested(scanContext.libraryId())) {
                cancelled[0] = true;
                return;
            }
            Counters counters = scanContext.counters();
            counters.total = Math.max(counters.total, counters.processed + batch.size());
            flush(batch, scanContext.libraryId(), scanContext.libraryPathId(), scanContext.archiveRoot(),
                    scanContext.caches(), counters);
            publish(scanContext.libraryId(), scanContext.libraryName(), counters, InpxScanStatus.RUNNING);
        }
    }

    private void flushRemaining(boolean[] cancelled, List<InpxBookDto> batch, ScanContext scanContext) {
        if (!cancelled[0] && !batch.isEmpty()) {
            Counters counters = scanContext.counters();
            counters.total = Math.max(counters.total, counters.processed + batch.size());
            flush(batch, scanContext.libraryId(), scanContext.libraryPathId(), scanContext.archiveRoot(),
                    scanContext.caches(), counters);
            publish(scanContext.libraryId(), scanContext.libraryName(), counters, InpxScanStatus.RUNNING);
        }
    }

    private record ScanContext(long libraryId, long libraryPathId, String archiveRoot,
                               InpxScanCaches caches, Counters counters, String libraryName) {
    }

    private void flush(List<InpxBookDto> batch, long libraryId, long libraryPathId, String archiveRoot,
                       InpxScanCaches caches, Counters counters) {
        int size = batch.size();
        archiveScanner.populateFileSizes(batch, archiveRoot);
        InpxBatchWriter.BatchResult result = batchWriter.persist(List.copyOf(batch), libraryId, libraryPathId, caches);
        batch.clear();
        counters.processed += size;
        counters.added += result.added();
        counters.skipped += result.skipped();
    }

    private void publish(long libraryId, String libraryName, Counters counters, InpxScanStatus status) {
        notificationService.sendMessage(Topic.LIBRARY_SCAN_PROGRESS, new InpxScanProgress(
                libraryId, libraryName, counters.total, counters.processed,
                counters.added, counters.skipped, status));
    }

    private static final class Counters {
        private long total;
        private long processed;
        private long added;
        private long skipped;

        private ScanResult toResult(boolean cancelled) {
            return new ScanResult(total, processed, added, skipped, cancelled);
        }
    }

    public record ScanResult(long total, long processed, long added, long skipped, boolean cancelled) {
    }
}
