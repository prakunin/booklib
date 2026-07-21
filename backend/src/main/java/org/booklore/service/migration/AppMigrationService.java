package org.booklore.service.migration;

import org.booklore.model.entity.AppMigrationEntity;
import org.booklore.repository.AppMigrationRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@AllArgsConstructor
@Service
public class AppMigrationService {

    private AppMigrationRepository migrationRepository;
    private PlatformTransactionManager transactionManager;


    public void executeMigration(Migration migration) {
        if (isExecuted(migration)) {
            log.debug("Migration '{}' already executed, skipping", migration.getKey());
            return;
        }
        try {
            if (migration.runsInSingleTransaction()) {
                transactionTemplate().executeWithoutResult(status -> {
                    if (!migrationRepository.existsById(migration.getKey())) {
                        migration.execute();
                        saveMigrationRecord(migration);
                    }
                });
            } else {
                migration.execute();
                transactionTemplate().executeWithoutResult(status -> {
                    if (!migrationRepository.existsById(migration.getKey())) {
                        saveMigrationRecord(migration);
                    }
                });
            }

            log.info("Migration '{}' completed successfully", migration.getKey());
        } catch (Exception e) {
            if (e instanceof MigrationIncompleteException) {
                log.warn("Migration '{}' did not complete and will be retried later: {}", migration.getKey(), e.getMessage());
                return;
            }
            log.error("Migration '{}' failed", migration.getKey(), e);
            throw e;
        }
    }

    private boolean isExecuted(Migration migration) {
        return Boolean.TRUE.equals(transactionTemplate().execute(status -> migrationRepository.existsById(migration.getKey())));
    }

    private void saveMigrationRecord(Migration migration) {
        AppMigrationEntity entity = new AppMigrationEntity(migration.getKey(), LocalDateTime.now(ZoneId.systemDefault()), migration.getDescription());
        migrationRepository.save(entity);
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }
}
