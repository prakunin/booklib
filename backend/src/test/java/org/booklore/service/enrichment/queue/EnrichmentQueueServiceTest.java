package org.booklore.service.enrichment.queue;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.EnrichmentQueueEntity;
import org.booklore.model.enums.EnrichmentQueueStatus;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.repository.BookRepository;
import org.booklore.repository.EnrichmentQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrichmentQueueServiceTest {

    private final EnrichmentQueueRepository queueRepository = mock(EnrichmentQueueRepository.class);
    private final BookRepository bookRepository = mock(BookRepository.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);

    private final EnrichmentQueueService service =
            new EnrichmentQueueService(queueRepository, bookRepository, authenticationService);

    @BeforeEach
    void setUp() {
        when(queueRepository.findOutstandingForBook(anyLong(), anyCollection())).thenReturn(Optional.empty());
        when(authenticationService.getAuthenticatedUser()).thenReturn(null);
    }

    private EnrichmentRequest request(EnrichmentRequest.Scope scope, Set<Long> bookIds) {
        return EnrichmentRequest.builder()
                .scope(scope)
                .bookIds(bookIds)
                .writePolicy(EnrichmentWritePolicy.AUTO_IF_EMPTY)
                .build();
    }

    private List<EnrichmentQueueEntity> savedRows(int expected) {
        ArgumentCaptor<EnrichmentQueueEntity> captor = ArgumentCaptor.forClass(EnrichmentQueueEntity.class);
        verify(queueRepository, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    class Queueing {

        @Test
        void queuesOneRowPerBookUnderOneJobId() {
            service.enqueue(request(EnrichmentRequest.Scope.BOOKS, Set.of(1L, 2L, 3L)), EnrichmentPriority.SELECTION);

            List<EnrichmentQueueEntity> rows = savedRows(3);
            assertThat(rows).extracting(EnrichmentQueueEntity::getJobId).containsOnly(rows.getFirst().getJobId());
            assertThat(rows).extracting(EnrichmentQueueEntity::getStatus).containsOnly(EnrichmentQueueStatus.QUEUED);
            assertThat(rows).extracting(EnrichmentQueueEntity::getPriority).containsOnly(EnrichmentPriority.SELECTION);
        }

        @Test
        void resolvesALibraryScopeThroughTheRepository() {
            when(bookRepository.findBookIdsByLibraryId(7L)).thenReturn(Set.of(10L, 11L));

            service.enqueue(EnrichmentRequest.builder()
                    .scope(EnrichmentRequest.Scope.LIBRARY)
                    .libraryId(7L)
                    .writePolicy(EnrichmentWritePolicy.AUTO_IF_EMPTY)
                    .build(), EnrichmentPriority.LIBRARY_SWEEP);

            assertThat(savedRows(2)).extracting(EnrichmentQueueEntity::getBookId).containsExactlyInAnyOrder(10L, 11L);
        }

        @Test
        void refusesALibraryScopeWithoutALibrary() {
            assertThatThrownBy(() -> service.enqueue(EnrichmentRequest.builder()
                    .scope(EnrichmentRequest.Scope.LIBRARY)
                    .writePolicy(EnrichmentWritePolicy.AUTO_IF_EMPTY)
                    .build(), EnrichmentPriority.LIBRARY_SWEEP))
                    .hasMessageContaining("Library id is required");
        }

        @Test
        void refusesAnEmptySelection() {
            assertThatThrownBy(() -> service.enqueue(request(EnrichmentRequest.Scope.BOOKS, Set.of()),
                    EnrichmentPriority.SELECTION))
                    .hasMessageContaining("Nothing to enrich");
        }
    }

    /**
     * Pressing the button on a book that a library sweep queued an hour ago and has not reached yet
     * should move it to the front, not add a second identical unit of work.
     */
    @Nested
    class ReQueueing {

        @Test
        void raisesThePriorityOfWorkAlreadyWaiting() {
            EnrichmentQueueEntity waiting = EnrichmentQueueEntity.builder()
                    .id(1L)
                    .bookId(5L)
                    .status(EnrichmentQueueStatus.QUEUED)
                    .priority(EnrichmentPriority.LIBRARY_SWEEP)
                    .build();
            when(queueRepository.findOutstandingForBook(anyLong(), anyCollection())).thenReturn(Optional.of(waiting));

            service.enqueue(request(EnrichmentRequest.Scope.BOOK, Set.of(5L)), EnrichmentPriority.SINGLE_BOOK);

            assertThat(savedRows(1).getFirst().getPriority()).isEqualTo(EnrichmentPriority.SINGLE_BOOK);
            assertThat(savedRows(1).getFirst().getId()).isEqualTo(1L);
        }

        @Test
        void leavesHigherPriorityWorkAlone() {
            EnrichmentQueueEntity waiting = EnrichmentQueueEntity.builder()
                    .id(1L)
                    .bookId(5L)
                    .status(EnrichmentQueueStatus.QUEUED)
                    .priority(EnrichmentPriority.SINGLE_BOOK)
                    .build();
            when(queueRepository.findOutstandingForBook(anyLong(), anyCollection())).thenReturn(Optional.of(waiting));

            service.enqueue(request(EnrichmentRequest.Scope.BOOK, Set.of(5L)), EnrichmentPriority.LIBRARY_SWEEP);

            verify(queueRepository, never()).save(any());
        }

        @Test
        void doesNotDisturbABookAlreadyBeingEnriched() {
            EnrichmentQueueEntity running = EnrichmentQueueEntity.builder()
                    .id(1L)
                    .bookId(5L)
                    .status(EnrichmentQueueStatus.RUNNING)
                    .priority(EnrichmentPriority.LIBRARY_SWEEP)
                    .build();
            when(queueRepository.findOutstandingForBook(anyLong(), anyCollection())).thenReturn(Optional.of(running));

            service.enqueue(request(EnrichmentRequest.Scope.BOOK, Set.of(5L)), EnrichmentPriority.SINGLE_BOOK);

            verify(queueRepository, never()).save(any());
        }
    }

    @Nested
    class StepEncoding {

        @Test
        void roundTripsThroughTheStoredColumn() {
            Set<EnrichmentStepType> steps = EnumSet.of(EnrichmentStepType.LOCAL_CATALOG, EnrichmentStepType.PROVIDERS);

            assertThat(EnrichmentQueueService.decodeSteps(EnrichmentQueueService.encodeSteps(steps)))
                    .isEqualTo(steps);
        }

        @Test
        void treatsAnEmptySelectionAsEveryStep() {
            assertThat(EnrichmentQueueService.encodeSteps(Set.of())).isNull();
            assertThat(EnrichmentQueueService.decodeSteps(null)).isNull();
            assertThat(EnrichmentQueueService.decodeSteps("  ")).isNull();
        }

        /**
         * A queued row outlives the enum: dropping a step name a later version removed is better than
         * failing work that is still mostly valid.
         */
        @Test
        void ignoresStepNamesItNoLongerKnows() {
            assertThat(EnrichmentQueueService.decodeSteps("LOCAL_CATALOG,SOMETHING_REMOVED"))
                    .containsExactly(EnrichmentStepType.LOCAL_CATALOG);
            assertThat(EnrichmentQueueService.decodeSteps("ALL_REMOVED")).isNull();
        }
    }
}
