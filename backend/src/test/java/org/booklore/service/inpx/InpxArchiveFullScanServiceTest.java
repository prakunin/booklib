package org.booklore.service.inpx;

import org.booklore.exception.APIException;
import org.booklore.exception.ArchiveEntryMissingException;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class InpxArchiveFullScanServiceTest {

    @Mock
    private InpxArchiveCatalogService catalogService;
    @Mock
    private InpxArchiveScanner archiveScanner;
    @Mock
    private InpxBatchWriter batchWriter;
    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private InpxArchiveBookRefreshService bookRefreshService;
    @Mock
    private NotificationService notificationService;

    private InpxArchiveFullScanService service;

    @BeforeEach
    void setUp() {
        TaskExecutor directExecutor = Runnable::run;
        service = new InpxArchiveFullScanService(catalogService, archiveScanner, batchWriter,
                bookFileRepository, bookRefreshService, notificationService, directExecutor);
    }

    @Test
    void importsMissingEntriesThenRefreshesMetadataAndCoversForEveryBook() {
        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath("/books")
                .libraryPaths(new ArrayList<>(List.of(LibraryPathEntity.builder().id(3L).build())))
                .build();
        InpxArchiveScanner.ArchiveCandidate candidate = new InpxArchiveScanner.ArchiveCandidate(
                Path.of("/books/new.zip"), "new.zip", 2);
        when(catalogService.requireInpxLibrary(7L)).thenReturn(library);
        when(archiveScanner.inspectArchive("/books", "new.zip")).thenReturn(candidate);
        when(archiveScanner.discoveryForArchive(7L, candidate)).thenReturn(
                new InpxArchiveScanner.Discovery(List.of(candidate), 2, 7L));
        when(catalogService.queue(7L, "new.zip", 2)).thenReturn(true);
        when(batchWriter.persist(any(), eq(7L), eq(3L), any()))
                .thenReturn(new InpxBatchWriter.BatchResult(2, 0));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<InpxBookDto> consumer = invocation.getArgument(1);
            consumer.accept(book("1"));
            consumer.accept(book("2"));
            return null;
        }).when(archiveScanner).forEach(any(), any(), any());
        when(bookFileRepository.findBookIdsByArchive(7L, "new.zip")).thenReturn(List.of(11L, 12L));
        when(bookRefreshService.refresh(11L)).thenReturn(true);
        when(bookRefreshService.refresh(12L)).thenReturn(false);

        service.start(7L, "new.zip");

        verify(batchWriter).persist(any(), eq(7L), eq(3L), any());
        verify(bookRefreshService).refresh(11L);
        verify(bookRefreshService).refresh(12L);
        var ordered = inOrder(catalogService);
        ordered.verify(catalogService).importing(7L, "new.zip", 2);
        ordered.verify(catalogService).refreshing(7L, "new.zip", 2, 2);
        ordered.verify(catalogService).completed(7L, "new.zip");
        verify(notificationService).sendMessage(Topic.LIBRARY_SCAN_COMPLETE, 7L);
    }

    @Test
    void continuesAfterOneBookFailsAndRecordsItsFailure() {
        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath("/books")
                .libraryPaths(new ArrayList<>(List.of(LibraryPathEntity.builder().id(3L).build())))
                .build();
        InpxArchiveScanner.ArchiveCandidate candidate = new InpxArchiveScanner.ArchiveCandidate(
                Path.of("/books/new.zip"), "new.zip", 2);
        when(catalogService.requireInpxLibrary(7L)).thenReturn(library);
        when(archiveScanner.inspectArchive("/books", "new.zip")).thenReturn(candidate);
        when(archiveScanner.discoveryForArchive(7L, candidate)).thenReturn(
                new InpxArchiveScanner.Discovery(List.of(candidate), 0, 7L));
        when(catalogService.queue(7L, "new.zip", 2)).thenReturn(true);
        when(bookFileRepository.findBookIdsByArchive(7L, "new.zip")).thenReturn(List.of(11L, 12L));
        doThrow(new IllegalStateException("bad metadata")).when(bookRefreshService).refresh(11L);
        when(bookRefreshService.refresh(12L)).thenReturn(true);

        service.start(7L, "new.zip");

        verify(bookRefreshService).refresh(12L);
        verify(catalogService).progress(7L, "new.zip", 2, 1, 1);
        verify(catalogService).completed(7L, "new.zip");
    }

    @Test
    void retiresABookWhoseArchiveEntryVanishedInsteadOfOnlyCountingItFailed() {
        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath("/books")
                .libraryPaths(new ArrayList<>(List.of(LibraryPathEntity.builder().id(3L).build())))
                .build();
        InpxArchiveScanner.ArchiveCandidate candidate = new InpxArchiveScanner.ArchiveCandidate(
                Path.of("/books/new.zip"), "new.zip", 2);
        when(catalogService.requireInpxLibrary(7L)).thenReturn(library);
        when(archiveScanner.inspectArchive("/books", "new.zip")).thenReturn(candidate);
        when(archiveScanner.discoveryForArchive(7L, candidate)).thenReturn(
                new InpxArchiveScanner.Discovery(List.of(candidate), 0, 7L));
        when(catalogService.queue(7L, "new.zip", 2)).thenReturn(true);
        when(bookFileRepository.findBookIdsByArchive(7L, "new.zip")).thenReturn(List.of(11L, 12L));
        // The archive was rewritten: 11's entry is gone, 12 still resolves.
        doThrow(new ArchiveEntryMissingException("old.fb2")).when(bookRefreshService).refresh(11L);
        when(bookRefreshService.refresh(12L)).thenReturn(true);

        service.start(7L, "new.zip");

        verify(bookRefreshService).retireOrphan(11L);
        verify(bookRefreshService, never()).retireOrphan(12L);
        // A retired orphan is not a failure: both books processed, one cover, zero failures.
        verify(catalogService).progress(7L, "new.zip", 2, 1, 0);
        verify(catalogService).completed(7L, "new.zip");
    }

    @Test
    void marksArchiveFailedWhenExecutorRejectsTheTask() {
        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath("/books")
                .build();
        InpxArchiveScanner.ArchiveCandidate candidate = new InpxArchiveScanner.ArchiveCandidate(
                Path.of("/books/new.zip"), "new.zip", 2);
        when(catalogService.requireInpxLibrary(7L)).thenReturn(library);
        when(archiveScanner.inspectArchive("/books", "new.zip")).thenReturn(candidate);
        when(catalogService.queue(7L, "new.zip", 2)).thenReturn(true);
        TaskExecutor rejectingExecutor = task -> {
            throw new RejectedExecutionException("full");
        };
        InpxArchiveFullScanService rejectingService = new InpxArchiveFullScanService(
                catalogService, archiveScanner, batchWriter, bookFileRepository,
                bookRefreshService, notificationService, rejectingExecutor);

        assertThatThrownBy(() -> rejectingService.start(7L, "new.zip"))
                .hasMessageContaining("scan queue is full")
                .isInstanceOfSatisfying(APIException.class,
                        exception -> assertThat(exception.getStatus())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(catalogService).failed(7L, "new.zip",
                "INPX archive scan queue is full. Please try again later.");
    }

    private InpxBookDto book(String fileName) {
        return InpxBookDto.builder()
                .archiveName("new.zip")
                .fileName(fileName)
                .extension("fb2")
                .title(fileName)
                .authors(List.of())
                .genres(List.of())
                .build();
    }
}
