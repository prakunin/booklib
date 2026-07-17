package org.booklore.service.inpx;

import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.BookCoverService;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
    private Fb2MetadataExtractor fb2MetadataExtractor;
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
                fb2MetadataExtractor, bookMetadataUpdater, bookCoverService, transactionTemplate);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void reloadsBookInsideMetadataTransactionToAvoidMergingDetachedCategoryMappings() {
        BookFileEntity archivedFile = BookFileEntity.builder()
                .sourceArchive("books.zip")
                .sourceArchiveEntry("book.fb2")
                .build();
        BookEntity detachedBook = BookEntity.builder()
                .id(42L)
                .bookFiles(List.of(archivedFile))
                .build();
        BookEntity managedBook = BookEntity.builder()
                .id(42L)
                .bookFiles(List.of(archivedFile))
                .build();
        BookMetadata extractedMetadata = BookMetadata.builder().title("Updated title").build();

        when(bookRepository.findByIdForInpxArchiveRefresh(42L))
                .thenReturn(Optional.of(detachedBook), Optional.of(managedBook));
        // Revalidated, not cached: the refresh is the repair path for a replaced archive.
        when(archivedBookContentService.resolveRevalidated(archivedFile)).thenReturn(Path.of("book.fb2"));
        when(fb2MetadataExtractor.extractMetadata(Path.of("book.fb2").toFile()))
                .thenReturn(extractedMetadata);
        when(bookRepository.findById(42L)).thenReturn(Optional.of(managedBook));

        service.refresh(42L);

        ArgumentCaptor<MetadataUpdateContext> contextCaptor = ArgumentCaptor.forClass(MetadataUpdateContext.class);
        verify(bookMetadataUpdater).setBookMetadata(contextCaptor.capture());
        assertThat(contextCaptor.getValue().getBookEntity()).isSameAs(managedBook);
        assertThat(contextCaptor.getValue().getBookEntity()).isNotSameAs(detachedBook);
        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getMetadata()).isSameAs(extractedMetadata);
        assertThat(managedBook.getScannedOn()).isNotNull();
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
                .build();
        BookEntity detachedBook = BookEntity.builder()
                .id(42L)
                .coverProbedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"))
                .bookFiles(List.of(archivedFile))
                .build();
        BookEntity managedBook = BookEntity.builder()
                .id(42L)
                .coverProbedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"))
                .bookFiles(List.of(archivedFile))
                .build();

        when(bookRepository.findByIdForInpxArchiveRefresh(42L))
                .thenReturn(Optional.of(detachedBook), Optional.of(managedBook));
        when(archivedBookContentService.resolveRevalidated(archivedFile)).thenReturn(Path.of("book.fb2"));
        when(fb2MetadataExtractor.extractMetadata(Path.of("book.fb2").toFile())).thenReturn(null);
        when(bookRepository.findById(42L)).thenReturn(Optional.of(managedBook));

        service.refresh(42L);

        assertThat(managedBook.getCoverProbedAt()).isNull();
        verify(bookRepository).save(managedBook);
    }
}
