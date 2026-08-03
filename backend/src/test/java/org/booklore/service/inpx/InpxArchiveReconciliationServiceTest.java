package org.booklore.service.inpx;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxArchiveReconciliationServiceTest {

    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private ArchiveEntryMetadataRecognizer entryMetadataRecognizer;
    @Mock
    private InpxArchiveRemovalBatchService archiveRemovalBatchService;

    @Test
    void removesBooksFromMissingArchivesInBoundedBatches() {
        InpxArchiveReconciliationService service = service();
        givenPersistedArchives("present.zip", "missing.zip");
        when(archiveRemovalBatchService.removeNext(
                7L, java.util.Set.of("missing.zip"), java.util.Set.of("missing.zip"), 0))
                .thenReturn(new InpxArchiveRemovalBatchService.RemovalBatch(500, 500, 800L));
        when(archiveRemovalBatchService.removeNext(
                7L, java.util.Set.of("missing.zip"), java.util.Set.of("missing.zip"), 800L))
                .thenReturn(new InpxArchiveRemovalBatchService.RemovalBatch(12, 12, 820L));
        when(archiveRemovalBatchService.removeNext(
                7L, java.util.Set.of("missing.zip"), java.util.Set.of("missing.zip"), 820L))
                .thenReturn(new InpxArchiveRemovalBatchService.RemovalBatch(0, 0, 0));

        InpxArchiveReconciliationService.RemovalResult result = service.removeBooksFromMissingArchives(
                7L, java.util.Set.of("present.zip"), () -> false);

        assertThat(result.removed()).isEqualTo(512);
        assertThat(result.cancelled()).isFalse();
        verify(archiveRemovalBatchService, times(3)).removeNext(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(java.util.Set.of("missing.zip")),
                org.mockito.ArgumentMatchers.eq(java.util.Set.of("missing.zip")),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void skipsRemovalWhenTheArchiveSnapshotIsEmpty() {
        InpxArchiveReconciliationService service = service();
        givenPersistedArchives("missing.zip");

        assertThat(service.removeBooksFromMissingArchives(
                7L, java.util.Set.of(), () -> false).removed()).isZero();

        verify(archiveRemovalBatchService, never()).removeNext(
                org.mockito.ArgumentMatchers.anyLong(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void doesNothingWhenEveryPersistedArchiveIsPresent() {
        InpxArchiveReconciliationService service = service();
        givenPersistedArchives("present.zip");

        assertThat(service.removeBooksFromMissingArchives(
                7L, java.util.Set.of("present.zip"), () -> false).removed()).isZero();

        verify(archiveRemovalBatchService, never()).removeNext(
                org.mockito.ArgumentMatchers.anyLong(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void keepsCaseDistinctArchiveNamesFromThePagedSourceRows() {
        InpxArchiveReconciliationService service = service();
        givenPersistedArchives("Archive.zip", "archive.zip");
        when(archiveRemovalBatchService.removeNext(
                7L, java.util.Set.of("archive.zip"), java.util.Set.of("archive.zip"), 0))
                .thenReturn(new InpxArchiveRemovalBatchService.RemovalBatch(0, 0, 0));

        service.removeBooksFromMissingArchives(
                7L, java.util.Set.of("Archive.zip"), () -> false);

        verify(archiveRemovalBatchService).removeNext(
                7L, java.util.Set.of("archive.zip"), java.util.Set.of("archive.zip"), 0);
    }

    @Test
    void stopsBetweenRemovalBatchesWhenCancelled() {
        InpxArchiveReconciliationService service = service();
        givenPersistedArchives("present.zip", "missing.zip");
        when(archiveRemovalBatchService.removeNext(
                7L, java.util.Set.of("missing.zip"), java.util.Set.of("missing.zip"), 0))
                .thenReturn(new InpxArchiveRemovalBatchService.RemovalBatch(500, 500, 800L));
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();

        InpxArchiveReconciliationService.RemovalResult result = service.removeBooksFromMissingArchives(
                7L, java.util.Set.of("present.zip"), () -> checks.incrementAndGet() > 1);

        assertThat(result.removed()).isEqualTo(500);
        assertThat(result.cancelled()).isTrue();
        verify(archiveRemovalBatchService).removeNext(
                7L, java.util.Set.of("missing.zip"), java.util.Set.of("missing.zip"), 0);
    }

    @Test
    void retiresLegacyContainerOnlyAfterANestedLeafHasBeenPersisted() {
        InpxArchiveReconciliationService service = new InpxArchiveReconciliationService(
                bookFileRepository, bookRepository, entryMetadataRecognizer, archiveRemovalBatchService);
        BookEntity legacyBook = BookEntity.builder().id(10L).deleted(false).build();
        BookFileEntity legacyContainer = BookFileEntity.builder()
                .book(legacyBook).bookType(BookFileType.OTHER)
                .sourceArchive("outer.zip").sourceArchiveEntry("inner.zip").build();
        BookEntity leafBook = BookEntity.builder().id(11L).deleted(false).build();
        BookFileEntity nestedLeaf = BookFileEntity.builder()
                .book(leafBook).sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("inner.zip", "book.fb2")))
                .build();
        when(bookFileRepository.findBookFilesByArchives(7L, List.of("outer.zip")))
                .thenReturn(List.of(legacyContainer, nestedLeaf));
        when(entryMetadataRecognizer.isGenericArchive("inner.zip")).thenReturn(true);
        int retired = service.retireObsoleteGenericContainers(7L, List.of("outer.zip"));

        assertThat(retired).isOne();
        assertThat(legacyBook.getDeleted()).isTrue();
        verify(bookRepository).saveAll(List.of(legacyBook));
    }

    @Test
    void returnsImmediatelyForEmptyArchiveNames() {
        InpxArchiveReconciliationService service = service();

        assertThat(service.retireObsoleteGenericContainers(7L, List.of())).isZero();

        verify(bookFileRepository, never()).findBookFilesByArchives(7L, List.of());
        verify(bookRepository, never()).saveAll(any());
    }

    @Test
    void leavesContainersActiveUntilALiveNestedLeafExists() {
        InpxArchiveReconciliationService service = service();
        BookEntity legacyBook = BookEntity.builder().id(10L).deleted(false).build();
        BookFileEntity legacyContainer = BookFileEntity.builder()
                .book(legacyBook).sourceArchive("outer.zip").sourceArchiveEntry("inner.zip").build();
        BookEntity deletedLeafBook = BookEntity.builder().id(11L).deleted(true).build();
        BookFileEntity deletedNestedLeaf = BookFileEntity.builder()
                .book(deletedLeafBook).sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("inner.zip", "book.fb2")))
                .build();
        when(bookFileRepository.findBookFilesByArchives(7L, List.of("outer.zip")))
                .thenReturn(List.of(legacyContainer, deletedNestedLeaf));

        assertThat(service.retireObsoleteGenericContainers(7L, List.of("outer.zip"))).isZero();
        assertThat(legacyBook.getDeleted()).isFalse();
        verify(bookRepository, never()).saveAll(any());
    }

    @Test
    void ignoresDeletedNestedContainersAndNonGenericEntries() {
        InpxArchiveReconciliationService service = service();
        BookEntity leafBook = BookEntity.builder().id(11L).deleted(false).build();
        BookFileEntity nestedLeaf = BookFileEntity.builder()
                .book(leafBook).sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("inner.zip", "book.fb2")))
                .build();
        BookEntity deletedBook = BookEntity.builder().id(12L).deleted(true).build();
        BookFileEntity deletedContainer = BookFileEntity.builder()
                .book(deletedBook).sourceArchive("outer.zip").sourceArchiveEntry("deleted.zip").build();
        BookEntity nonGenericBook = BookEntity.builder().id(13L).deleted(false).build();
        BookFileEntity nonGenericContainer = BookFileEntity.builder()
                .book(nonGenericBook).sourceArchive("outer.zip").sourceArchiveEntry("cover.jpg").build();
        when(bookFileRepository.findBookFilesByArchives(7L, List.of("outer.zip")))
                .thenReturn(List.of(nestedLeaf, deletedContainer, nonGenericContainer));

        assertThat(service.retireObsoleteGenericContainers(7L, List.of("outer.zip"))).isZero();
        assertThat(deletedBook.getDeleted()).isTrue();
        assertThat(nonGenericBook.getDeleted()).isFalse();
        verify(bookRepository, never()).saveAll(any());
    }

    @Test
    void promotesNestedHtmlAndRetiresItsFormerAssetCards() {
        InpxArchiveReconciliationService service = service();
        BookEntity htmlBook = BookEntity.builder().id(20L).deleted(false).build();
        BookFileEntity html = BookFileEntity.builder()
                .book(htmlBook)
                .fileName("letter.html")
                .bookType(BookFileType.OTHER)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("publication.zip", "letter.html")))
                .build();
        BookEntity imageBook = BookEntity.builder().id(21L).deleted(false).build();
        BookFileEntity image = BookFileEntity.builder()
                .book(imageBook)
                .fileName("00.gif")
                .bookType(BookFileType.OTHER)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("publication.zip", "img/00.gif")))
                .build();
        imageBook.setBookFiles(List.of(image));
        when(bookFileRepository.findBookFilesByArchives(7L, List.of("outer.zip")))
                .thenReturn(List.of(html, image));

        InpxArchiveReconciliationService.ReconciliationResult result =
                service.reconcileNestedPublications(7L, "outer.zip");

        assertThat(result.promotedHtml()).isOne();
        assertThat(result.retiredAssets()).isOne();
        assertThat(html.getBookType()).isEqualTo(BookFileType.HTML);
        assertThat(imageBook.getDeleted()).isTrue();
        verify(bookFileRepository).saveAll(List.of(html));
        verify(bookRepository).saveAll(List.of(imageBook));
    }

    @Test
    void promotesLegacyImageContainerToCbxBeforeRetiringItsPages() {
        InpxArchiveReconciliationService service = service();
        BookEntity containerBook = BookEntity.builder().id(30L).deleted(false).build();
        BookFileEntity container = BookFileEntity.builder()
                .book(containerBook).fileName("comic.zip").bookType(BookFileType.OTHER)
                .sourceArchive("outer.zip").sourceArchiveEntry("comic.zip").build();
        BookEntity pageBook = BookEntity.builder().id(31L).deleted(false).build();
        BookFileEntity page = BookFileEntity.builder()
                .book(pageBook).fileName("page.jpg").bookType(BookFileType.OTHER)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("comic.zip", "page.jpg"))).build();
        pageBook.setBookFiles(List.of(page));
        BookEntity otherLeafBook = BookEntity.builder().id(32L).deleted(false).build();
        BookFileEntity otherLeaf = BookFileEntity.builder()
                .book(otherLeafBook).fileName("book.fb2").bookType(BookFileType.FB2)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("books.zip", "book.fb2"))).build();
        when(bookFileRepository.findBookFilesByArchives(7L, List.of("outer.zip")))
                .thenReturn(List.of(container, page, otherLeaf));
        when(entryMetadataRecognizer.isGenericArchive("comic.zip")).thenReturn(true);

        InpxArchiveReconciliationService.ReconciliationResult result =
                service.reconcileNestedPublications(7L, "outer.zip");

        assertThat(container.getBookType()).isEqualTo(BookFileType.CBX);
        assertThat(pageBook.getDeleted()).isTrue();
        assertThat(result.retiredAssets()).isOne();
        verify(bookFileRepository).saveAll(List.of(container));

        assertThat(service.retireObsoleteGenericContainers(7L, List.of("outer.zip"))).isZero();
        assertThat(containerBook.getDeleted()).isFalse();
    }

    @Test
    void doesNotRetireAGroupedBookWhenOnlyOneFileIsAPublicationAsset() {
        InpxArchiveReconciliationService service = service();
        BookEntity htmlBook = BookEntity.builder().id(40L).deleted(false).build();
        BookFileEntity html = BookFileEntity.builder()
                .book(htmlBook).fileName("letter.html").bookType(BookFileType.HTML)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("publication.zip", "letter.html")))
                .build();
        BookEntity groupedBook = BookEntity.builder().id(41L).deleted(false).build();
        BookFileEntity image = BookFileEntity.builder()
                .book(groupedBook).fileName("00.gif").bookType(BookFileType.OTHER)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("publication.zip", "img/00.gif")))
                .build();
        BookFileEntity retained = BookFileEntity.builder()
                .book(groupedBook).fileName("notes.txt").bookType(BookFileType.OTHER).build();
        groupedBook.setBookFiles(List.of(image, retained));
        when(bookFileRepository.findBookFilesByArchives(7L, List.of("outer.zip")))
                .thenReturn(List.of(html, image));

        InpxArchiveReconciliationService.ReconciliationResult result =
                service.reconcileNestedPublications(7L, "outer.zip");

        assertThat(result.retiredAssets()).isZero();
        assertThat(groupedBook.getDeleted()).isFalse();
        verify(bookRepository, never()).saveAll(any());
    }

    private InpxArchiveReconciliationService service() {
        return new InpxArchiveReconciliationService(
                bookFileRepository, bookRepository, entryMetadataRecognizer, archiveRemovalBatchService);
    }

    private void givenPersistedArchives(String... archiveNames) {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int index = 0; index < archiveNames.length; index++) {
            rows.add(new Object[]{(long) index + 1, archiveNames[index]});
        }
        when(bookFileRepository.findArchiveSourcesAfterId(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(rows, List.of());
    }
}
