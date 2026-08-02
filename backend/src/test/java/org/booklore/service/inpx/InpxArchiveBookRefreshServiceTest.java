package org.booklore.service.inpx;

import org.booklore.exception.ArchiveEntryMissingException;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.BookCoverService;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class InpxArchiveBookRefreshServiceTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private ArchivedBookContentService archivedBookContentService;
    @Mock
    private ArchiveEntryMetadataRecognizer entryMetadataRecognizer;
    @Mock
    private BookMetadataUpdater bookMetadataUpdater;
    @Mock
    private BookCoverService bookCoverService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private InpxArchiveBookRefreshService service;

    @BeforeEach
    void setUp() {
        service = new InpxArchiveBookRefreshService(bookRepository, archivedBookContentService,
                entryMetadataRecognizer, bookMetadataUpdater, bookCoverService, transactionTemplate);
        // Lenient: the retry test overrides this to throw once before running the callback.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void refreshesUsingSingleManagedBookLoad() {
        BookFileEntity archivedFile = BookFileEntity.builder()
                .sourceArchive("books.zip")
                .sourceArchiveEntry("book.fb2")
                .fileName("book.fb2")
                .build();
        BookEntity managedBook = BookEntity.builder()
                .id(42L)
                .bookFiles(List.of(archivedFile))
                .build();
        BookMetadata extractedMetadata = BookMetadata.builder().title("Updated title").build();

        when(bookRepository.findByIdForInpxArchiveRefresh(42L)).thenReturn(Optional.of(managedBook));
        when(entryMetadataRecognizer.hasExtractor("book.fb2")).thenReturn(true);
        // Revalidated, not cached: the refresh is the repair path for a replaced archive.
        when(archivedBookContentService.resolveRevalidated(archivedFile)).thenReturn(Path.of("book.fb2"));
        when(entryMetadataRecognizer.recognize("book.fb2", Path.of("book.fb2").toFile()))
                .thenReturn(extractedMetadata);

        service.refresh(42L);

        ArgumentCaptor<MetadataUpdateContext> contextCaptor = ArgumentCaptor.forClass(MetadataUpdateContext.class);
        verify(bookMetadataUpdater).setBookMetadata(contextCaptor.capture());
        assertThat(contextCaptor.getValue().getBookEntity()).isSameAs(managedBook);
        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getMetadata()).isSameAs(extractedMetadata);
        assertThat(managedBook.getScannedOn()).isNotNull();
        verify(bookRepository, times(1)).findByIdForInpxArchiveRefresh(42L);
        verify(bookRepository, never()).findById(42L);
        verify(bookRepository).save(managedBook);
        verify(bookCoverService).regenerateCover(42L);
    }

    @Test
    void clearsThePreviouslyProbedMarkerSoAGainedCoverIsPickedUp() {
        // A prior lazy probe may have recorded "no cover" before the archive was replaced with one
        // that does have a cover. The rescan must not let that stale answer survive.
        BookFileEntity archivedFile = BookFileEntity.builder()
                .sourceArchive("books.zip")
                .sourceArchiveEntry("book.fb2")
                .fileName("book.fb2")
                .build();
        BookEntity managedBook = BookEntity.builder()
                .id(42L)
                .coverProbedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"))
                .bookFiles(List.of(archivedFile))
                .build();

        when(bookRepository.findByIdForInpxArchiveRefresh(42L)).thenReturn(Optional.of(managedBook));
        when(entryMetadataRecognizer.hasExtractor("book.fb2")).thenReturn(true);
        when(archivedBookContentService.resolveRevalidated(archivedFile)).thenReturn(Path.of("book.fb2"));
        when(entryMetadataRecognizer.recognize("book.fb2", Path.of("book.fb2").toFile())).thenReturn(null);

        service.refresh(42L);

        assertThat(managedBook.getCoverProbedAt()).isNull();
        verify(bookRepository).save(managedBook);
    }

    @Test
    void retriesTheMetadataRefreshOnATransientOptimisticLock() {
        BookFileEntity archivedFile = BookFileEntity.builder()
                .sourceArchive("usr.zip")
                .sourceArchiveEntry("book.pdf")
                .fileName("book.pdf")
                .build();
        BookEntity managedBook = BookEntity.builder()
                .id(42L)
                .bookFiles(List.of(archivedFile))
                .build();

        when(bookRepository.findByIdForInpxArchiveRefresh(42L)).thenReturn(Optional.of(managedBook));
        when(entryMetadataRecognizer.hasExtractor("book.pdf")).thenReturn(true);
        when(archivedBookContentService.resolveRevalidated(archivedFile)).thenReturn(Path.of("book.pdf"));
        when(entryMetadataRecognizer.recognize("book.pdf", Path.of("book.pdf").toFile()))
                .thenReturn(BookMetadata.builder().title("Recovered").build());
        // First attempt hits a transient stale-state failure; the retry runs the callback. Uses the
        // do*-form so re-stubbing does not invoke the mock (which would trip the setUp answer).
        doThrow(new ObjectOptimisticLockingFailureException("AuthorEntity", 1L))
                .doAnswer(invocation ->
                        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)))
                .when(transactionTemplate).execute(any());

        boolean refreshed = service.refresh(42L);

        assertThat(refreshed).isTrue();
        verify(transactionTemplate, times(2)).execute(any());
        verify(bookCoverService).regenerateCover(42L);
    }

    @Test
    void keepsFilenameMetadataWithoutMaterialisingFormatsThatHaveNoExtractor() {
        BookFileEntity archivedFile = BookFileEntity.builder()
                .sourceArchive("usr.zip")
                .sourceArchiveEntry("scan.djvu")
                .fileName("scan.djvu")
                .build();
        BookEntity managedBook = BookEntity.builder()
                .id(42L)
                .bookFiles(List.of(archivedFile))
                .build();

        when(bookRepository.findByIdForInpxArchiveRefresh(42L)).thenReturn(Optional.of(managedBook));
        when(entryMetadataRecognizer.hasExtractor("scan.djvu")).thenReturn(false);

        boolean refreshed = service.refresh(42L);

        assertThat(refreshed).isFalse();
        assertThat(managedBook.getScannedOn()).isNotNull();
        verify(bookRepository).save(managedBook);
        verify(archivedBookContentService, never()).resolveRevalidated(any());
        verify(bookMetadataUpdater, never()).setBookMetadata(any());
        verify(bookCoverService, never()).regenerateCover(anyLong());
    }

    @Test
    void doesNotOverwriteMetadataWhenThePreviouslyCorruptedEntryIdentityIsMissing() {
        BookFileEntity archivedFile = BookFileEntity.builder()
                .sourceArchive("books.zip")
                .sourceArchiveEntry("??????.fb2")
                .fileName("??????.fb2")
                .build();
        BookEntity managedBook = BookEntity.builder()
                .id(42L)
                .bookFiles(List.of(archivedFile))
                .build();
        when(bookRepository.findByIdForInpxArchiveRefresh(42L)).thenReturn(Optional.of(managedBook));
        when(entryMetadataRecognizer.hasExtractor("??????.fb2")).thenReturn(true);
        when(archivedBookContentService.resolveRevalidated(archivedFile))
                .thenThrow(new ArchiveEntryMissingException("??????.fb2"));

        assertThatThrownBy(() -> service.refresh(42L))
                .isInstanceOf(ArchiveEntryMissingException.class);

        verify(bookMetadataUpdater, never()).setBookMetadata(any());
        verify(bookRepository, never()).save(managedBook);
        verify(bookCoverService, never()).regenerateCover(anyLong());
    }

}
