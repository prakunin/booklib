package org.booklore.service.migration;

import org.booklore.model.entity.AppMigrationEntity;
import org.booklore.repository.AppMigrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppMigrationServiceTest {

    @Mock private AppMigrationRepository migrationRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private AppMigrationService service;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new AppMigrationService(migrationRepository, transactionTemplate);
    }

    @Test
    void executeMigration_runsTransactionalMigrationInsideTransactionTemplate() {
        Migration migration = migration("tx", "Transactional migration", true);
        when(migrationRepository.existsById("tx")).thenReturn(false);

        service.executeMigration(migration);

        verify(transactionTemplate).executeWithoutResult(any());
        verify(migration).execute();

        ArgumentCaptor<AppMigrationEntity> entityCaptor = ArgumentCaptor.forClass(AppMigrationEntity.class);
        verify(migrationRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getKey()).isEqualTo("tx");
        assertThat(entityCaptor.getValue().getDescription()).isEqualTo("Transactional migration");
    }

    @Test
    void executeMigration_runsNonTransactionalMigrationBeforeRecordingTransaction() {
        Migration migration = migration("fs", "Filesystem migration", false);
        when(migrationRepository.existsById("fs")).thenReturn(false);

        service.executeMigration(migration);

        InOrder inOrder = inOrder(migration, transactionTemplate);
        inOrder.verify(migration).execute();
        inOrder.verify(transactionTemplate).executeWithoutResult(any());

        ArgumentCaptor<AppMigrationEntity> entityCaptor = ArgumentCaptor.forClass(AppMigrationEntity.class);
        verify(migrationRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getKey()).isEqualTo("fs");
        assertThat(entityCaptor.getValue().getDescription()).isEqualTo("Filesystem migration");
    }

    private Migration migration(String key, String description, boolean transactional) {
        Migration migration = mock(Migration.class);
        when(migration.getKey()).thenReturn(key);
        when(migration.getDescription()).thenReturn(description);
        when(migration.isTransactional()).thenReturn(transactional);
        return migration;
    }
}
