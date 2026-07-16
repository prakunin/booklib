package org.booklore.service.inpx;

import org.booklore.exception.APIException;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.dto.inpx.InpxScanProgress;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.InpxScanStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxLibraryScannerTest {

    private static final long LIBRARY_ID = 7L;

    @Mock
    private InpxParser inpxParser;
    @Mock
    private InpxArchiveScanner archiveScanner;
    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private InpxBatchWriter batchWriter;
    @Mock
    private InpxScanControl scanControl;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private InpxLibraryScanner scanner;

    private void givenLibrary() {
        LibraryEntity library = LibraryEntity.builder()
                .id(LIBRARY_ID)
                .name("Flibusta")
                .inpxPath("/books/index.inpx")
                .inpxArchivePath("/books")
                .libraryPaths(new ArrayList<>(List.of(LibraryPathEntity.builder().id(3L).path("/books").build())))
                .build();
        when(libraryRepository.findByIdWithPaths(LIBRARY_ID)).thenReturn(Optional.of(library));
        lenient().when(archiveScanner.discover(LIBRARY_ID, "/books"))
                .thenReturn(new InpxArchiveScanner.Discovery(List.of(), 0));
    }

    private void givenIndexOf(int records) {
        when(inpxParser.count("/books/index.inpx")).thenReturn((long) records);
        doAnswer(invocation -> {
            Consumer<InpxBookDto> consumer = invocation.getArgument(1);
            for (int i = 0; i < records; i++) {
                consumer.accept(InpxBookDto.builder()
                        .id("fb2-1.zip|book" + i + ".fb2")
                        .archiveName("fb2-1.zip")
                        .fileName("book" + i)
                        .extension("fb2")
                        .title("Book " + i)
                        .authors(List.of())
                        .genres(List.of())
                        .build());
            }
            return null;
        }).when(inpxParser).forEach(eq("/books/index.inpx"), any());
    }

    @Test
    void writesInBatchesOfFiveHundredAndReportsTotals() {
        givenLibrary();
        givenIndexOf(1200);
        when(batchWriter.persist(any(), eq(LIBRARY_ID), eq(3L), any()))
                .thenAnswer(invocation -> new InpxBatchWriter.BatchResult(
                        ((List<?>) invocation.getArgument(0)).size(), 0));

        InpxLibraryScanner.ScanResult result = scanner.scan(LIBRARY_ID);

        // 500 + 500 + 200
        verify(batchWriter, times(3)).persist(any(), eq(LIBRARY_ID), eq(3L), any());
        assertThat(result.total()).isEqualTo(1200);
        assertThat(result.processed()).isEqualTo(1200);
        assertThat(result.added()).isEqualTo(1200);
        assertThat(result.cancelled()).isFalse();
    }

    @Test
    void emitsProgressPerBatchAndAFinalCompletedEvent() {
        givenLibrary();
        givenIndexOf(600);
        when(batchWriter.persist(any(), eq(LIBRARY_ID), eq(3L), any()))
                .thenAnswer(invocation -> new InpxBatchWriter.BatchResult(
                        ((List<?>) invocation.getArgument(0)).size(), 0));

        scanner.scan(LIBRARY_ID);

        ArgumentCaptor<InpxScanProgress> captor = ArgumentCaptor.forClass(InpxScanProgress.class);
        verify(notificationService, times(3))
                .sendMessage(eq(Topic.LIBRARY_SCAN_PROGRESS), captor.capture());

        List<InpxScanProgress> events = captor.getAllValues();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.libraryId()).isEqualTo(LIBRARY_ID);
            assertThat(event.libraryName()).isEqualTo("Flibusta");
            assertThat(event.total()).isEqualTo(600);
        });
        assertThat(events.get(0).processed()).isEqualTo(500);
        assertThat(events.get(0).status()).isEqualTo(InpxScanStatus.RUNNING);
        assertThat(events.get(2).processed()).isEqualTo(600);
        assertThat(events.get(2).status()).isEqualTo(InpxScanStatus.COMPLETED);
    }

    @Test
    void stopsBetweenBatchesWhenCancelledAndReportsCancelled() {
        givenLibrary();
        givenIndexOf(1200);
        when(batchWriter.persist(any(), eq(LIBRARY_ID), eq(3L), any()))
                .thenAnswer(invocation -> new InpxBatchWriter.BatchResult(
                        ((List<?>) invocation.getArgument(0)).size(), 0));
        // not cancelled for the first batch, cancelled afterwards
        when(scanControl.isCancelRequested(LIBRARY_ID)).thenReturn(false, true);

        InpxLibraryScanner.ScanResult result = scanner.scan(LIBRARY_ID);

        verify(batchWriter, times(1)).persist(any(), eq(LIBRARY_ID), eq(3L), any());
        assertThat(result.cancelled()).isTrue();
        assertThat(result.processed()).isEqualTo(500);

        ArgumentCaptor<InpxScanProgress> captor = ArgumentCaptor.forClass(InpxScanProgress.class);
        verify(notificationService, times(2)).sendMessage(eq(Topic.LIBRARY_SCAN_PROGRESS), captor.capture());
        assertThat(captor.getAllValues().getLast().status()).isEqualTo(InpxScanStatus.CANCELLED);
        // Only in the finally block: clearing on entry would wipe a cancellation that arrived
        // after LibraryService opened the scan guard. LibraryService.beginScan clears instead.
        verify(scanControl, times(1)).clear(LIBRARY_ID);
    }

    @Test
    void doesNothingWhenTheIndexIsEmpty() {
        givenLibrary();
        when(inpxParser.count("/books/index.inpx")).thenReturn(0L);

        InpxLibraryScanner.ScanResult result = scanner.scan(LIBRARY_ID);

        assertThat(result.total()).isZero();
        verify(batchWriter, never()).persist(any(), anyLong(), anyLong(), any());
        ArgumentCaptor<InpxScanProgress> captor = ArgumentCaptor.forClass(InpxScanProgress.class);
        verify(notificationService).sendMessage(eq(Topic.LIBRARY_SCAN_PROGRESS), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(InpxScanStatus.COMPLETED);
    }

    @Test
    void importsBooksFromZipArchivesMissingFromTheIndex() {
        givenLibrary();
        givenIndexOf(1);
        InpxArchiveScanner.Discovery discovery = new InpxArchiveScanner.Discovery(
                List.of(new InpxArchiveScanner.ArchiveCandidate(
                        java.nio.file.Path.of("/books/new.zip"), "new.zip", 2)), 2);
        when(archiveScanner.discover(LIBRARY_ID, "/books")).thenReturn(discovery);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<InpxBookDto> consumer = invocation.getArgument(1);
            consumer.accept(archiveBook("one"));
            consumer.accept(archiveBook("two"));
            return null;
        }).when(archiveScanner).forEach(eq(discovery), any(), any());
        when(batchWriter.persist(any(), eq(LIBRARY_ID), eq(3L), any()))
                .thenAnswer(invocation -> new InpxBatchWriter.BatchResult(
                        ((List<?>) invocation.getArgument(0)).size(), 0));

        InpxLibraryScanner.ScanResult result = scanner.scan(LIBRARY_ID);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.processed()).isEqualTo(3);
        assertThat(result.added()).isEqualTo(3);
        verify(batchWriter, times(2)).persist(any(), eq(LIBRARY_ID), eq(3L), any());
    }

    private InpxBookDto archiveBook(String fileName) {
        return InpxBookDto.builder()
                .id("new.zip|" + fileName + ".fb2")
                .archiveName("new.zip")
                .fileName(fileName)
                .extension("fb2")
                .title(fileName)
                .authors(List.of())
                .genres(List.of())
                .build();
    }

    @Test
    void reportsRealCountersOnTheFailedEventWhenABatchFailsAfterEarlierBatchesCommitted() {
        givenLibrary();
        givenIndexOf(1200);
        when(batchWriter.persist(any(), eq(LIBRARY_ID), eq(3L), any()))
                .thenAnswer(invocation -> new InpxBatchWriter.BatchResult(
                        ((List<?>) invocation.getArgument(0)).size(), 0))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> scanner.scan(LIBRARY_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // Previously the FAILED event was built from counters declared inside the try,
        // so a failure after the first batch committed would still report total=0,
        // processed=0 - contradicting the 500 books already in the database.
        ArgumentCaptor<InpxScanProgress> captor = ArgumentCaptor.forClass(InpxScanProgress.class);
        verify(notificationService, times(2)).sendMessage(eq(Topic.LIBRARY_SCAN_PROGRESS), captor.capture());
        InpxScanProgress failedEvent = captor.getAllValues().getLast();
        assertThat(failedEvent.status()).isEqualTo(InpxScanStatus.FAILED);
        assertThat(failedEvent.total()).isEqualTo(1200);
        assertThat(failedEvent.processed()).isEqualTo(500);
        assertThat(failedEvent.added()).isEqualTo(500);
        assertThat(failedEvent.skipped()).isZero();
        // Only in the finally block: clearing on entry would wipe a cancellation that arrived
        // after LibraryService opened the scan guard. LibraryService.beginScan clears instead.
        verify(scanControl, times(1)).clear(LIBRARY_ID);
    }

    @Test
    void publishesAFailedEventAndClearsTheCancelFlagWhenTheLibraryLookupFails() {
        when(libraryRepository.findByIdWithPaths(LIBRARY_ID)).thenReturn(Optional.empty());

        // Previously the lookup ran before the try/finally, so a failure here published
        // nothing and left any cancel flag set for the next scan of this library.
        assertThatThrownBy(() -> scanner.scan(LIBRARY_ID)).isInstanceOf(APIException.class);

        ArgumentCaptor<InpxScanProgress> captor = ArgumentCaptor.forClass(InpxScanProgress.class);
        verify(notificationService).sendMessage(eq(Topic.LIBRARY_SCAN_PROGRESS), captor.capture());
        InpxScanProgress failedEvent = captor.getValue();
        assertThat(failedEvent.status()).isEqualTo(InpxScanStatus.FAILED);
        assertThat(failedEvent.libraryId()).isEqualTo(LIBRARY_ID);
        assertThat(failedEvent.total()).isZero();
        assertThat(failedEvent.processed()).isZero();
        // Only in the finally block: clearing on entry would wipe a cancellation that arrived
        // after LibraryService opened the scan guard. LibraryService.beginScan clears instead.
        verify(scanControl, times(1)).clear(LIBRARY_ID);
        verify(inpxParser, never()).forEach(any(), any());
    }

    @Test
    void honoursCancellationRequestedWhileTheOnlyBatchIsSmallerThanTheBatchSize() {
        givenLibrary();
        givenIndexOf(150);
        when(batchWriter.persist(any(), eq(LIBRARY_ID), eq(3L), any()))
                .thenAnswer(invocation -> new InpxBatchWriter.BatchResult(
                        ((List<?>) invocation.getArgument(0)).size(), 0));
        when(scanControl.isCancelRequested(LIBRARY_ID)).thenReturn(true);

        // Previously the cancel check only lived inside the "batch is full" branch, so an
        // index smaller than BATCH_SIZE never checked cancellation at all and would always
        // report COMPLETED.
        InpxLibraryScanner.ScanResult result = scanner.scan(LIBRARY_ID);

        verify(batchWriter, times(1)).persist(any(), eq(LIBRARY_ID), eq(3L), any());
        assertThat(result.processed()).isEqualTo(150);
        assertThat(result.cancelled()).isTrue();

        ArgumentCaptor<InpxScanProgress> captor = ArgumentCaptor.forClass(InpxScanProgress.class);
        verify(notificationService, times(2)).sendMessage(eq(Topic.LIBRARY_SCAN_PROGRESS), captor.capture());
        assertThat(captor.getAllValues().getLast().status()).isEqualTo(InpxScanStatus.CANCELLED);
    }

    @Test
    void reportsCancelledInsteadOfCompletedWhenCancellationArrivesDuringTheTrailingPartialBatch() {
        givenLibrary();
        givenIndexOf(650);
        when(batchWriter.persist(any(), eq(LIBRARY_ID), eq(3L), any()))
                .thenAnswer(invocation -> new InpxBatchWriter.BatchResult(
                        ((List<?>) invocation.getArgument(0)).size(), 0));
        // Not cancelled during the first (full, 500-record) batch; only observed once
        // checked again around the trailing partial (150-record) batch.
        when(scanControl.isCancelRequested(LIBRARY_ID)).thenReturn(false, true);

        InpxLibraryScanner.ScanResult result = scanner.scan(LIBRARY_ID);

        // Committed work (both batches) must still be kept, even though the scan is
        // reported as cancelled rather than completed.
        verify(batchWriter, times(2)).persist(any(), eq(LIBRARY_ID), eq(3L), any());
        assertThat(result.processed()).isEqualTo(650);
        assertThat(result.cancelled()).isTrue();

        ArgumentCaptor<InpxScanProgress> captor = ArgumentCaptor.forClass(InpxScanProgress.class);
        verify(notificationService, times(3)).sendMessage(eq(Topic.LIBRARY_SCAN_PROGRESS), captor.capture());
        assertThat(captor.getAllValues().getLast().status()).isEqualTo(InpxScanStatus.CANCELLED);
    }
}
