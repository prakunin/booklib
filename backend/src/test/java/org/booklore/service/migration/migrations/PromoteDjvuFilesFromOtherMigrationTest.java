package org.booklore.service.migration.migrations;

import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromoteDjvuFilesFromOtherMigrationTest {

    @Mock
    private BookFileRepository bookFileRepository;
    private final PlatformTransactionManager transactionManager = new NoOpTransactionManager();

    private PromoteDjvuFilesFromOtherMigration migration() {
        return new PromoteDjvuFilesFromOtherMigration(bookFileRepository, transactionManager);
    }

    private static BookFileEntity file(long id) {
        BookFileEntity file = BookFileEntity.builder().id(id).build();
        file.setBookType(BookFileType.OTHER);
        return file;
    }

    @Test
    void promotesEveryReturnedFileAndStopsWhenThePageIsEmpty() {
        BookFileEntity first = file(7L);
        when(bookFileRepository.findDownloadOnlyDjvuFilesAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(first));
        when(bookFileRepository.findDownloadOnlyDjvuFilesAfterId(eq(7L), any(Pageable.class)))
                .thenReturn(List.of());

        migration().execute();

        assertThat(first.getBookType()).isEqualTo(BookFileType.DJVU);
        verify(bookFileRepository).saveAll(List.of(first));
    }

    @Test
    void walksBatchesByIdRatherThanRereadingTheSamePage() {
        BookFileEntity first = file(7L);
        BookFileEntity second = file(9L);
        when(bookFileRepository.findDownloadOnlyDjvuFilesAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(first));
        when(bookFileRepository.findDownloadOnlyDjvuFilesAfterId(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(second));
        when(bookFileRepository.findDownloadOnlyDjvuFilesAfterId(eq(9L), any(Pageable.class)))
                .thenReturn(List.of());

        migration().execute();

        assertThat(second.getBookType()).isEqualTo(BookFileType.DJVU);
        verify(bookFileRepository).saveAll(List.of(first));
        verify(bookFileRepository).saveAll(List.of(second));
    }

    @Test
    void writesNothingWhenThereIsNothingToPromote() {
        when(bookFileRepository.findDownloadOnlyDjvuFilesAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of());

        migration().execute();

        verify(bookFileRepository, never()).saveAll(any());
    }

    @Test
    void runsInBatchesRatherThanOneTransaction() {
        // A library of scanned books can be large; promoting it in a single transaction is how a
        // migration turns into a lock held for minutes.
        assertThat(migration().runsInSingleTransaction()).isFalse();
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op: test double, transactions are not actually managed
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op: test double, transactions are not actually managed
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op: test double, transactions are not actually managed
        }
    }
}
