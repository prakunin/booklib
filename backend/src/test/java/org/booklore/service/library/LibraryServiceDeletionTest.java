package org.booklore.service.library;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.inpx.InpxScanControl;
import org.booklore.service.monitoring.LibraryWatchService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryServiceDeletionTest {

    private static final long LIBRARY_ID = 8L;

    @Mock private LibraryRepository libraryRepository;
    @Mock private LibraryPathRepository libraryPathRepository;
    @Mock private BookRepository bookRepository;
    @Mock private BookMapper bookMapper;
    @Mock private LibraryMapper libraryMapper;
    @Mock private NotificationService notificationService;
    @Mock private FileService fileService;
    @Mock private LibraryWatchService libraryWatchService;
    @Mock private AuthenticationService authenticationService;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private LibraryProcessingService libraryProcessingService;
    @Mock private Executor taskExecutor;
    @Mock private InpxScanControl inpxScanControl;

    @InjectMocks
    private LibraryService libraryService;

    private LibraryEntity libraryEntity;
    private Library library;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        libraryEntity = LibraryEntity.builder()
                .id(LIBRARY_ID)
                .name("Large library")
                .build();
        library = Library.builder()
                .id(LIBRARY_ID)
                .name("Large library")
                .watch(true)
                .paths(List.of())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Nested
    class SuccessfulDeletion {

        @Test
        void bulkDeletesLibraryAndDefersCoverCleanupUntilCommit() {
            List<Long> bookIds = List.of(10L, 11L, 12L);
            stubExistingLibrary(bookIds);

            libraryService.deleteLibrary(LIBRARY_ID);

            verify(libraryRepository).findByIdWithPathsForUpdate(LIBRARY_ID);
            verify(libraryWatchService).unregisterLibrary(LIBRARY_ID);
            verify(bookRepository).findAllBookIdsByLibraryId(LIBRARY_ID);
            verify(libraryRepository).deleteDirectlyById(LIBRARY_ID);
            verify(libraryRepository, never()).deleteById(any());
            verify(fileService, never()).deleteBookCovers(any());
            verify(auditService).log(AuditAction.LIBRARY_DELETED, "Library", LIBRARY_ID,
                    "Deleted library: Large library");

            runSubmittedTasksInline();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(fileService).deleteBookCovers(bookIds);
            verify(libraryWatchService, never()).registerLibrary(any());
        }
    }

    @Nested
    class FailedDeletion {

        @Test
        void restoresWatcherAndKeepsCoversWhenTransactionRollsBack() {
            List<Long> bookIds = List.of(10L, 11L);
            stubExistingLibrary(bookIds);

            libraryService.deleteLibrary(LIBRARY_ID);

            runSubmittedTasksInline();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(libraryWatchService).registerLibrary(library);
            verify(fileService, never()).deleteBookCovers(any());
        }

        @Test
        void treatsMissingBulkDeleteTargetAsNotFoundAndRestoresWatcherOnRollback() {
            when(libraryRepository.findByIdWithPathsForUpdate(LIBRARY_ID)).thenReturn(Optional.of(libraryEntity));
            when(libraryMapper.toLibrary(libraryEntity)).thenReturn(library);
            when(bookRepository.findAllBookIdsByLibraryId(LIBRARY_ID)).thenReturn(List.of(10L));
            when(libraryRepository.deleteDirectlyById(LIBRARY_ID)).thenReturn(0);

            assertThatThrownBy(() -> libraryService.deleteLibrary(LIBRARY_ID))
                    .isInstanceOf(APIException.class)
                    .hasMessage("Library not found with ID: 8");

            runSubmittedTasksInline();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(libraryWatchService).registerLibrary(library);
            verify(fileService, never()).deleteBookCovers(any());
            verify(auditService, never()).log(any(), any(), any(), any());
        }
    }

    @Test
    void rejectsMissingLibraryWithoutSideEffects() {
        when(libraryRepository.findByIdWithPathsForUpdate(LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> libraryService.deleteLibrary(LIBRARY_ID))
                .isInstanceOf(APIException.class)
                .hasMessage("Library not found with ID: 8");

        verify(libraryWatchService, never()).unregisterLibrary(any());
        verify(bookRepository, never()).findAllBookIdsByLibraryId(anyLong());
        verify(libraryRepository, never()).deleteDirectlyById(any());
        verify(fileService, never()).deleteBookCovers(any());
    }

    private void stubExistingLibrary(List<Long> bookIds) {
        when(libraryRepository.findByIdWithPathsForUpdate(LIBRARY_ID)).thenReturn(Optional.of(libraryEntity));
        when(libraryMapper.toLibrary(libraryEntity)).thenReturn(library);
        when(bookRepository.findAllBookIdsByLibraryId(LIBRARY_ID)).thenReturn(bookIds);
        when(libraryRepository.deleteDirectlyById(LIBRARY_ID)).thenReturn(1);
    }

    private void runSubmittedTasksInline() {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
    }
}
