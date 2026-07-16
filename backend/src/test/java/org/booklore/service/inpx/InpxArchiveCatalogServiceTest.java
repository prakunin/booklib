package org.booklore.service.inpx;

import org.booklore.model.dto.inpx.InpxArchiveDto;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.InpxArchiveScanPhase;
import org.booklore.model.enums.InpxArchiveScanStatus;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxArchiveCatalogServiceTest {

    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private InpxArchiveScanner archiveScanner;

    private InpxArchiveCatalogService service;

    @BeforeEach
    void setUp() {
        InpxArchiveStatisticsService statisticsService =
                new InpxArchiveStatisticsService(bookFileRepository, Runnable::run);
        service = new InpxArchiveCatalogService(libraryRepository, archiveScanner, statisticsService);
    }

    @Test
    void mergesFilesystemArchivesWithPersistedBookStatistics() {
        Instant modified = Instant.parse("2026-07-15T10:00:00Z");
        Instant added = Instant.parse("2026-07-01T10:00:00Z");
        Instant scanned = Instant.parse("2026-07-10T10:00:00Z");
        givenLibrary();
        when(archiveScanner.listArchives("/books")).thenReturn(List.of(
                new InpxArchiveScanner.ArchiveFile(
                        Path.of("/books/new.zip"), "new.zip", 2048, modified, 100)));
        when(bookFileRepository.findArchiveStatistics(7L)).thenReturn(List.<Object[]>of(
                new Object[]{"new.zip", 90L, added, scanned, 75L}));

        List<InpxArchiveDto> archives = service.list(7L);

        assertThat(archives).singleElement().satisfies(archive -> {
            assertThat(archive.archiveName()).isEqualTo("new.zip");
            assertThat(archive.fb2Count()).isEqualTo(100);
            assertThat(archive.importedBookCount()).isEqualTo(90);
            assertThat(archive.coveredBookCount()).isEqualTo(75);
            assertThat(archive.addedAt()).isEqualTo(added);
            assertThat(archive.lastScannedAt()).isEqualTo(scanned);
            assertThat(archive.status()).isEqualTo(InpxArchiveScanStatus.IDLE);
        });
    }

    @Test
    void preventsTwoConcurrentScansOfTheSameArchiveAndExposesFailure() {
        givenLibrary();
        when(archiveScanner.listArchives("/books")).thenReturn(List.of(
                new InpxArchiveScanner.ArchiveFile(
                        Path.of("/books/new.zip"), "new.zip", 2048, Instant.now(), 1)));
        when(bookFileRepository.findArchiveStatistics(7L)).thenReturn(List.of());

        assertThat(service.queue(7L, "new.zip", 1)).isTrue();
        assertThat(service.queue(7L, "new.zip", 1)).isFalse();
        service.importing(7L, "new.zip", 1);
        service.refreshing(7L, "new.zip", 1, 1);
        service.progress(7L, "new.zip", 1, 0, 1);
        service.failed(7L, "new.zip", "broken ZIP");

        assertThat(service.list(7L)).singleElement().satisfies(archive -> {
            assertThat(archive.status()).isEqualTo(InpxArchiveScanStatus.FAILED);
            assertThat(archive.errorMessage()).isEqualTo("broken ZIP");
        });
        assertThat(service.listTasks(7L)).hasSize(2).satisfiesExactly(
                task -> {
                    assertThat(task.phase()).isEqualTo(InpxArchiveScanPhase.IMPORTING);
                    assertThat(task.status()).isEqualTo(InpxArchiveScanStatus.COMPLETED);
                    assertThat(task.addedBooks()).isEqualTo(1);
                },
                task -> {
                    assertThat(task.phase()).isEqualTo(InpxArchiveScanPhase.METADATA_AND_COVERS);
                    assertThat(task.status()).isEqualTo(InpxArchiveScanStatus.FAILED);
                    assertThat(task.processedBooks()).isEqualTo(1);
                    assertThat(task.remainingBooks()).isZero();
                    assertThat(task.failedBooks()).isEqualTo(1);
                    assertThat(task.errorMessage()).isEqualTo("broken ZIP");
                });
        assertThat(service.queue(7L, "new.zip", 1)).isTrue();
    }

    @Test
    void exposesImportAndMetadataAsSeparateQueuedTasks() {
        givenLibrary();

        assertThat(service.queue(7L, "new.zip", 25)).isTrue();

        assertThat(service.listTasks(7L))
                .extracting(task -> task.phase(), task -> task.status(), task -> task.totalBooks())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                InpxArchiveScanPhase.IMPORTING, InpxArchiveScanStatus.QUEUED, 25L),
                        org.assertj.core.groups.Tuple.tuple(
                                InpxArchiveScanPhase.METADATA_AND_COVERS, InpxArchiveScanStatus.QUEUED, 25L));
    }

    @Test
    void cachesArchiveStatisticsBetweenRequestsInsteadOfRecomputing() {
        givenLibrary();
        when(archiveScanner.listArchives("/books")).thenReturn(List.of(
                new InpxArchiveScanner.ArchiveFile(
                        Path.of("/books/new.zip"), "new.zip", 2048, Instant.now(), 100)));
        when(bookFileRepository.findArchiveStatistics(7L)).thenReturn(List.of());

        service.list(7L);
        service.list(7L);
        service.list(7L);

        // The expensive aggregation must run once and be served from cache afterwards
        verify(bookFileRepository, times(1)).findArchiveStatistics(7L);
    }

    @Test
    void recomputesArchiveStatisticsAfterScanCompletes() {
        givenLibrary();
        when(archiveScanner.listArchives("/books")).thenReturn(List.of(
                new InpxArchiveScanner.ArchiveFile(
                        Path.of("/books/new.zip"), "new.zip", 2048, Instant.now(), 100)));
        when(bookFileRepository.findArchiveStatistics(7L)).thenReturn(List.of());

        service.list(7L);
        service.completed(7L, "new.zip");
        service.list(7L);

        // A completed scan changes book statistics, so the cache must be invalidated
        verify(bookFileRepository, times(2)).findArchiveStatistics(7L);
    }

    @Test
    void recomputesArchiveStatisticsAfterScanFails() {
        givenLibrary();
        when(archiveScanner.listArchives("/books")).thenReturn(List.of(
                new InpxArchiveScanner.ArchiveFile(
                        Path.of("/books/new.zip"), "new.zip", 2048, Instant.now(), 100)));
        when(bookFileRepository.findArchiveStatistics(7L)).thenReturn(List.of());

        service.list(7L);
        service.failed(7L, "new.zip", "broken ZIP");
        service.list(7L);

        verify(bookFileRepository, times(2)).findArchiveStatistics(7L);
    }

    @Test
    void cachesStatisticsPerLibrarySoOneScanDoesNotInvalidateAnother() {
        when(libraryRepository.findByIdWithPaths(7L)).thenReturn(Optional.of(LibraryEntity.builder()
                .id(7L).name("A").sourceType(LibrarySourceType.INPX).inpxArchivePath("/a").build()));
        when(libraryRepository.findByIdWithPaths(8L)).thenReturn(Optional.of(LibraryEntity.builder()
                .id(8L).name("B").sourceType(LibrarySourceType.INPX).inpxArchivePath("/b").build()));
        when(archiveScanner.listArchives("/a")).thenReturn(List.of());
        when(archiveScanner.listArchives("/b")).thenReturn(List.of());
        when(bookFileRepository.findArchiveStatistics(7L)).thenReturn(List.of());
        when(bookFileRepository.findArchiveStatistics(8L)).thenReturn(List.of());

        service.list(7L);
        service.list(8L);
        service.completed(7L, "x.zip");
        service.list(7L);
        service.list(8L);

        verify(bookFileRepository, times(2)).findArchiveStatistics(7L); // invalidated
        verify(bookFileRepository, times(1)).findArchiveStatistics(8L); // untouched
    }

    private void givenLibrary() {
        when(libraryRepository.findByIdWithPaths(7L)).thenReturn(Optional.of(LibraryEntity.builder()
                .id(7L)
                .name("INPX")
                .sourceType(LibrarySourceType.INPX)
                .inpxArchivePath("/books")
                .build()));
    }
}
