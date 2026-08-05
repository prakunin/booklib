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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A scan adds a handful of books at a time, so newly persisted books are queued through the
 * ordinary {@code enrichment_queue} (which already owns retries, deduplication and priority)
 * rather than the whole-library backfill.
 * <p>
 * "A handful" is load-bearing rather than descriptive: a first import adds the entire library, which
 * the queue's five-books-per-fifteen-seconds drain rate cannot serve. {@link InpxScanCaches} therefore
 * caps what one scan may queue and leaves bulk to the backfill task.
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
     * flush would do, since {@code bookRepository} is mocked here. The counter runs across
     * invocations, so a scan of several batches produces distinct ids the way a database would —
     * restarting it per batch would make books from different batches indistinguishable.
     */
    private void stubSaveAllAssignsIds(long firstId) {
        AtomicLong nextId = new AtomicLong(firstId);
        doAnswer(invocation -> {
            List<BookEntity> saved = invocation.getArgument(0);
            for (BookEntity entity : saved) {
                entity.setId(nextId.getAndIncrement());
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

    /**
     * "A scan adds a handful at a time" is the justification for using the queue at all, so it is also
     * the limit. A first import does not add a handful: the library this feature exists for holds
     * 702,511 books, and every one of them was new. At {@code EnrichmentWorker}'s five books per
     * fifteen seconds that was about 24 days of queued work, plus a SELECT and an INSERT per book on
     * the import's hot path — the same arithmetic {@code LocalCatalogBackfillService} cites for not
     * using the queue itself.
     * <p>
     * Six batches of a thousand are driven through one scan's caches, so the budget is crossed
     * mid-batch rather than on a batch boundary: that is the case where a naive per-batch cap still
     * lets everything through. The assertion counts distinct queued ids across every enqueue call, so
     * it fails against the unbounded code (6,000) as well as against a cap applied per batch.
     */
    @Test
    void stopsQueueingOnceOneScanHasSpentItsEnrichmentBudget() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        stubSaveAllAssignsIds(1L);

        for (int batch = 0; batch < 6; batch++) {
            List<InpxBookDto> books = new ArrayList<>(1_000);
            for (int i = 0; i < 1_000; i++) {
                books.add(book("fb2-" + batch + ".zip", "book-" + i, "Book " + i));
            }
            writer.persist(books, 7L, 3L, caches);
        }

        ArgumentCaptor<EnrichmentRequest> captor = ArgumentCaptor.forClass(EnrichmentRequest.class);
        verify(enrichmentQueueService, atLeastOnce()).enqueue(captor.capture(), eq(EnrichmentPriority.IMPORT_TOP_UP));
        long queued = captor.getAllValues().stream()
                .flatMap(request -> request.getBookIds().stream())
                .distinct()
                .count();
        assertThat(queued).isEqualTo(InpxScanCaches.maxEnrichmentQueueBooks());
    }

    /**
     * The other half of the same rule: a rescan of an already imported library, which finds a handful
     * of genuinely new books, must be completely unaffected — it is the case the queue was justified
     * for. Reverting the budget leaves this green, which is the point: it pins that the fix did not
     * take the incremental path away with it.
     */
    @Test
    void aHandfulOfNewBooksIsStillQueuedInFull() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        stubSaveAllAssignsIds(1L);

        writer.persist(List.of(book("fb2-1.zip", "a", "A"), book("fb2-1.zip", "b", "B")), 7L, 3L, caches);

        ArgumentCaptor<EnrichmentRequest> captor = ArgumentCaptor.forClass(EnrichmentRequest.class);
        verify(enrichmentQueueService).enqueue(captor.capture(), eq(EnrichmentPriority.IMPORT_TOP_UP));
        assertThat(captor.getValue().getBookIds()).containsExactlyInAnyOrder(1L, 2L);
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
