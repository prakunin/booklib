package org.booklore.service.inpx;

import org.booklore.repository.BookFileRepository;
import org.booklore.service.library.BookDeletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxArchiveRemovalBatchServiceTest {

    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private BookDeletionService bookDeletionService;

    @Test
    void deletesOnlyTheBoundedIdsSelectedByTheRepository() {
        InpxArchiveRemovalBatchService service = service();
        Set<String> missingArchives = Set.of("missing.zip");
        when(bookFileRepository.findBookIdsWithSourceArchivesAfterId(
                eq(7L), eq(missingArchives), eq(100L),
                argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 500)))
                .thenReturn(List.of(101L, 120L));
        when(bookFileRepository.findBookFormatArchiveSourcesByBookIds(List.of(101L, 120L)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{101L, "missing.zip"},
                        new Object[]{120L, "missing.zip"}));

        InpxArchiveRemovalBatchService.RemovalBatch result = service.removeNext(
                7L, missingArchives, missingArchives, 100L);

        assertThat(result.removed()).isEqualTo(2);
        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.lastBookId()).isEqualTo(120L);
        verify(bookDeletionService).deleteRemovedBooks(List.of(101L, 120L));
    }

    @Test
    void retainsBooksWithAnotherBookFormatSource() {
        InpxArchiveRemovalBatchService service = service();
        Set<String> missingArchives = Set.of("missing.zip");
        when(bookFileRepository.findBookIdsWithSourceArchivesAfterId(
                eq(7L), eq(missingArchives), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(101L));
        when(bookFileRepository.findBookFormatArchiveSourcesByBookIds(List.of(101L)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{101L, "missing.zip"},
                        new Object[]{101L, "present.zip"}));

        InpxArchiveRemovalBatchService.RemovalBatch result = service.removeNext(
                7L, missingArchives, missingArchives, 0);

        assertThat(result.scanned()).isOne();
        assertThat(result.removed()).isZero();
        assertThat(result.lastBookId()).isEqualTo(101L);
        verify(bookDeletionService, never()).deleteRemovedBooks(any());
    }

    @Test
    void comparesArchiveNamesCaseSensitivelyAfterTheDatabaseCandidateLookup() {
        InpxArchiveRemovalBatchService service = service();
        Set<String> missingArchives = Set.of("Missing.zip");
        when(bookFileRepository.findBookIdsWithSourceArchivesAfterId(
                eq(7L), eq(missingArchives), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(101L));
        when(bookFileRepository.findBookFormatArchiveSourcesByBookIds(List.of(101L)))
                .thenReturn(List.<Object[]>of(new Object[]{101L, "missing.zip"}));

        InpxArchiveRemovalBatchService.RemovalBatch result = service.removeNext(
                7L, missingArchives, missingArchives, 0);

        assertThat(result.scanned()).isOne();
        assertThat(result.removed()).isZero();
        verify(bookDeletionService, never()).deleteRemovedBooks(any());
    }

    @Test
    void doesNotDeleteWhenNoEligibleBookIdsRemain() {
        InpxArchiveRemovalBatchService service = service();
        Set<String> missingArchives = Set.of("missing.zip");
        when(bookFileRepository.findBookIdsWithSourceArchivesAfterId(
                eq(7L), eq(missingArchives), eq(100L), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.removeNext(
                7L, missingArchives, missingArchives, 100L).removed()).isZero();

        verify(bookDeletionService, never()).deleteRemovedBooks(any());
    }

    private InpxArchiveRemovalBatchService service() {
        return new InpxArchiveRemovalBatchService(bookFileRepository, bookDeletionService);
    }
}
