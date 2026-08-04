package org.booklore.service.inpx;

import jakarta.persistence.EntityManager;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.CategoryRepository;
import org.booklore.service.author.AuthorLocalResolver;
import org.booklore.service.enrichment.catalog.LocalCatalogBackfillService;
import org.booklore.service.enrichment.queue.EnrichmentPriority;
import org.booklore.service.enrichment.queue.EnrichmentQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A scan adds a handful of books at a time, so newly persisted books are queued through the
 * ordinary {@code enrichment_queue} (which already owns retries, deduplication and priority)
 * rather than the whole-library backfill.
 */
@ExtendWith(MockitoExtension.class)
class InpxBatchWriterEnrichmentTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private AuthorLocalResolver authorLocalResolver;
    @Mock
    private EntityManager entityManager;
    @Mock
    private EnrichmentQueueService enrichmentQueueService;

    @InjectMocks
    private InpxBatchWriter writer;

    private InpxScanCaches caches;

    @BeforeEach
    void setUp() {
        caches = new InpxScanCaches();
        when(entityManager.getReference(eq(LibraryEntity.class), anyLong()))
                .thenReturn(LibraryEntity.builder().id(7L).build());
        when(entityManager.getReference(eq(LibraryPathEntity.class), anyLong()))
                .thenReturn(LibraryPathEntity.builder().id(3L).build());
    }

    private InpxBookDto book(String archive, String file, String title) {
        return InpxBookDto.builder()
                .id(InpxParser.id(archive, file, "fb2"))
                .archiveName(archive)
                .fileName(file)
                .extension("fb2")
                .title(title)
                .authors(List.of())
                .genres(List.of())
                .series("")
                .seriesNumber("")
                .libraryId("1")
                .date("")
                .language("ru")
                .build();
    }

    /**
     * Assigns fake ids to the entities passed to {@code saveAll}, mimicking what a real
     * flush would do, since {@code bookRepository} is mocked here.
     */
    private void stubSaveAllAssignsIds(long firstId) {
        doAnswer(invocation -> {
            List<BookEntity> saved = invocation.getArgument(0);
            long id = firstId;
            for (BookEntity entity : saved) {
                entity.setId(id++);
            }
            return saved;
        }).when(bookRepository).saveAll(any());
    }

    @Test
    void queuesLocalEnrichmentForNewlyPersistedBooksOnceTheBatchCommits() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        stubSaveAllAssignsIds(100L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            writer.persist(List.of(book("fb2-1.zip", "a", "A")), 7L, 3L, caches);

            // Not queued yet: the enclosing (simulated) batch transaction has not committed.
            verifyNoInteractions(enrichmentQueueService);

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<EnrichmentRequest> captor = ArgumentCaptor.forClass(EnrichmentRequest.class);
        verify(enrichmentQueueService).enqueue(captor.capture(), eq(EnrichmentPriority.IMPORT_TOP_UP));
        EnrichmentRequest request = captor.getValue();
        assertThat(request.getScope()).isEqualTo(EnrichmentRequest.Scope.BOOKS);
        assertThat(request.getBookIds()).containsExactly(100L);
        assertThat(request.getSteps()).isEqualTo(LocalCatalogBackfillService.LOCAL_STEPS);
        assertThat(request.getWritePolicy()).isEqualTo(EnrichmentWritePolicy.AUTO_IF_EMPTY);
        assertThat(request.isAgentAllowed()).isFalse();
    }

    @Test
    void queuesNothingWhenNoNewBookWasPersisted() {
        // Every book in the batch is already in the library, so persist() adds nothing.
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"fb2-1.zip", "a.fb2"}));

        writer.persist(List.of(book("fb2-1.zip", "a", "A")), 7L, 3L, caches);

        verifyNoInteractions(enrichmentQueueService);
    }

    @Test
    void aQueueingFailureDoesNotEscapePersist() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        stubSaveAllAssignsIds(1L);
        when(enrichmentQueueService.enqueue(any(), anyInt())).thenThrow(new RuntimeException("queue is down"));

        // No transaction synchronization active here, so the hook runs inline: enrichment
        // must never break a scan even when nothing defers it to a later commit.
        assertThatCode(() -> writer.persist(List.of(book("fb2-1.zip", "a", "A")), 7L, 3L, caches))
                .doesNotThrowAnyException();
    }
}
